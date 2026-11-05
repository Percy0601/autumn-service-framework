package com.microapp.autumn.springcloud.examples.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

import com.microapp.autumn.springcloud.examples.consumer.config.ConsumerNativeHints;

@SpringBootApplication
@ImportRuntimeHints(ConsumerNativeHints.class)
public class ConsumerBootstrap {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerBootstrap.class, args);
    }
}
