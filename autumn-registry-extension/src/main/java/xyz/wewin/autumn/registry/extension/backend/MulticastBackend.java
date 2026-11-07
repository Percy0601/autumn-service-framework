package xyz.wewin.autumn.registry.extension.backend;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.wewin.autumn.cloud.extension.core.api.Registration;
import xyz.wewin.autumn.cloud.extension.core.api.ServiceDiscoveryClient;
import xyz.wewin.autumn.cloud.extension.core.api.ServiceInstance;
import xyz.wewin.autumn.cloud.extension.core.api.ServiceRegistryClient;
import xyz.wewin.autumn.cloud.extension.core.model.InstanceRegistration;
import xyz.wewin.autumn.cloud.extension.core.model.InstanceSnapshot;
import xyz.wewin.autumn.cloud.extension.core.multicast.MulticastConfig;
import xyz.wewin.autumn.cloud.extension.core.multicast.MulticastDiscovery;

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
    /** 已发布的实例 id 集合；按实例去重，不再用单个布尔量把进程内二次注册静默跳过 */
    private final Set<String> published = ConcurrentHashMap.newKeySet();

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
        published.clear();
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
            String instanceId = registration.getInstanceId();
            if (!published.add(instanceId)) {
                log.warn("multicast registration already published, skip. instanceId:{}", instanceId);
                return;
            }
            multicast.publish(toInstance(registration));
            log.info("multicast registered, instanceId:{}, traffic:{}", instanceId,
                    registration.getTraffic());
        }

        @Override
        public void deregister(String instanceId) {
            // 注销实例 ≠ 关闭通道：接收线程与发现侧必须继续可用。
            // 组播后端没有单实例 bye 接口，远端实例由心跳超时剔除，这里只摘本地发布状态，
            // 从而允许同 instanceId 后续重新注册。
            if (published.remove(instanceId)) {
                log.info("multicast deregistered locally, instanceId:{}, channel stays open, remote peers evict by heartbeat timeout", instanceId);
            }
        }

        @Override
        public String description() {
            return "autumn-cloud-extension multicast registry";
        }

        @Override
        public void close() {
            // 通道归 MulticastBackend 统一持有，registry client 关闭时不得连带关闭发现侧
            published.clear();
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
