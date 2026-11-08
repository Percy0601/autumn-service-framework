package xyz.wewin.autumn.registry.extension;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.wewin.autumn.api.Discovery;
import xyz.wewin.autumn.api.config.ApplicationConfig;
import xyz.wewin.autumn.api.config.ConsumerConfig;
import xyz.wewin.autumn.api.extension.TrafficRouter;
import xyz.wewin.autumn.core.pool.AutumnPool;
import xyz.wewin.autumn.registry.extension.backend.RegistryBackends;

import xyz.wewin.autumn.cloud.extension.core.api.ServiceInstance;

/**
 * 基于 autumn-cloud-extension 的服务发现实现。
 *
 * <p>按 {@code registry.discovery-interval}（默认 5s）轮询 extension 的
 * {@code ServiceDiscoveryClient}，把 {@code ServiceInstance} 翻译成 autumn 连接池使用的
 * {@link ConsumerConfig}。实例对象按 {@code host:port} 复用，避免连接池活跃计数被重置。</p>
 *
 * <p>快照保留所有泳道实例及其元数据标签；{@link #getInstances(String, Map)} 依据本次调用的隐式参数
 * （{@code traffic} / {@code X-Traffic} / {@code label.*}）筛选可用实例，语义与
 * autumn-cloud-extension 的 {@code TrafficSelector} 一致。</p>
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
        return snapshot(name);
    }

    @Override
    public List<ConsumerConfig> getInstances(String name, Map<String, String> parameters) {
        List<ConsumerConfig> all = snapshot(name);
        TrafficRouter.Route route = TrafficRouter.of(parameters);
        List<ConsumerConfig> routed = route.select(all);
        if (routed.size() != all.size()) {
            log.debug("autumn traffic route, service:{}, route:{}, candidates:{}/{}",
                    name, route, routed.size(), all.size());
        }
        return routed;
    }

    /** 全量实例快照（含各泳道标签）；首次访问时同步刷新一次。 */
    private List<ConsumerConfig> snapshot(String name) {
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
        // 泳道过滤下沉到每次调用：快照必须保留全量实例，否则按隐式参数切换泳道时看不到其它泳道的实例
        Map<String, ConsumerConfig> current = registry.computeIfAbsent(service, key -> new ConcurrentHashMap<>());
        Set<String> alive = new HashSet<>();
        long now = System.currentTimeMillis();

        for (ServiceInstance instance : found) {
            if (Objects.isNull(instance) || Objects.isNull(instance.getHost()) || instance.getPort() <= 0) {
                continue;
            }
            // 同一服务名下可能还有 http 实例（spring-cloud 注册或双协议声明），thrift 客户端跳过；
            // 未标注 protocol 的实例沿用既有语义，视为 thrift 可用，保持向后兼容
            if (!isThriftInstance(instance)) {
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
            consumerConfig.setLabels(metadataOf(instance));
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
        // 稳定排序，保证轮询游标不会被 ConcurrentHashMap 的迭代顺序打乱
        snapshots.put(service, current.values().stream()
                .sorted(Comparator.comparing(config -> config.getIp() + ":" + config.getPort()))
                .toList());
    }

    /**
     * 判断实例是否可作为 thrift 目标：显式声明 protocol 且不是 thrift 的实例跳过，
     * 未声明 protocol 的实例按既有语义视为 thrift 可用。
     */
    private static boolean isThriftInstance(ServiceInstance instance) {
        Map<String, String> metadata = instance.getMetadata();
        if (Objects.isNull(metadata)) {
            return true;
        }
        String protocol = metadata.get("protocol");
        return Objects.isNull(protocol) || protocol.isBlank() || "thrift".equalsIgnoreCase(protocol.trim());
    }

    /** 实例元数据快照，供任意标签选择器（label.*）匹配。 */
    private static Map<String, String> metadataOf(ServiceInstance instance) {        Map<String, String> metadata = instance.getMetadata();
        if (Objects.isNull(metadata) || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (Objects.nonNull(key) && Objects.nonNull(value)) {
                labels.put(key, value);
            }
        });
        return Map.copyOf(labels);
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
