package com.microapp.autumn.core.registry.consul.client.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/5
 */
@Deprecated(since = "1.0.0", forRemoval = true)
@Data
public class ConsulService {
    @JsonProperty("ID")
    private String id;
    @JsonProperty(value = "Name", access = JsonProperty.Access.WRITE_ONLY)
    private String name;
    @JsonProperty(value = "Service", access = JsonProperty.Access.READ_ONLY)
    private String service;
    @JsonProperty("Tags")
    private List<String> tags;
    @JsonProperty("Address")
    private String address;
    @JsonProperty("Port")
    private Integer port;
    @JsonProperty("Namespace")
    private String namespace;
    @JsonProperty("Datacenter")
    private String datacenter;
    @JsonProperty("Meta")
    private Map<String, String> meta;
    @JsonProperty("Check")
    private ConsulServiceCheck check;
    @JsonProperty("Weights")
    private ConsulServiceWeights weights;


}
