package com.microapp.autumn.core.server;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.thrift.TMultiplexedProcessor;
import org.apache.thrift.TProcessor;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadedSelectorServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TNonblockingServerTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.layered.TFramedTransport;

import com.microapp.autumn.api.ControlApi;
import com.microapp.autumn.api.config.ProviderConfig;
import com.microapp.autumn.api.config.ServiceConfig;
import com.microapp.autumn.api.extension.AttachableBinaryProtocol;
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
    private volatile TServer controlServer;
    private volatile TNonblockingServerTransport controlTransport;
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
     * 同步启动：监听端口绑定成功后才返回，绑定失败直接抛出，调用方不得继续注册。
     * provider.control-port > 0 时控制面单独监听（默认只绑 127.0.0.1），业务端口不再暴露控制 API。
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
            // 构造时完成 bind，端口占用会在这里抛异常
            serverTransport = new TNonblockingServerSocket(providerConfig.getPort());

            ThreadUtil threadUtil = ThreadUtil.getInstance();
            ExecutorService workerExecutor = threadUtil.getWorkerExecutor(providerConfig);
            Integer timeout = providerConfig.getTimeout();
            ExecutorService executorService = new RequestTimeoutExecutor(workerExecutor,
                    threadUtil.getSingleExecutorService(), Objects.isNull(timeout) ? 0L : timeout.longValue());

            TThreadedSelectorServer.Args tArgs = serverArgs(serverTransport, executorService, processor);

            int controlPort = Objects.isNull(providerConfig.getControlPort()) ? 0 : providerConfig.getControlPort();
            boolean separateControl = controlPort > 0;
            if (!separateControl) {
                registerControlApi(processor);
            }

            TServer tServer = new TThreadedSelectorServer(tArgs);
            serveInBackground(tServer, "autumn-server", providerConfig.getPort());
            this.server = tServer;

            if (separateControl) {
                startControlServer(providerConfig, executorService);
            }
            this.started = true;
            log.info("autumn server running, port:{}, controlPort:{}, services:{}",
                    providerConfig.getPort(), separateControl ? controlPort : "shared", services.keySet());
        } catch (TTransportException e) {
            closeQuietly(serverTransport);
            stopServers();
            throw new AutumnException("autumn server start failed, port:" + providerConfig.getPort(), e);
        } catch (RuntimeException e) {
            closeQuietly(serverTransport);
            stopServers();
            throw e;
        }
    }

    private static TThreadedSelectorServer.Args serverArgs(TNonblockingServerTransport transport,
                                                           ExecutorService executorService,
                                                           TProcessor targetProcessor) {
        TThreadedSelectorServer.Args args = new TThreadedSelectorServer.Args(transport);
        args.transportFactory(new TFramedTransport.Factory());
        args.protocolFactory(new AttachableBinaryProtocol.Factory());
        args.executorService(executorService);
        args.acceptQueueSizePerThread(1000);
        args.stopTimeoutVal(30);
        args.stopTimeoutUnit(TimeUnit.SECONDS);
        args.processor(targetProcessor);
        return args;
    }

    private void registerControlApi(TMultiplexedProcessor targetProcessor) {
        ServiceConfig<ControlApi.Iface> controlApiService = new ServiceConfig<>();
        controlApiService.setInterfaceClass(ControlApi.Iface.class);
        TProcessor controlProcessor = new ControlApi.Processor<ControlApi.Iface>(new ControlApiImpl());
        controlApiService.setRef(new AttachableProcessor(controlProcessor));
        targetProcessor.registerProcessor(ControlApi.Iface.class.getName(), controlApiService.getRef());
        services.put(ControlApi.Iface.class, controlApiService);
    }

    private void startControlServer(ProviderConfig providerConfig, ExecutorService executorService)
            throws TTransportException {
        String host = Objects.isNull(providerConfig.getControlHost()) || providerConfig.getControlHost().isBlank()
                ? "127.0.0.1" : providerConfig.getControlHost();
        TNonblockingServerTransport transport = null;
        try {
            transport = new TNonblockingServerSocket(new InetSocketAddress(host, providerConfig.getControlPort()));
            TMultiplexedProcessor controlProcessor = new TMultiplexedProcessor();
            TProcessor api = new ControlApi.Processor<ControlApi.Iface>(new ControlApiImpl());
            controlProcessor.registerProcessor(ControlApi.Iface.class.getName(), new AttachableProcessor(api));
            TServer tServer = new TThreadedSelectorServer(serverArgs(transport, executorService, controlProcessor));
            serveInBackground(tServer, "autumn-control-server", providerConfig.getControlPort());
            this.controlTransport = transport;
            this.controlServer = tServer;
            log.info("autumn control plane listening on {}:{}", host, providerConfig.getControlPort());
        } catch (TTransportException e) {
            closeQuietly(transport);
            throw e;
        }
    }

    private void serveInBackground(TServer tServer, String threadName, int port) {
        Thread thread = new Thread(() -> {
            try {
                tServer.serve();
            } catch (Throwable e) {
                log.error("autumn server terminated unexpectedly, port:{}", port, e);
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 停止监听并释放端口，可重复调用
     */
    public synchronized void stop() {
        this.started = false;
        stopServers();
    }

    private void stopServers() {
        TServer current = this.server;
        this.server = null;
        if (Objects.nonNull(current)) {
            try {
                current.stop();
                log.info("autumn server stopped.");
            } catch (Exception e) {
                log.warn("autumn server stop exception:{}", e.getMessage());
            }
        }
        TServer control = this.controlServer;
        this.controlServer = null;
        if (Objects.nonNull(control)) {
            try {
                control.stop();
                log.info("autumn control server stopped.");
            } catch (Exception e) {
                log.warn("autumn control server stop exception:{}", e.getMessage());
            }
        }
        closeQuietly(this.controlTransport);
        this.controlTransport = null;
    }

    public boolean isStarted() {
        return started;
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
