package com.microapp.autumn.boot;

import org.springframework.context.SmartLifecycle;

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
        // 启动失败会抛异常，让 spring 上下文启动失败，而不是注册一个不可用的实例
        AutumnBootstrap.getInstance().serve();
        running = true;
        log.info("autumn framework started.");
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        // 注销注册中心、停止发现线程、停止 server、关闭连接池
        AutumnBootstrap.getInstance().close();
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
