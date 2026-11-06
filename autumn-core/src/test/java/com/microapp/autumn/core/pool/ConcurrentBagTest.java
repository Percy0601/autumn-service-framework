package com.microapp.autumn.core.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.thrift.TServiceClient;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.layered.TLayeredTransport;
import org.junit.jupiter.api.Test;

import com.microapp.autumn.api.config.ConsumerConfig;
import com.microapp.autumn.api.config.ReferenceConfig;
import com.microapp.autumn.api.enums.RegistryTypeEnum;
import com.microapp.autumn.core.pool.impl.ConcurrentBag;
import com.microapp.autumn.core.pool.impl.ConcurrentBagEntry;

/**
 * 连接池的合同测试（每次修改连接池逻辑都应先让这些用例可运行）。
 */
class ConcurrentBagTest {

    /**
     * 仅接受 TCP 连接的桩服务端，用来让 TSocket.open() 成功，不参与协议交互。
     */
    static final class TcpStub implements AutoCloseable {
        private final ServerSocket server;
        private final List<Socket> accepted = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger acceptCount = new AtomicInteger();
        private volatile boolean running = true;

        TcpStub() throws IOException {
            server = new ServerSocket(0, 100, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (running) {
                    try {
                        Socket socket = server.accept();
                        accepted.add(socket);
                        acceptCount.incrementAndGet();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "test-tcp-stub");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        int acceptCount() {
            return acceptCount.get();
        }

        @Override
        public void close() throws IOException {
            running = false;
            server.close();
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // test cleanup
                    }
                }
            }
        }
    }

    private static ReferenceConfig<TServiceClient> directConfig(String service, int port, int connections) {
        ReferenceConfig<TServiceClient> config = new ReferenceConfig<>();
        config.setName(service);
        config.setRegistryTypeEnum(RegistryTypeEnum.DIRECT);
        ConsumerConfig instance = new ConsumerConfig();
        instance.setName(service);
        instance.setIp("127.0.0.1");
        instance.setPort(port);
        instance.setConnections(connections);
        config.setInstances(List.of(instance));
        return config;
    }

