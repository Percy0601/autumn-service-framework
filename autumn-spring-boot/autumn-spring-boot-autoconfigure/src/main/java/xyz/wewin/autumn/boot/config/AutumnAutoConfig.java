package xyz.wewin.autumn.boot.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Role;

import xyz.wewin.autumn.boot.AutumnBeanPostProcessor;
import xyz.wewin.autumn.boot.AutumnLifecycle;
import xyz.wewin.autumn.boot.aot.AutumnRuntimeHints;

/**
 * autumn spring boot auto configuration.
 *
 * <pre>
 * 1、bind autumn.* properties into AutumnProperties
 * 2、register AutumnBeanPostProcessor to handle @Export / @Reference
 * 3、register AutumnLifecycle to start/stop autumn framework with spring context
 * </pre>
 *
 * @author: baoxin.zhao
 * @date: 2026/11/3
 */
@AutoConfiguration
@ImportRuntimeHints(AutumnRuntimeHints.class)
public class AutumnAutoConfig {

    @Bean
    @ConfigurationProperties(prefix = "autumn")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static AutumnProperties autumnProperties() {
        return new AutumnProperties();
    }

    @Bean
    public static AutumnBeanPostProcessor autumnBeanPostProcessor(AutumnProperties properties) {
        return new AutumnBeanPostProcessor(properties);
    }

    @Bean
    public AutumnLifecycle autumnLifecycle() {
        return new AutumnLifecycle();
    }
}
