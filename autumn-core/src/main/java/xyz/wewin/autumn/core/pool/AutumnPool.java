package xyz.wewin.autumn.core.pool;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.thrift.TServiceClient;

import xyz.wewin.autumn.api.config.ConsumerConfig;
import xyz.wewin.autumn.api.config.ReferenceConfig;
import xyz.wewin.autumn.core.devops.AutumnMetrics;
import xyz.wewin.autumn.core.pool.impl.ConcurrentBag;
import xyz.wewin.autumn.core.pool.impl.ConcurrentBagEntry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AutumnPool implements AutoCloseable {
    private static final long EVICT_INTERVAL_SECONDS = 30L;

    private volatile static AutumnPool singleton = null;
    private ConcurrentHashMap<String, ConcurrentBag> mapping;
    private ScheduledExecutorService evictExecutor;

    private AutumnPool() {

    }

    private void init() {
        mapping = new ConcurrentHashMap<>();
        evictExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "autumn-pool-evict");
            thread.setDaemon(true);
            return thread;
        });
        evictExecutor.scheduleWithFixedDelay(this::evict, EVICT_INTERVAL_SECONDS, EVICT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public static AutumnPool getInstance() {
        if (singleton == null) {
            synchronized (AutumnPool.class) {
                if (singleton == null) {
                    singleton = new AutumnPool();
                    singleton.init();
                    return singleton;
                }
            }
        }
        return singleton;
    }

    public AutumnPool referenceConfig(ReferenceConfig<? extends TServiceClient> config) {
        if (Objects.isNull(config) || Objects.isNull(config.getName())) {
            return this;
        }
        mapping.computeIfAbsent(config.getName(), name -> new ConcurrentBag(config));
        return this;
    }

    public ReferenceConfig<? extends TServiceClient> getReferenceConfig(String name) {
        ConcurrentBag bag = mapping.get(name);
        if(Objects.isNull(bag)) {
            return null;
        }
        ReferenceConfig<? extends TServiceClient> config = bag.getConfig();
        return config;
    }

    public ConcurrentBagEntry borrow(String service) {
        ConcurrentBag bag = mapping.get(service);
        if(Objects.isNull(bag)) {
            log.warn("autumn reference service not registered, service:{}", service);
            return null;
        }
        try {
            ReferenceConfig<? extends TServiceClient> config = bag.getConfig();
            // 借用超时必须为正：连接池内部用 0 表示立即失败，这里统一回落到默认 1s
            long poolTimeout = Objects.isNull(config) ? 1_000L : config.effectivePoolTimeout();
            ConcurrentBagEntry entry = bag.borrow(poolTimeout, TimeUnit.MILLISECONDS);
            if (Objects.nonNull(entry)) {
                AutumnMetrics.borrowed(service);
            }
            return entry;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            log.warn("autumn borrow connection fail, service:{}, error:{}", service, e.getMessage());
            return null;
        }
    }

    public void requite(ConcurrentBagEntry entry) {
        if(Objects.isNull(entry)) {
            return;
        }
        AutumnMetrics.requited(entry.getService());
        ConcurrentBag bag = mapping.get(entry.getService());
        if(Objects.isNull(bag)) {
            // 池已关闭或配置已移除，连接没有归属，直接关闭
            try {
                entry.close();
            } catch (Exception e) {
                log.debug("autumn requite close orphan connection fail, error:{}", e.getMessage());
            }
            return;
        }
        bag.requite(entry);
    }

    /**
     * 调用异常后丢弃连接（读超时等场景下连接上的协议状态可能已错位）
     */
    public void discard(ConcurrentBagEntry entry) {
        if(Objects.isNull(entry)) {
            return;
        }
        AutumnMetrics.discarded(entry.getService());
        ConcurrentBag bag = mapping.get(entry.getService());
        if(Objects.isNull(bag)) {
            try {
                entry.close();
            } catch (Exception e) {
                log.debug("autumn discard close orphan connection fail, error:{}", e.getMessage());
            }
            return;
        }
        bag.discard(entry);
    }

    public void remove(ConcurrentBagEntry entry) {
        if(Objects.isNull(entry)) {
            return;
        }
        ConcurrentBag bag = mapping.get(entry.getService());
        if(Objects.isNull(bag)) {
            return;
        }
        bag.remove(entry);
    }

    public void join(ConsumerConfig consumerConfig) {
        if(Objects.isNull(consumerConfig) || Objects.isNull(consumerConfig.getName())) {
            return;
        }
        ConcurrentBag bag = mapping.get(consumerConfig.getName());
        if(Objects.isNull(bag)) {
            log.warn("autumn join consumer config fail, service not registered:{}", consumerConfig.getName());
            return;
        }
        bag.join(consumerConfig);
    }

    public void leave(ConsumerConfig consumerConfig) {
        if(Objects.isNull(consumerConfig) || Objects.isNull(consumerConfig.getName())) {
            return;
        }
        ConcurrentBag bag = mapping.get(consumerConfig.getName());
        if(Objects.isNull(bag)) {
            return;
        }
        bag.remove(consumerConfig.getIp(), consumerConfig.getPort());
    }

    private void evict() {
        try {
            mapping.values().forEach(ConcurrentBag::evict);
        } catch (Exception e) {
            log.warn("autumn pool evict fail, error:{}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if(Objects.nonNull(evictExecutor)) {
            evictExecutor.shutdownNow();
        }
        mapping.forEach((name, bag) -> {
            try {
                bag.close();
            } catch (Exception e) {
                log.warn("autumn pool close fail, service:{}, error:{}", name, e.getMessage());
            }
        });
        mapping.clear();
        log.info("autumn pool closed.");
    }

}
