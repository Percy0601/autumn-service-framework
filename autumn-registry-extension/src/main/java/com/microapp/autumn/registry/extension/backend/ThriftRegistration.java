package com.microapp.autumn.registry.extension.backend;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ProviderConfig;
import com.microapp.autumn.api.util.CommonUtil;
import com.microapp.autumn.api.extension.TrafficRouter;

import com.autumn.cloud.extension.core.api.Registration;
import com.autumn.cloud.extension.core.model.InstanceRegistration;

/**
 * thrift provider 的注册信息，实现 extension 的 {@link Registration} 契约。
 */
public final class ThriftRegistration implements Registration {

    private static final String DEFAULT_PORT = "30880";

    private final String service;
    private final String instanceId;
    private final String host;
    private final int port;
    private final Map<String, String> metadata;

    private ThriftRegistration(String service, String instanceId, String host, int port, Map<String, String> metadata) {
        this.service = service;
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
        this.metadata = metadata;
    }

    /**
     * 由 autumn 配置构建注册信息
     */
    public static ThriftRegistration of(ApplicationConfig applicationConfig, ProviderConfig providerConfig) {
        String service = Objects.isNull(applicationConfig.getName()) || applicationConfig.getName().isBlank()
                ? "autumn-application" : applicationConfig.getName();
        String host = Objects.isNull(providerConfig.getIp()) || providerConfig.getIp().isBlank()
                ? CommonUtil.getHostIpAddress() : providerConfig.getIp();
        int port = Objects.isNull(providerConfig.getPort())
                ? Integer.parseInt(DEFAULT_PORT) : providerConfig.getPort();

        String instanceId = applicationConfig.getConsulInstanceId();
        if (Objects.isNull(instanceId) || instanceId.isBlank()) {
            instanceId = service + ":" + host + ":" + port;
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        if (Objects.nonNull(applicationConfig.getLabels())) {
            // 自定义实例标签（application.labels.*），供消费侧 label.* 隐式参数筛选
            metadata.putAll(applicationConfig.getLabels());
        }
        metadata.put("protocol", "thrift");
        metadata.put("application", service);
        if (Objects.nonNull(applicationConfig.getTraffic()) && !applicationConfig.getTraffic().isBlank()) {
            metadata.put(TrafficRouter.META_KEY, applicationConfig.getTraffic());
        }
        return new ThriftRegistration(service, instanceId, host, port, Map.copyOf(metadata));
    }

    public InstanceRegistration toInstanceRegistration() {
        return new InstanceRegistration(service, instanceId, host, port, metadata);
    }

    @Override
    public String getServiceId() {
        return service;
    }

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public String getHost() {
        return host;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public String getUri() {
        return "thrift://" + host + ":" + port;
    }

    @Override
    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "ThriftRegistration{" + instanceId + ", traffic=" + getTraffic() + '}';
    }
}
