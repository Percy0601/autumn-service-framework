package com.microapp.autumn.api.config;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.Objects;
import java.util.Properties;

import org.apache.thrift.utils.StringUtils;

import com.microapp.autumn.api.util.CommonUtil;

import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * @author: baoxin.zhao
 * @date: 2024/5/8
 */
@Slf4j
@Getter
@ToString(exclude = {"consulToken", "nacosAccessToken"})
public class ApplicationConfig {
    private static volatile ApplicationConfig instance;

    private ApplicationConfig() {

    }
    private String name;
    private String registryType;
    private String multicastIp;
    private Integer multicastPort;
    private String jvm;
    private String arch;
    /**
     * whether register into consul, default true
     */
    private Boolean consulRegister;
    /**
     * whether discovery consul service, default true
     */
    private Boolean consulDiscovery;
    /**
     * consul instance id, default [application]:[ip]:[port]
     */
    private String consulInstanceId;
    /**
     * consul health check interval, unit second, default 10
     */
    private Integer consulHealthCheckInterval;
    /**
     * consul server host, default 127.0.0.1
     */
    private String consulHost;
    /**
     * consul server port, default 8500
     */
    private Integer consulPort;
    /**
     * consul namespace, not supported by autumn-cloud-extension consul client yet
     */
    private String consulNamespace;
    /**
     * consul scheme, http or https, default http
     */
    private String consulScheme;
    /**
     * consul acl token, optional
     */
    private String consulToken;
    /**
     * nacos server address, default 127.0.0.1:8848
     */
    private String nacosServerAddr;
    /**
     * nacos namespace, default public
     */
    private String nacosNamespace;
    /**
     * nacos group, default DEFAULT
     */
    private String nacosGroup;
    /**
     * nacos access token, optional
     */
    private String nacosAccessToken;
    /**
     * nacos ephemeral instance beat interval, unit millisecond, default 5000
     */
    private Integer nacosBeatInterval;
    /**
     * service discovery refresh interval, unit millisecond, default 5000
     */
    private Integer registryDiscoveryInterval;
    /**
     * instance traffic label, used for grey routing
     */
    private String traffic;
    /**
     * multicast network interface, auto detect when empty
     */
    private String multicastNetworkInterface;
    /**
     * independent control plane thrift port, optional
     */
    private Integer controlPort;
    /**
     * control plane access token, optional
     */
    private String controlToken;
    public static ApplicationConfig getInstance() {
        if(Objects.isNull(instance)) {
            synchronized (ApplicationConfig.class) {
                if(Objects.isNull(instance)) {
                    instance = new ApplicationConfig();
                    String appName = System.getProperty("spring.application.name");
                    if(Objects.isNull(appName) || appName.length() < 1) {
                        appName = System.getenv("spring.application.name");
                    }
                    log.info("application name is: {}", appName);
                    instance.name = appName;

                    Properties configProperties = null;
                    if(Objects.nonNull(appName) && appName.length() > 0) {
                        configProperties = CommonUtil.readClasspath(appName.concat(".properties"));
                    }
                    if(Objects.isNull(configProperties)) {
                        configProperties = CommonUtil.readClasspath("application.properties");
                    }
                    if(Objects.nonNull(configProperties)) {
                        instance.init(configProperties);
                    } else {
                        log.warn("autumn application config not found, use default config: registry-type=multicast, multicast-ip=224.0.0.1, multicast-port=5555");
                    }
                }
            }
        }
        return instance;
    }

