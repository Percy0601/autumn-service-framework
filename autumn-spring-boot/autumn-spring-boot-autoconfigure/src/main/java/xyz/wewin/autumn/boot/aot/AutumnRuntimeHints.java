package xyz.wewin.autumn.boot.aot;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * GraalVM native-image reachability hints for the autumn RPC framework.
 *
 * <p>The framework uses {@code ServiceLoader} (SPI), classpath property loading and
 * {@code Class.forName} for thrift Client/Processor classes. Closed-world analysis
 * cannot see those dynamic lookups, so they must be registered explicitly. The
 * concrete names of compile-time generated {@code _XxxProxy} / {@code _XxxServiceRegister}
 * classes are emitted by the annotation processors into
 * {@code META-INF/native-image/xyz.wewin.autumn/*-reflect-config.json}.</p>
 */
public class AutumnRuntimeHints implements RuntimeHintsRegistrar {

    private static final MemberCategory[] CONSTRUCTORS =
            {MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS};

    /**
     * Jackson 反序列化 record 需要的反射：canonical 构造器是包级可见，必须用
     * {@code INVOKE_DECLARED_CONSTRUCTORS} 而不是 public 构造器。
     */
    private static final MemberCategory[] JACKSON_BINDING =
            {MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.DECLARED_FIELDS, MemberCategory.PUBLIC_FIELDS};

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // CommonUtil.readClasspath("application.properties" / "<app>.properties")
        hints.resources().registerPattern("*.properties");
        // ServiceLoader descriptors for Registry / Discovery / AutumnServiceAssembler
        hints.resources().registerPattern("META-INF/services/.*");
        // reflect-config emitted by autumn-compiler for generated classes
        hints.resources().registerPattern("META-INF/native-image/.*");

        // SPI contracts instantiated through ServiceLoader
        hints.reflection().registerType(
                TypeReference.of("xyz.wewin.autumn.api.extension.AutumnServiceAssembler"), CONSTRUCTORS);
        hints.reflection().registerType(
                TypeReference.of("xyz.wewin.autumn.api.Registry"), CONSTRUCTORS);
        hints.reflection().registerType(
                TypeReference.of("xyz.wewin.autumn.api.Discovery"), CONSTRUCTORS);

        // runtime fallback path: Class.forName(outer + "$Processor") / "$Client" + getConstructor
        hints.reflection().registerType(
                TypeReference.of("xyz.wewin.autumn.api.extension.AttachableProcessor"), CONSTRUCTORS);
        hints.reflection().registerType(
                TypeReference.of("xyz.wewin.autumn.api.config.ServiceConfig"), CONSTRUCTORS);

        // multicast 注册中心的心跳帧用 Jackson 序列化 record：发送侧靠 accessor（可达性分析能看到），
        // 接收侧反序列化需要 canonical 构造器，closed-world 分析看不到，native 下缺注册会把心跳帧全部丢弃。
        hints.reflection().registerType(TypeReference.of(
                "xyz.wewin.autumn.cloud.extension.core.multicast.MulticastDiscovery$Frame"), JACKSON_BINDING);
        hints.reflection().registerType(TypeReference.of(
                "xyz.wewin.autumn.cloud.extension.core.model.InstanceRegistration"), JACKSON_BINDING);
        hints.reflection().registerType(TypeReference.of(
                "xyz.wewin.autumn.cloud.extension.core.model.InstanceSnapshot"), JACKSON_BINDING);
    }
}
