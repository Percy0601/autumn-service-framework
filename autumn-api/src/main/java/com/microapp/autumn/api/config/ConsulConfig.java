package com.microapp.autumn.api.config;

import lombok.Data;

/**
 * @author: baoxin.zhao
 *
 * @date: 2024/5/9
 */
@Data
public class ConsulConfig {
    /**
     * need registry consul
     */
    private Boolean register;
    /**
     * need discovery consul service
     */
    private Boolean discovery;
    /**
     * registry consul id, format [application]:[ip]:[port]
     */
    private String instanceId;
    /**
     * default 10, unit second
     */
    private Integer healthCheckInterval;
    /**
     * consul server host
     */
    private String host;
    /**
     * consul server port
     */
    private Integer port;
    /**
     * namespace
     */
    private String namespace;
}