    public void init(Properties properties) {
        if(Objects.isNull(properties)) {
            return;
        }

        String nameValue = properties.getProperty("application.name");
        if(Objects.isNull(nameValue) || nameValue.length() < 1) {
            nameValue = properties.getProperty("spring.application.name");
        }
        if(Objects.nonNull(nameValue) && nameValue.length() > 0) {
            this.name = nameValue;
        }

        String registry_type = properties.getProperty("application.registry-type");
        if(Objects.isNull(registry_type) || registry_type.length() < 1) {
            registry_type = "multicast";
        }
        this.registryType = registry_type;

        String multicast_ip = properties.getProperty("application.multicast.ip");
        if(Objects.isNull(multicast_ip) || multicast_ip.length() < 1) {
            multicast_ip = "224.0.0.1";
        }
        this.multicastIp = multicast_ip;

        String multicast_port = properties.getProperty("application.multicast.port");
        if(Objects.isNull(multicast_port) || multicast_port.length() < 1) {
            multicast_port = "5555";
        }
        this.multicastPort = Integer.valueOf(multicast_port);

        String consulRegisterValue = properties.getProperty("consul.register");
        this.consulRegister = Boolean.valueOf((Objects.isNull(consulRegisterValue) || consulRegisterValue.length() < 1)
                ? Boolean.TRUE.toString() : consulRegisterValue);

        String consulDiscoveryValue = properties.getProperty("consul.discovery");
        this.consulDiscovery = Boolean.valueOf((Objects.isNull(consulDiscoveryValue) || consulDiscoveryValue.length() < 1)
                ? Boolean.TRUE.toString() : consulDiscoveryValue);

        this.consulInstanceId = properties.getProperty("consul.instance-id");

        String healthCheckInterval = properties.getProperty("consul.health-check-interval");
        this.consulHealthCheckInterval = Integer.valueOf((Objects.isNull(healthCheckInterval) || healthCheckInterval.length() < 1)
                ? "10" : healthCheckInterval);

        String consulHostValue = properties.getProperty("consul.host");
        this.consulHost = (Objects.isNull(consulHostValue) || consulHostValue.length() < 1) ? "127.0.0.1" : consulHostValue;

        String consulPortValue = properties.getProperty("consul.port");
        this.consulPort = Integer.valueOf((Objects.isNull(consulPortValue) || consulPortValue.length() < 1) ? "8500" : consulPortValue);

        this.consulNamespace = properties.getProperty("consul.namespace");

        String consulSchemeValue = properties.getProperty("consul.scheme");
        this.consulScheme = (Objects.isNull(consulSchemeValue) || consulSchemeValue.length() < 1) ? "http" : consulSchemeValue;

        this.consulToken = properties.getProperty("consul.token");

        String nacosServerAddrValue = properties.getProperty("nacos.server-addr");
        this.nacosServerAddr = (Objects.isNull(nacosServerAddrValue) || nacosServerAddrValue.length() < 1)
                ? "127.0.0.1:8848" : nacosServerAddrValue;

        String nacosNamespaceValue = properties.getProperty("nacos.namespace");
        this.nacosNamespace = (Objects.isNull(nacosNamespaceValue) || nacosNamespaceValue.length() < 1)
                ? "public" : nacosNamespaceValue;

        String nacosGroupValue = properties.getProperty("nacos.group");
        this.nacosGroup = (Objects.isNull(nacosGroupValue) || nacosGroupValue.length() < 1)
                ? "DEFAULT" : nacosGroupValue;

        this.nacosAccessToken = properties.getProperty("nacos.access-token");

        String nacosBeatIntervalValue = properties.getProperty("nacos.beat-interval");
        this.nacosBeatInterval = Integer.valueOf((Objects.isNull(nacosBeatIntervalValue) || nacosBeatIntervalValue.length() < 1)
                ? "5000" : nacosBeatIntervalValue);

        String discoveryIntervalValue = properties.getProperty("registry.discovery-interval");
        this.registryDiscoveryInterval = Integer.valueOf((Objects.isNull(discoveryIntervalValue) || discoveryIntervalValue.length() < 1)
                ? "5000" : discoveryIntervalValue);

        String trafficValue = properties.getProperty("application.traffic");
        this.traffic = (Objects.isNull(trafficValue) || trafficValue.length() < 1) ? null : trafficValue;

        String multicastNetworkInterfaceValue = properties.getProperty("application.multicast.network-interface");
        this.multicastNetworkInterface = (Objects.isNull(multicastNetworkInterfaceValue) || multicastNetworkInterfaceValue.length() < 1)
                ? null : multicastNetworkInterfaceValue;

        String controlPortValue = properties.getProperty("control.port");
        this.controlPort = (Objects.isNull(controlPortValue) || controlPortValue.length() < 1) ? null : Integer.valueOf(controlPortValue);

        this.controlToken = properties.getProperty("control.token");

        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        String jvm = runtime.getVmName();
        this.jvm = jvm;

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        this.arch = os.getArch();
    }



}
