package com.microapp.autumn.springcloud.examples.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

import com.microapp.autumn.springcloud.examples.provider.config.ProviderNativeHints;

@SpringBootApplication
@ImportRuntimeHints(ProviderNativeHints.class)
public class ProviderBootstrap {

    public static void main(String[] args) {
        SpringApplication.run(ProviderBootstrap.class, args);
    }
}
