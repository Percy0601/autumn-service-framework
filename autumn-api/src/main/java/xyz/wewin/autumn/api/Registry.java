package xyz.wewin.autumn.api;

/**
 * 服务注册 SPI。
 *
 * <p>实现由 {@code autumn-registry-extension} 提供（基于 {@code autumn-cloud-extension}），
 * 通过 {@code META-INF/services} 装配；本模块不再内置实现。</p>
 *
 * @author: baoxin.zhao
 * @date: 2024/10/28
 */
public interface Registry {
    Boolean register();
    void shutdownHook();
}