    @Test
    void waiterReceivesConnectionReleasedByAnotherThread() throws Exception {
        try (TcpStub stub = new TcpStub()) {
            ConcurrentBag bag = new ConcurrentBag(directConfig("svc", stub.port(), 1));
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                ConcurrentBagEntry first = bag.borrow(500, TimeUnit.MILLISECONDS);
                assertNotNull(first, "first borrow should get the only connection");

                Future<ConcurrentBagEntry> waiting = pool.submit(() -> bag.borrow(300, TimeUnit.MILLISECONDS));
                Thread.sleep(50L);
                bag.requite(first);

                ConcurrentBagEntry second = waiting.get(1, TimeUnit.SECONDS);
                assertNotNull(second, "requited connection must be handed off to the waiter, not dropped");
                assertTrue(second.getEntry().isOpen());
                bag.requite(second);
            } finally {
                pool.shutdownNow();
                bag.close();
            }
        }
    }

    @Test
    void borrowReturnsNullAfterTimeoutWhenNoConnectionCanBeCreated() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        ConcurrentBag bag = new ConcurrentBag(directConfig("svc", closedPort, 1));
        try {
            long start = System.nanoTime();
            ConcurrentBagEntry entry = bag.borrow(200, TimeUnit.MILLISECONDS);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertNull(entry);
            assertTrue(elapsedMillis >= 150L, "borrow should block for the configured timeout, elapsed=" + elapsedMillis);
        } finally {
            bag.close();
        }
    }

    @Test
    void borrowShouldNotBurnCpuWhileWaiting() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        ConcurrentBag bag = new ConcurrentBag(directConfig("svc", closedPort, 1));
        try {
            long cpuBefore = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
            bag.borrow(200, TimeUnit.MILLISECONDS);
            long cpuUsed = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpuBefore;
            assertTrue(cpuUsed < TimeUnit.MILLISECONDS.toNanos(50),
                    "borrow must block instead of spinning, cpu=" + TimeUnit.NANOSECONDS.toMillis(cpuUsed) + "ms");
        } finally {
            bag.close();
        }
    }

    @Test
    void removeMustNotCloseConnectionThatIsStillInUse() throws Exception {
        try (TcpStub stub = new TcpStub()) {
            ConcurrentBag bag = new ConcurrentBag(directConfig("svc", stub.port(), 4));
            try {
                ConcurrentBagEntry inUse = bag.borrow(500, TimeUnit.MILLISECONDS);
                ConcurrentBagEntry temp = bag.borrow(500, TimeUnit.MILLISECONDS);
                assertNotNull(inUse);
                assertNotNull(temp);
                assertTrue(inUse.getEntry().isOpen());
                bag.requite(temp);
                assertTrue(temp.getEntry().isOpen(), "requited connection should stay open in the pool");

                bag.remove("127.0.0.1");

                assertTrue(inUse.getEntry().isOpen(),
                        "a removed instance must not close a connection that is still borrowed");
                assertFalse(temp.getEntry().isOpen(), "idle connection of a removed instance should be closed");

                bag.requite(inUse);
                assertFalse(inUse.getEntry().isOpen(), "requited connection of a removed instance should be closed");
            } finally {
                bag.close();
            }
        }
    }

    /**
     * 未显式配置超时时，池里建出来的连接也必须带读超时（默认 3s），
     * 否则服务端接了连接不回包会把调用线程永久挂住。
     */
    @Test
    void defaultTimeoutsAreAppliedToPooledConnection() throws Exception {
        try (TcpStub stub = new TcpStub()) {
            ConcurrentBag bag = new ConcurrentBag(directConfig("svc", stub.port(), 1));
            try {
                ConcurrentBagEntry entry = bag.borrow(500, TimeUnit.MILLISECONDS);
                assertNotNull(entry);
                TSocket socket = (TSocket) ((TLayeredTransport) entry.getEntry()).getInnerTransport();
                assertEquals(ReferenceConfig.DEFAULT_SOCKET_TIMEOUT_MILLIS, socket.getSocket().getSoTimeout());
                bag.requite(entry);
            } finally {
                bag.close();
            }
        }
    }

    /**
     * 服务端接受连接但不回包时，读取必须按配置的 socketTimeout 失败，而不是无限等待。
     */
    @Test
    void socketReadTimeoutAbortsBlockedReply() throws Exception {
        try (TcpStub stub = new TcpStub()) {
            ReferenceConfig<TServiceClient> config = directConfig("svc", stub.port(), 1);
            config.setSocketTimeout(200L);
            ConcurrentBag bag = new ConcurrentBag(config);
            try {
                ConcurrentBagEntry entry = bag.borrow(1, TimeUnit.SECONDS);
                assertNotNull(entry);
                TProtocol protocol = new TBinaryProtocol(entry.getEntry());
                long start = System.nanoTime();
                assertThrows(TTransportException.class, protocol::readMessageBegin);
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertTrue(elapsedMillis < 2_000L, "read should be aborted by socket timeout, elapsed=" + elapsedMillis);
                bag.discard(entry);
            } finally {
                bag.close();
            }
        }
    }

    /**
     * 配置成 null / 0 / 负数时必须回落到默认值：0 在连接池和 JDK socket 里都表示"无限等待"。
     */
    @Test
    void invalidTimeoutConfigFallsBackToDefaults() {
        ReferenceConfig<TServiceClient> config = new ReferenceConfig<>();
        assertEquals(ReferenceConfig.DEFAULT_POOL_TIMEOUT_MILLIS, config.effectivePoolTimeout());
        assertEquals(ReferenceConfig.DEFAULT_CONNECTION_TIMEOUT_MILLIS, config.effectiveConnectionTimeout());
        assertEquals(ReferenceConfig.DEFAULT_SOCKET_TIMEOUT_MILLIS, config.effectiveSocketTimeout());

        config.setPoolTimeout(0L);
        config.setConnectionTimeout(-1L);
        config.setSocketTimeout(null);
        assertEquals(ReferenceConfig.DEFAULT_POOL_TIMEOUT_MILLIS, config.effectivePoolTimeout());
        assertEquals(ReferenceConfig.DEFAULT_CONNECTION_TIMEOUT_MILLIS, config.effectiveConnectionTimeout());
        assertEquals(ReferenceConfig.DEFAULT_SOCKET_TIMEOUT_MILLIS, config.effectiveSocketTimeout());
    }
}
