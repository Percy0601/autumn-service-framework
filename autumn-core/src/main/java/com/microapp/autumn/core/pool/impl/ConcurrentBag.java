package com.microapp.autumn.core.pool.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ConsumerConfig;
import com.microapp.autumn.api.config.ReferenceConfig;
import com.microapp.autumn.api.enums.RegistryTypeEnum;
import com.microapp.autumn.api.extension.AttachmentContext;
import com.microapp.autumn.api.extension.TrafficRouter;
import com.microapp.autumn.api.util.AutumnException;
import com.microapp.autumn.api.util.SpiUtil;
import com.microapp.autumn.core.pool.LoadBalancer;

import lombok.extern.slf4j.Slf4j;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * 单服务的连接池。
 *
 * <p>容量模型：{@code total} 是池内连接总数（含借出中的连接），{@code maxPoolSize} 是全局上限，
 * {@code ConsumerConfig.connections} 是单实例上限。扩容只在需要且未达上限时发生，建连动作在锁外执行，
 * 因此一个慢连接不会阻塞其它线程借用。</p>
 *
 * <p>借用时按当前调用的隐式参数（泳道 / 标签）筛选实例，保证连接池复用不会把请求打到别的泳道。</p>
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
    private final LoadBalancer loadBalancer =
            new LoadBalancer(ApplicationConfig.getInstance().getConsumerLoadBalance());

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
        return borrow(timeout, timeUnit, AttachmentContext.snapshot());
    }

    /**
     * 按本次调用的隐式参数借用连接：只返回当前泳道 / 标签命中的实例上的连接。
     *
     * <p>连接池是全服务共享的，池里可能同时存在多个泳道的空闲连接。这里把非本次候选实例的
     * 空闲连接暂存到本地列表，借到连接后原样放回队列，既不丢连接也不把流量打错泳道；
     * 扩容也只针对候选实例建连，池被其它泳道占满时优先释放那些空闲连接腾出名额。</p>
     */
    public ConcurrentBagEntry borrow(long timeout, final TimeUnit timeUnit,
                                     Map<String, String> parameters) throws InterruptedException {
        Objects.requireNonNull(timeUnit, "timeUnit");
        if (closed) {
            throw new AutumnException("autumn pool has been closed");
        }
        long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
        int staleRetries = 0;

        TrafficRouter.Route route = TrafficRouter.of(parameters);
        List<ConsumerConfig> candidates = resolveInstances(route, parameters);
        Set<String> eligible = TrafficRouter.endpointKeys(candidates);
        // 发现结果为空时保持原有行为（fail-open），避免注册中心暂时不可用却拒绝借用现成连接
        boolean laneAware = !eligible.isEmpty();
        List<ConcurrentBagEntry> parked = new ArrayList<>();
        try {
            for (;;) {
                ConcurrentBagEntry entry = sharedList.poll();
                if (Objects.nonNull(entry)) {
                    if (laneAware && entry.getState() == ConcurrentBagEntry.STATE_NOT_IN_USE
                            && !eligible.contains(LoadBalancer.key(entry.getIp(), entry.getPort()))) {
                        // 其它泳道的空闲连接：本次不借用，结束时原样放回
                        parked.add(entry);
                        staleRetries = 0;
                        if (System.nanoTime() >= deadline) {
                            return null;
                        }
                        continue;
                    }
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
                    } else if (entry.compareAndSet(ConcurrentBagEntry.STATE_NOT_IN_USE,
                            ConcurrentBagEntry.STATE_IN_USE)) {
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

                if (ensureCapacity(deadline, candidates, parked, laneAware ? eligible : Set.of()) > 0) {
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
                    if (laneAware && entry.getState() == ConcurrentBagEntry.STATE_NOT_IN_USE
                            && !eligible.contains(LoadBalancer.key(entry.getIp(), entry.getPort()))) {
                        parked.add(entry);
                        if (System.nanoTime() >= deadline) {
                            return null;
                        }
                        continue;
                    }
                    if (entry.getState() == ConcurrentBagEntry.STATE_REMOVED) {
                        closeQuietly(entry);
                    } else if (entry.compareAndSet(ConcurrentBagEntry.STATE_NOT_IN_USE,
                            ConcurrentBagEntry.STATE_IN_USE)) {
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
        } finally {
            restoreParked(parked);
        }
    }

    /** 归还本次借用中暂存的非本泳道空闲连接；已被扩容摘除的连接不再放回。 */
    private void restoreParked(List<ConcurrentBagEntry> parked) {
        if (parked.isEmpty()) {
            return;
        }
        for (ConcurrentBagEntry entry : parked) {
            if (closed) {
                closeQuietly(entry);
            } else if (entry.getState() == ConcurrentBagEntry.STATE_NOT_IN_USE) {
                if (waiters.get() > 0 && handoffQueue.offer(entry)) {
                    continue;
                }
                sharedList.offer(entry);
            }
        }
    }

    /**
     * 按需扩容：名额判断在锁内，建连在锁外。
     *
     * <p>建连（TCP connect）耗时计入本次借用的剩余预算：服务端未启动时连接可能长时间挂住，
     * 这里用剩余预算截断建连超时，保证 {@code borrow} 不会超过调用方给的超时时间。</p>
     *
     * @param deadlineNanos 本次借用的截止时间（{@link System#nanoTime()} 口径），预算耗尽后不再建连
     * @return 本次新建的连接数
     */
    private int ensureCapacity(long deadlineNanos, List<ConsumerConfig> candidates,
                               List<ConcurrentBagEntry> parked, Set<String> eligible) {
        if (closed) {
            return 0;
        }
        if (Objects.isNull(candidates) || candidates.isEmpty()) {
            return 0;
        }
        if (!eligible.isEmpty() && total.get() + creating.get() >= maxPoolSize()) {
            // 池被其它泳道的空闲连接占满：先释放它们，给当前泳道腾出建连名额
            freeIdleOutside(parked, eligible);
        }
        int created = 0;
        for (ConsumerConfig instance : candidates) {
            if (total.get() >= maxPoolSize()) {
                break;
            }
            if (countOf(instance) >= maxConnectionsOf(instance)) {
                continue;
            }
            // 每建一条连接前重新计算预算，多实例扩容的总耗时同样受借用超时约束
            long remainingMillis = NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMillis <= 0) {
                break;
            }
            if (!reserveSlot()) {
                break;
            }
            ConcurrentBagEntry entry;
            try {
                entry = converter(instance, remainingMillis);
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

    private void freeIdleOutside(List<ConcurrentBagEntry> parked, Set<String> eligible) {
        for (ConcurrentBagEntry entry : parked) {
            if (total.get() + creating.get() < maxPoolSize()) {
                return;
            }
            if (entry.getState() == ConcurrentBagEntry.STATE_NOT_IN_USE
                    && !eligible.contains(LoadBalancer.key(entry.getIp(), entry.getPort()))) {
                remove(entry);
            }
        }
    }

    private List<ConsumerConfig> resolveInstances(TrafficRouter.Route route, Map<String, String> parameters) {
        List<ConsumerConfig> instances;
        if (RegistryTypeEnum.DIRECT == config.getRegistryTypeEnum()) {
            instances = config.getInstances();
        } else {
            Discovery discovery = SpiUtil.load(Discovery.class);
            instances = Objects.isNull(discovery) ? null : discovery.getInstances(config.getName(), parameters);
            config.setInstances(instances);
        }
        // 注册中心实现已按参数过滤；这里按同一条件再收敛一次，兼容返回全量实例的
        // Discovery 实现与 direct 模式（重复筛选幂等）
        List<ConsumerConfig> selected = route.select(instances);
        // 按负载均衡策略旋转候选实例，扩容时轮流为各实例建连；归还走 sharedList FIFO，形成稳定轮询
        return loadBalancer.order(selected, activeCounts());
    }

    private Map<String, Long> activeCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (ConcurrentBagEntry entry : mapping.values()) {
            if (entry.getState() == ConcurrentBagEntry.STATE_IN_USE) {
                counts.merge(LoadBalancer.key(entry.getIp(), entry.getPort()), 1L, Long::sum);
            }
        }
        return counts;
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

    private ConcurrentBagEntry converter(ConsumerConfig consumerConfig, long maxConnectMillis) {
        String ip = consumerConfig.getIp();
        Integer port = consumerConfig.getPort();
        String name = consumerConfig.getName();
        if (Objects.isNull(ip) || Objects.isNull(port)) {
            return null;
        }

        long connectTimeout = Math.min(config.effectiveConnectionTimeout(), maxConnectMillis);
        if (connectTimeout <= 0) {
            // 剩余预算已耗尽，不再发起建连
            return null;
        }

        TSocket tsocket = null;
        TTransport transport = null;
        try {
            tsocket = new TSocket(ip, port);
            applyTimeout(tsocket, connectTimeout, config.effectiveSocketTimeout());
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

    /**
     * 建连与读响应都必须有上限：0 在 JDK 中表示无限等待，负值会抛异常，因此统一收敛到正数。
     */
    private static void applyTimeout(TSocket tsocket, long connectMillis, long socketMillis) {
        tsocket.setConnectTimeout(toTimeoutMillis(connectMillis, ReferenceConfig.DEFAULT_CONNECTION_TIMEOUT_MILLIS));
        tsocket.setSocketTimeout(toTimeoutMillis(socketMillis, ReferenceConfig.DEFAULT_SOCKET_TIMEOUT_MILLIS));
    }

    static int toTimeoutMillis(long millis, long fallbackMillis) {
        long value = millis <= 0 ? fallbackMillis : millis;
        if (value <= 0) {
            value = 1L;
        }
        return (int) Math.min(value, Integer.MAX_VALUE);
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
     * remove() 对借出中的连接只标记 REMOVED 并延迟关闭，而 discard 的持有者不会再 requite，
     * 因此这里必须立即关闭；closeQuietly 幂等，与后续 requite 的兜底 close 不冲突。
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
     * 实例下线：按 ip:port 精确摘除，避免同机多实例（不同端口）互相误删。
     * port 为 null 时退化为按 ip 摘除，仅用于兼容旧调用。
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
     * @deprecated 使用 {@link #remove(String, Integer)}，同机多实例必须带端口
     */
    @Deprecated
    public void remove(String ip) {
        remove(ip, null);
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
                entry = converter(consumerConfig, config.effectiveConnectionTimeout());
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
