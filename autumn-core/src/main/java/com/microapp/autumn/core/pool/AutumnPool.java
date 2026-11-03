package com.microapp.autumn.core.pool;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.thrift.TServiceClient;

import com.microapp.autumn.api.config.ConsumerConfig;
import com.microapp.autumn.api.config.ReferenceConfig;
import com.microapp.autumn.core.pool.impl.ConcurrentBag;
import com.microapp.autumn.core.pool.impl.ConcurrentBagEntry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AutumnPool {
    private volatile static AutumnPool singleton = null;
    private ConcurrentHashMap<String, ConcurrentBag> mapping;

    private AutumnPool() {

    }

    private void init() {
        mapping = new ConcurrentHashMap<>();
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
        if(mapping.contains(config.getName())) {
            return this;
        }
        ConcurrentBag bag = new ConcurrentBag(config);
        mapping.put(config.getName(), bag);
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
            long poolTimeout = 1000L;
            if(Objects.nonNull(config) && Objects.nonNull(config.getPoolTimeout())) {
                poolTimeout = config.getPoolTimeout();
            }
            ConcurrentBagEntry entry = bag.borrow(poolTimeout, TimeUnit.MILLISECONDS);
            return entry;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void requite(ConcurrentBagEntry entry) {
        if(Objects.isNull(entry)) {
            return;
        }
        ConcurrentBag bag = mapping.get(entry.getService());
        bag.requite(entry);
    }

    public void remove(ConcurrentBagEntry entry) {
        ConcurrentBag bag = mapping.get(entry.getService());
        bag.remove(entry);
    }

    public void join(ConsumerConfig consumerConfig) {
        ConcurrentBag bag = mapping.get(consumerConfig.getName());
        if(Objects.isNull(bag)) {
            log.warn("autumn join consumer config fail, service not registered:{}", consumerConfig.getName());
            return;
        }
        bag.join(consumerConfig);
    }

    public void leave(ConsumerConfig consumerConfig) {
        ConcurrentBag bag = mapping.get(consumerConfig.getName());
        if(Objects.isNull(bag)) {
            return;
        }
        bag.remove(consumerConfig.getIp());
    }

}
