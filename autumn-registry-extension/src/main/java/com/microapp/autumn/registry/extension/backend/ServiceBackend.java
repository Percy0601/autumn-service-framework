package com.microapp.autumn.registry.extension.backend;

import com.autumn.cloud.extension.core.api.ServiceDiscoveryClient;
import com.autumn.cloud.extension.core.api.ServiceRegistryClient;

/**
 * 注册中心后端（consul / nacos / multicast）统一入口。
 *
 * <p>每个后端同时提供注册与发现两个客户端，二者共享同一份连接与本地缓存，
 * 因此必须以进程内单例方式使用（见 {@link RegistryBackends}）。</p>
 */
public interface ServiceBackend extends AutoCloseable {

    /**
     * 注册侧客户端
     */
    ServiceRegistryClient registry();

    /**
     * 发现侧客户端
     */
    ServiceDiscoveryClient discoveryClient();

    /**
     * 启动发现侧需要常驻的资源（组播需要加入组播组才开始收包），幂等
     */
    default void startDiscovery() {
    }

    /**
     * 释放后端资源
     */
    @Override
    void close();
}
