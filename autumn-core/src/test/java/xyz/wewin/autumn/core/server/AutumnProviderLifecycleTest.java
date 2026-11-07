package xyz.wewin.autumn.core.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import xyz.wewin.autumn.api.config.ProviderConfig;
import xyz.wewin.autumn.api.util.AutumnException;

/**
 * Provider 启动/停止的合同：端口占用必须同步失败，stop 必须释放端口。
 */
class AutumnProviderLifecycleTest {

    @AfterEach
    void tearDown() {
        AutumnProvider.provider().stop();
    }

    private static void configure(int port) {
        ProviderConfig config = ProviderConfig.getInstance();
        // 测试直接设定端口，阻止 application.properties 覆盖
        config.setInitialized(Boolean.TRUE);
        config.setEnabled(Boolean.TRUE);
        config.setPort(port);
        config.setMinThreads(0);
        config.setMaxThreads(2);
        config.setWorkerKeepAliveTime(1);
        config.setThreadQueueSize(10);
        config.setTimeout(3);
        config.setIp("127.0.0.1");
    }

    @Test
    void startThrowsSynchronouslyWhenPortIsOccupied() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            configure(occupied.getLocalPort());

            AutumnException exception = assertThrows(AutumnException.class,
                    () -> AutumnProvider.provider().start());

            assertTrue(exception.getMessage().contains("start failed"), exception.getMessage());
            assertFalse(AutumnProvider.provider().isStarted(),
                    "start failure must not leave the provider in started state");
        }
    }

    @Test
    void startIsIdempotentAndStopReleasesPort() throws Exception {
        int port = freePort();
        configure(port);

        AutumnProvider.provider().start();
        assertTrue(AutumnProvider.provider().isStarted());

        assertDoesNotThrow(() -> AutumnProvider.provider().start(), "repeat start must not bind twice");
        assertTrue(AutumnProvider.provider().isStarted());

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            assertTrue(socket.isConnected(), "server should accept connections on " + port);
        }

        AutumnProvider.provider().stop();
        assertFalse(AutumnProvider.provider().isStarted());
        assertTrue(waitBindable(port), "port must be released after stop");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean waitBindable(int port) {
        for (int i = 0; i < 30; i++) {
            try (ServerSocket socket = new ServerSocket(port)) {
                return true;
            } catch (IOException e) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
