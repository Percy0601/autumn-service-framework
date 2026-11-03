package com.microapp.autumn.api.config;

import java.util.Objects;
import java.util.Properties;

import com.microapp.autumn.api.util.CommonUtil;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * @author: baoxin.zhao
 * @date: 2024/5/9
 */
@Getter
@Setter
@ToString
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
            this.minThreads = 0;
        }
        String maxThreadsValue = properties.getProperty("provider.max-threads");
        if(Objects.nonNull(maxThreadsValue) && maxThreadsValue.length() > 0) {
            Integer _maxThreads = Integer.valueOf(maxThreadsValue);
            this.maxThreads = _maxThreads;
        } else {
            this.maxThreads = 1;
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
        this.initialized = Boolean.TRUE;
    }
}
