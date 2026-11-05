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
import com.autumn.cloud.extension.core.consul.ConsulClient;
import com.autumn.cloud.extension.core.consul.ConsulSettings;
import com.autumn.cloud.extension.core.model.InstanceRegistration;
import com.autumn.cloud.extension.core.model.InstanceSnapshot;

/**
 * consul 后端：直连 Consul Agent HTTP API，TTL 健康检查由本地线程续约。
 * 续约按实例管理：每个实例独立调度，并发 register 不会相互覆盖/泄漏调度器。
 */
public final class ConsulBackend implements ServiceBackend {

    private static final Logger log = LoggerFactory.getLogger(ConsulBackend.class);

    private final ConsulClient client;
    private final long passIntervalMillis;
    private final Map<String, ScheduledFuture<?>> ttlFutures = new ConcurrentHashMap<>();
    private final ServiceRegistryClient registry = new ConsulRegistryClient();
    private final ServiceDiscoveryClient discovery = new ConsulDiscoveryClient();

    private volatile ScheduledExecutorService ttlScheduler;

    public ConsulBackend(ConsulSettings settings) {
        this.client = new ConsulClient(settings);
        this.passIntervalMillis = Math.max(settings.healthCheckTtlSeconds() * 1000L / 3, 1000L);
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
        stopAllTtl();
        client.close();
    }

    private void stopAllTtl() {
        ttlFutures.values().forEach(future -> future.cancel(true));
        ttlFutures.clear();
        ScheduledExecutorService scheduler = this.ttlScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
            this.ttlScheduler = null;
        }
    }

    private void stopTtl(String instanceId) {
        ScheduledFuture<?> future = ttlFutures.remove(instanceId);
        if (future != null) {
            future.cancel(true);
        }
    }

    private void startTtl(String instanceId) {
        ScheduledExecutorService scheduler = this.ttlScheduler;
        if (scheduler == null) {
            synchronized (this) {
                scheduler = this.ttlScheduler;
                if (scheduler == null) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "autumn-consul-ttl");
                        thread.setDaemon(true);
                        return thread;
                    });
                    this.ttlScheduler = scheduler;
                }
            }
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                client.passCheck(instanceId);
            } catch (Exception e) {
                log.warn("consul ttl pass failed, instanceId:{}, error:{}", instanceId, e.getMessage());
            }
        }, passIntervalMillis, passIntervalMillis, TimeUnit.MILLISECONDS);
        ttlFutures.put(instanceId, future);
    }

    private final class ConsulRegistryClient implements ServiceRegistryClient {

        @Override
        public void register(Registration registration) {
            client.register(toInstance(registration));
            startTtl(registration.getInstanceId());
            log.info("consul registered, instanceId:{}, ttl:{}ms", registration.getInstanceId(), passIntervalMillis);
        }

        @Override
        public void deregister(String instanceId) {
            stopTtl(instanceId);
            try {
                client.deregister(instanceId);
            } catch (Exception e) {
                log.warn("consul deregister failed, instanceId:{}, error:{}", instanceId, e.getMessage());
            }
        }

        @Override
        public String description() {
            return "autumn-cloud-extension consul registry";
        }

        @Override
        public void close() {
            stopAllTtl();
        }
    }

    private final class ConsulDiscoveryClient implements ServiceDiscoveryClient {

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
            return "autumn-cloud-extension consul discovery";
        }
    }

    private static InstanceRegistration toInstance(Registration registration) {
        Map<String, String> metadata = registration.getMetadata();
        return new InstanceRegistration(registration.getServiceId(), registration.getInstanceId(),
                registration.getHost(), registration.getPort(), metadata);
    }

    private static ExtensionServiceInstance toServiceInstance(String serviceId, InstanceSnapshot snapshot) {
        return new ExtensionServiceInstance(snapshot.instanceId(), serviceId, snapshot.host(), snapshot.port(),
                snapshot.metadata());
    }
}
