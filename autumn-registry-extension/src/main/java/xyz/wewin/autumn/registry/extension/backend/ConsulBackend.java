package xyz.wewin.autumn.registry.extension.backend;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.wewin.autumn.cloud.extension.core.api.Registration;
import xyz.wewin.autumn.cloud.extension.core.api.ServiceDiscoveryClient;
import xyz.wewin.autumn.cloud.extension.core.api.ServiceInstance;
import xyz.wewin.autumn.cloud.extension.core.api.ServiceRegistryClient;
import xyz.wewin.autumn.cloud.extension.core.consul.ConsulClient;
import xyz.wewin.autumn.cloud.extension.core.consul.ConsulSettings;
import xyz.wewin.autumn.cloud.extension.core.model.InstanceRegistration;
import xyz.wewin.autumn.cloud.extension.core.model.InstanceSnapshot;

/**
 * consul 后端：直连 Consul Agent HTTP API，TTL 健康检查由本地线程续约。
 *
 * <p>TTL 任务按 instanceId 管理：注销单个实例只取消它自己的续约，同进程其它实例心跳不受影响；
 * 调度器的创建/替换在锁内完成，避免并发 register 覆盖引用导致旧线程泄漏。</p>
 */
public final class ConsulBackend implements ServiceBackend {

    private static final Logger log = LoggerFactory.getLogger(ConsulBackend.class);

    private final ConsulClient client;
    private final long passIntervalMillis;
    private final ServiceRegistryClient registry = new ConsulRegistryClient();
    private final ServiceDiscoveryClient discovery = new ConsulDiscoveryClient();

    private final Map<String, ScheduledFuture<?>> ttlTasks = new ConcurrentHashMap<>();
    private final Object schedulerLock = new Object();
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
        stopTtl();
        client.close();
    }

    private void startTtl(String instanceId) {
        synchronized (schedulerLock) {
            ScheduledFuture<?> previous = ttlTasks.remove(instanceId);
            if (Objects.nonNull(previous)) {
                previous.cancel(false);
            }
            ScheduledExecutorService scheduler = this.ttlScheduler;
            if (Objects.isNull(scheduler) || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "autumn-consul-ttl");
                    thread.setDaemon(true);
                    return thread;
                });
                this.ttlScheduler = scheduler;
            }
            ttlTasks.put(instanceId, scheduler.scheduleAtFixedRate(() -> {
                try {
                    client.passCheck(instanceId);
                } catch (Exception e) {
                    log.warn("consul ttl pass failed, instanceId:{}, error:{}", instanceId, e.getMessage());
                }
            }, passIntervalMillis, passIntervalMillis, TimeUnit.MILLISECONDS));
        }
    }

    private void stopTtl(String instanceId) {
        ScheduledFuture<?> task = ttlTasks.remove(instanceId);
        if (Objects.nonNull(task)) {
            task.cancel(false);
        }
    }

    private void stopTtl() {
        synchronized (schedulerLock) {
            ttlTasks.forEach((instanceId, task) -> task.cancel(false));
            ttlTasks.clear();
            ScheduledExecutorService scheduler = this.ttlScheduler;
            this.ttlScheduler = null;
            if (Objects.nonNull(scheduler)) {
                scheduler.shutdownNow();
            }
        }
    }

    private final class ConsulRegistryClient implements ServiceRegistryClient {

        @Override
        public void register(Registration registration) {
            String instanceId = registration.getInstanceId();
            client.register(toInstance(registration));
            startTtl(instanceId);
            log.info("consul registered, instanceId:{}, ttl:{}ms", instanceId, passIntervalMillis);
        }

        @Override
        public void deregister(String instanceId) {
            // 只停该实例的续约，同进程其它实例继续心跳
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
            stopTtl();
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
