package com.microapp.autumn.boot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMultiplexedProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TTransport;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ClassUtils;

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
 * 1、@Export: export spring bean as thrift service into AutumnBootstrap (AOP proxy aware)
 * 2、@Reference: inject compile-time generated proxy (fallback to runtime JDK proxy)
 * </pre>
 *
 * @author: baoxin.zhao
 * @date: 2026/11/3
 */
@Slf4j
public class AutumnBeanPostProcessor implements BeanPostProcessor {

    /** thrift generated interface is nested: binary name is like com.x.SomeService$Iface */
    private static final String THRIFT_IFACE_SUFFIX = "$Iface";

    private static final Map<Class<?>, Constructor<?>> CLIENT_CONSTRUCTORS = new ConcurrentHashMap<>();

    private final AutumnProperties properties;

    public AutumnBeanPostProcessor(AutumnProperties properties) {
        this.properties = properties;
        initConfigs(properties);
    }

    /**
     * push spring environment config into autumn singleton config, must run before provider/consumer start.
     * null/blank values are skipped so setProperty never writes the literal string "null".
     */
    private void initConfigs(AutumnProperties properties) {
        if (Objects.isNull(properties)) {
            return;
        }
        Properties props = new Properties();
        put(props, "application.name", properties.getName());
        put(props, "application.registry-type", properties.getRegistryType());
        put(props, "application.multicast.ip", properties.getMulticastIp());
        put(props, "application.multicast.port", properties.getMulticastPort());
        put(props, "application.multicast.network-interface", properties.getMulticastNetworkInterface());
        put(props, "application.traffic", properties.getTraffic());
        put(props, "registry.discovery-interval", properties.getDiscoveryInterval());
        put(props, "consumer.load-balance", properties.getLoadBalance());

        AutumnProperties.Provider provider = properties.getProvider();
        if (Objects.nonNull(provider)) {
            put(props, "provider.enabled", provider.getEnabled());
            put(props, "provider.port", provider.getPort());
            put(props, "provider.min-threads", provider.getMinThreads());
            put(props, "provider.max-threads", provider.getMaxThreads());
            put(props, "provider.worker-keep-alive-time", provider.getWorkerKeepAliveTime());
            put(props, "provider.thread-queue-size", provider.getThreadQueueSize());
            put(props, "provider.timeout", provider.getTimeout());
            put(props, "provider.ip", provider.getIp());
            put(props, "provider.control-port", provider.getControlPort());
            put(props, "provider.control-host", provider.getControlHost());
            put(props, "provider.control-token", provider.getControlToken());
        }
        AutumnProperties.Consul consul = properties.getConsul();
        if (Objects.nonNull(consul)) {
            put(props, "consul.register", consul.getRegister());
            put(props, "consul.discovery", consul.getDiscovery());
            put(props, "consul.health-check-interval", consul.getHealthCheckInterval());
            put(props, "consul.host", consul.getHost());
            put(props, "consul.port", consul.getPort());
            put(props, "consul.scheme", consul.getScheme());
            put(props, "consul.token", consul.getToken());
            put(props, "consul.instance-id", consul.getInstanceId());
            put(props, "consul.namespace", consul.getNamespace());
        }
        AutumnProperties.Nacos nacos = properties.getNacos();
        if (Objects.nonNull(nacos)) {
            put(props, "nacos.server-addr", nacos.getServerAddr());
            put(props, "nacos.namespace", nacos.getNamespace());
            put(props, "nacos.group", nacos.getGroup());
            put(props, "nacos.access-token", nacos.getAccessToken());
            put(props, "nacos.beat-interval", nacos.getBeatInterval());
        }
        // control plane token is also exposed to ApplicationConfig for health/diagnostics
        if (Objects.nonNull(provider)) {
            put(props, "control.token", provider.getControlToken());
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
        log.info("autumn config initialized, application:{}, registry:{}, providerPort:{}",
                props.getProperty("application.name"), props.getProperty("application.registry-type"),
                props.getProperty("provider.port"));
    }

    private static void put(Properties props, String key, Object value) {
        if (Objects.isNull(value)) {
            return;
        }
        String text = String.valueOf(value);
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return;
        }
        props.setProperty(key, text);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        exportService(bean);
        injectReference(bean);
        return bean;
    }

