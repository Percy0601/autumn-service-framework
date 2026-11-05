package com.microapp.autumn.api.config;

import java.util.List;
import java.util.function.Function;

import org.apache.thrift.TServiceClient;
import org.apache.thrift.protocol.TMultiplexedProtocol;

import com.microapp.autumn.api.enums.RegistryTypeEnum;

import lombok.Data;

/**
 * @author: baoxin.zhao
 * @date: 2024/5/9
 */
@Data
public class ReferenceConfig<R extends TServiceClient> {
    /**
     * refer provider app name
     */
    private String name;
    /**
     * pool timeout: default 1s
     */
    private Long poolTimeout;
    /**
     * connection timeout: default 1s
     */
    private Long connectionTimeout;
    /**
     * socket timeout: default 1s
     */
    private Long socketTimeout;
    /**
     * max connections of one service pool, default 32
     */
    private Integer maxPoolSize = 32;
    /**
     * min idle connections of one service pool, default 0
     */
    private Integer minIdle = 0;
    /**
     * close idle connection after this milliseconds, default 5 minutes
     */
    private Long idleTimeout = 300_000L;
    /**
     * close connection after this milliseconds, default 30 minutes
     */
    private Long maxLifetime = 1_800_000L;
    /**
     * validate connection (open state and lifetime) before borrow, default true
     */
    private Boolean validateOnBorrow = Boolean.TRUE;
    /**
     * registryTypeEnum: CONSUL, MULTICAST
     */
    private RegistryTypeEnum registryTypeEnum;
    private List<ConsumerConfig> instances;
    private Class<R> interfaceClass;
    private Function<TMultiplexedProtocol, R> converter;
}
