package com.microapp.autumn.boot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMultiplexedProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TTransport;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.aop.support.AopUtils;

import com.microapp.autumn.api.annotation.Export;
import com.microapp.autumn.api.annotation.Reference;
import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ConsumerConfig;
import com.microapp.autumn.api.config.ProviderConfig;
import com.microapp.autumn.api.config.ReferenceConfig;
import com.microapp.autumn.api.config.ServiceConfig;
import com.microapp.autumn.api.enums.RegistryTypeEnum;
import com.microapp.autumn.api.extension.AttachableProcessor;
import com.microapp.autumn.api.extension.AutumnServiceAssembler;
import com.microapp.autumn.api.util.AutumnException;
import com.microapp.autumn.api.util.SpiUtil;
import com.microapp.autumn.boot.config.AutumnProperties;
import com.microapp.autumn.core.AutumnBootstrap;
import com.microapp.autumn.core.pool.AutumnPool;
import com.microapp.autumn.core.pool.impl.ConcurrentBagEntry;
import com.microapp.autumn.core.server.AutumnConsumer;

import lombok.extern.slf4j.Slf4j;

/**
 * spring bean post processor, handle @Export and @Reference annotation.
 *
 * <pre>
 * 1、@Export: export spring bean as thrift service into AutumnBootstrap
 * 2、@Reference: inject runtime proxy into field, proxy borrows connection from AutumnPool
 * </pre>
 *
 * @author: baoxin.zhao
 * @date: 2026/11/3
 */
@Slf4j
public class AutumnBeanPostProcessor implements BeanPostProcessor {

    private final AutumnProperties properties;

    public AutumnBeanPostProcessor(AutumnProperties properties) {
        this.properties = properties;
        initConfigs(properties);
    }

    /**
     * push spring environment config into autumn singleton config, must run before provider/consumer start
     */
    private void initConfigs(AutumnProperties properties) {
        if (Objects.isNull(properties)) {
            return;
        }
        Properties props = new Properties();
        if (Objects.nonNull(properties.getName())) {
            props.setProperty("application.name", properties.getName());
        }
        props.setProperty("application.registry-type", properties.getRegistryType());
        props.setProperty("application.multicast.ip", properties.getMulticastIp());
        props.setProperty("application.multicast.port", String.valueOf(properties.getMulticastPort()));
        if (Objects.nonNull(properties.getDiscoveryInterval())) {
            props.setProperty("registry.discovery-interval", String.valueOf(properties.getDiscoveryInterval()));
        }
        if (Objects.nonNull(properties.getTraffic()) && !properties.getTraffic().isBlank()) {
            props.setProperty("application.traffic", properties.getTraffic());
        }
        AutumnProperties.Provider provider = properties.getProvider();
        if (Objects.nonNull(provider)) {
            props.setProperty("provider.enabled", String.valueOf(provider.getEnabled()));
            props.setProperty("provider.port", String.valueOf(provider.getPort()));
            props.setProperty("provider.min-threads", String.valueOf(provider.getMinThreads()));
            props.setProperty("provider.max-threads", String.valueOf(provider.getMaxThreads()));
            props.setProperty("provider.worker-keep-alive-time", String.valueOf(provider.getWorkerKeepAliveTime()));
            props.setProperty("provider.thread-queue-size", String.valueOf(provider.getThreadQueueSize()));
            props.setProperty("provider.timeout", String.valueOf(provider.getTimeout()));
            if (Objects.nonNull(provider.getIp())) {
                props.setProperty("provider.ip", provider.getIp());
            }
        }
        AutumnProperties.Consul consul = properties.getConsul();
        if (Objects.nonNull(consul)) {
            props.setProperty("consul.register", String.valueOf(consul.getRegister()));
            props.setProperty("consul.discovery", String.valueOf(consul.getDiscovery()));
            props.setProperty("consul.health-check-interval", String.valueOf(consul.getHealthCheckInterval()));
            props.setProperty("consul.host", String.valueOf(consul.getHost()));
            props.setProperty("consul.port", String.valueOf(consul.getPort()));
            if (Objects.nonNull(consul.getScheme())) {
                props.setProperty("consul.scheme", consul.getScheme());
            }
            if (Objects.nonNull(consul.getToken())) {
                props.setProperty("consul.token", consul.getToken());
            }
            if (Objects.nonNull(consul.getInstanceId())) {
                props.setProperty("consul.instance-id", consul.getInstanceId());
            }
            if (Objects.nonNull(consul.getNamespace())) {
                props.setProperty("consul.namespace", consul.getNamespace());
            }
        }
        AutumnProperties.Nacos nacos = properties.getNacos();
        if (Objects.nonNull(nacos)) {
            props.setProperty("nacos.server-addr", nacos.getServerAddr());
            props.setProperty("nacos.namespace", nacos.getNamespace());
            props.setProperty("nacos.group", nacos.getGroup());
            if (Objects.nonNull(nacos.getAccessToken())) {
                props.setProperty("nacos.access-token", nacos.getAccessToken());
            }
            props.setProperty("nacos.beat-interval", String.valueOf(nacos.getBeatInterval()));
        }
        AutumnProperties.Control control = properties.getControl();
        if (Objects.nonNull(control)) {
            if (Objects.nonNull(control.getPort())) {
                props.setProperty("control.port", String.valueOf(control.getPort()));
            }
            if (Objects.nonNull(control.getToken())) {
                props.setProperty("control.token", control.getToken());
            }
        }
        ApplicationConfig.getInstance().init(props);
        ProviderConfig.getInstance().init(props);
        log.info("autumn config initialized: {}", props);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        exportService(bean);
        injectReference(bean);
        return bean;
    }