    /**
     * export spring bean annotated with @Export as thrift service.
     * <p>
     * uses AopUtils.getTargetClass so CGLIB / JDK proxies keep their annotations and thrift Iface;
     * generated assemblers are checked with the target class (isAssignableFrom) and only one match is exported.
     */
    private void exportService(Object bean) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        List<AutumnServiceAssembler> assemblers = SpiUtil.loads(AutumnServiceAssembler.class);
        if (Objects.nonNull(assemblers)) {
            for (AutumnServiceAssembler assembler : assemblers) {
                try {
                    if (assembler.supports(targetClass)) {
                        AutumnBootstrap.getInstance().service(assembler.build(bean));
                        log.info("autumn export service by generated assembler, bean:{}, assembler:{}",
                                targetClass.getName(), assembler.getClass().getName());
                        return;
                    }
                } catch (Exception e) {
                    log.warn("autumn export service fail by generated assembler, bean:{}, assembler:{}",
                            targetClass.getName(), assembler.getClass().getName(), e);
                }
            }
        }
        boolean annotated = targetClass.isAnnotationPresent(Export.class);
        List<Class<?>> ifaces = thriftIfaces(targetClass);
        if (!annotated && ifaces.isEmpty()) {
            return;
        }
        if (ifaces.isEmpty()) {
            log.warn("autumn @Export bean has no thrift Iface interface, skip. bean:{}", targetClass.getName());
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
                log.info("autumn export service, iface:{}, bean:{}", iface.getName(), targetClass.getName());
            } catch (Exception e) {
                log.warn("autumn export service fail, iface:{}, bean:{}", iface.getName(), targetClass.getName(), e);
            }
        }
    }

    private static List<Class<?>> thriftIfaces(Class<?> targetClass) {
        List<Class<?>> ifaces = new ArrayList<>();
        for (Class<?> itf : ClassUtils.getAllInterfacesForClassAsSet(targetClass)) {
            if (itf.getName().endsWith(THRIFT_IFACE_SUFFIX)) {
                ifaces.add(itf);
            }
        }
        return ifaces;
    }

    /**
     * inject @Reference into declared fields of the whole class hierarchy and into setter methods.
     */
    private void injectReference(Object bean) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Object target = resolveTarget(bean);
        injectFields(bean, target, targetClass);
        injectMethods(bean, targetClass);
    }

    /** field injection must target the underlying object when the bean is an AOP proxy */
    private static Object resolveTarget(Object bean) {
        if (!AopUtils.isAopProxy(bean)) {
            return bean;
        }
        Object singletonTarget = AopProxyUtils.getSingletonTarget(bean);
        return Objects.isNull(singletonTarget) ? bean : singletonTarget;
    }

    private void injectFields(Object bean, Object target, Class<?> targetClass) {
        for (Class<?> type = targetClass; Objects.nonNull(type) && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                Reference reference = field.getAnnotation(Reference.class);
                if (Objects.isNull(reference)) {
                    continue;
                }
                try {
                    Class<?> owner = field.getDeclaringClass();
                    if (!owner.isInstance(target)) {
                        log.warn("autumn @Reference field owner mismatch, skip. bean:{}, field:{}",
                                targetClass.getName(), field.getName());
                        continue;
                    }
                    field.setAccessible(true);
                    if (Objects.nonNull(field.get(target))) {
                        continue;
                    }
                    Class<?> fieldType = field.getType();
                    if (!fieldType.isInterface()) {
                        log.warn("autumn @Reference field type must be interface, skip. bean:{}, field:{}",
                                targetClass.getName(), field.getName());
                        continue;
                    }
                    String refer = resolveRefer(reference.refer(), fieldType);
                    AutumnConsumer.provider().reference(buildReferenceConfig(refer, reference.ipPort()));
                    field.set(target, createProxy(refer, fieldType));
                    log.info("autumn inject reference, bean:{}, field:{}, refer:{}",
                            targetClass.getName(), field.getName(), refer);
                } catch (Exception e) {
                    log.warn("autumn inject reference fail, bean:{}, field:{}", targetClass.getName(), field.getName(), e);
                }
            }
        }
    }

    private void injectMethods(Object bean, Class<?> targetClass) {
        Set<String> handled = new HashSet<>();
        for (Class<?> type = targetClass; Objects.nonNull(type) && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                Reference reference = method.getAnnotation(Reference.class);
                if (Objects.isNull(reference) || !handled.add(method.getName() + "#" + method.getParameterCount())) {
                    continue;
                }
                try {
                    if (method.getParameterCount() != 1 || !method.getParameterTypes()[0].isInterface()) {
                        log.warn("autumn @Reference method needs one interface parameter, skip. bean:{}, method:{}",
                                targetClass.getName(), method.getName());
                        continue;
                    }
                    Class<?> iface = method.getParameterTypes()[0];
                    String refer = resolveRefer(reference.refer(), iface);
                    AutumnConsumer.provider().reference(buildReferenceConfig(refer, reference.ipPort()));
                    method.setAccessible(true);
                    method.invoke(bean, createProxy(refer, iface));
                    log.info("autumn inject reference method, bean:{}, method:{}, refer:{}",
                            targetClass.getName(), method.getName(), refer);
                } catch (Exception e) {
                    log.warn("autumn inject reference method fail, bean:{}, method:{}",
                            targetClass.getName(), method.getName(), e);
                }
            }
        }
    }

    private static String resolveRefer(String refer, Class<?> iface) {
        return Objects.isNull(refer) || refer.isEmpty() ? iface.getName() : refer;
    }

    private ReferenceConfig<?> buildReferenceConfig(String refer, String ipPort) {
        ReferenceConfig<?> config = new ReferenceConfig<>();
        config.setName(refer);
        if (Objects.nonNull(ipPort) && !ipPort.isEmpty()) {
            String[] parts = ipPort.split(":");
            if (parts.length == 2) {
                ConsumerConfig instance = new ConsumerConfig();
                instance.setName(refer);
                instance.setIp(parts[0]);
                instance.setPort(Integer.valueOf(parts[1]));
                config.setInstances(List.of(instance));
                config.setRegistryTypeEnum(RegistryTypeEnum.DIRECT);
            } else {
                log.warn("autumn @Reference ipPort must be ip:port, ignore. value:{}", ipPort);
            }
        }
        if (Objects.isNull(config.getRegistryTypeEnum())) {
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
     * prefer compile-time generated _XxxProxy (zero reflection), fallback to runtime JDK proxy.
     */
    private Object createProxy(String service, Class<?> iface) {
        Object generated = createGeneratedProxy(service, iface);
        if (Objects.nonNull(generated)) {
            return generated;
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> iface.getSimpleName() + "$autumnProxy@"
                            + Integer.toHexString(System.identityHashCode(proxy));
                    // Object 的 final 方法（wait/notify/getClass）反射到 proxy 本身，不再打到 BPP 实例
                    default -> method.invoke(proxy, args);
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

    private Object createGeneratedProxy(String service, Class<?> iface) {
        String ifaceName = iface.getName();
        if (!ifaceName.endsWith(THRIFT_IFACE_SUFFIX)) {
            return null;
        }
        String outer = ifaceName.substring(0, ifaceName.length() - THRIFT_IFACE_SUFFIX.length());
        int dot = outer.lastIndexOf('.');
        String generatedName = outer.substring(0, dot + 1) + "_" + outer.substring(dot + 1) + "Proxy";
        try {
            Class<?> proxyClass = Class.forName(generatedName, true, iface.getClassLoader());
            if (!iface.isAssignableFrom(proxyClass)) {
                return null;
            }
            Object proxy = proxyClass.getConstructor(String.class).newInstance(service);
            log.info("autumn use generated proxy:{}, service:{}", generatedName, service);
            return proxy;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            log.warn("autumn generated proxy unavailable, fallback to runtime proxy. class:{}", generatedName, e);
            return null;
        }
    }

    /** thrift Client 构造器按 Iface 缓存，避免每次调用都 Class.forName + 反射查找 */
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
