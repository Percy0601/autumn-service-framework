package com.microapp.autumn.core.server;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.thrift.TMultiplexedProcessor;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadedSelectorServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TNonblockingServerTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.layered.TFramedTransport;

import com.microapp.autumn.api.ControlApi;
import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ProviderConfig;
import com.microapp.autumn.api.config.ServiceConfig;
import com.microapp.autumn.api.extension.AttachableProcessor;
import com.microapp.autumn.api.util.AutumnException;
import com.microapp.autumn.api.util.CommonUtil;
import com.microapp.autumn.api.util.ThreadUtil;
import com.microapp.autumn.core.devops.ControlApiImpl;

import lombok.extern.slf4j.Slf4j;

/**
 * @author: baoxin.zhao
 * @date: 2024/10/30
 */
@Slf4j
public class AutumnProvider {
    private volatile static AutumnProvider singleton = null;
    private TMultiplexedProcessor processor;
    private Map<Class, ServiceConfig> services;
    private volatile TServer server;
    private volatile boolean started;

    private AutumnProvider() {

    }

    public static AutumnProvider provider() {
        if (singleton == null) {
            synchronized (AutumnProvider.class) {
                if (singleton == null) {
                    singleton = new AutumnProvider();
                    singleton.init();
                    return singleton;
                }
            }
        }
        return singleton;
    }

    private void export(Class<?> interfaceClass, String name, TProcessor serviceProcessor) {
        if(services.containsKey(interfaceClass)) {
            log.warn("autumn provider processor contains the service, not need repeat export service. service-name:{}", name);
            return;
        }
        log.info("autumn provider export service: {}", name);
        processor.registerProcessor(name, serviceProcessor);
    }

    public <T> AutumnProvider service(ServiceConfig<T> serviceConfig) {
        export(serviceConfig.getInterfaceClass(), serviceConfig.getInterfaceClass().getName(), serviceConfig.getRef());
        services.put(serviceConfig.getInterfaceClass(), serviceConfig);
        return this;
    }

    private void init() {
        Properties properties = CommonUtil.readClasspath("application.properties");
        ProviderConfig providerConfig = ProviderConfig.getInstance();
        providerConfig.init(properties);
        services = new ConcurrentHashMap<>();
        processor = new TMultiplexedProcessor();
    }

    /**
     * 同步启动：监听端口绑定成功后才返回，绑定失败直接抛出，调用方不得继续注册
     */
    public synchronized void start() {
        Properties properties = CommonUtil.readClasspath("application.properties");
        ProviderConfig providerConfig = ProviderConfig.getInstance();
        providerConfig.init(properties);
        if(!Boolean.TRUE.equals(providerConfig.getEnabled())) {
            log.info("autumn provider disabled, skip thrift server start.");
            return;
        }
        if(started) {
            log.warn("autumn provider already started, skip repeat start. port:{}", providerConfig.getPort());
            return;
        }

        TNonblockingServerTransport serverTransport = null;
        try {
            // 构造时完成 bind，端口占用会在这里抛异常；
            // provider.timeout（秒）用于已 accept 客户端连接的读写超时，避免慢/僵死连接长期占用服务端资源
            int clientTimeoutMillis = clientTimeoutMillis(providerConfig);
            serverTransport = new TNonblockingServerSocket(providerConfig.getPort(), clientTimeoutMillis);

            ThreadUtil singleton = ThreadUtil.getInstance();
            ExecutorService executorService = singleton.getWorkerExecutor(providerConfig);

            TThreadedSelectorServer.Args tArgs = new TThreadedSelectorServer.Args(serverTransport);
            tArgs.transportFactory(new TFramedTransport.Factory());
            tArgs.protocolFactory(new TBinaryProtocol.Factory());
            tArgs.executorService(executorService);
            tArgs.acceptQueueSizePerThread(1000);
            tArgs.stopTimeoutVal(30);
            tArgs.stopTimeoutUnit(TimeUnit.SECONDS);
            tArgs.processor(processor);

            Integer controlPort = ApplicationConfig.getInstance().getControlPort();
            boolean independentControl = (controlPort != null && controlPort > 0);
            if (!independentControl) {
                // 默认：ControlApi 复用业务端口（多路复用）
                ServiceConfig<ControlApi.Iface> controlApiService = new ServiceConfig();
                controlApiService.setInterfaceClass(ControlApi.Iface.class);
                TProcessor controlProcessor = new ControlApi.Processor<ControlApi.Iface>(new ControlApiImpl());
                AttachableProcessor attachableProcessor = new AttachableProcessor(controlProcessor);
                controlApiService.setRef(attachableProcessor);
                service(controlApiService);
            }

            TServer tServer = new TThreadedSelectorServer(tArgs);
            Thread thread = new Thread(() -> {
                try {
                    tServer.serve();
                } catch (Throwable e) {
                    log.error("autumn server terminated unexpectedly, port:{}", providerConfig.getPort(), e);
                }
            }, "autumn-server");
            thread.setDaemon(true);
            thread.start();

            this.server = tServer;
            this.started = true;
            log.info("autumn server running, port:{}, clientTimeout:{}ms, services:{}",
                    providerConfig.getPort(), clientTimeoutMillis, services.keySet());

            if (independentControl) {
                // 控制面独立端口：仅承载 ControlApi，与业务流量隔离
                startControlServer(controlPort, providerConfig);
            }
        } catch (TTransportException e) {
            closeQuietly(serverTransport);
            throw new AutumnException("autumn server start failed, port:" + providerConfig.getPort(), e);
        } catch (RuntimeException e) {
            closeQuietly(serverTransport);
            throw e;
        }
    }

