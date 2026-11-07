package xyz.wewin.autumn.registry.extension.backend;

import java.util.Map;

import xyz.wewin.autumn.cloud.extension.core.api.ServiceInstance;

/**
 * extension 发现结果的不可变快照，实现 {@link ServiceInstance} 契约。
 */
public final class ExtensionServiceInstance implements ServiceInstance {

    private final String instanceId;
    private final String serviceId;
    private final String host;
    private final int port;
    private final Map<String, String> metadata;

    public ExtensionServiceInstance(String instanceId, String serviceId, String host, int port,
                                    Map<String, String> metadata) {
        this.instanceId = instanceId;
        this.serviceId = serviceId;
        this.host = host;
        this.port = port;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public String getServiceId() {
        return serviceId;
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
        return "ExtensionServiceInstance{" + serviceId + "@" + host + ":" + port + '}';
    }
}
