package xyz.wewin.autumn.springcloud.examples.consumer.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

import xyz.wewin.autumn.springcloud.examples.api.UserClient;
import xyz.wewin.autumn.springcloud.examples.api.UserDto;

/**
 * native-image hints for the consumer.
 *
 * <p>{@code HttpServiceProxyFactory.createClient(UserClient.class)} builds a JDK dynamic
 * proxy at runtime; closed-world analysis cannot see it, so the interface must be
 * registered explicitly. {@link UserDto} needs reflection for Jackson (de)serialization.
 * Tomcat's connector/protocol methods are registered for the same reason as in the
 * provider: {@code TomcatWebServer#disableBindOnInit} reaches them via reflection.</p>
 */
public class ConsumerNativeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.proxies().registerJdkProxy(UserClient.class);
        hints.reflection().registerType(UserDto.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.DECLARED_FIELDS,
                MemberCategory.INVOKE_DECLARED_METHODS);

        hints.reflection().registerType(TypeReference.of("org.apache.coyote.AbstractProtocol"),
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_METHODS);
        hints.reflection().registerType(TypeReference.of("org.apache.coyote.http11.Http11NioProtocol"),
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_METHODS);
        hints.reflection().registerType(TypeReference.of("org.apache.catalina.connector.Connector"),
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
