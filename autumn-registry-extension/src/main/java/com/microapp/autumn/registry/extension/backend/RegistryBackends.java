package com.microapp.autumn.registry.extension.backend;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ProviderConfig;
import com.microapp.autumn.api.util.CommonUtil;

import com.autumn.cloud.extension.core.api.ServiceDiscoveryClient;
import com.autumn.cloud.extension.core.api.ServiceRegistryClient;
import com.autumn.cloud.extension.core.consul.ConsulSettings;
import com.autumn.cloud.extension.core.multicast.MulticastConfig;
import com.autumn.cloud.extension.core.nacos.NacosSettings;

/**
 * 按 {@code application.registry-type} 装配注册中心后端，进程内单例。
 *
 * <p>registry-type 取值：{@code consul} / {@code nacos} / {@code multicast}（默认）/ {@code direct}（不注册）。</p>
 */
public final class RegistryBackends {

    private static final Logger log = LoggerFactory.getLogger(RegistryBackends.class);

    private static volatile ServiceBackend backend;

    private RegistryBackends() {
    }

    public static ServiceBackend shared() {
        ServiceBackend current = backend;
        if (Objects.nonNull(current)) {
            return current;
        }
        synchronized (RegistryBackends.class) {
            if (Objects.isNull(backend)) {
                backend = create(ApplicationConfig.getInstance());
                log.info("autumn registry backend initialized: {}", backend.getClass().getSimpleName());
            }
            return backend;
        }
    }

    /**
     * 注入自定义后端（测试 / 嵌入式场景），会先释放当前后端
     */
    public static synchronized void install(ServiceBackend custom) {
        reset();
        backend = custom;
    }

    /**
     * 释放后端资源并允许重新装配（主要给测试用）
     */
    public static synchronized void reset() {
        if (Objects.nonNull(backend)) {
            try {
                backend.close();
            } catch (Exception e) {
                log.warn("autumn registry backend close exception:{}", e.getMessage());
            }
            backend = null;
        }
    }

    private static ServiceBackend create(ApplicationConfig config) {
        String type = Objects.isNull(config.getRegistryType())
                ? "multicast" : config.getRegistryType().trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "consul" -> new ConsulBackend(new ConsulSettings(
                    config.getConsulHost(),
                    Objects.isNull(config.getConsulPort()) ? 8500 : config.getConsulPort(),
                    config.getConsulScheme(),
                    config.getConsulToken(),
                    config.getConsulNamespace(),
                    Objects.isNull(config.getConsulHealthCheckInterval()) ? 10 : config.getConsulHealthCheckInterval()));
            case "nacos" -> new NacosBackend(new NacosSettings(
                    config.getNacosServerAddr(),
                    config.getNacosNamespace(),
                    config.getNacosGroup(),
                    config.getNacosAccessToken(),
                    Objects.isNull(config.getNacosBeatInterval()) ? 5000L : config.getNacosBeatInterval()));
            case "direct" -> new NoopBackend();
            default -> new MulticastBackend(new MulticastConfig(
                    config.getMulticastIp(),
                    Objects.isNull(config.getMulticastPort()) ? 5555 : config.getMulticastPort(),
                    1, 0, 0,
                    multicastNetworkInterface(config)));
        };
    }

    /**
     * 网卡选择统一到 CommonUtil：显式 network-interface 优先，否则用注册 IP 反查网卡，
     * 避免注册 IP 与组播出口落在不同网卡导致跨机发现不通。
     */
    private static String multicastNetworkInterface(ApplicationConfig config) {
        String preferred = config.getMulticastNetworkInterface();
        if (Objects.isNull(preferred) || preferred.isBlank()) {
            preferred = ProviderConfig.getInstance().getIp();
        }
        return CommonUtil.resolveNetworkInterface(preferred);
    }

    /**
     * direct 模式：点对点直连，不需要注册中心
     */
    private static final class NoopBackend implements ServiceBackend {

        private final ServiceRegistryClient registry = new ServiceRegistryClient() {
            @Override
            public void register(com.autumn.cloud.extension.core.api.Registration registration) {
                // direct 模式不注册
            }

            @Override
            public void deregister(String instanceId) {
                // direct 模式不注销
            }

            @Override
            public String description() {
                return "autumn direct (no registry)";
            }

            @Override
            public void close() {
                // no-op
            }
        };

        private final ServiceDiscoveryClient discovery = new ServiceDiscoveryClient() {
            @Override
            public List<com.autumn.cloud.extension.core.api.ServiceInstance> getInstances(String serviceId) {
                return List.of();
            }

            @Override
            public List<String> getServices() {
                return List.of();
            }

            @Override
            public String description() {
                return "autumn direct (no discovery)";
            }
        };

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
            // no-op
        }
    }
}
