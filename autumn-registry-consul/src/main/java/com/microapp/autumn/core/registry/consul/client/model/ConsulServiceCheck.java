package com.microapp.autumn.core.registry.consul.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/5
 */
@Deprecated(since = "1.0.0", forRemoval = true)
@Data
public class ConsulServiceCheck {
    @JsonProperty("DeregisterCriticalServiceAfter")
    private String deregisterCriticalServiceAfter;
    @JsonProperty("TCP")
    private String tcp;
    @JsonProperty("Interval")
    private String interval;
    @JsonProperty("Timeout")
    private String timeout;

}
