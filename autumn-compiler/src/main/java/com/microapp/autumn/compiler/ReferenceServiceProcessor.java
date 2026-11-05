package com.microapp.autumn.compiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
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
import javax.tools.JavaFileObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microapp.autumn.api.annotation.Reference;
import com.microapp.autumn.compiler.model.MethodElement;
import com.microapp.autumn.compiler.model.ReferenceEntry;
import com.microapp.autumn.compiler.util.ClassNameUtil;
import com.microapp.autumn.compiler.util.FreemarkerUtil;
import com.microapp.autumn.compiler.util.MetaHolder;

/**
 * 为 {@code @Reference} 生成零反射客户端代理。
 *
 * <p>按 round 累积：每轮只生成本轮新收集到的接口，后续 round 出现的注解不会再被漏掉。</p>
 */
@SupportedAnnotationTypes(value = {"com.microapp.autumn.api.annotation.Reference"})
@SupportedSourceVersion(value = SourceVersion.RELEASE_25)
public class ReferenceServiceProcessor extends AbstractProcessor {

    private static final String IFACE_SUFFIX = ".Iface";
    private static final String PROXY_SUFFIX = "Proxy";

    private final Logger log = LoggerFactory.getLogger(ReferenceServiceProcessor.class);
    private final MetaHolder.State state = new MetaHolder.State();
    private final Set<String> pending = new LinkedHashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotationElement : annotations) {
            for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(annotationElement)) {
                collect(annotatedElement);
            }
        }
        if (!roundEnv.processingOver() && !pending.isEmpty()) {
            handleWrite();
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
        if (!(interfaceType instanceof DeclaredType)) {
            log.warn("autumn @Reference element type must be a thrift Iface interface, skip. element:{}", annotatedElement);
            return;
        }
        String ifaceName = interfaceType.toString();
        if (!ifaceName.endsWith(IFACE_SUFFIX)) {
            log.warn("autumn @Reference element type must end with Iface, skip. element:{}", annotatedElement);
            return;
        }
        if (state.containsReference(ifaceName)) {
            return;
        }
        ReferenceEntry entry = new ReferenceEntry();
        entry.setInterfaceName(ifaceName);
        entry.setReferApplication(reference.refer());
        entry.setMethodElements(resolveMethods((DeclaredType) interfaceType));
        state.addReferService(ifaceName, entry);
        pending.add(ifaceName);
        log.info("autumn reference collect entry:{}, refer:{}", ifaceName, reference.refer());
    }

    /**
     * 字段注入取字段类型；方法注入优先取第一个参数（setter），无参时取返回类型（getter 风格）。
     */
    private TypeMirror resolveInterfaceType(Element annotatedElement) {
        if (annotatedElement instanceof VariableElement) {
            return ((VariableElement) annotatedElement).asType();
        }
        if (annotatedElement instanceof ExecutableElement) {
            ExecutableElement method = (ExecutableElement) annotatedElement;
            List<? extends VariableElement> params = method.getParameters();
            return params.isEmpty() ? method.getReturnType() : params.get(0).asType();
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

    private void handleWrite() {
        for (String ifaceName : new ArrayList<>(pending)) {
            pending.remove(ifaceName);
            ReferenceEntry entry = state.getReferService().get(ifaceName);
            if (entry == null) {
                continue;
            }
            String ifaceOuterName = ifaceName.substring(0, ifaceName.length() - IFACE_SUFFIX.length());
            String packageName = ClassNameUtil.getPackageName(ifaceOuterName);
            String outerSimpleName = ClassNameUtil.getSimpleClassName(ifaceOuterName);
            String generatedFullName = packageName + "._" + outerSimpleName + PROXY_SUFFIX;
            try {
                JavaFileObject builderFile = processingEnv.getFiler().createSourceFile(generatedFullName);
                // must close the writer: javac skips generated sources whose writer is left open
                try (java.io.Writer sourceWriter = builderFile.openWriter()) {
                    FreemarkerUtil.handleProxy(packageName, outerSimpleName, ifaceName, ifaceOuterName,
                            entry.getReferApplication(), entry, sourceWriter);
                }
                log.info("autumn reference generate proxy class:{}, refer:{}", generatedFullName, entry.getReferApplication());
            } catch (FilerException alreadyGenerated) {
                log.debug("autumn reference proxy already generated:{}", generatedFullName);
            } catch (Exception e) {
                log.warn("autumn reference write proxy exception, class:{}", generatedFullName, e);
            }
        }
    }
}
