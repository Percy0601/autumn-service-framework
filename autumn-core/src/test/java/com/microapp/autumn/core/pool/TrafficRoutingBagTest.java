package com.microapp.autumn.core.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.thrift.TServiceClient;
import org.apache.thrift.transport.TTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ConsumerConfig;
import com.microapp.autumn.api.config.ReferenceConfig;
import com.microapp.autumn.api.enums.RegistryTypeEnum;
import com.microapp.autumn.api.extension.AttachmentContext;
import com.microapp.autumn.core.pool.impl.ConcurrentBag;
import com.microapp.autumn.core.pool.impl.ConcurrentBagEntry;

/**
 * 连接池的泳道一致性：池里同时存在多个泳道的空闲连接时，借用不能串泳道。
 */
class TrafficRoutingBagTest {

    private static final String MAIN_IP = "127.0.0.1";
    private static final int MAIN_PORT = 2;
    private static final int GRAY_PORT = 1;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.setProperty("application.name", "traffic-bag-test");
        props.setProperty("application.registry-type", "direct");
        ApplicationConfig.getInstance().init(props);
        AttachmentContext.clear();
    }

    @AfterEach
    void tearDown() {
        AttachmentContext.clear();
    }

    private static ReferenceConfig<TServiceClient> config(int maxPoolSize, List<ConsumerConfig> instances) {
        ReferenceConfig<TServiceClient> config = new ReferenceConfig<>();
        config.setName("svc");
        config.setRegistryTypeEnum(RegistryTypeEnum.DIRECT);
        config.setInstances(instances);
        config.setMaxPoolSize(maxPoolSize);
        config.setValidateOnBorrow(Boolean.FALSE);
        config.setConnectionTimeout(100L);
        config.setSocketTimeout(100L);
        return config;
    }

    private static ConsumerConfig instance(int port, String lane) {
        ConsumerConfig item = new ConsumerConfig();
        item.setName("svc");
        item.setIp(MAIN_IP);
        item.setPort(port);
        item.setLabel(lane);
        item.setLabels(lane == null ? Map.of() : Map.of("traffic", lane));
        return item;
    }

    @Test
    void borrowSkipsOtherLaneIdleConnectionsAndRestoresThem() throws Exception {
        List<ConsumerConfig> instances = List.of(instance(MAIN_PORT, null), instance(GRAY_PORT, "gray"));
        ConcurrentBag bag = new ConcurrentBag(config(4, instances));
        FakeEntry main = new FakeEntry(MAIN_PORT);
        FakeEntry gray = new FakeEntry(GRAY_PORT);
        bag.add(main);   // 队首是基线连接
        bag.add(gray);

        ConcurrentBagEntry borrowed = bag.borrow(300, TimeUnit.MILLISECONDS, Map.of("traffic", "gray"));
        assertSame(gray, borrowed, "灰度泳道请求必须只借到灰度实例的连接");
        bag.requite(borrowed);

        ConcurrentBagEntry baseline = bag.borrow(300, TimeUnit.MILLISECONDS, Map.of());
        assertSame(main, baseline, "被暂存的基线连接必须原样回到池中，不能丢失");
        bag.requite(baseline);
        bag.close();
    }

    @Test
    void attachmentContextDrivesBorrowWithoutExplicitParameters() throws Exception {
        List<ConsumerConfig> instances = List.of(instance(MAIN_PORT, null), instance(GRAY_PORT, "gray"));
        ConcurrentBag bag = new ConcurrentBag(config(4, instances));
        FakeEntry main = new FakeEntry(MAIN_PORT);
        FakeEntry gray = new FakeEntry(GRAY_PORT);
        bag.add(main);
        bag.add(gray);

        AttachmentContext.put("traffic", "gray");
        ConcurrentBagEntry borrowed = bag.borrow(300, TimeUnit.MILLISECONDS);
        assertSame(gray, borrowed);
        bag.requite(borrowed);
        bag.close();
    }

    @Test
    void freesOtherLaneIdleConnectionsWhenPoolIsFull() throws Exception {
        List<ConsumerConfig> instances = List.of(instance(MAIN_PORT, null), instance(GRAY_PORT, "gray"));
        ConcurrentBag bag = new ConcurrentBag(config(2, instances));
        FakeEntry first = new FakeEntry(MAIN_PORT);
        FakeEntry second = new FakeEntry(MAIN_PORT);
        bag.add(first);
        bag.add(second);

        // 灰度实例端口未监听，最终借不到连接；关键是池满时先释放其它泳道的空闲连接腾名额
        ConcurrentBagEntry borrowed = bag.borrow(250, TimeUnit.MILLISECONDS, Map.of("traffic", "gray"));
        assertNull(borrowed);
        assertEquals(ConcurrentBagEntry.STATE_REMOVED, first.getState(), "池满时应先释放其它泳道的空闲连接");
        assertEquals(1, bag.size(), "只腾出必要名额，不无谓清空整个连接池");
        assertEquals(ConcurrentBagEntry.STATE_NOT_IN_USE, second.getState(), "未被释放的连接应原样留在池中");
        bag.close();
    }

    @Test
    void failsOpenWhenDiscoveryHasNoInstances() throws Exception {
        ConcurrentBag bag = new ConcurrentBag(config(4, List.of()));
        FakeEntry gray = new FakeEntry(GRAY_PORT);
        bag.add(gray);

        ConcurrentBagEntry borrowed = bag.borrow(200, TimeUnit.MILLISECONDS, Map.of("traffic", "blue"));
        assertSame(gray, borrowed, "发现结果为空时保持原有行为，不能拒绝借用现成连接");
        bag.requite(borrowed);
        bag.close();
    }

    /** 不建立真实 TCP 连接的桩连接；validateOnBorrow=false 时不会触碰 getEntry()。 */
    private static final class FakeEntry implements ConcurrentBagEntry {

        private final AtomicInteger state = new AtomicInteger(STATE_NOT_IN_USE);
        private final String id = UUID.randomUUID().toString();
        private final int port;
        private volatile long lastUsed = System.currentTimeMillis();

        FakeEntry(int port) {
            this.port = port;
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
        @SuppressWarnings("unchecked")
        public <T extends TTransport> T getEntry() {
            return null;
        }

        @Override
        public String getService() {
            return "svc";
        }

        @Override
        public String getIp() {
            return MAIN_IP;
        }

        @Override
        public Integer getPort() {
            return port;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void close() {
            // no-op stub
        }

        @Override
        public long getCreatedAt() {
            return lastUsed;
        }

        @Override
        public long getLastUsed() {
            return lastUsed;
        }

        @Override
        public void touch() {
            lastUsed = System.currentTimeMillis();
        }
    }
}
