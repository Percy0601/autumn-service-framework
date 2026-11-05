package com.microapp.autumn.compiler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microapp.autumn.compiler.model.ExportEntry;
import com.microapp.autumn.compiler.model.TargetClass;
import com.microapp.autumn.compiler.util.ClassNameUtil;
import com.microapp.autumn.compiler.util.ClassResolverUtil;
import com.microapp.autumn.compiler.util.FreemarkerUtil;
import com.microapp.autumn.compiler.util.MetaHolder;

@SupportedAnnotationTypes(value = {"com.microapp.autumn.api.annotation.Export"})
@SupportedSourceVersion(value = SourceVersion.RELEASE_25)
public class ExportServiceProcessor extends AbstractProcessor {
    private static final String IFACE_SUFFIX = ".Iface";
    private static final String SERVICE_REGISTER_SUFFIX = "ServiceRegister";
    private static final String SERVICES_LOCATION = "META-INF/services";
    private static final String ASSEMBLER_INTERFACE = "com.microapp.autumn.api.extension.AutumnServiceAssembler";
    private static final String NATIVE_IMAGE_LOCATION = "META-INF/native-image/com.microapp.autumn";
    private Logger log = LoggerFactory.getLogger(ExportServiceProcessor.class);

    private Filer mFilerUtils;
    private Types mTypesUtils;
    private Elements mElementsUtils;
    private Messager mMessager;
    private Map<String, TargetClass> mapping = new ConcurrentHashMap<>();
    private final Set<String> written = ConcurrentHashMap.newKeySet();
    // accumulated generated assembler (ServiceRegister) FQNs across rounds, for the SPI file
    private final Set<String> generatedAssemblers = ConcurrentHashMap.newKeySet();
    // per-processor metadata, isolated from other processors / compilation units
    private final MetaHolder metaHolder = new MetaHolder();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        log.info("begin handle export annotation process init.");
        super.init(processingEnvironment);
        mFilerUtils = processingEnv.getFiler();
        mTypesUtils = processingEnv.getTypeUtils();
        mElementsUtils = processingEnv.getElementUtils();
        mMessager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotationElement : annotations) {
            Set<? extends Element> annotatedClasses = roundEnv.getElementsAnnotatedWith(annotationElement);
            for (Element annotatedClass : annotatedClasses) {
                collect(annotatedClass);
            }
        }
            if (!roundEnv.processingOver()) {
                writePending();
            }
            return true;
    }

    private void collect(Element annotatedClass) {
        TargetClass targetClass = ClassResolverUtil.handleExport(annotatedClass);
        if (mapping.containsKey(targetClass.getFullName())) {
            return;
        }
        mapping.put(targetClass.getFullName(), targetClass);

        List<String> interfaces = targetClass.getImplementList();
        if (interfaces == null || interfaces.isEmpty()) {
            log.warn("autumn export class has no interfaces, skip code generation. class:{}", targetClass.getFullName());
            return;
        }
        String ifaceName = interfaces.stream()
                .filter(it -> it.endsWith(IFACE_SUFFIX))
                .findFirst()
                .orElse(null);
        if (ifaceName == null) {
            log.warn("autumn export class has no thrift Iface interface, skip code generation. class:{}", targetClass.getFullName());
            return;
        }
        ExportEntry entry = new ExportEntry();
        entry.setImplementName(targetClass.getFullName());
        entry.setInterfaceName(ifaceName);
        entry.setName(targetClass.getName());
        entry.setMethods(targetClass.getMethods());
        metaHolder.addExportEntry(entry);
        log.info("autumn export collect entry:{}", entry);
    }

    private void writePending() {
        List<ExportEntry> entries = metaHolder.getExportEntries();
        if (entries.isEmpty()) {
            log.warn("autumn export entries empty, skip code generation.");
            return;
        }
        try {
            Set<String> generatedThisRound = new LinkedHashSet<>();
            for (ExportEntry entry : entries) {
                String implFullName = entry.getImplementName();
                if (written.contains(implFullName)) {
                    continue;
                }
                String ifaceName = entry.getInterfaceName();
                if (ifaceName == null) {
                    continue;
                }
                String packageName = ClassNameUtil.getPackageName(implFullName);
                String simpleClassName = ClassNameUtil.getSimpleClassName(implFullName);
                String ifaceOuterName = ifaceName.substring(0, ifaceName.length() - IFACE_SUFFIX.length());
                String generatedFullName = packageName + "._" + simpleClassName + SERVICE_REGISTER_SUFFIX;
                JavaFileObject builderFile = mFilerUtils.createSourceFile(generatedFullName);
                // must close the writer: javac skips generated sources whose writer is left open
                try (java.io.Writer sourceWriter = builderFile.openWriter()) {
                    FreemarkerUtil.handleServer(packageName, simpleClassName, implFullName, ifaceName, ifaceOuterName, sourceWriter);
                }
                written.add(implFullName);
                generatedThisRound.add(generatedFullName);
                generatedAssemblers.add(generatedFullName);
                log.info("autumn export generate register class:{}", generatedFullName);
            }
            if (!generatedThisRound.isEmpty()) {
                FileObject servicesFile = mFilerUtils.createResource(StandardLocation.CLASS_OUTPUT, "",
                        SERVICES_LOCATION + "/" + ASSEMBLER_INTERFACE);
                // the SPI file must list the generated assembler classes (which implement AutumnServiceAssembler),
                // NOT the implementation classes.
                try (java.io.Writer writer = servicesFile.openWriter()) {
                    for (String cls : generatedAssemblers) {
                        writer.write(cls + "\n");
                    }
                }
                // native-image 闭世界分析看不到 ServiceLoader 动态加载的生成类，显式注册反射构造
                FileObject reflectFile = mFilerUtils.createResource(StandardLocation.CLASS_OUTPUT, "",
                        NATIVE_IMAGE_LOCATION + "/autumn-export-reflect-config.json");
                try (java.io.Writer writer = reflectFile.openWriter()) {
                    writer.write(buildReflectConfig(generatedAssemblers));
                }
                log.info("autumn export generate spi services file:{}, entries:{}", ASSEMBLER_INTERFACE, generatedAssemblers);
            }
        } catch (Exception e) {
            log.warn("autumn export write source exception:", e);
        }
    }

    private static String buildReflectConfig(Set<String> classNames) {
        StringBuilder json = new StringBuilder("[\n");
        int index = 0;
        for (String name : classNames) {
            if (index++ > 0) {
                json.append(",\n");
            }
            json.append("  {\"name\":\"").append(name).append("\",\"allPublicConstructors\":true}");
        }
        return json.append("\n]\n").toString();
    }
}
