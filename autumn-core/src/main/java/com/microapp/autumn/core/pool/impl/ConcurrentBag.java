package com.microapp.autumn.core.pool.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.thrift.TServiceClient;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.layered.TFramedTransport;

import com.microapp.autumn.api.Discovery;
import com.microapp.autumn.api.config.ConsumerConfig;
import com.microapp.autumn.api.config.ReferenceConfig;
import com.microapp.autumn.api.enums.RegistryTypeEnum;
import com.microapp.autumn.api.util.AutumnException;
import com.microapp.autumn.api.util.SpiUtil;

import lombok.extern.slf4j.Slf4j;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

@Slf4j
public class ConcurrentBag implements AutoCloseable {
    private volatile boolean closed;
    // TODO use ConcurrentLinkedDeque replace ConcurrentLinkedQueue
    private final ConcurrentLinkedQueue<ConcurrentBagEntry> sharedList = new ConcurrentLinkedQueue<>();
    private final SynchronousQueue<ConcurrentBagEntry> handoffQueue = new SynchronousQueue<>(true);
    private final AtomicInteger waiters = new AtomicInteger(0);
    private final Map<String, ConcurrentBagEntry> mapping = new ConcurrentHashMap<>();

    private int maxWaiters;
    private ReferenceConfig<? extends TServiceClient> config;

    public ConcurrentBag(ReferenceConfig<? extends TServiceClient> config) {
        maxWaiters = 10;
        this.config = config;
    }

    public ReferenceConfig<? extends TServiceClient> getConfig() {
        return config;
    }

    public ConcurrentBagEntry borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException {
        ConcurrentBagEntry entry = sharedList.poll();
        if(null != entry && entry.getState() == ConcurrentBagEntry.STATE_NOT_IN_USE) {
            entry.compareAndSet(ConcurrentBagEntry.STATE_NOT_IN_USE, ConcurrentBagEntry.STATE_IN_USE);
            return entry;
        }
        scale();
        entry = sharedList.poll();
        if(null != entry && entry.getState() == ConcurrentBagEntry.STATE_NOT_IN_USE) {
            entry.compareAndSet(ConcurrentBagEntry.STATE_NOT_IN_USE, ConcurrentBagEntry.STATE_IN_USE);
            return entry;
        }
        int waiting = waiters.get();
        if(waiting >= maxWaiters) {
            throw new AutumnException("autumn pool waiters over max!");
        }
        waiters.incrementAndGet();
        try {
            log.info("pool begin queue");
            do {
                final long start = System.currentTimeMillis();
                entry = handoffQueue.poll(timeout, NANOSECONDS);
                if (entry != null && entry.getState() == ConcurrentBagEntry.STATE_NOT_IN_USE) {
                    entry.compareAndSet(ConcurrentBagEntry.STATE_NOT_IN_USE, ConcurrentBagEntry.STATE_IN_USE);
                    return entry;
                }
                final long end = System.currentTimeMillis();
                timeout -= (end - start);
            } while (timeout > 0);
            log.info("pool end queue, entry null");
            return null;
        } finally {
            waiters.decrementAndGet();
        }
    }

    private synchronized Boolean scale() {
        List<ConsumerConfig> instances = null;
        if(RegistryTypeEnum.DIRECT == config.getRegistryTypeEnum()) {
            // direct connect: use pre-configured instances, skip discovery
            instances = config.getInstances();
        } else {
            Discovery discovery = SpiUtil.load(Discovery.class);
            instances = Objects.isNull(discovery) ? null : discovery.getInstances(config.getName());
        }
        config.setInstances(instances);
        List<ConsumerConfig> consumerConfigs = config.getInstances();
        if(Objects.isNull(consumerConfigs)) {
            return false;
        }
        for(ConsumerConfig consumerConfig: consumerConfigs) {
            AtomicInteger active = consumerConfig.getActive();
            if(Objects.isNull(active)) {
                active = new AtomicInteger(1);
                consumerConfig.setActive(active);
            } else {
                active.incrementAndGet();
            }
            if(Objects.isNull(consumerConfig.getConnections())) {
                consumerConfig.setConnections(10);
            }

            if(active.get() >= 500) {
                break;
            }

            ConcurrentBagEntry entry = converter(consumerConfig);
            if(Objects.isNull(entry)) {
                continue;
            }
            add(entry);
        }
        return true;
    }

