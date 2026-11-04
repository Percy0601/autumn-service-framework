package com.microapp.autumn.boot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * autumn spring boot starter configuration properties.
 *
 * <pre>
 * autumn.name=training-b
 * autumn.registry-type=multicast
 * autumn.multicast.ip=224.0.0.1
 * autumn.multicast.port=5555
 * autumn.provider.enabled=true
 * autumn.provider.port=30880
 * autumn.provider.min-threads=0
 * autumn.provider.max-threads=1
 * autumn.provider.worker-keep-alive-time=60
 * autumn.provider.thread-queue-size=10
 * autumn.provider.timeout=3
 * autumn.provider.ip=127.0.0.1
 * </pre>
 *
 * @author: baoxin.zhao
 * @date: 2026/11/3
 */
@Data
@ConfigurationProperties(prefix = "autumn")
public class AutumnProperties {

    /**
     * application name, used for registry and discovery
     */
    private String name;

    /**
     * registry type: multicast / consul / nacos / direct
     */
    private String registryType = "multicast";

    /**
     * multicast registry ip, default 224.0.0.1
     */
    private String multicastIp = "224.0.0.1";

    /**
     * multicast registry port, default 5555
     */
    private Integer multicastPort = 5555;

    /**
     * service discovery refresh interval, unit millisecond, default 5000
     */
    private Integer discoveryInterval = 5000;

    /**
     * instance traffic label, used for grey routing, empty means unlabeled
     */
    private String traffic;

    /**
     * provider config
     */
    private Provider provider = new Provider();

    /**
     * consul registry config
     */
    private Consul consul = new Consul();

    /**
     * nacos registry config
     */
    private Nacos nacos = new Nacos();

    @Data
    public static class Provider {
        /**
         * enable thrift provider server, default true
         */
        private Boolean enabled = Boolean.TRUE;
        /**
         * provider server port, default 30880
         */
        private Integer port = 30880;
        /**
         * worker executor min threads
         */
        private Integer minThreads = 0;
        /**
         * worker executor max threads
         */
        private Integer maxThreads = 1;
        /**
         * worker keep alive time, unit second
         */
        private Integer workerKeepAliveTime = 60;
        /**
         * worker thread queue size
         */
        private Integer threadQueueSize = 10;
        /**
         * provider process timeout, unit second
         */
        private Integer timeout = 3;
        /**
         * provider bind ip, default auto detect
         */
        private String ip;
    }

    @Data
    public static class Consul {
        /**
         * whether register into consul, default true
         */
        private Boolean register = Boolean.TRUE;
        /**
         * whether discovery consul service, default true
         */
        private Boolean discovery = Boolean.TRUE;
        /**
         * consul instance id, default [application]:[ip]:[port]
         */
        private String instanceId;
        /**
         * consul health check interval, unit second, default 10
         */
        private Integer healthCheckInterval = 10;
        /**
         * consul server host, default 127.0.0.1
         */
        private String host = "127.0.0.1";
        /**
         * consul server port, default 8500
         */
        private Integer port = 8500;
        /**
         * consul namespace
         */
        private String namespace;
    }

    @Data
    public static class Nacos {
        /**
         * nacos server address, default 127.0.0.1:8848
         */
        private String serverAddr = "127.0.0.1:8848";
        /**
         * nacos namespace, default public
         */
        private String namespace = "public";
        /**
         * nacos group, default DEFAULT
         */
        private String group = "DEFAULT";
        /**
         * nacos access token, optional
         */
        private String accessToken;
        /**
         * ephemeral instance beat interval, unit millisecond, default 5000
         */
        private Integer beatInterval = 5000;
    }
}
