package com.microapp.autumn.registry.extension;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ConsumerConfig;
import com.microapp.autumn.api.config.ProviderConfig;
import com.microapp.autumn.registry.extension.backend.ExtensionServiceInstance;
import com.microapp.autumn.registry.extension.backend.RegistryBackends;
import com.microapp.autumn.registry.extension.backend.ServiceBackend;

import com.autumn.cloud.extension.core.api.Registration;
import com.autumn.cloud.extension.core.api.ServiceDiscoveryClient;
import com.autumn.cloud.extension.core.api.ServiceInstance;
import com.autumn.cloud.extension.core.api.ServiceRegistryClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 extension 适配层：注册信息翻译、实例发现翻译与实例对象复用。
 */
class ExtensionRegistryDiscoveryTest {

    private FakeBackend backend;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.setProperty("application.name", "training-b");
        props.setProperty("application.registry-type", "multicast");
        props.setProperty("application.traffic", "gray");
        props.setProperty("provider.enabled", "true");
        props.setProperty("provider.ip", "127.0.0.1");
        props.setProperty("provider.port", "30880");
        props.setProperty("registry.discovery-interval", "200");
        ApplicationConfig.getInstance().init(props);
        ProviderConfig.getInstance().init(props);
        backend = new FakeBackend();
        RegistryBackends.install(backend);
    }

    @AfterEach
    void tearDown() {
        RegistryBackends.reset();
    }

    @Test
    void registerPublishesThriftProvider() {
        ExtensionRegistry registry = new ExtensionRegistry();
        assertTrue(registry.register());

        assertEquals(1, backend.registrations.size());
        Registration registration = backend.registrations.get(0);
        assertEquals("training-b", registration.getServiceId());
        assertEquals("training-b:127.0.0.1:30880", registration.getInstanceId());
        assertEquals("thrift://127.0.0.1:30880", registration.getUri());
        assertEquals("thrift", registration.getMetadata().get("protocol"));
        assertEquals("gray", registration.getTraffic());

        registry.shutdownHook();
        assertEquals(List.of("training-b:127.0.0.1:30880"), backend.deregistered);
    }

    @Test
    void discoveryMapsInstancesAndReuseConsumerConfig() {
        backend.instances = List.of(instance("10.0.0.1", 30880, "gray"),
                instance("10.0.0.2", 30880, null));

        ExtensionDiscovery discovery = new ExtensionDiscovery();
        discovery.watch("training-b");

        List<ConsumerConfig> first = discovery.getInstances("training-b");
        assertEquals(2, first.size());
        assertTrue(discovery.services().contains("training-b"));
        ConsumerConfig gray = find(first, "10.0.0.1");
        assertEquals(30880, gray.getPort());
        assertEquals("gray", gray.getLabel());

        // 实例集合变化后刷新：保留的实例必须复用同一个 ConsumerConfig（连接池活跃计数依赖对象身份）
        backend.instances = List.of(instance("10.0.0.1", 30880, "gray"));
        discovery.refresh("training-b");
        List<ConsumerConfig> second = discovery.getInstances("training-b");
        assertEquals(1, second.size());
        assertSame(gray, second.get(0));
    }

    private static ConsumerConfig find(List<ConsumerConfig> configs, String ip) {
        return configs.stream().filter(config -> ip.equals(config.getIp())).findFirst().orElseThrow();
    }

    private static ServiceInstance instance(String host, int port, String traffic) {
        Map<String, String> metadata = traffic == null ? Map.of() : Map.of("traffic", traffic);
        return new ExtensionServiceInstance(host + ":" + port, "training-b", host, port, metadata);
    }

    private static final class FakeBackend implements ServiceBackend {

        private final List<Registration> registrations = new CopyOnWriteArrayList<>();
        private final List<String> deregistered = new CopyOnWriteArrayList<>();
        private volatile List<ServiceInstance> instances = List.of();
        private final AtomicInteger starts = new AtomicInteger();

        private final ServiceRegistryClient registryClient = new ServiceRegistryClient() {
            @Override
            public void register(Registration registration) {
                registrations.add(registration);
            }

            @Override
            public void deregister(String instanceId) {
                deregistered.add(instanceId);
            }

            @Override
            public String description() {
                return "fake registry";
            }

            @Override
            public void close() {
                // no-op
            }
        };

        private final ServiceDiscoveryClient discoveryClient = new ServiceDiscoveryClient() {
            @Override
            public List<ServiceInstance> getInstances(String serviceId) {
                return instances;
            }

            @Override
            public List<String> getServices() {
                return List.of("training-b");
            }

            @Override
            public String description() {
                return "fake discovery";
            }
        };

        @Override
        public ServiceRegistryClient registry() {
            return registryClient;
        }

        @Override
        public ServiceDiscoveryClient discoveryClient() {
            return discoveryClient;
        }

        @Override
        public void startDiscovery() {
            starts.incrementAndGet();
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
