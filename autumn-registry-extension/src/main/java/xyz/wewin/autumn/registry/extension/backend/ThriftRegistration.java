package xyz.wewin.autumn.registry.extension.backend;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import xyz.wewin.autumn.api.config.ApplicationConfig;
import xyz.wewin.autumn.api.config.ProviderConfig;
import xyz.wewin.autumn.api.util.CommonUtil;
import xyz.wewin.autumn.api.extension.TrafficRouter;

import xyz.wewin.autumn.cloud.extension.core.api.Registration;
import xyz.wewin.autumn.cloud.extension.core.model.InstanceRegistration;

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
        metadata.put("application", service);
        // 只声明 protocols：thrift 恒在，web 容器真实启动后追加 http；
        // 历史实例只写 protocol 的，由消费侧兜底识别，无需重复声明
        StringBuilder protocols = new StringBuilder("thrift");
        Integer httpPort = providerConfig.getHttpPort();
        if (Objects.nonNull(httpPort) && httpPort > 0) {
            protocols.append(",http");
            metadata.put("http-port", String.valueOf(httpPort));
        }
        metadata.put("protocols", protocols.toString());
        metadata.put("runtime", runtime());
        metadata.put("vm-version", vmVersion());
        // 泳道标签始终写入（未配置归一化为基线 main），运维和消费侧都能按 traffic 统一筛选
        metadata.put(TrafficRouter.META_KEY, TrafficRouter.normalize(applicationConfig.getTraffic()));
        return new ThriftRegistration(service, instanceId, host, port, Map.copyOf(metadata));
    }

    /**
     * 运行时类型：GraalVM native image 与普通 JVM 分开标注，便于运维/灰度按运行时筛选实例。
     *
     * <p>判定方式与 spring-boot-native-samples 一致：native image 下
     * {@code RuntimeMXBean.getVmName()} 为 "Substrate VM"；系统属性
     * {@code org.graalvm.nativeimage.imagecode} 作为更可靠的兜底判定。</p>
     */
    private static String runtime() {
        return isNativeImage() ? "graalvm-native" : "jvm";
    }

    private static boolean isNativeImage() {
        if (Objects.nonNull(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return true;
        }
        String vmName = vmName();
        return Objects.nonNull(vmName) && vmName.toLowerCase(Locale.ROOT).contains("substrate");
    }

    private static RuntimeMXBean runtimeBean() {
        try {
            return ManagementFactory.getRuntimeMXBean();
        } catch (Exception e) {
            return null;
        }
    }

    private static String vmName() {
        RuntimeMXBean runtime = runtimeBean();
        return Objects.isNull(runtime) ? null : runtime.getVmName();
    }

    /** 运行时版本：native 下为 Substrate VM 版本（含 JDK 版本），取不到时回退 java.version */
    private static String vmVersion() {
        RuntimeMXBean runtime = runtimeBean();
        String vmVersion = Objects.isNull(runtime) ? null : runtime.getVmVersion();
        return Objects.isNull(vmVersion) || vmVersion.isBlank()
                ? System.getProperty("java.version", "unknown") : vmVersion;
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
