package com.microapp.autumn.compiler.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microapp.autumn.compiler.model.TargetAnnotation;
import com.microapp.autumn.compiler.model.TargetAnnotationAttribute;
import com.microapp.autumn.compiler.model.TargetClass;
import com.microapp.autumn.compiler.model.TargetMethod;
import com.microapp.autumn.compiler.model.TargetVariable;

/**
 * resolve annotation element into metadata model, used by annotation processors.
 *
 * @author: baoxin.zhao
 * @date: 2024/11/16
 */

public class ClassResolverUtil {
    private static Logger log = LoggerFactory.getLogger(ClassResolverUtil.class);
    private ClassResolverUtil() {

    }

    public static TargetClass handleExport(Element annotatedClass) {
        log.info("begin handle annotation Export:{}", annotatedClass.getSimpleName().toString());
        // ==========================begin handle basic==========================
        String fullName = annotatedClass.asType().toString();
        TargetClass targetClass = new TargetClass();
        targetClass.setName(annotatedClass.getSimpleName().toString());
        targetClass.setFullName(fullName);
        targetClass.setPackageName(ClassNameUtil.getPackageName(fullName));
        // ==========================end handle basic==========================

        // ==========================begin handle implement interfaces==========================
        List<String> interfaces = new ArrayList<>();
        targetClass.setImplementList(interfaces);
        if(annotatedClass instanceof TypeElement) {
            TypeElement typeElement = (TypeElement)annotatedClass;
            List<? extends TypeMirror> interfaceMirrors = typeElement.getInterfaces();
            interfaceMirrors.forEach(it -> {
                if(it instanceof DeclaredType) {
                    DeclaredType dt = (DeclaredType)it;
                    String interfaceName = dt.asElement().toString();
                    interfaces.add(interfaceName);
                }
            });
        }

        // ==========================begin handle annotation==========================
        List<TargetAnnotation> annotations = new ArrayList<>();
        targetClass.setAnnotations(annotations);
        List<? extends AnnotationMirror> mirrors = annotatedClass.getAnnotationMirrors();
        annotatedClass.getEnclosingElement();
        for(AnnotationMirror mirror: mirrors) {
            TargetAnnotation annotation = new TargetAnnotation();
            annotation.setType(mirror.getAnnotationType().toString());
            annotations.add(annotation);
            List<TargetAnnotationAttribute> attributes = new ArrayList<>();
            annotation.setAttributes(attributes);

            Map<? extends ExecutableElement, ? extends AnnotationValue> mapping = mirror.getElementValues();
            mapping.forEach((k, v) -> {
                TargetAnnotationAttribute attribute = new TargetAnnotationAttribute();
                String defaultValue = k.getDefaultValue().toString();
                String name = k.getSimpleName().toString();
                attribute.setName(name);
                attribute.setDefaultValue(defaultValue);
                String value = v.toString();
                attribute.setValue(value);
                attribute.setReturnType(k.getReturnType().toString());
                attributes.add(attribute);
            });
        }
        // ==========================end handle annotation==========================

        // ==========================begin handle field and method==========================
        List<TargetMethod> methods = new ArrayList<>();
        targetClass.setMethods(methods);
        List<? extends Element> elements = annotatedClass.getEnclosedElements();
        elements.forEach(it -> {
            if(it instanceof ExecutableElement) {

                TargetMethod method = new TargetMethod();
                ExecutableElement ee = (ExecutableElement)it;
                String name = ee.getSimpleName().toString();
                if(name.equals("<init>")) {
                    return;
                }
                method.setName(name);
                List<TargetVariable> variables = new ArrayList<>();
                method.setVariables(variables);
                List<? extends VariableElement> vars = ee.getParameters();
                vars.forEach(v -> {
                    TargetVariable variable = new TargetVariable();
                    String varName = v.getSimpleName().toString();
                    variable.setName(varName);
                    String typeName = v.asType().toString();
                    variable.setType(typeName);
                    variables.add(variable);
                });
                TypeMirror returnType = ee.getReturnType();
                method.setReturnType(returnType.toString());

                List<? extends TypeMirror> thrownTypes = ee.getThrownTypes();

                List<String> exceptions = thrownTypes.stream()
                        .map(TypeMirror::toString)
                        .collect(Collectors.toList());
                method.setExceptions(exceptions);
                methods.add(method);
            }
        });
        // ==========================end handle field and method==========================

        log.info("=====================Export:{}", targetClass);
        return targetClass;
    }

}
