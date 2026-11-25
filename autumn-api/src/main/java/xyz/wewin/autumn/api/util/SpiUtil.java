package xyz.wewin.autumn.api.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import xyz.wewin.autumn.api.Discovery;
import xyz.wewin.autumn.api.Registry;

import lombok.extern.slf4j.Slf4j;


/**
 * SPI util
 *
 *
 * @author baoxin.zhao
 * @date 2024/10/28
 */
@Slf4j
public class SpiUtil {
    private static final Object EMPTY_MARKER = new Object();
    private static volatile Map<Class<?>, Object> mapping = new ConcurrentHashMap<>();
    private static volatile Map<Class<?>, List<?>> mappings = new ConcurrentHashMap<>();

    private SpiUtil() {

    }

    public static <S> S load(Class<S> service) {
        if(mapping.containsKey(service)) {
            Object value = mapping.get(service);
            return EMPTY_MARKER == value ? null : service.cast(value);
        }
        ServiceLoader<S> load = ServiceLoader.load(service);
        Iterator<S> iterator = load.iterator();
        if(!iterator.hasNext()) {
            mapping.put(service, EMPTY_MARKER);
            log.warn("autumn spi implementation not found, service:{}", service.getName());
            return null;
        }
        S s = iterator.next();
        mapping.put(service, s);
        return s;
    }

    public static <S> List<S> loads(Class<S> service) {
        if(mappings.containsKey(service)) {
            return narrow(service, mappings.get(service));
        }
        ServiceLoader<S> load = ServiceLoader.load(service);
        Iterator<S> iterator = load.iterator();
        if(!iterator.hasNext()) {
            mappings.put(service, Collections.emptyList());
            return Collections.emptyList();
        }
        List<S> result = new ArrayList<>();
        while(iterator.hasNext()) {
            result.add(iterator.next());
        }
        mappings.put(service, result);
        return result;
    }

    /**
     * 缓存里存的是以 {@code service} 为 key 构造出来的 List，取回时无法用泛型表达关联关系，
     * 只能在这里收敛回 {@code List<S>}；类型安全由写入侧保证（只写 ServiceLoader&lt;S&gt; 的结果）。
     */
    @SuppressWarnings("unchecked")
    private static <S> List<S> narrow(Class<S> service, Object cached) {
        return (List<S>) cached;
    }
}
