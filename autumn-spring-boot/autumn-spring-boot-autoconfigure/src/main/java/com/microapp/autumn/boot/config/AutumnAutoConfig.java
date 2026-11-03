package com.microapp.autumn.boot.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.microapp.autumn.boot.AutumnBeanPostProcessor;
import com.microapp.autumn.boot.AutumnLifecycle;

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
@EnableConfigurationProperties(AutumnProperties.class)
public class AutumnAutoConfig {

    @Bean
    public AutumnBeanPostProcessor autumnBeanPostProcessor(AutumnProperties properties) {
        return new AutumnBeanPostProcessor(properties);
    }

    @Bean
    public AutumnLifecycle autumnLifecycle() {
        return new AutumnLifecycle();
    }
}
