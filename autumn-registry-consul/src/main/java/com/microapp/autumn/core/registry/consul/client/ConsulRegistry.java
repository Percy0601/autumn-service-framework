package com.microapp.autumn.core.registry.consul.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.microapp.autumn.api.Registry;
import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ProviderConfig;
import com.microapp.autumn.core.registry.consul.client.util.ConsulClient;

import lombok.extern.slf4j.Slf4j;

/**
 * consul registry, register provider into consul agent with tcp health check,
 * deregister on shutdown.
 *
 * @author: baoxin.zhao
 * @date: 2024/10/28
 */
@Slf4j
public class ConsulRegistry implements Registry {
    private static volatile ConsulRegistry instance;
    private ConsulRegistry() {

    }

    public static ConsulRegistry provider() {
        if(Objects.isNull(instance)) {
            synchronized (ConsulRegistry.class) {
                if(Objects.isNull(instance)) {
                    instance = new ConsulRegistry();
                }
            }
        }
        return instance;
    }

    @Override
    public Boolean register() {
        ApplicationConfig applicationConfig = ApplicationConfig.getInstance();
        ProviderConfig providerConfig = ProviderConfig.getInstance();
        if(!Boolean.TRUE.equals(applicationConfig.getConsulRegister())) {
            log.info("consul register disabled, skip. application:{}", applicationConfig.getName());
            return Boolean.TRUE;
        }
        String host = applicationConfig.getConsulHost();
        Integer port = applicationConfig.getConsulPort();
        String baseUrl = "http://" + host + ":" + port;
        String name = applicationConfig.getName();
        String ip = providerConfig.getIp();
        Integer providerPort = providerConfig.getPort();
        String instanceId = applicationConfig.getConsulInstanceId();
        if(Objects.isNull(instanceId) || instanceId.length() < 1) {
            instanceId = name + ":" + ip + ":" + providerPort;
        }
        List<String> tags = List.of("autumn", "thrift");
        Map<String, String> meta = new HashMap<>();
        meta.put("registry", "consul");
        meta.put("protocol", "thrift");
        meta.put("application", Objects.isNull(name) ? "" : name);
        try {
            Boolean result = ConsulClient.getInstance().register(
                    baseUrl + "/v1/agent/service/register",
                    instanceId,
                    name,
                    tags,
                    ip,
                    providerPort,
                    meta,
                    consulHeaders(applicationConfig));
            log.info("consul register, application:{}, instanceId:{}, ip:{}, port:{}, result:{}", name, instanceId, ip, providerPort, result);
            return result;
        } catch (Exception e) {
            log.warn("consul register exception, application:{}", name, e);
            return Boolean.FALSE;
        }
    }

    @Override
    public void shutdownHook() {
        ApplicationConfig applicationConfig = ApplicationConfig.getInstance();
        ProviderConfig providerConfig = ProviderConfig.getInstance();
        try {
            String host = applicationConfig.getConsulHost();
            Integer port = applicationConfig.getConsulPort();
            String baseUrl = "http://" + host + ":" + port;
            String name = applicationConfig.getName();
            String ip = providerConfig.getIp();
            Integer providerPort = providerConfig.getPort();
            String instanceId = applicationConfig.getConsulInstanceId();
            if(Objects.isNull(instanceId) || instanceId.length() < 1) {
                instanceId = name + ":" + ip + ":" + providerPort;
            }
            ConsulClient.getInstance().deregister(baseUrl, instanceId, consulHeaders(applicationConfig));
            log.info("consul deregister, application:{}, instanceId:{}", name, instanceId);
        } catch (Exception e) {
            log.warn("consul shutdownHook exception:", e);
        }
    }

    private static Map<String, String> consulHeaders(ApplicationConfig applicationConfig) {
        Map<String, String> headers = new HashMap<>();
        String namespace = applicationConfig.getConsulNamespace();
        if(Objects.nonNull(namespace) && namespace.length() > 0) {
            headers.put("X-Consul-Namespace", namespace);
        }
        return headers;
    }
}
