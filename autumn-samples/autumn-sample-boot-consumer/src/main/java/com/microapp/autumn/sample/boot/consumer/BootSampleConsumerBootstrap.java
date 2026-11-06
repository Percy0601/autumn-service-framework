package com.microapp.autumn.sample.boot.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

import com.microapp.autumn.sample.nativehints.TomcatNativeHints;

/**
 * @author: baoxin.zhao
 * @date: 2026/11/3
 *
 * <p>native-image 元数据全部在构建期产生：{@code @Reference} 的生成代理 / 动态代理组由
 * autumn-compiler 的 JSR-269 处理器写出，嵌入式 Tomcat 的反射与资源由
 * {@link TomcatNativeHints} 经 Spring AOT 写出。</p>
 */
@SpringBootApplication
@ImportRuntimeHints(TomcatNativeHints.class)
public class BootSampleConsumerBootstrap {

    public static void main(String[] args) {
        SpringApplication.run(BootSampleConsumerBootstrap.class, args);
    }

}
