package com.microapp.autumn.api.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 隐式传参（attachment）上下文。
 *
 * <p>调用方 put 的键值随 thrift 请求的 field-0 透传到服务端，服务端业务代码通过
 * {@link #snapshot()} / {@link #get(String)} 读取。服务端由 {@link AttachableProcessor}
 * 在请求结束时清理，避免线程池复用造成上下文串扰。未接线隐式传参的旧链路读到的是空 Map。</p>
 */
public final class AttachmentContext {

    private static final ThreadLocal<Map<String, String>> CURRENT = new ThreadLocal<>();

    private AttachmentContext() {
    }

    public static void put(String key, String value) {
        if (key == null || key.isEmpty() || value == null) {
            return;
        }
        Map<String, String> values = CURRENT.get();
        if (values == null) {
            values = new LinkedHashMap<>();
            CURRENT.set(values);
        }
        values.put(key, value);
    }

    public static void putAll(Map<String, String> values) {
        if (values != null) {
            values.forEach(AttachmentContext::put);
        }
    }

    /** 服务端解码 field-0 后整体替换当前线程的隐式参数。 */
    public static void replace(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            clear();
            return;
        }
        CURRENT.set(new LinkedHashMap<>(values));
    }

    public static Map<String, String> snapshot() {
        Map<String, String> values = CURRENT.get();
        if (values == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static String get(String key) {
        return snapshot().get(key);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
