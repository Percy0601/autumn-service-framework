package xyz.wewin.autumn.api.config;

import java.util.Objects;
import java.util.Properties;

import xyz.wewin.autumn.api.util.CommonUtil;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * @author: baoxin.zhao
 * @date: 2024/5/9
 */
@Getter
@Setter
@ToString(exclude = {"controlToken"})
public class ProviderConfig {
    private static volatile ProviderConfig instance;
    private String ip;
    private Integer port;
    private Integer minThreads;
    private Integer maxThreads;
    private Integer workerKeepAliveTime;
    private Integer threadQueueSize;
    private Integer timeout;
    private Boolean enabled;
    /**
     * independent control plane port; 0 means control api shares the business thrift port
     */
    private Integer controlPort;
    /**
     * bind address of the independent control plane
     */
    private String controlHost;
    /**
     * shared token for control api, null/empty disables authentication
     */
    private String controlToken;
    /**
     * whether config already initialized, avoid classpath config overwrite spring config
     */
    private volatile Boolean initialized = Boolean.FALSE;

    public static ProviderConfig getInstance() {
        if(Objects.isNull(instance)) {
            synchronized (ProviderConfig.class) {
                if(Objects.isNull(instance)) {
                    instance = new ProviderConfig();
                }
            }
        }
        return instance;
    }

    public void init(Properties properties) {
        if(Boolean.TRUE.equals(initialized)) {
            return;
        }
        String enabledValue = properties.getProperty("provider.enabled");
        if(Objects.nonNull(enabledValue) && enabledValue.length() > 0) {
            Boolean _enabled = Boolean.valueOf(enabledValue);
            this.enabled = _enabled;
        } else {
            this.enabled = true;
        }
        String portValue = properties.getProperty("provider.port");
        if(Objects.nonNull(portValue) && portValue.length() > 0) {
            Integer _port = Integer.valueOf(portValue);
            this.port = _port;
        } else {
            this.port = 30880;
        }
        String minThreadsValue = properties.getProperty("provider.min-threads");
        if(Objects.nonNull(minThreadsValue) && minThreadsValue.length() > 0) {
            Integer _minThreads = Integer.valueOf(minThreadsValue);
            this.minThreads = _minThreads;
        } else {
            this.minThreads = 8;
        }
        String maxThreadsValue = properties.getProperty("provider.max-threads");
        if(Objects.nonNull(maxThreadsValue) && maxThreadsValue.length() > 0) {
            Integer _maxThreads = Integer.valueOf(maxThreadsValue);
            this.maxThreads = _maxThreads;
        } else {
            this.maxThreads = 32;
        }

        String workerKeepAliveTimeValue = properties.getProperty("provider.worker-keep-alive-time");
        if(Objects.nonNull(workerKeepAliveTimeValue) && workerKeepAliveTimeValue.length() > 0) {
            Integer inner_workerKeepAliveTime = Integer.valueOf(workerKeepAliveTimeValue);
            this.workerKeepAliveTime = inner_workerKeepAliveTime;
        } else {
            this.workerKeepAliveTime = 60;
        }
        String threadQueueSizeValue = properties.getProperty("provider.thread-queue-size");
        if(Objects.nonNull(threadQueueSizeValue) && threadQueueSizeValue.length() > 0) {
            Integer _threadQueueSize = Integer.valueOf(threadQueueSizeValue);
            this.threadQueueSize = _threadQueueSize;
        } else {
            this.threadQueueSize = 10;
        }
        String timeoutValue = properties.getProperty("provider.timeout");
        if(Objects.nonNull(timeoutValue) && timeoutValue.length() > 0) {
            Integer _timeout = Integer.valueOf(timeoutValue);
            this.timeout = _timeout;
        } else {
            this.timeout = 3;
        }

        String _ip = properties.getProperty("provider.ip");
        if(Objects.nonNull(_ip) && _ip.length() > 0) {
            this.ip = _ip;
        } else {
            this.ip = CommonUtil.getHostIpAddress();
        }

        String controlPortValue = properties.getProperty("provider.control-port");
        this.controlPort = Integer.valueOf((Objects.isNull(controlPortValue) || controlPortValue.length() < 1)
                ? "0" : controlPortValue);

        String controlHostValue = properties.getProperty("provider.control-host");
        this.controlHost = (Objects.isNull(controlHostValue) || controlHostValue.length() < 1)
                ? "127.0.0.1" : controlHostValue;

        String controlTokenValue = properties.getProperty("provider.control-token");
        this.controlToken = (Objects.isNull(controlTokenValue) || controlTokenValue.length() < 1)
                ? null : controlTokenValue;
        this.initialized = Boolean.TRUE;
    }
}
