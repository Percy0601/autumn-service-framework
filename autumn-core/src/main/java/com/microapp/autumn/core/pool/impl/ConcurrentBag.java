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

/**
 * 单服务的连接池。
 *
 * <p>容量模型：{@code total} 是池内连接总数（含借出中的连接），{@code maxPoolSize} 是全局上限，
 * {@code ConsumerConfig.connections} 是单实例上限。扩容只在需要且未达上限时发生，建连动作在锁外执行，
 * 因此一个慢连接不会阻塞其它线程借用。</p>
 */
@Slf4j
public class ConcurrentBag implements AutoCloseable {

    private static final int MAX_WAITERS = 100;

    private volatile boolean closed;
    private final ConcurrentLinkedQueue<ConcurrentBagEntry> sharedList = new ConcurrentLinkedQueue<>();
    private final SynchronousQueue<ConcurrentBagEntry> handoffQueue = new SynchronousQueue<>(true);
    private final AtomicInteger waiters = new AtomicInteger(0);
    private final Map<String, ConcurrentBagEntry> mapping = new ConcurrentHashMap<>();
    /**
     * 池内连接总数（含借出中），只有 add/remove 会改变，保证名额判断准确
     */
    private final AtomicInteger total = new AtomicInteger(0);
    /**
     * 正在建立、尚未 add 的连接数量，扩容时用它预留名额
     */
    private final AtomicInteger creating = new AtomicInteger(0);

    private final ReferenceConfig<? extends TServiceClient> config;

    public ConcurrentBag(ReferenceConfig<? extends TServiceClient> config) {
        this.config = config;
    }

    public ReferenceConfig<? extends TServiceClient> getConfig() {
        return config;
    }

    public int size() {
        return total.get();
    }

    public int idleSize() {
        return sharedList.size();
    }

    public ConcurrentBagEntry borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException {
        Objects.requireNonNull(timeUnit, "timeUnit");
        if (closed) {
            throw new AutumnException("autumn pool has been closed");
        }
        long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
        int staleRetries = 0;

        for (;;) {
            ConcurrentBagEntry entry = sharedList.poll();
            if (Objects.nonNull(entry)) {
                int state = entry.getState();
                if (state == ConcurrentBagEntry.STATE_REMOVED) {
                    // 队列里残留的已摘除连接，关闭后继续
                    closeQuietly(entry);
                } else if (state == ConcurrentBagEntry.STATE_IN_USE) {
                    // 同一 entry 被重复入队的异常副本：放回并限制重试次数，避免死循环
                    sharedList.offer(entry);
                    if (++staleRetries > 3) {
                        staleRetries = 0;
                    } else {
                        continue;
                    }
                } else if (entry.compareAndSet(ConcurrentBagEntry.STATE_NOT_IN_USE, ConcurrentBagEntry.STATE_IN_USE)) {
                    if (validate(entry)) {
                        entry.touch();
                        return entry;
                    }
                    discard(entry);
                } else {
                    sharedList.offer(entry);
                }
            } else {
                staleRetries = 0;
            }

            if (ensureCapacity() > 0) {
                continue;
            }

            if (waiters.get() >= MAX_WAITERS) {
                log.warn("autumn pool waiters over limit {}, service:{}", MAX_WAITERS, config.getName());
                return null;
            }
            long remain = deadline - System.nanoTime();
            if (remain <= 0) {
                return null;
            }
            waiters.incrementAndGet();
            try {
                entry = handoffQueue.poll(remain, NANOSECONDS);
            } finally {
                waiters.decrementAndGet();
            }
            if (Objects.nonNull(entry)) {
                if (entry.getState() == ConcurrentBagEntry.STATE_REMOVED) {
                    closeQuietly(entry);
                } else if (entry.compareAndSet(ConcurrentBagEntry.STATE_NOT_IN_USE, ConcurrentBagEntry.STATE_IN_USE)) {
                    if (validate(entry)) {
                        entry.touch();
                        return entry;
                    }
                    discard(entry);
                } else {
                    sharedList.offer(entry);
                }
            }
        }
    }

