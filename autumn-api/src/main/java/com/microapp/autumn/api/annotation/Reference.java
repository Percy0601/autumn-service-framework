package com.microapp.autumn.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * reference remote service
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Reference {
    /**
     * register center service name
     *
     * @return
     */
    String refer() default "";

    /**
     * direct connect remote ip and port
     * @return
     */
    String ipPort() default "";
}
