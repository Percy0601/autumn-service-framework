package com.microapp.autumn.api.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import com.microapp.autumn.api.Discovery;
import com.microapp.autumn.api.Registry;

import lombok.extern.slf4j.Slf4j;


/**
 * SPI util
 *
 *
 * @author: baoxin.zhao
 * @date: 2024/10/28
 */
@Slf4j
public class SpiUtil {
    private static final Object EMPTY_MARKER = new Object();
    private static volatile Map<Class, Object> mapping = new ConcurrentHashMap<>();
    private static volatile Map<Class, List<Object>> mappings = new ConcurrentHashMap<>();

    private SpiUtil() {

    }

    public static <S> S load(Class<S> service) {
        if(mapping.containsKey(service)) {
            Object value = mapping.get(service);
            return EMPTY_MARKER == value ? null : (S)value;
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
            return (List<S>)mappings.get(service);
        }
        ServiceLoader<S> load = ServiceLoader.load(service);
        Iterator<S> iterator = load.iterator();
        if(!iterator.hasNext()) {
            mappings.put(service, Collections.emptyList());
            return Collections.emptyList();
        }
        List<Object> result = new ArrayList<>();
        while(iterator.hasNext()) {
            S s = iterator.next();
            result.add(s);
        }
        mappings.put(service, result);
        return (List<S>) result;
    }
}
