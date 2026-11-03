package com.microapp.autumn.core.registry.consul.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.thrift.TServiceClient;

import com.microapp.autumn.api.Discovery;
import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ConsumerConfig;
import com.microapp.autumn.api.config.ReferenceConfig;
import com.microapp.autumn.core.pool.AutumnPool;
import com.microapp.autumn.core.registry.consul.client.util.ConsulClient;

import lombok.extern.slf4j.Slf4j;

/**
 * consul discovery, periodically refresh healthy instances of watched services
 * from consul agent(/v1/health/service/{name}?passing=true) into local cache.
 * <p>
 * consul agent itself maintains tcp health check, so no local health check needed,
 * the passing=true filter guarantees returned instances are healthy.
 *
 * @author: baoxin.zhao
 * @date: 2024/10/28
 */
@Slf4j
public class ConsulDiscovery implements Discovery {

    private static final long DISCOVERY_INTERVAL_MILLIS = 5000L;

    private static volatile ConsulDiscovery instance;

    /**
     * watched service names
     */
    private final Set<String> watched = ConcurrentHashMap.newKeySet();
    /**
     * service name -> healthy instances cache
     */
    private final Map<String, List<ConsumerConfig>> instances = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ConsulDiscovery() {

    }

    public static ConsulDiscovery provider() {
        if(Objects.isNull(instance)) {
            synchronized (ConsulDiscovery.class) {
                if(Objects.isNull(instance)) {
                    instance = new ConsulDiscovery();
                }
            }
        }
        return instance;
    }

    @Override
    public void watch(String name) {
        watched.add(name);
    }

    @Override
    public Set<String> services() {
        return watched;
    }

    @Override
    public List<ConsumerConfig> getInstances(String name) {
        List<ConsumerConfig> list = instances.get(name);
        if(Objects.nonNull(list)) {
            return list;
        }
        ReferenceConfig<? extends TServiceClient> config = AutumnPool.getInstance().getReferenceConfig(name);
        if(Objects.isNull(config)) {
            return null;
        }
        return config.getInstances();
    }

    @Override
    public void checkHealth() {
        // consul agent owns tcp health check, passing=true query is enough, nothing to do locally
    }

    @Override
    public void discovery() {
        if(!running.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            log.info("consul discovery thread started.");
            while(!Thread.currentThread().isInterrupted()) {
                try {
                    refresh();
                    Thread.sleep(DISCOVERY_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("consul discovery thread interrupted.");
                    return;
                } catch (Exception e) {
                    log.warn("consul discovery refresh exception:", e);
                }
            }
        }, "autumn-consul-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    private void refresh() {
        ApplicationConfig applicationConfig = ApplicationConfig.getInstance();
        if(!Boolean.TRUE.equals(applicationConfig.getConsulDiscovery())) {
            return;
        }
        String host = applicationConfig.getConsulHost();
        Integer port = applicationConfig.getConsulPort();
        String baseUrl = "http://" + host + ":" + port;
        for(String name : watched) {
            try {
                List<ConsumerConfig> list = ConsulClient.getInstance().discovery(baseUrl, name, consulHeaders(applicationConfig));
                instances.put(name, list);
                if(log.isDebugEnabled()) {
                    log.debug("consul discovery refreshed, service:{}, instances:{}", name, list);
                }
            } catch (Exception e) {
                log.warn("consul discovery exception, service:{}", name, e);
            }
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
