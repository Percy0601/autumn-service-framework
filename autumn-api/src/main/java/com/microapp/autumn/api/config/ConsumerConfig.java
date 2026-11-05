package com.microapp.autumn.api.config;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Setter
@Getter
@ToString
public class ConsumerConfig {

    /**
     * reference service name
     */
    private String name;
    /**
     * consumer ip
     */
    private String ip;
    /**
     * consumer port
     */
    private Integer port;
    /**
     * consumer label, with label route the consumer instance.
     */
    private String label;
    /**
     * consumer contains references service list
     */
    private Set<String> references;
    /**
     * current active connection
     */
    private AtomicInteger active;
    /**
     * max connection
     */
    private Integer connections;
    /**
     * check health fail count
     */
    private Integer checkFail;
    /**
     * get latest registry time
     */
    private Long latestTime;
    /**
     * registry version, less 3 version, delete this instance
     */
    private Integer version;
}
