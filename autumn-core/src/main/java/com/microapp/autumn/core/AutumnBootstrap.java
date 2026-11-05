package com.microapp.autumn.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.microapp.autumn.api.Discovery;
import com.microapp.autumn.api.Registry;
import com.microapp.autumn.api.config.ServiceConfig;
import com.microapp.autumn.api.util.AutumnException;
import com.microapp.autumn.api.util.SpiUtil;
import com.microapp.autumn.api.util.ThreadUtil;
import com.microapp.autumn.core.pool.AutumnPool;
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

    private final AtomicBoolean serving = new AtomicBoolean(false);
    private volatile boolean closed;
    private volatile boolean hookRegistered;

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

    /**
     * 启动服务端并注册；端口绑定失败时抛出异常，调用方（Spring 生命周期）应让上下文启动失败
     */
    public void serve() {
        if(closed) {
            throw new AutumnException("autumn bootstrap has been closed, create a new instance to serve again");
        }
        if(!serving.compareAndSet(false, true)) {
            log.warn("autumn bootstrap already serving, skip repeat serve.");
            return;
        }
        try {
            AutumnProvider provider = AutumnProvider.provider();
            provider.start();
            log.info("autumn provider finish");

            Registry registry = SpiUtil.load(Registry.class);
            if (Objects.nonNull(registry)) {
                Boolean registered = registry.register();
                if(Boolean.FALSE.equals(registered)) {
                    log.warn("autumn registry finish with failure, local server keeps serving but this instance may be undiscoverable.");
                } else {
                    log.info("autumn registry finish");
                }
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
        } catch (RuntimeException e) {
            serving.set(false);
            throw e;
        }
    }

    /**
     * 优雅停机：注销实例 -> 停发现 -> 停 server -> 关连接池，可重复调用
     */
    public synchronized void close() {
        if(closed) {
            return;
        }
        closed = true;
        try {
            Registry registry = SpiUtil.load(Registry.class);
            if (Objects.nonNull(registry)) {
                registry.shutdownHook();
            }
        } catch (Exception e) {
            log.warn("autumn registry shutdown exception:{}", e.getMessage());
        }
        try {
            Discovery discovery = SpiUtil.load(Discovery.class);
            if (Objects.nonNull(discovery)) {
                discovery.close();
            }
        } catch (Exception e) {
            log.warn("autumn discovery shutdown exception:{}", e.getMessage());
        }
        try {
            AutumnProvider.provider().stop();
        } catch (Exception e) {
            log.warn("autumn server stop exception:{}", e.getMessage());
        }
        try {
            ThreadUtil.getInstance().shutdown();
        } catch (Exception e) {
            log.warn("autumn thread pool shutdown exception:{}", e.getMessage());
        }
        try {
            AutumnPool.getInstance().close();
        } catch (Exception e) {
            log.warn("autumn pool close exception:{}", e.getMessage());
        }
        serving.set(false);
        log.info("autumn framework closed.");
    }

    private synchronized void shutdownHook() {
        if(hookRegistered) {
            return;
        }
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "autumn-shutdown"));
    }

}
