package xyz.wewin.autumn.boot;

import java.util.Objects;

import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;

import xyz.wewin.autumn.api.config.ProviderConfig;
import xyz.wewin.autumn.core.AutumnBootstrap;

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

    /** web 容器真正完成初始化后由 spring boot 写入的运行时端口，未启动 web 时不存 */
    private static final String LOCAL_SERVER_PORT = "local.server.port";

    private final Environment environment;

    private volatile boolean running = false;

    public AutumnLifecycle(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        detectHttpEndpoint();
        // 启动失败会抛异常，让 spring 上下文启动失败，而不是注册一个不可用的实例
        AutumnBootstrap.getInstance().serve();
        running = true;
        log.info("autumn framework started.");
    }

    /**
     * 只有 web 容器真的启动并绑定端口后，spring boot 才会写入 local.server.port；
     * 这里读的是运行时实际端口，而不是 server.port 配置：random port、未引入 web starter、
     * 容器启动失败都不会误报 http。
     */
    private void detectHttpEndpoint() {
        Integer port = environment.getProperty(LOCAL_SERVER_PORT, Integer.class);
        if (Objects.isNull(port) || port <= 0) {
            log.info("autumn no http endpoint detected, thrift only");
            return;
        }
        ProviderConfig.getInstance().setHttpPort(port);
        log.info("autumn http endpoint detected, port:{}", port);
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
