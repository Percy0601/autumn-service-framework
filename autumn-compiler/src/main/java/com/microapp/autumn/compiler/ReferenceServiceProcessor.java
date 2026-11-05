package com.microapp.autumn.compiler;

import com.microapp.autumn.api.annotation.Reference;
import com.microapp.autumn.compiler.model.MethodElement;
import com.microapp.autumn.compiler.model.ReferenceEntry;
import com.microapp.autumn.compiler.util.ClassNameUtil;
import com.microapp.autumn.compiler.util.FreemarkerUtil;
import com.microapp.autumn.compiler.util.MetaHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@SupportedAnnotationTypes(value = {"com.microapp.autumn.api.annotation.Reference"})
@SupportedSourceVersion(value = SourceVersion.RELEASE_25)
public class ReferenceServiceProcessor extends AbstractProcessor {
    private static final String IFACE_SUFFIX = ".Iface";
    private static final String PROXY_SUFFIX = "Proxy";
    private static final String NATIVE_IMAGE_LOCATION = "META-INF/native-image/com.microapp.autumn";
    private Logger log = LoggerFactory.getLogger(ReferenceServiceProcessor.class);
    private Map<String, ReferenceEntry> mapping = new ConcurrentHashMap<>();
    private final Set<String> written = ConcurrentHashMap.newKeySet();
    private final Set<String> generatedProxies = ConcurrentHashMap.newKeySet();
    // per-processor metadata, isolated from other processors / compilation units
    private final MetaHolder metaHolder = new MetaHolder();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        log.info("begin handle reference annotation.");
        for (TypeElement annotationElement : annotations) {
            Set<? extends Element> annotatedElements = roundEnv.getElementsAnnotatedWith(annotationElement);
            for (Element annotatedElement : annotatedElements) {
                collect(annotatedElement);
            }
        }
            if (!roundEnv.processingOver()) {
                writePending();
            }
            return true;
    }

    private void collect(Element annotatedElement) {
        Reference reference = annotatedElement.getAnnotation(Reference.class);
        if (reference == null) {
            log.warn("autumn reference annotation not found, skip. element:{}", annotatedElement);
            return;
        }
        TypeMirror interfaceType = resolveInterfaceType(annotatedElement);
        if (interfaceType == null || !(interfaceType instanceof DeclaredType)) {
            log.warn("autumn @Reference element type must be a thrift Iface interface, skip. element:{}", annotatedElement);
            return;
        }
        String ifaceName = interfaceType.toString();
        if (!ifaceName.endsWith(IFACE_SUFFIX)) {
            log.warn("autumn @Reference element type must end with Iface, skip. element:{}", annotatedElement);
            return;
        }
        if (mapping.containsKey(ifaceName)) {
            return;
        }
        ReferenceEntry entry = new ReferenceEntry();
        entry.setInterfaceName(ifaceName);
        entry.setReferApplication(reference.refer());
        entry.setMethodElements(resolveMethods((DeclaredType) interfaceType));
        mapping.put(ifaceName, entry);
        metaHolder.addReferService(ifaceName, entry);
        log.info("autumn reference collect entry:{}, refer:{}", ifaceName, reference.refer());
    }

    private TypeMirror resolveInterfaceType(Element annotatedElement) {
        if (annotatedElement instanceof VariableElement) {
            // @Reference field, type is the thrift Iface interface
            return ((VariableElement) annotatedElement).asType();
        }
        if (annotatedElement instanceof ExecutableElement) {
            // @Reference on method, return type is the thrift Iface interface
            return ((ExecutableElement) annotatedElement).getReturnType();
        }
        return null;
    }

    private List<MethodElement> resolveMethods(DeclaredType interfaceType) {
        List<MethodElement> methodElements = new ArrayList<>();
        Element interfaceElement = interfaceType.asElement();
        for (Element enclosed : interfaceElement.getEnclosedElements()) {
            if (!(enclosed instanceof ExecutableElement)) {
                continue;
            }
            ExecutableElement ee = (ExecutableElement) enclosed;
            MethodElement method = new MethodElement();
            method.setName(ee.getSimpleName().toString());
            method.setReturnType(ee.getReturnType().toString());
            List<String> params = new ArrayList<>();
            for (VariableElement ve : ee.getParameters()) {
                params.add(ve.asType().toString());
            }
            method.setParamTypes(params);
            methodElements.add(method);
        }
        return methodElements;
    }

    private void writePending() {
        ConcurrentHashMap<String, ReferenceEntry> referenceEntries = metaHolder.getReferService();
        referenceEntries.forEach((ifaceName, entry) -> {
            if (written.contains(ifaceName)) {
                return;
            }
            try {
                String ifaceOuterName = ifaceName.substring(0, ifaceName.length() - IFACE_SUFFIX.length());
                String packageName = ClassNameUtil.getPackageName(ifaceOuterName);
                String outerSimpleName = ClassNameUtil.getSimpleClassName(ifaceOuterName);
                String generatedFullName = packageName + "._" + outerSimpleName + PROXY_SUFFIX;
                JavaFileObject builderFile = processingEnv.getFiler().createSourceFile(generatedFullName);
                // must close the writer: javac skips generated sources whose writer is left open
                try (java.io.Writer sourceWriter = builderFile.openWriter()) {
                    FreemarkerUtil.handleProxy(packageName, outerSimpleName, ifaceName, ifaceOuterName,
                            entry.getReferApplication(), entry, sourceWriter);
                }
                written.add(ifaceName);
                generatedProxies.add(generatedFullName);
                log.info("autumn reference generate proxy class:{}, refer:{}", generatedFullName, entry.getReferApplication());
            } catch (Exception e) {
                log.warn("handleWrite Proxy Exception:", e);
            }
        });
        writeReflectConfig();
    }

    /**
     * 生成代理通过 Class.forName + getConstructor(String) 加载，native-image 闭世界分析看不到，
     * 因此把生成类的具体名字写进 reflect-config（编译期已知，无需运行时通配）。
     */
    private void writeReflectConfig() {
        if (generatedProxies.isEmpty()) {
            return;
        }
        try {
            FileObject reflectFile = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                    NATIVE_IMAGE_LOCATION + "/autumn-reference-reflect-config.json");
            try (java.io.Writer writer = reflectFile.openWriter()) {
                StringBuilder json = new StringBuilder("[\n");
                int index = 0;
                for (String name : generatedProxies) {
                    if (index++ > 0) {
                        json.append(",\n");
                    }
                    json.append("  {\"name\":\"").append(name).append("\",\"allPublicConstructors\":true}");
                }
                writer.write(json.append("\n]\n").toString());
            }
        } catch (Exception e) {
            // 多 round 下同名资源已存在时 Filer 会拒绝重建，首轮写入已覆盖绝大多数场景
            log.debug("autumn reference reflect-config write skipped:{}", e.getMessage());
        }
    }

}
