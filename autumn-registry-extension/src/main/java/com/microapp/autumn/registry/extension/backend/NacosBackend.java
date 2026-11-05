package com.microapp.autumn.registry.extension.backend;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autumn.cloud.extension.core.api.Registration;
import com.autumn.cloud.extension.core.api.ServiceDiscoveryClient;
import com.autumn.cloud.extension.core.api.ServiceInstance;
import com.autumn.cloud.extension.core.api.ServiceRegistryClient;
import com.autumn.cloud.extension.core.model.InstanceRegistration;
import com.autumn.cloud.extension.core.model.InstanceSnapshot;
import com.autumn.cloud.extension.core.nacos.NacosClient;
import com.autumn.cloud.extension.core.nacos.NacosSettings;

/**
 * nacos 后端：直连 Nacos OpenAPI v1，临时实例由本地线程持续心跳。
 * 心跳按实例管理：每个实例独立调度，注销单个实例只取消其心跳，不影响同进程其它实例。
 */
public final class NacosBackend implements ServiceBackend {

    private static final Logger log = LoggerFactory.getLogger(NacosBackend.class);

    private final NacosClient client;
    private final long beatIntervalMillis;
    private final Map<String, InstanceRegistration> registrations = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> beatFutures = new ConcurrentHashMap<>();
    private final ServiceRegistryClient registry = new NacosRegistryClient();
    private final ServiceDiscoveryClient discovery = new NacosDiscoveryClient();

    private volatile ScheduledExecutorService beatScheduler;

    public NacosBackend(NacosSettings settings) {
        this.client = new NacosClient(settings);
        this.beatIntervalMillis = settings.beatIntervalMs();
    }

    @Override
    public ServiceRegistryClient registry() {
        return registry;
    }

    @Override
    public ServiceDiscoveryClient discoveryClient() {
        return discovery;
    }

    @Override
    public void close() {
        stopAllBeats();
        client.close();
    }

    private void stopAllBeats() {
        beatFutures.values().forEach(future -> future.cancel(true));
        beatFutures.clear();
        ScheduledExecutorService scheduler = this.beatScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
            this.beatScheduler = null;
        }
    }

    private void stopBeat(String instanceId) {
        ScheduledFuture<?> future = beatFutures.remove(instanceId);
        if (future != null) {
            future.cancel(true);
        }
    }

    private void startBeat(String instanceId) {
        ScheduledExecutorService scheduler = this.beatScheduler;
        if (scheduler == null) {
            synchronized (this) {
                scheduler = this.beatScheduler;
                if (scheduler == null) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "autumn-nacos-beat");
                        thread.setDaemon(true);
                        return thread;
                    });
                    this.beatScheduler = scheduler;
                }
            }
        }
        InstanceRegistration instance = registrations.get(instanceId);
        if (instance == null) {
            return;
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                client.heartbeat(instance);
            } catch (Exception e) {
                log.warn("nacos heartbeat failed, instanceId:{}, error:{}", instanceId, e.getMessage());
            }
        }, beatIntervalMillis, beatIntervalMillis, TimeUnit.MILLISECONDS);
        beatFutures.put(instanceId, future);
    }

    private final class NacosRegistryClient implements ServiceRegistryClient {

        @Override
        public void register(Registration registration) {
            InstanceRegistration instance = toInstance(registration);
            client.register(instance);
            registrations.put(registration.getInstanceId(), instance);
            startBeat(registration.getInstanceId());
            log.info("nacos registered, instanceId:{}, beat:{}ms", registration.getInstanceId(), beatIntervalMillis);
        }

        @Override
        public void deregister(String instanceId) {
            InstanceRegistration instance = registrations.remove(instanceId);
            stopBeat(instanceId);
            if (instance == null) {
                log.warn("nacos deregister skipped, instance not registered:{}", instanceId);
                return;
            }
            try {
                client.deregister(instance);
            } catch (Exception e) {
                log.warn("nacos deregister failed, instanceId:{}, error:{}", instanceId, e.getMessage());
            }
        }

        @Override
        public String description() {
            return "autumn-cloud-extension nacos registry";
        }

        @Override
        public void close() {
            stopAllBeats();
        }
    }

    private final class NacosDiscoveryClient implements ServiceDiscoveryClient {

        @Override
        public List<ServiceInstance> getInstances(String serviceId) {
            return client.listInstances(serviceId).stream()
                    .map((InstanceSnapshot snapshot) -> toServiceInstance(serviceId, snapshot))
                    .map(ServiceInstance.class::cast)
                    .toList();
        }

        @Override
        public List<String> getServices() {
            return client.listServiceNames();
        }

        @Override
        public String description() {
            return "autumn-cloud-extension nacos discovery";
        }
    }

    private static InstanceRegistration toInstance(Registration registration) {
        return new InstanceRegistration(registration.getServiceId(), registration.getInstanceId(),
                registration.getHost(), registration.getPort(), registration.getMetadata());
    }

    private static ExtensionServiceInstance toServiceInstance(String serviceId, InstanceSnapshot snapshot) {
        return new ExtensionServiceInstance(snapshot.instanceId(), serviceId, snapshot.host(), snapshot.port(),
                snapshot.metadata());
    }
}
