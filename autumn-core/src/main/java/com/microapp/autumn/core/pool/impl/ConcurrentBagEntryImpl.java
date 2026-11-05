package com.microapp.autumn.core.pool.impl;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.thrift.transport.TTransport;

public class ConcurrentBagEntryImpl<T extends TTransport> implements ConcurrentBagEntry{
    private final AtomicInteger state =  new AtomicInteger(STATE_NOT_IN_USE);
    private final long createdAt = System.currentTimeMillis();
    private final AtomicLong lastUsed = new AtomicLong(createdAt);
    private String service;
    private String ip;
    private Integer port;
    private String id;
    private T entry;

    public ConcurrentBagEntryImpl(String service, String ip, Integer port, T entry) {
        this.service = service;
        this.entry = entry;
        this.ip = ip;
        this.port = port;
        id = UUID.randomUUID().toString();
    }

    @Override
    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public long getLastUsed() {
        return lastUsed.get();
    }

    @Override
    public void touch() {
        lastUsed.set(System.currentTimeMillis());
    }

    @Override
    public T getEntry() {
        return entry;
    }

    @Override
    public String getService() {
        return this.service;
    }

    @Override
    public String getIp() {
        return this.ip;
    }

    @Override
    public Integer getPort() {
        return this.port;
    }

    @Override
    public String getId() {
        return this.id;
    }

    @Override
    public void close() {
        entry.close();
    }

    @Override
    public boolean compareAndSet(int expectState, int newState) {
        return state.compareAndSet(expectState, newState);
    }

    @Override
    public void setState(int newState) {
        state.set(newState);
    }

    @Override
    public int getState() {
        return state.get();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ConcurrentBagEntryImpl<?> that = (ConcurrentBagEntryImpl<?>) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
