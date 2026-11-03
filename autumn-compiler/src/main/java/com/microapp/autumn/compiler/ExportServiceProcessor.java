package com.microapp.autumn.compiler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
@SupportedSourceVersion(value = SourceVersion.RELEASE_17)
public class ExportServiceProcessor extends AbstractProcessor {
    private static final String IFACE_SUFFIX = ".Iface";
    private static final String SERVICE_REGISTER_SUFFIX = "ServiceRegister";
    private static final String SERVICES_LOCATION = "META-INF/services";
    private static final String ASSEMBLER_INTERFACE = "com.microapp.autumn.api.extension.AutumnServiceAssembler";
    private Logger log = LoggerFactory.getLogger(ExportServiceProcessor.class);

    private Filer mFilerUtils;
    private Types mTypesUtils;
    private Elements mElementsUtils;
    private Messager mMessager;
    private Map<String, TargetClass> mapping = new ConcurrentHashMap<>();
    private final AtomicBoolean generated = new AtomicBoolean(false);

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
        if (generated.compareAndSet(false, true)) {
            writeAll();
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
        MetaHolder.addExportEntry(entry);
        log.info("autumn export collect entry:{}", entry);
    }

    private void writeAll() {
        List<ExportEntry> entries = MetaHolder.getExportEntries();
        if (entries.isEmpty()) {
            log.warn("autumn export entries empty, skip code generation.");
            return;
        }
        try {
            StringBuilder services = new StringBuilder();
            for (ExportEntry entry : entries) {
                String implFullName = entry.getImplementName();
                String ifaceName = entry.getInterfaceName();
                if (ifaceName == null) {
                    continue;
                }
                String packageName = ClassNameUtil.getPackageName(implFullName);
                String simpleClassName = ClassNameUtil.getSimpleClassName(implFullName);
                String ifaceOuterName = ifaceName.substring(0, ifaceName.length() - IFACE_SUFFIX.length());
                String generatedFullName = packageName + "._" + simpleClassName + SERVICE_REGISTER_SUFFIX;
                JavaFileObject builderFile = mFilerUtils.createSourceFile(generatedFullName);
                FreemarkerUtil.handleServer(packageName, simpleClassName, implFullName, ifaceName, ifaceOuterName, builderFile.openWriter());
                log.info("autumn export generate register class:{}", generatedFullName);
                services.append(generatedFullName).append("\n");
            }
            if (services.length() > 0) {
                FileObject servicesFile = mFilerUtils.createResource(StandardLocation.CLASS_OUTPUT, "",
                        SERVICES_LOCATION + "/" + ASSEMBLER_INTERFACE);
                java.io.Writer writer = servicesFile.openWriter();
                writer.write(services.toString());
                writer.flush();
                writer.close();
                log.info("autumn export generate spi services file:{}", ASSEMBLER_INTERFACE);
            }
        } catch (Exception e) {
            log.warn("autumn export write source exception:", e);
        }
    }
}
