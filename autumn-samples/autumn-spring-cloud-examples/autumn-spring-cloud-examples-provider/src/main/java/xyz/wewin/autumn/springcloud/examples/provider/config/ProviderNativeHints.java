package xyz.wewin.autumn.springcloud.examples.provider.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

import xyz.wewin.autumn.springcloud.examples.api.UserDto;

/**
 * native-image hints for the provider.
 *
 * <p>Jackson needs reflective access to {@link UserDto} on the MVC (de)serialization path.
 * In addition, {@code TomcatWebServer#disableBindOnInit} calls
 * {@code Connector#getProperty("bindOnInit")}, which delegates to
 * {@code IntrospectionUtils} and reflectively invokes
 * {@code AbstractProtocol#getProperty(String)} / {@code setProperty(String, String)}.
 * Spring Boot's built-in AOT hints do not cover those methods, and GraalVM's strict
 * reflection checks abort startup with a {@code MissingReflectionRegistrationError},
 * so the Tomcat connector/protocol types are registered explicitly.</p>
 */
public class ProviderNativeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
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
