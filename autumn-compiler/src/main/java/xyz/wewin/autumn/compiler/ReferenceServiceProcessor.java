package xyz.wewin.autumn.compiler;

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

import xyz.wewin.autumn.api.annotation.Reference;
import xyz.wewin.autumn.compiler.model.MethodElement;
import xyz.wewin.autumn.compiler.model.ReferenceEntry;
import xyz.wewin.autumn.compiler.util.ClassNameUtil;
import xyz.wewin.autumn.compiler.util.FreemarkerUtil;
import xyz.wewin.autumn.compiler.util.MetaHolder;
import xyz.wewin.autumn.compiler.util.NativeImageMetadataWriter;

/**
 * 为 {@code @Reference} 生成零反射客户端代理。
 *
 * <p>按 round 累积：每轮只生成本轮新收集到的接口，后续 round 出现的注解不会再被漏掉。</p>
 */
@SupportedAnnotationTypes(value = {"xyz.wewin.autumn.api.annotation.Reference"})
@SupportedSourceVersion(value = SourceVersion.RELEASE_25)
public class ReferenceServiceProcessor extends AbstractProcessor {

    private static final String IFACE_SUFFIX = ".Iface";
    private static final String BINARY_IFACE_SUFFIX = "$Iface";
    private static final String PROXY_SUFFIX = "Proxy";

    private final Logger log = LoggerFactory.getLogger(ReferenceServiceProcessor.class);
    private final MetaHolder.State state = new MetaHolder.State();
    private final Set<String> pending = new LinkedHashSet<>();
    /** 编译期产出的 native-image 反射元数据：代理类 / thrift Client / @Reference 注入点 */
    private final NativeImageMetadataWriter nativeMetadata = new NativeImageMetadataWriter();

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
        // 反射元数据按注入点累积：同一个 Iface 被多处 @Reference 引用时，每个注入点的字段都要登记
        collectNativeMetadata(annotatedElement, ifaceName, binaryIfaceName((DeclaredType) interfaceType));
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
     * 编译期登记 native-image 反射元数据，替代手写 reflect-config.json：
     * <pre>
     * 1、生成的 _XxxProxy：Spring 侧 Class.forName + getConstructor(String) 实例化
     * 2、thrift Xxx$Client：反射兜底（未生成代理时走 JDK 动态代理，每次调用反射建 client）
     * 3、@Reference 注入点：字段需要 allowWrite，setter 需要方法注册
     * 4、Iface 接口：JDK 动态代理兜底路径需要 proxy-config（必须是二进制名 Xxx$Iface）
     * </pre>
     */
    private void collectNativeMetadata(Element annotatedElement, String ifaceName, String binaryIfaceName) {
        String ifaceOuterName = ifaceName.substring(0, ifaceName.length() - IFACE_SUFFIX.length());
        String packageName = ClassNameUtil.getPackageName(ifaceOuterName);
        String outerSimpleName = ClassNameUtil.getSimpleClassName(ifaceOuterName);
        nativeMetadata.constructors(packageName + "._" + outerSimpleName + PROXY_SUFFIX);
        String binaryOuterName = binaryIfaceName == null ? ifaceOuterName
                : binaryIfaceName.substring(0, binaryIfaceName.length() - BINARY_IFACE_SUFFIX.length());
        nativeMetadata.constructors(binaryOuterName + "$Client");
        nativeMetadata.proxy(binaryIfaceName == null ? ifaceOuterName + "$Iface" : binaryIfaceName);

        String ownerClass = enclosingTypeName(annotatedElement);
        if (ownerClass == null) {
            return;
        }
        if (annotatedElement instanceof VariableElement) {
            nativeMetadata.writableField(ownerClass, annotatedElement.getSimpleName().toString());
        } else if (annotatedElement instanceof ExecutableElement) {
            ExecutableElement method = (ExecutableElement) annotatedElement;
            List<String> parameterTypes = new ArrayList<>();
            for (VariableElement parameter : method.getParameters()) {
                parameterTypes.add(parameter.asType().toString());
            }
            nativeMetadata.method(ownerClass, method.getSimpleName().toString(), parameterTypes);
        }
    }

    /**
     * 反射元数据里必须用二进制名（{@code com.x.SomeService$Iface}），
     * 而 {@code TypeMirror#toString()} 给的是源码形态（{@code com.x.SomeService.Iface}）。
     */
    private String binaryIfaceName(DeclaredType interfaceType) {
        Element element = interfaceType.asElement();
        if (element instanceof TypeElement) {
            return processingEnv.getElementUtils().getBinaryName((TypeElement) element).toString();
        }
        return null;
    }

    /** 注入点所在类；@Reference 标在方法参数上时框架不注入，返回 null 跳过元数据 */
    private static String enclosingTypeName(Element annotatedElement) {
        Element enclosing = annotatedElement.getEnclosingElement();
        if (enclosing instanceof TypeElement) {
            return ((TypeElement) enclosing).getQualifiedName().toString();
        }
        return null;
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
        writeNativeMetadata();
    }

    /**
     * 编译期落盘 native-image 反射元数据（META-INF/native-image/xyz.we-win.autumn/autumn-reference-hints/），
     * 随模块 jar 打包，native-image 构建时自动合并，宿主工程无需手写 reflect-config.json。
     */
    private void writeNativeMetadata() {
        if (nativeMetadata.isEmpty()) {
            return;
        }
        nativeMetadata.write(processingEnv.getFiler(), "autumn-reference-hints", log);
    }
}