    private ConcurrentBagEntry converter(ConsumerConfig consumerConfig) {
        String ip = consumerConfig.getIp();
        Integer port = consumerConfig.getPort();
        String name = consumerConfig.getName();

        TTransport transport = null;
        TTransport tsocket = null;
        try {
            tsocket = new TSocket(ip, port);
            transport = new TFramedTransport(tsocket);
            transport.open();
            ConcurrentBagEntry entry = new ConcurrentBagEntryImpl<>(name, ip, port, transport);
            entry.setState(ConcurrentBagEntry.STATE_NOT_IN_USE);
            return entry;
        } catch (TTransportException e) {
            log.warn("scale thrift connection fail, error:", e);
            return null;
        } catch (Exception e) {
            log.warn("scale thrift connection open fail, entry null!");
            return null;
        }
    }

    public void requite(final ConcurrentBagEntry bagEntry){
        if(closed || bagEntry.getState() == ConcurrentBagEntry.STATE_REMOVED) {
            log.info("ConcurrentBag has been closed.");
            bagEntry.close();
            return;
        }

        long timeout = 500L;
        for (int i = 0; waiters.get() > 0; i++) {
            if (handoffQueue.offer(bagEntry)) {
                bagEntry.setState(ConcurrentBagEntry.STATE_NOT_IN_USE);
                return;
            }
            final long start = System.currentTimeMillis();
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            final long end = System.currentTimeMillis();
            timeout -= (end - start);
            if(timeout < 0) {
                break;
            }
        }
        bagEntry.setState(ConcurrentBagEntry.STATE_NOT_IN_USE);
        sharedList.offer(bagEntry);
    }

    public void add(ConcurrentBagEntry entry) {
        mapping.put(entry.getId(), entry);
        long timeout = 500;
        while (waiters.get() > 0 && !handoffQueue.offer(entry)) {
            final long start = System.currentTimeMillis();
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            final long end = System.currentTimeMillis();
            timeout -= (end - start);
            if(timeout < 0) {
                break;
            }
        }
        sharedList.add(entry);
    }

    @Override
    public void close() throws Exception {
        closed = true;
        mapping.forEach((k, v) -> {
            if(v.getState() != ConcurrentBagEntry.STATE_IN_USE) {
                v.setState(ConcurrentBagEntry.STATE_REMOVED);
                v.close();
                return;
            }
            v.setState(ConcurrentBagEntry.STATE_REMOVED);
        });

        mapping.clear();
        sharedList.clear();
        handoffQueue.clear();
        waiters.set(0);
    }

    public void remove(ConcurrentBagEntry entry) {
        entry.setState(ConcurrentBagEntry.STATE_REMOVED);
        sharedList.forEach(it -> {
            if(it.getId().equals(entry.getId())) {
                sharedList.remove(it);
            }
        });

        handoffQueue.forEach(it -> {
            if(it.getId().equals(entry.getId())) {
                handoffQueue.remove(it);
            }
        });
        mapping.remove(entry.getId());
    }

    public void remove(String ip) {
        sharedList.forEach(it -> {
            if(it.getIp().equals(ip)) {
                sharedList.remove(it);
            }
        });

        handoffQueue.forEach(it -> {
            if(it.getIp().equals(ip)) {
                handoffQueue.remove(it);
            }
        });

        mapping.forEach((k, v) -> {
            if(!v.getIp().equals(ip)) {
                return;
            }
            mapping.remove(k);
            v.setState(ConcurrentBagEntry.STATE_REMOVED);
            if(v.getState() != ConcurrentBagEntry.STATE_IN_USE) {
                v.close();
            }
        });
    }

    public void join(ConsumerConfig consumerConfig) {
        List<ConsumerConfig> consumerConfigs = config.getInstances();
        long count = mapping.values().stream()
                .filter(it -> it.getService().equals(consumerConfig.getName()))
                .count();
        int size = config.getInstances().size();
        long avg = count / size;
        if(avg < 1L) {
            ConcurrentBagEntry entry = converter(consumerConfig);
            if(Objects.nonNull(entry)) {
                add(entry);
                return;
            }
        }
        for(long i = 0; i < avg; i++) {
            ConcurrentBagEntry entry = converter(consumerConfig);
            if(Objects.nonNull(entry)) {
                add(entry);
                return;
            }
        }
    }



}
