package xyz.wewin.autumn.core.pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import xyz.wewin.autumn.api.config.ConsumerConfig;

/**
 * 消费侧负载均衡：决定新建连接时优先服务哪个实例，配合 sharedList 的 FIFO 归还形成稳定轮询。
 *
 * <p>支持 round-robin（默认）/ random / least-connections，策略由 {@code consumer.load-balance} 配置。</p>
 */
public final class LoadBalancer {

    public enum Strategy {
        ROUND_ROBIN, RANDOM, LEAST_CONNECTIONS
    }

    private final AtomicInteger cursor = new AtomicInteger(0);
    private final Strategy strategy;

    public LoadBalancer(String strategyName) {
        this.strategy = parse(strategyName);
    }

    private static Strategy parse(String name) {
        if (Objects.isNull(name) || name.isBlank()) {
            return Strategy.ROUND_ROBIN;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "random" -> Strategy.RANDOM;
            case "least-connections", "leastconnections" -> Strategy.LEAST_CONNECTIONS;
            default -> Strategy.ROUND_ROBIN;
        };
    }

    public Strategy strategy() {
        return strategy;
    }

    /**
     * @param instances    discovery 返回的候选实例（不修改原列表）
     * @param activeCounts ip:port -> 当前借出中的连接数，供 least-connections 使用
     */
    public List<ConsumerConfig> order(List<ConsumerConfig> instances, Map<String, Long> activeCounts) {
        if (Objects.isNull(instances) || instances.size() < 2) {
            return instances;
        }
        List<ConsumerConfig> ordered = new ArrayList<>(instances);
        switch (strategy) {
            case RANDOM -> Collections.shuffle(ordered);
            case LEAST_CONNECTIONS -> ordered.sort(Comparator.comparingLong(
                    instance -> activeCounts.getOrDefault(key(instance.getIp(), instance.getPort()), 0L)));
            case ROUND_ROBIN -> Collections.rotate(ordered,
                    -Math.floorMod(cursor.getAndIncrement(), ordered.size()));
        }
        return ordered;
    }

    public static String key(String ip, Integer port) {
        return ip + ":" + port;
    }
}
