package com.microapp.autumn.registry.extension.backend;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autumn.cloud.extension.core.api.Registration;
import com.autumn.cloud.extension.core.api.ServiceDiscoveryClient;
import com.autumn.cloud.extension.core.api.ServiceInstance;
import com.autumn.cloud.extension.core.api.ServiceRegistryClient;
import com.autumn.cloud.extension.core.model.InstanceRegistration;
import com.autumn.cloud.extension.core.model.InstanceSnapshot;
import com.autumn.cloud.extension.core.multicast.MulticastConfig;
import com.autumn.cloud.extension.core.multicast.MulticastDiscovery;

/**
 * multicast 后端：纯 JDK UDP 组播，零外部依赖，用于开发/测试环境。
 *
 * <p>发现侧必须先 {@link MulticastDiscovery#start()} 加入组播组才能收包，
 * 因此 {@link #startDiscovery()} 需在消费方启动时调用。</p>
 */
public final class MulticastBackend implements ServiceBackend {

    private static final Logger log = LoggerFactory.getLogger(MulticastBackend.class);

    private final MulticastDiscovery multicast;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean published = new AtomicBoolean(false);

    public MulticastBackend(MulticastConfig config) {
        this.multicast = new MulticastDiscovery(config);
    }

    @Override
    public void startDiscovery() {
        ensureStarted();
    }

    @Override
    public ServiceRegistryClient registry() {
        return new MulticastRegistryClient();
    }

    @Override
    public ServiceDiscoveryClient discoveryClient() {
        return new MulticastDiscoveryClient();
    }

    @Override
    public void close() {
        multicast.close();
    }

    private void ensureStarted() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            multicast.start();
            log.info("multicast discovery started.");
        } catch (IOException e) {
            started.set(false);
            throw new IllegalStateException("multicast start failed: " + e.getMessage(), e);
        }
    }

    private final class MulticastRegistryClient implements ServiceRegistryClient {

        @Override
        public void register(Registration registration) {
            ensureStarted();
            if (!published.compareAndSet(false, true)) {
                log.warn("multicast registration already published, skip. instanceId:{}", registration.getInstanceId());
                return;
            }
            multicast.publish(toInstance(registration));
            log.info("multicast registered, instanceId:{}, traffic:{}", registration.getInstanceId(),
                    registration.getTraffic());
        }

        @Override
        public void deregister(String instanceId) {
            // 组播通过 close 广播 bye 帧，主动摘除自身实例
            multicast.close();
        }

        @Override
        public String description() {
            return "autumn-cloud-extension multicast registry";
        }

        @Override
        public void close() {
            multicast.close();
        }
    }

    private final class MulticastDiscoveryClient implements ServiceDiscoveryClient {

        @Override
        public List<ServiceInstance> getInstances(String serviceId) {
            ensureStarted();
            return multicast.getInstances(serviceId).stream()
                    .map((InstanceSnapshot snapshot) -> toServiceInstance(serviceId, snapshot))
                    .map(ServiceInstance.class::cast)
                    .toList();
        }

        @Override
        public List<String> getServices() {
            return multicast.listServices();
        }

        @Override
        public String description() {
            return "autumn-cloud-extension multicast discovery";
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
