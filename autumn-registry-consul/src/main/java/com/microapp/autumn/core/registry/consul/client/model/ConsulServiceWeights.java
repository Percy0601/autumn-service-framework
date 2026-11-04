package com.microapp.autumn.core.registry.consul.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/5
 */
@Deprecated(since = "1.0.0", forRemoval = true)
@Data
public class ConsulServiceWeights {
    @JsonProperty("Passing")
    private String passing;
    @JsonProperty("Warning")
    private String warning;
}