    /**
     * 按需扩容：名额判断在锁内，建连在锁外。
     *
     * @return 本次新建的连接数
     */
    private int ensureCapacity() {
        if (closed) {
            return 0;
        }
        List<ConsumerConfig> instances = resolveInstances();
        if (Objects.isNull(instances) || instances.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (ConsumerConfig instance : instances) {
            if (total.get() >= maxPoolSize()) {
                break;
            }
            if (countOf(instance) >= maxConnectionsOf(instance)) {
                continue;
            }
            if (!reserveSlot()) {
                break;
            }
            ConcurrentBagEntry entry;
            try {
                entry = converter(instance);
            } finally {
                creating.decrementAndGet();
            }
            if (Objects.isNull(entry)) {
                continue;
            }
            add(entry);
            created++;
        }
        return created;
    }

    private boolean reserveSlot() {
        synchronized (this) {
            if (closed) {
                return false;
            }
            if (total.get() + creating.get() >= maxPoolSize()) {
                return false;
            }
            creating.incrementAndGet();
            return true;
        }
    }

    private List<ConsumerConfig> resolveInstances() {
        if (RegistryTypeEnum.DIRECT == config.getRegistryTypeEnum()) {
            return config.getInstances();
        }
        Discovery discovery = SpiUtil.load(Discovery.class);
        List<ConsumerConfig> instances = Objects.isNull(discovery)
                ? null : discovery.getInstances(config.getName());
        config.setInstances(instances);
        return instances;
    }

    private int maxPoolSize() {
        Integer size = config.getMaxPoolSize();
        return Objects.isNull(size) || size <= 0 ? 32 : size;
    }

    private int maxConnectionsOf(ConsumerConfig instance) {
        Integer connections = instance.getConnections();
        int perInstance = Objects.isNull(connections) || connections <= 0 ? maxPoolSize() : connections;
        return Math.min(perInstance, maxPoolSize());
    }

    private long countOf(ConsumerConfig instance) {
        return mapping.values().stream()
                .filter(entry -> Objects.equals(entry.getIp(), instance.getIp())
                        && Objects.equals(entry.getPort(), instance.getPort()))
                .count();
    }

    private ConcurrentBagEntry converter(ConsumerConfig consumerConfig) {
        String ip = consumerConfig.getIp();
        Integer port = consumerConfig.getPort();
        String name = consumerConfig.getName();
        if (Objects.isNull(ip) || Objects.isNull(port)) {
            return null;
        }

        TSocket tsocket = null;
        TTransport transport = null;
        try {
            tsocket = new TSocket(ip, port);
            applyTimeout(tsocket);
            transport = new TFramedTransport(tsocket);
            transport.open();
            ConcurrentBagEntry entry = new ConcurrentBagEntryImpl<>(name, ip, port, transport);
            entry.setState(ConcurrentBagEntry.STATE_NOT_IN_USE);
            return entry;
        } catch (TTransportException e) {
            closeQuietly(transport, tsocket);
            log.warn("autumn pool create connection fail, address:{}:{}, error:{}", ip, port, e.getMessage());
            return null;
        } catch (Exception e) {
            closeQuietly(transport, tsocket);
            log.warn("autumn pool create connection fail, address:{}:{}, error:{}", ip, port, e.getMessage());
            return null;
        }
    }

    private void applyTimeout(TSocket tsocket) {
        Long connectionTimeout = config.getConnectionTimeout();
        if (Objects.nonNull(connectionTimeout) && connectionTimeout > 0) {
            tsocket.setConnectTimeout(Math.toIntExact(connectionTimeout));
        }
        Long socketTimeout = config.getSocketTimeout();
        if (Objects.nonNull(socketTimeout) && socketTimeout > 0) {
            tsocket.setTimeout(Math.toIntExact(socketTimeout));
        }
    }

    private boolean validate(ConcurrentBagEntry entry) {
        if (Boolean.FALSE.equals(config.getValidateOnBorrow())) {
            return true;
        }
        Long maxLifetime = config.getMaxLifetime();
        if (Objects.nonNull(maxLifetime) && maxLifetime > 0
                && System.currentTimeMillis() - entry.getCreatedAt() > maxLifetime) {
            return false;
        }
        TTransport transport = entry.getEntry();
        return Objects.nonNull(transport) && transport.isOpen();
    }

    public void add(ConcurrentBagEntry entry) {
        if (Objects.isNull(entry)) {
            return;
        }
        if (closed) {
            closeQuietly(entry);
            return;
        }
        entry.setState(ConcurrentBagEntry.STATE_NOT_IN_USE);
        mapping.put(entry.getId(), entry);
        total.incrementAndGet();
        // offer 成功后连接已经交给等待者，不能再进入 sharedList，否则同一连接会被重复借出
        if (waiters.get() > 0 && handoffQueue.offer(entry)) {
            return;
        }
        sharedList.offer(entry);
    }

    public void requite(final ConcurrentBagEntry bagEntry) {
        if (Objects.isNull(bagEntry)) {
            return;
        }
        if (closed || bagEntry.getState() == ConcurrentBagEntry.STATE_REMOVED) {
            closeQuietly(bagEntry);
            return;
        }
        // 先改状态再交接，避免等待者拿到仍标记为 IN_USE 的连接
        bagEntry.setState(ConcurrentBagEntry.STATE_NOT_IN_USE);
        if (waiters.get() > 0 && handoffQueue.offer(bagEntry)) {
            return;
        }
        sharedList.offer(bagEntry);
    }

    /**
     * 调用失败（协议可能已错位）时丢弃连接，不再归还池。
     * 无论连接当前处于何种状态都立即物理关闭：持有方已显式放弃该连接，不会再 requite；
     * 若仅标记 REMOVED 而不关闭，会造成 socket 永久泄漏。
     */
    public void discard(ConcurrentBagEntry entry) {
        if (Objects.isNull(entry)) {
            return;
        }
        remove(entry);
        closeQuietly(entry);
    }

    @Override
    public void close() {
        closed = true;
        mapping.forEach((id, entry) -> {
            entry.setState(ConcurrentBagEntry.STATE_REMOVED);
            if (entry.getState() != ConcurrentBagEntry.STATE_IN_USE) {
                closeQuietly(entry);
            }
        });
        mapping.clear();
        sharedList.clear();
        handoffQueue.clear();
        waiters.set(0);
        total.set(0);
    }

    /**
     * 从池中彻底移除一条连接（包括队列中的引用）
     */
    public void remove(ConcurrentBagEntry entry) {
        if (Objects.isNull(entry)) {
            return;
        }
        if (Objects.nonNull(mapping.remove(entry.getId()))) {
            total.decrementAndGet();
        }
        for (;;) {
            int state = entry.getState();
            if (state == ConcurrentBagEntry.STATE_IN_USE) {
                if (entry.compareAndSet(ConcurrentBagEntry.STATE_IN_USE, ConcurrentBagEntry.STATE_REMOVED)) {
                    // 借出中：标记后由持有者 requite/discard 时关闭
                    return;
                }
                continue;
            }
            if (state == ConcurrentBagEntry.STATE_REMOVED) {
                return;
            }
            if (entry.compareAndSet(state, ConcurrentBagEntry.STATE_REMOVED)) {
                sharedList.remove(entry);
                closeQuietly(entry);
                return;
            }
        }
    }

    /**
     * 实例下线（按 IP 粒度，兼容旧调用）：关闭空闲连接，借出中的连接只标记，等归还时关闭。
     * 建议优先使用 {@link #remove(String, Integer)} 精确到端口，避免误删同机其它端口实例的连接。
     */
    public void remove(String ip) {
        remove(ip, null);
    }

    /**
     * 实例下线（精确到 ip:port）：关闭空闲连接，借出中的连接只标记，等归还时关闭。
     * 仅匹配 IP 相同且（port 非 null 时）端口相同的连接，避免同机多实例互相误删。
     */
    public void remove(String ip, Integer port) {
        if (Objects.isNull(ip)) {
            return;
        }
        for (ConcurrentBagEntry entry : mapping.values()) {
            if (!Objects.equals(ip, entry.getIp())) {
                continue;
            }
            if (Objects.nonNull(port) && !Objects.equals(port, entry.getPort())) {
                continue;
            }
            remove(entry);
        }
    }

    /**
     * 预建 minIdle 条连接（按实例平均），不会除零
     */
    public void join(ConsumerConfig consumerConfig) {
        if (Objects.isNull(consumerConfig) || closed) {
            return;
        }
        int target = Math.max(1, minIdle());
        int existing = (int) countOf(consumerConfig);
        for (int i = existing; i < target; i++) {
            if (total.get() >= maxPoolSize() || !reserveSlot()) {
                return;
            }
            ConcurrentBagEntry entry;
            try {
                entry = converter(consumerConfig);
            } finally {
                creating.decrementAndGet();
            }
            if (Objects.isNull(entry)) {
                return;
            }
            add(entry);
        }
    }

    /**
     * 回收超时空闲与超龄连接，由 AutumnPool 定时调用
     */
    public void evict() {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        long idleTimeout = idleTimeout();
        long maxLifetime = maxLifetime();
        for (ConcurrentBagEntry entry : mapping.values()) {
            if (entry.getState() == ConcurrentBagEntry.STATE_IN_USE) {
                continue;
            }
            boolean idleExpired = idleTimeout > 0 && now - entry.getLastUsed() > idleTimeout;
            boolean lifetimeExpired = maxLifetime > 0 && now - entry.getCreatedAt() > maxLifetime;
            if (idleExpired || lifetimeExpired) {
                remove(entry);
            }
        }
    }

    private int minIdle() {
        Integer minIdle = config.getMinIdle();
        return Objects.isNull(minIdle) || minIdle < 0 ? 0 : minIdle;
    }

    private long idleTimeout() {
        Long value = config.getIdleTimeout();
        return Objects.isNull(value) || value < 0 ? 0 : value;
    }

    private long maxLifetime() {
        Long value = config.getMaxLifetime();
        return Objects.isNull(value) || value < 0 ? 0 : value;
    }

    private void closeQuietly(ConcurrentBagEntry entry) {
        try {
            entry.close();
        } catch (Exception e) {
            log.debug("autumn pool close connection fail, error:{}", e.getMessage());
        }
    }

    private void closeQuietly(TTransport transport, TSocket socket) {
        if (Objects.nonNull(transport)) {
            try {
                transport.close();
            } catch (Exception ignored) {
                // 建连失败清理
            }
        }
        if (Objects.nonNull(socket)) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 建连失败清理
            }
        }
    }
}
