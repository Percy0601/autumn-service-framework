package com.microapp.autumn.registry.extension;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microapp.autumn.api.Discovery;
import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ConsumerConfig;
import com.microapp.autumn.core.pool.AutumnPool;
import com.microapp.autumn.registry.extension.backend.RegistryBackends;

import com.autumn.cloud.extension.core.api.ServiceInstance;

/**
 * 基于 autumn-cloud-extension 的服务发现实现。
 *
 * <p>按 {@code registry.discovery-interval}（默认 5s）轮询 extension 的
 * {@code ServiceDiscoveryClient}，把 {@code ServiceInstance} 翻译成 autumn 连接池使用的
 * {@link ConsumerConfig}。实例对象按 {@code host:port} 复用，避免连接池活跃计数被重置。</p>
 */
public class ExtensionDiscovery implements Discovery {

    private static final Logger log = LoggerFactory.getLogger(ExtensionDiscovery.class);

    private static final long DEFAULT_INTERVAL_MILLIS = 5000L;

    private final Set<String> watched = ConcurrentHashMap.newKeySet();
    /**
     * service -> (host:port -> consumerConfig)，复用实例对象
     */
    private final Map<String, Map<String, ConsumerConfig>> registry = new ConcurrentHashMap<>();
    /**
     * service -> 当前实例快照，供连接池读取
     */
    private final Map<String, List<ConsumerConfig>> snapshots = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;

    @Override
    public void watch(String name) {
        if (Objects.isNull(name) || name.isBlank()) {
            return;
        }
        if (watched.add(name)) {
            log.info("autumn discovery watch service:{}", name);
        }
        start();
    }

    @Override
    public Set<String> services() {
        return Set.copyOf(watched);
    }

    @Override
    public List<ConsumerConfig> getInstances(String name) {
        if (Objects.isNull(name) || name.isBlank()) {
            return List.of();
        }
        if (watched.add(name)) {
            start();
        }
        List<ConsumerConfig> current = snapshots.get(name);
        if (Objects.isNull(current)) {
            refresh(name);
            current = snapshots.get(name);
        }
        return Objects.isNull(current) ? List.of() : current;
    }

    @Override
    public void checkHealth() {
        // 健康检查由注册中心负责（consul TTL / nacos 心跳 / multicast 心跳超时剔除），本地无需额外探测
    }

    @Override
    public void discovery() {
        start();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread worker = this.worker;
        if (Objects.nonNull(worker)) {
            worker.interrupt();
        }
        watched.clear();
        registry.clear();
        snapshots.clear();
        log.info("autumn discovery closed.");
    }

    private void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            RegistryBackends.shared().startDiscovery();
        } catch (Exception e) {
            running.set(false);
            log.error("autumn discovery start failed:{}", e.getMessage(), e);
            return;
        }
        Thread worker = new Thread(this::loop, "autumn-extension-discovery");
        worker.setDaemon(true);
        this.worker = worker;
        worker.start();
        log.info("autumn discovery started, interval:{}ms", intervalMillis());
    }

    private void loop() {
        long interval = intervalMillis();
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            for (String service : watched) {
                refreshQuietly(service);
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void refreshQuietly(String service) {
        try {
            refresh(service);
        } catch (Exception e) {
            log.warn("autumn discovery refresh failed, service:{}, error:{}", service, e.getMessage());
        }
    }

    /**
     * 拉取并刷新指定服务的实例快照（包级可见，便于测试）
     */
    void refresh(String service) {
        ApplicationConfig applicationConfig = ApplicationConfig.getInstance();
        if (isConsul(applicationConfig) && Boolean.FALSE.equals(applicationConfig.getConsulDiscovery())) {
            return;
        }
        List<ServiceInstance> found = RegistryBackends.shared().discoveryClient().getInstances(service);
        Map<String, ConsumerConfig> current = registry.computeIfAbsent(service, key -> new ConcurrentHashMap<>());
        Set<String> alive = new HashSet<>();
        long now = System.currentTimeMillis();

        for (ServiceInstance instance : found) {
            if (Objects.isNull(instance) || Objects.isNull(instance.getHost()) || instance.getPort() <= 0) {
                continue;
            }
            String key = instance.getHost() + ":" + instance.getPort();
            ConsumerConfig consumerConfig = current.get(key);
            if (Objects.isNull(consumerConfig)) {
                consumerConfig = new ConsumerConfig();
                consumerConfig.setName(service);
                consumerConfig.setIp(instance.getHost());
                consumerConfig.setPort(instance.getPort());
                current.put(key, consumerConfig);
                log.info("autumn discovery instance added, service:{}, instance:{}", service, key);
            }
            consumerConfig.setLabel(instance.getTraffic());
            consumerConfig.setLatestTime(now);
            alive.add(key);
        }

        current.entrySet().removeIf(entry -> {
            if (alive.contains(entry.getKey())) {
                return false;
            }
            ConsumerConfig removed = entry.getValue();
            AutumnPool.getInstance().leave(removed);
            log.info("autumn discovery instance removed, service:{}, instance:{}", service, entry.getKey());
            return true;
        });
        snapshots.put(service, List.copyOf(current.values()));
    }

    private static long intervalMillis() {
        Integer interval = ApplicationConfig.getInstance().getRegistryDiscoveryInterval();
        return Objects.isNull(interval) || interval <= 0 ? DEFAULT_INTERVAL_MILLIS : interval;
    }

    private static boolean isConsul(ApplicationConfig applicationConfig) {
        return Objects.nonNull(applicationConfig.getRegistryType())
                && "consul".equalsIgnoreCase(applicationConfig.getRegistryType().trim());
    }
}
