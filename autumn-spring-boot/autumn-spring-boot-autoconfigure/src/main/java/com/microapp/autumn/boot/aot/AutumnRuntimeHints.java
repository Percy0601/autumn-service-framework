package com.microapp.autumn.boot.aot;

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
 * {@code META-INF/native-image/com.microapp.autumn/*-reflect-config.json}.</p>
 */
public class AutumnRuntimeHints implements RuntimeHintsRegistrar {

    private static final MemberCategory[] CONSTRUCTORS =
            {MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS};

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
                TypeReference.of("com.microapp.autumn.api.extension.AutumnServiceAssembler"), CONSTRUCTORS);
        hints.reflection().registerType(
                TypeReference.of("com.microapp.autumn.api.Registry"), CONSTRUCTORS);
        hints.reflection().registerType(
                TypeReference.of("com.microapp.autumn.api.Discovery"), CONSTRUCTORS);

        // runtime fallback path: Class.forName(outer + "$Processor") / "$Client" + getConstructor
        hints.reflection().registerType(
                TypeReference.of("com.microapp.autumn.api.extension.AttachableProcessor"), CONSTRUCTORS);
        hints.reflection().registerType(
                TypeReference.of("com.microapp.autumn.api.config.ServiceConfig"), CONSTRUCTORS);
    }
}
