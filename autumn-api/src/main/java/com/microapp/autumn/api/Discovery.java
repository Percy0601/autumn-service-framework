package com.microapp.autumn.api;

import java.util.List;
import java.util.Set;

import org.apache.thrift.TServiceClient;

import com.microapp.autumn.api.config.ConsumerConfig;
import com.microapp.autumn.api.config.ReferenceConfig;


/**
 * 服务发现 SPI。
 *
 * <p>实现由 {@code autumn-registry-extension} 提供（基于 {@code autumn-cloud-extension}），
 * 通过 {@code META-INF/services} 装配；本模块不再内置实现。</p>
 *
 * @author: baoxin.zhao
 * @date: 2024/10/28
 */
public interface Discovery {

    /**
     * add watch service
     *
     * @param name
     */
    void watch(String name);

    /**
     * get all watch service list
     * @return
     */
    Set<String> services();

    /**
     * get service instances
     *
     * @param name
     * @return
     */
    List<ConsumerConfig> getInstances(String name);

    /**
     * handle check watch service health
     */
    void checkHealth();

    /**
     * discovery reference service
     */
    void discovery();

    /**
     * 停止发现线程并释放资源，默认空实现
     */
    default void close() {
    }
}