    /**
     * thrift generated interface is nested: binary name is like com.microapp.autumn.sample.api.SomeService$Iface
     */
    private static final String THRIFT_IFACE_SUFFIX = "$Iface";

    /**
     * export spring bean annotated with @Export as thrift service.
     * <p>
     * priority: compile-time generated AutumnServiceAssembler (zero reflection, native image friendly),
     * fallback to runtime reflection for beans without generated assembler.
     */
    private void exportService(Object bean) {
        // 穿透 CGLIB/AOP 代理拿到真实实现类；否则代理类上既无 @Export 也匹配不到编译期生成的 Assembler，@Export 会失效
        Class<?> beanClass = org.springframework.aop.support.AopUtils.getTargetClass(bean);
        List<AutumnServiceAssembler> assemblers = SpiUtil.loads(AutumnServiceAssembler.class);
        boolean exported = false;
        if (assemblers != null && !assemblers.isEmpty()) {
            for (AutumnServiceAssembler assembler : assemblers) {
                try {
                    if (assembler.supports(beanClass)) {
                        AutumnBootstrap.getInstance().service(assembler.build(bean));
                        exported = true;
                        log.info("autumn export service by generated assembler, bean:{}, assembler:{}", beanClass.getName(), assembler.getClass().getName());
                    }
                } catch (Exception e) {
                    log.warn("autumn export service fail by generated assembler, bean:{}, assembler:{}", beanClass.getName(), assembler.getClass().getName(), e);
                }
            }
        }
        if (exported) {
            return;
        }
        boolean annotated = beanClass.isAnnotationPresent(Export.class);
        List<Class<?>> ifaces = new ArrayList<>();
        for (Class<?> itf : beanClass.getInterfaces()) {
            if (itf.getName().endsWith(THRIFT_IFACE_SUFFIX)) {
                ifaces.add(itf);
            }
        }
        if (!annotated && ifaces.isEmpty()) {
            return;
        }
        if (annotated && ifaces.isEmpty()) {
            log.warn("autumn @Export bean has no thrift Iface interface, skip. bean:{}", beanClass.getName());
            return;
        }
        for (Class<?> iface : ifaces) {
            try {
                String outer = iface.getName().substring(0, iface.getName().length() - THRIFT_IFACE_SUFFIX.length());
                Class<?> processorClass = Class.forName(outer + "$Processor");
                Object processor = processorClass.getConstructor(iface).newInstance(bean);
                ServiceConfig<Object> config = new ServiceConfig<>();
                config.setInterfaceClass(iface);
                config.setRef(new AttachableProcessor((TProcessor) processor));
                AutumnBootstrap.getInstance().service(config);
                log.info("autumn export service, iface:{}, bean:{}", iface.getName(), beanClass.getName());
            } catch (Exception e) {
                log.warn("autumn export service fail, iface:{}, bean:{}", iface.getName(), beanClass.getName(), e);
            }
        }
    }

