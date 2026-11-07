package xyz.wewin.autumn.api.config;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.apache.thrift.TServiceClient;
import org.apache.thrift.protocol.TMultiplexedProtocol;

import xyz.wewin.autumn.api.enums.RegistryTypeEnum;

import lombok.Data;

/**
 * @author: baoxin.zhao
 * @date: 2024/5/9
 */
@Data
public class ReferenceConfig<R extends TServiceClient> {
    /** 连接池借用超时：拿不到连接时最多等这么久，避免调用线程在无实例时干等 */
    public static final long DEFAULT_POOL_TIMEOUT_MILLIS = 1_000L;
    /** 建连超时：服务端未启动 / 网络不可达时 TCP connect 不会无限等待 */
    public static final long DEFAULT_CONNECTION_TIMEOUT_MILLIS = 1_000L;
    /** 读响应超时：服务端接了连接却不回包时，调用线程不会挂死 */
    public static final long DEFAULT_SOCKET_TIMEOUT_MILLIS = 3_000L;

    /**
     * refer provider app name
     */
    private String name;
    /**
     * pool timeout: default 1s, borrow a connection from pool
     */
    private Long poolTimeout = DEFAULT_POOL_TIMEOUT_MILLIS;
    /**
     * connection timeout: default 1s, TCP connect
     */
    private Long connectionTimeout = DEFAULT_CONNECTION_TIMEOUT_MILLIS;
    /**
     * socket timeout: default 3s, read reply from server
     */
    private Long socketTimeout = DEFAULT_SOCKET_TIMEOUT_MILLIS;
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

    /**
     * 借用超时：未配置或配置为非正数时回落到默认值（0 在连接池里会立即失败，不能当成无限等待）
     */
    public long effectivePoolTimeout() {
        return positiveOr(poolTimeout, DEFAULT_POOL_TIMEOUT_MILLIS);
    }

    /**
     * 建连超时：未配置或配置为非正数时回落到默认值
     */
    public long effectiveConnectionTimeout() {
        return positiveOr(connectionTimeout, DEFAULT_CONNECTION_TIMEOUT_MILLIS);
    }

    /**
     * 读响应超时：未配置或配置为非正数时回落到默认值
     */
    public long effectiveSocketTimeout() {
        return positiveOr(socketTimeout, DEFAULT_SOCKET_TIMEOUT_MILLIS);
    }

    private static long positiveOr(Long value, long fallback) {
        return Objects.isNull(value) || value <= 0 ? fallback : value;
    }
}
