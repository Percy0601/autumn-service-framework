package com.microapp.autumn.boot;

import java.util.Objects;

import org.springframework.context.SmartLifecycle;

import com.microapp.autumn.api.Registry;
import com.microapp.autumn.api.util.SpiUtil;
import com.microapp.autumn.core.AutumnBootstrap;

import lombok.extern.slf4j.Slf4j;

/**
 * autumn framework lifecycle, start provider/registry/discovery when spring context ready,
 * stop gracefully when spring context closed.
 *
 * @author: baoxin.zhao
 * @date: 2026/11/3
 */
@Slf4j
public class AutumnLifecycle implements SmartLifecycle {

    private volatile boolean running = false;

    @Override
    public void start() {
        if (running) {
            return;
        }
        AutumnBootstrap.getInstance().serve();
        running = true;
        log.info("autumn framework started.");
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        Registry registry = SpiUtil.load(Registry.class);
        if (Objects.nonNull(registry)) {
            registry.shutdownHook();
        }
        running = false;
        log.info("autumn framework stopped.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