    /**
     * inject @Reference into fields and setter methods across the bean's class hierarchy
     */
    private void injectReference(Object bean) {
        Class<?> beanClass = bean.getClass();
        // 字段注入：遍历自身及所有父类字段
        for (Class<?> c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                Reference reference = field.getAnnotation(Reference.class);
                if (Objects.isNull(reference)) {
                    continue;
                }
                injectField(bean, field, reference);
            }
        }
        // 方法（setter）注入：遍历自身及所有父类方法
        for (Class<?> c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                Reference reference = method.getAnnotation(Reference.class);
                if (Objects.isNull(reference)) {
                    continue;
                }
                injectMethod(bean, method, reference);
            }
        }
    }

    private void injectField(Object bean, Field field, Reference reference) {
        try {
            field.setAccessible(true);
            if (Objects.nonNull(field.get(bean))) {
                return;
            }
            Class<?> fieldType = field.getType();
            if (!fieldType.isInterface()) {
                log.warn("autumn @Reference field type must be interface, skip. bean:{}, field:{}", bean.getClass().getName(), field.getName());
                return;
            }
            field.set(bean, buildProxy(reference, fieldType));
            log.info("autumn inject reference(field), bean:{}, field:{}, refer:{}", bean.getClass().getName(), field.getName(), reference.refer());
        } catch (Exception e) {
            log.warn("autumn inject reference(field) fail, bean:{}, field:{}", bean.getClass().getName(), field.getName(), e);
        }
    }

    private void injectMethod(Object bean, Method method, Reference reference) {
        try {
            if (method.getParameterCount() != 1) {
                log.warn("autumn @Reference method must have exactly one parameter, skip. bean:{}, method:{}", bean.getClass().getName(), method.getName());
                return;
            }
            Class<?> paramType = method.getParameterTypes()[0];
            if (!paramType.isInterface()) {
                log.warn("autumn @Reference method param type must be interface, skip. bean:{}, method:{}", bean.getClass().getName(), method.getName());
                return;
            }
            method.setAccessible(true);
            method.invoke(bean, buildProxy(reference, paramType));
            log.info("autumn inject reference(method), bean:{}, method:{}, refer:{}", bean.getClass().getName(), method.getName(), reference.refer());
        } catch (Exception e) {
            log.warn("autumn inject reference(method) fail, bean:{}, method:{}", bean.getClass().getName(), method.getName(), e);
        }
    }

    /**
     * build @Reference proxy: prefer compile-time generated _&lt;Outer&gt;Proxy (zero reflection, native friendly),
     * fall back to runtime JDK dynamic proxy. Either way the reference config is registered first to trigger discovery.
     */
    private Object buildProxy(Reference reference, Class<?> iface) {
        String refer = reference.refer();
        if (Objects.isNull(refer) || refer.isEmpty()) {
            refer = iface.getName();
        }
        AutumnConsumer.provider().reference(buildReferenceConfig(reference, refer));
        Object generated = tryGeneratedProxy(refer, iface);
        if (Objects.nonNull(generated)) {
            return generated;
        }
        return createProxy(refer, iface);
    }

    private Object tryGeneratedProxy(String refer, Class<?> iface) {
        Class<?> outer = iface.getDeclaringClass();
        if (Objects.isNull(outer)) {
            return null;
        }
        String generatedName = iface.getPackageName() + "._" + outer.getSimpleName() + "Proxy";
        try {
            Class<?> proxyClass = Class.forName(generatedName, true, iface.getClassLoader());
            Constructor<?> ctor = proxyClass.getConstructor(String.class);
            return ctor.newInstance(refer);
        } catch (ClassNotFoundException e) {
            // 该接口未生成编译期代理，回退运行时动态代理
            return null;
        } catch (Exception e) {
            log.warn("autumn load generated proxy fail, iface:{}, fallback runtime proxy", iface.getName(), e);
            return null;
        }
    }

    private ReferenceConfig<?> buildReferenceConfig(Reference reference, String refer) {
        ReferenceConfig<?> config = new ReferenceConfig<>();
        config.setName(refer);
        String ipPort = reference.ipPort();
        if (Objects.nonNull(ipPort) && ipPort.length() > 0) {
            String[] parts = ipPort.split(":");
            if (parts.length == 2) {
                ConsumerConfig instance = new ConsumerConfig();
                instance.setName(refer);
                instance.setIp(parts[0]);
                instance.setPort(Integer.valueOf(parts[1]));
                config.setInstances(List.of(instance));
                config.setRegistryTypeEnum(RegistryTypeEnum.DIRECT);
            }
        } else {
            String registryType = ApplicationConfig.getInstance().getRegistryType();
            if ("consul".equalsIgnoreCase(registryType)) {
                config.setRegistryTypeEnum(RegistryTypeEnum.CONSUL);
            } else if ("nacos".equalsIgnoreCase(registryType)) {
                config.setRegistryTypeEnum(RegistryTypeEnum.NACOS);
            } else {
                config.setRegistryTypeEnum(RegistryTypeEnum.MULTICAST);
            }
        }
        config.setPoolTimeout(10000L);
        config.setConnectionTimeout(1000L);
        config.setSocketTimeout(3000L);
        return config;
    }

    /**
     * create runtime proxy for thrift Iface interface, each invoke borrows connection from AutumnPool
     */
    private Object createProxy(String service, Class<?> iface) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> iface.getSimpleName() + "$autumnProxy@" + Integer.toHexString(System.identityHashCode(proxy));
                    case "getClass" -> proxy.getClass();
                    default -> throw new UnsupportedOperationException("autumn proxy does not support " + method);
                };
            }
            ConcurrentBagEntry entry = null;
            boolean discard = false;
            try {
                entry = AutumnPool.getInstance().borrow(service);
                if (Objects.isNull(entry)) {
                    throw new AutumnException("autumn reference no available instance, service: " + service);
                }
                TTransport transport = entry.getEntry();
                TProtocol protocol = new TBinaryProtocol(transport);
                TMultiplexedProtocol multiplexedProtocol = new TMultiplexedProtocol(protocol, iface.getName());
                Object client = clientConstructor(iface).newInstance(multiplexedProtocol);
                try {
                    return method.invoke(client, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (TException e) {
                // 传输/协议异常后连接状态不可信，不能再放回池里
                discard = true;
                throw e;
            } finally {
                if (Objects.nonNull(entry)) {
                    if (discard) {
                        AutumnPool.getInstance().discard(entry);
                    } else {
                        AutumnPool.getInstance().requite(entry);
                    }
                }
            }
        };
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    /**
     * thrift Client 构造器按 Iface 缓存，避免每次调用都 Class.forName + 反射查找
     */
    private static final Map<Class<?>, Constructor<?>> CLIENT_CONSTRUCTORS = new ConcurrentHashMap<>();

    private Constructor<?> clientConstructor(Class<?> iface) {
        return CLIENT_CONSTRUCTORS.computeIfAbsent(iface, type -> {
            String outer = type.getName().substring(0, type.getName().length() - THRIFT_IFACE_SUFFIX.length());
            try {
                return Class.forName(outer + "$Client").getConstructor(TProtocol.class);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                throw new AutumnException("autumn thrift client class not found, iface:" + type.getName(), e);
            }
        });
    }
}
