package com.microapp.autumn.registry.extension;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microapp.autumn.api.Registry;
import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ProviderConfig;
import com.microapp.autumn.registry.extension.backend.RegistryBackends;
import com.microapp.autumn.registry.extension.backend.ThriftRegistration;

/**
 * 基于 autumn-cloud-extension 的服务注册实现。
 *
 * <p>替换原 {@code MulticastRegistry} / {@code ConsulRegistry}：注册协议、TTL 续约、
 * 心跳全部由 extension 后端负责，这里只做 thrift provider 到 {@code Registration} 的翻译。</p>
 */
public class ExtensionRegistry implements Registry {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistry.class);

    private final AtomicBoolean registered = new AtomicBoolean(false);

    private volatile ThriftRegistration registration;

    @Override
    public Boolean register() {
        ApplicationConfig applicationConfig = ApplicationConfig.getInstance();
        ProviderConfig providerConfig = ProviderConfig.getInstance();
        if (Boolean.FALSE.equals(providerConfig.getEnabled())) {
            log.info("autumn provider disabled, skip registry. application:{}", applicationConfig.getName());
            return Boolean.TRUE;
        }
        if (isConsul(applicationConfig) && Boolean.FALSE.equals(applicationConfig.getConsulRegister())) {
            log.info("consul register disabled, skip. application:{}", applicationConfig.getName());
            return Boolean.TRUE;
        }

        ThriftRegistration target = ThriftRegistration.of(applicationConfig, providerConfig);
        try {
            RegistryBackends.shared().registry().register(target);
        } catch (Exception e) {
            log.error("autumn registry failed, application:{}, instance:{}, error:{}",
                    applicationConfig.getName(), target.getInstanceId(), e.getMessage(), e);
            return Boolean.FALSE;
        }
        this.registration = target;
        registered.set(true);
        return Boolean.TRUE;
    }

    @Override
    public void shutdownHook() {
        if (!registered.compareAndSet(true, false)) {
            return;
        }
        ThriftRegistration target = this.registration;
        try {
            if (Objects.nonNull(target)) {
                RegistryBackends.shared().registry().deregister(target.getInstanceId());
            }
        } catch (Exception e) {
            log.warn("autumn deregister failed, instance:{}, error:{}",
                    Objects.isNull(target) ? null : target.getInstanceId(), e.getMessage());
        } finally {
            RegistryBackends.reset();
            log.info("autumn registry shutdown.");
        }
    }

    private static boolean isConsul(ApplicationConfig applicationConfig) {
        return Objects.nonNull(applicationConfig.getRegistryType())
                && "consul".equalsIgnoreCase(applicationConfig.getRegistryType().trim());
    }
}
