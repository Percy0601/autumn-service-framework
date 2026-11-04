package com.microapp.autumn.core;

import java.util.Objects;

import com.microapp.autumn.api.Discovery;
import com.microapp.autumn.api.Registry;
import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ServiceConfig;
import com.microapp.autumn.api.util.SpiUtil;
import com.microapp.autumn.core.server.AutumnProvider;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * @author: baoxin.zhao
 * @date: 2024/5/8
 */
@Slf4j
@Setter
@Getter
public class AutumnBootstrap {

    private static volatile AutumnBootstrap instance;

    private AutumnBootstrap() {}

    public static AutumnBootstrap getInstance() {
        if(Objects.isNull(instance)) {
            synchronized (AutumnBootstrap.class) {
                if(Objects.isNull(instance)) {
                    instance = new AutumnBootstrap();
                }
            }
        }
        return instance;
    }

    public <T> AutumnBootstrap service(ServiceConfig<T> serviceConfig) {
        AutumnProvider provider = AutumnProvider.provider();
        provider.service(serviceConfig);
        return this;
    }

    public void serve() {
        AutumnProvider provider = AutumnProvider.provider();
        provider.start();
        log.info("autumn provider finish");

        Registry registry = SpiUtil.load(Registry.class);
        if (Objects.nonNull(registry)) {
            registry.register();
            log.info("autumn registry finish");
        } else {
            log.warn("autumn registry spi not found, add autumn-registry-extension dependency to enable registry.");
        }

        Discovery discovery = SpiUtil.load(Discovery.class);
        if (Objects.nonNull(discovery)) {
            discovery.discovery();
            discovery.checkHealth();
        } else {
            log.warn("autumn discovery spi not found, add autumn-registry-extension dependency to enable discovery.");
        }
        shutdownHook();
    }

    private void shutdownHook() {
        Runnable shutdownHook = () -> {
            Registry registry = SpiUtil.load(Registry.class);
            if (Objects.nonNull(registry)) {
                registry.shutdownHook();
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(shutdownHook));
    }



}
