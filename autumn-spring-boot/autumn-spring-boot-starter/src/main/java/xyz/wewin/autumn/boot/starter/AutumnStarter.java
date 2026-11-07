package xyz.wewin.autumn.boot.starter;

/**
 * Marker type for the Autumn Spring Boot starter.
 *
 * <p>The starter itself carries no logic; adding
 * {@code xyz.we-win.autumn:autumn-spring-boot-starter} to a Spring Boot application
 * activates the auto-configuration shipped by {@code autumn-spring-boot-autoconfigure}.
 * This class exists so that consumers can reliably detect the starter on the classpath
 * and so that the module ships the sources / javadoc jars required by Maven Central.
 */
public final class AutumnStarter {

    /** Maven artifact id of this starter. */
    public static final String ARTIFACT_ID = "autumn-spring-boot-starter";

    private AutumnStarter() {
    }
}
