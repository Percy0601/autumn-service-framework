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
import xyz.wewin.autumn.cloud.extension.core.model.InstanceRegistration;
import xyz.wewin.autumn.cloud.extension.core.model.InstanceSnapshot;
import xyz.wewin.autumn.cloud.extension.core.nacos.NacosClient;
import xyz.wewin.autumn.cloud.extension.core.nacos.NacosSettings;

/**
 * nacos 后端：直连 Nacos OpenAPI v1，临时实例由本地线程持续心跳。
 *
 * <p>心跳任务按 instanceId 管理：deregister 只取消该实例心跳，不会停掉同进程其它实例；
 * 调度器创建/替换在锁内完成，并发 register 不会覆盖并泄漏旧调度器线程。</p>
 */
public final class NacosBackend implements ServiceBackend {

    private static final Logger log = LoggerFactory.getLogger(NacosBackend.class);

    private final NacosClient client;
    private final long beatIntervalMillis;
    private final Map<String, InstanceRegistration> registrations = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> beats = new ConcurrentHashMap<>();
    private final Object schedulerLock = new Object();
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
        stopBeat();
        registrations.clear();
        client.close();
    }

    private void startBeat(String instanceId, InstanceRegistration instance) {
        synchronized (schedulerLock) {
            ScheduledFuture<?> previous = beats.remove(instanceId);
            if (Objects.nonNull(previous)) {
                previous.cancel(false);
            }
            ScheduledExecutorService scheduler = this.beatScheduler;
            if (Objects.isNull(scheduler) || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "autumn-nacos-beat");
                    thread.setDaemon(true);
                    return thread;
                });
                this.beatScheduler = scheduler;
            }
            beats.put(instanceId, scheduler.scheduleAtFixedRate(() -> {
                try {
                    client.heartbeat(instance);
                } catch (Exception e) {
                    log.warn("nacos heartbeat failed, instanceId:{}, error:{}", instanceId, e.getMessage());
                }
            }, beatIntervalMillis, beatIntervalMillis, TimeUnit.MILLISECONDS));
        }
    }

    private void stopBeat(String instanceId) {
        ScheduledFuture<?> beat = beats.remove(instanceId);
        if (Objects.nonNull(beat)) {
            beat.cancel(false);
        }
    }

    private void stopBeat() {
        synchronized (schedulerLock) {
            beats.forEach((instanceId, beat) -> beat.cancel(false));
            beats.clear();
            ScheduledExecutorService scheduler = this.beatScheduler;
            this.beatScheduler = null;
            if (Objects.nonNull(scheduler)) {
                scheduler.shutdownNow();
            }
        }
    }

    private final class NacosRegistryClient implements ServiceRegistryClient {

        @Override
        public void register(Registration registration) {
            InstanceRegistration instance = toInstance(registration);
            client.register(instance);
            registrations.put(registration.getInstanceId(), instance);
            startBeat(registration.getInstanceId(), instance);
            log.info("nacos registered, instanceId:{}, beat:{}ms", registration.getInstanceId(), beatIntervalMillis);
        }

        @Override
        public void deregister(String instanceId) {
            // 只停该实例心跳，其余实例继续续约
            stopBeat(instanceId);
            InstanceRegistration instance = registrations.remove(instanceId);
            if (Objects.isNull(instance)) {
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
            stopBeat();
            registrations.clear();
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
