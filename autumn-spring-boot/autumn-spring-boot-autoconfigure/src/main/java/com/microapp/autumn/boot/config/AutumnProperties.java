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
     * registry type: multicast / consul
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
     * provider config
     */
    private Provider provider = new Provider();

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
}
