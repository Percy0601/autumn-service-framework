package com.microapp.autumn.registry.extension.backend;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
    /**
     * 按 instanceId 记录本进程发布的实例。原来的全局 {@code published} 布尔量会让
     * 同进程第二个实例被静默跳过，且无法区分要注销的是哪个实例。
     */
    private final Map<String, Registration> published = new ConcurrentHashMap<>();

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
        // 整体关闭时才广播 bye 帧并释放组播通道，发现侧此时也应一并停止
        published.clear();
        started.set(false);
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
            // 按 instanceId 幂等注册：重复注册刷新发布内容，而不是静默跳过第二个实例。
            published.put(registration.getInstanceId(), registration);
            multicast.publish(toInstance(registration));
            log.info("multicast registered, instanceId:{}, traffic:{}, published:{}",
                    registration.getInstanceId(), registration.getTraffic(), published.size());
        }

        @Override
        public void deregister(String instanceId) {
            Registration removed = published.remove(instanceId);
            if (Objects.isNull(removed)) {
                return;
            }
            // 组播依赖库没有按实例 unpublish 的 API，close() 会关闭整个接收通道并广播 bye，
            // 那样会连带使本进程的发现侧失效。因此这里只做本地摘除，通道生命周期统一由
            // MulticastBackend.close() 管理；若仍有其它实例，重新发布一个以刷新心跳。
            published.values().stream().findFirst()
                    .ifPresent(remaining -> multicast.publish(toInstance(remaining)));
            log.info("multicast deregistered, instanceId:{}, remaining:{}", instanceId, published.size());
        }

        @Override
        public String description() {
            return "autumn-cloud-extension multicast registry";
        }

        @Override
        public void close() {
            // 注册侧与发现侧共享同一组播通道，此处不能关闭，否则 getInstances 立刻失效。
            log.debug("multicast registry client close, channel kept for discovery.");
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