    /**
     * 停止监听并释放端口，可重复调用
     */
    public synchronized void stop() {
        TServer current = this.server;
        this.server = null;
        this.started = false;
        if(current == null) {
            return;
        }
        try {
            current.stop();
            log.info("autumn server stopped.");
        } catch (Exception e) {
            log.warn("autumn server stop exception:{}", e.getMessage());
        }
    }

    public boolean isStarted() {
        return started;
    }

    /**
     * 启动独立控制面 thrift 服务器，仅承载 ControlApi，与业务端口隔离。
     * 当配置了 control.token 时只绑定回环地址，作为访问控制（逐调用 token 校验需 thrift IDL 增加握手方法，后续迭代）。
     */
    private void startControlServer(int controlPort, ProviderConfig providerConfig) {
        TNonblockingServerTransport controlTransport = null;
        try {
            String controlToken = ApplicationConfig.getInstance().getControlToken();
            InetSocketAddress bindAddr;
            if (controlToken != null && !controlToken.isEmpty()) {
                bindAddr = new InetSocketAddress("127.0.0.1", controlPort);
                log.info("autumn control server binds loopback only because control.token is configured, port:{}", controlPort);
            } else {
                bindAddr = new InetSocketAddress(controlPort);
            }
            controlTransport = new TNonblockingServerSocket(bindAddr, clientTimeoutMillis(providerConfig));
            TMultiplexedProcessor controlProcessor = new TMultiplexedProcessor();
            controlProcessor.registerProcessor(ControlApi.Iface.class.getName(),
                    new ControlApi.Processor<ControlApi.Iface>(new ControlApiImpl()));
            TThreadedSelectorServer.Args cArgs = new TThreadedSelectorServer.Args(controlTransport);
            cArgs.transportFactory(new TFramedTransport.Factory());
            cArgs.protocolFactory(new TBinaryProtocol.Factory());
            cArgs.processor(controlProcessor);
            cArgs.acceptQueueSizePerThread(1000);
            cArgs.stopTimeoutVal(30);
            cArgs.stopTimeoutUnit(TimeUnit.SECONDS);
            TServer controlServer = new TThreadedSelectorServer(cArgs);
            Thread ct = new Thread(() -> {
                try {
                    controlServer.serve();
                } catch (Throwable e) {
                    log.error("autumn control server terminated unexpectedly, port:{}", controlPort, e);
                }
            }, "autumn-control-server");
            ct.setDaemon(true);
            ct.start();
            log.info("autumn control server running, port:{}", controlPort);
        } catch (TTransportException e) {
            closeQuietly(controlTransport);
            log.error("autumn control server start failed, port:{}", controlPort, e);
        }
    }

    /**
     * provider.timeout（秒）转换为 thrift 客户端连接超时（毫秒）。
     * 未配置或 <=0 时返回 0（不设超时），保持旧行为。
     */
    private static int clientTimeoutMillis(ProviderConfig config) {
        Integer timeout = config.getTimeout();
        if (timeout == null || timeout <= 0) {
            return 0;
        }
        return (int) Math.min(timeout * 1000L, Integer.MAX_VALUE);
    }

    private void closeQuietly(TNonblockingServerTransport serverTransport) {
        if(serverTransport == null) {
            return;
        }
        try {
            serverTransport.close();
        } catch (Exception e) {
            log.debug("autumn server transport close exception:{}", e.getMessage());
        }
    }

}
