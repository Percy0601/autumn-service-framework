package com.microapp.autumn.compiler.util;

import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import org.slf4j.Logger;

/**
 * JSR-269 阶段的 GraalVM native-image 元数据生成器。
 *
 * <p>注解处理器本来就知道"自己生成了哪些类"、"哪些字段/方法会被反射注入"，所以
 * {@code reflect-config.json} / {@code proxy-config.json} 不需要手写、也不需要等
 * native-image-agent 跑一遍再回填，直接在编译期按 GraalVM 约定的目录写到
 * {@code CLASS_OUTPUT}：</p>
 *
 * <pre>
 * META-INF/native-image/com.micro-app/&lt;artifact&gt;/reflect-config.json
 * META-INF/native-image/com.micro-app/&lt;artifact&gt;/proxy-config.json
 * </pre>
 *
 * <p>native-image 构建时会自动扫描 classpath 上所有 {@code META-INF/native-image/<source>/<artifact>/*.json}
 * 并合并，因此这些文件随模块 jar 一起打包即可，宿主工程无需任何 {@code -H:*} 参数。
 * 每个处理器使用独立 artifact 目录，避免同一模块内多个处理器争抢同一个文件。</p>
 *
 * @author: baoxin.zhao
 * @date: 2026/11/6
 */
public final class NativeImageMetadataWriter {

    private static final String NATIVE_IMAGE_ROOT = "META-INF/native-image/com.micro-app/";
    private static final String REFLECT_CONFIG = "reflect-config.json";
    private static final String PROXY_CONFIG = "proxy-config.json";

    /** 类型名 -> 该类型需要注册的反射成员 */
    private final Map<String, TypeHints> types = new LinkedHashMap<>();
    /** JDK 动态代理接口组 */
    private final List<List<String>> proxies = new ArrayList<>();

    /**
     * 注册构造器：{@code Class.forName(...).getConstructor(...).newInstance(...)} 或 ServiceLoader 实例化都需要。
     */
    public void constructors(String className) {
        if (isBlank(className)) {
            return;
        }
        type(className).declaredConstructors = true;
    }

    /**
     * 注册字段写入：框架用 {@code field.setAccessible(true) + field.set(bean, proxy)} 做 @Reference 注入。
     */
    public void writableField(String ownerClass, String fieldName) {
        if (isBlank(ownerClass) || isBlank(fieldName)) {
            return;
        }
        type(ownerClass).fields.put(fieldName, Boolean.TRUE);
    }

    /**
     * 注册方法：@Reference 标注在 setter 上时由 {@code method.invoke} 调用。
     */
    public void method(String ownerClass, String methodName, List<String> parameterTypes) {
        if (isBlank(ownerClass) || isBlank(methodName)) {
            return;
        }
        List<String> parameters = parameterTypes == null ? List.of() : new ArrayList<>(parameterTypes);
        type(ownerClass).methods.put(methodName + "(" + String.join(",", parameters) + ")",
                new MethodHint(methodName, parameters));
    }

    /**
     * 注册 JDK 动态代理接口，对应 {@code Proxy.newProxyInstance(...)}。
     */
    public void proxy(String... interfaceNames) {
        List<String> group = new ArrayList<>();
        for (String interfaceName : interfaceNames) {
            if (!isBlank(interfaceName)) {
                group.add(interfaceName);
            }
        }
        if (!group.isEmpty() && !proxies.contains(group)) {
            proxies.add(group);
        }
    }

    public boolean isEmpty() {
        return types.isEmpty() && proxies.isEmpty();
    }

    /**
     * 全量重写元数据文件。多轮编译下每轮都覆盖为当前累积内容；同名文件由上一轮创建时复用并覆盖。
     *
     * @param filer    processor filer
     * @param artifact 本处理器独占的目录名，例如 {@code autumn-export-hints}
     * @param log      处理器日志
     */
    public void write(Filer filer, String artifact, Logger log) {
        if (!types.isEmpty()) {
            writeResource(filer, NATIVE_IMAGE_ROOT + artifact + "/" + REFLECT_CONFIG, reflectConfig(), log);
        }
        if (!proxies.isEmpty()) {
            writeResource(filer, NATIVE_IMAGE_ROOT + artifact + "/" + PROXY_CONFIG, proxyConfig(), log);
        }
    }

    private TypeHints type(String className) {
        return types.computeIfAbsent(className, key -> new TypeHints());
    }

    private void writeResource(Filer filer, String path, String content, Logger log) {
        try {
            FileObject file;
            try {
                file = filer.createResource(StandardLocation.CLASS_OUTPUT, "", path);
            } catch (FilerException alreadyExists) {
                // 上一轮已创建，复用并全量覆盖
                file = filer.getResource(StandardLocation.CLASS_OUTPUT, "", path);
            }
            try (Writer writer = file.openWriter()) {
                writer.write(content);
            }
            log.info("autumn generate native-image metadata:{}, types:{}, proxies:{}",
                    path, types.size(), proxies.size());
        } catch (Exception e) {
            log.warn("autumn generate native-image metadata fail, path:{}", path, e);
        }
    }

    private String reflectConfig() {
        StringBuilder json = new StringBuilder("[\n");
        int index = 0;
        for (Map.Entry<String, TypeHints> entry : types.entrySet()) {
            if (index++ > 0) {
                json.append(",\n");
            }
            appendType(json, entry.getKey(), entry.getValue());
        }
        return json.append("\n]\n").toString();
    }

    private void appendType(StringBuilder json, String className, TypeHints hints) {
        json.append("  {\n    \"name\": \"").append(escape(className)).append("\"");
        if (hints.declaredConstructors) {
            json.append(",\n    \"allDeclaredConstructors\": true");
        }
        if (!hints.fields.isEmpty()) {
            json.append(",\n    \"fields\": [\n");
            int index = 0;
            for (Map.Entry<String, Boolean> field : hints.fields.entrySet()) {
                if (index++ > 0) {
                    json.append(",\n");
                }
                json.append("      {\"name\": \"").append(escape(field.getKey())).append("\"");
                if (Boolean.TRUE.equals(field.getValue())) {
                    json.append(", \"allowWrite\": true");
                }
                json.append("}");
            }
            json.append("\n    ]");
        }
        if (!hints.methods.isEmpty()) {
            json.append(",\n    \"methods\": [\n");
            int index = 0;
            for (MethodHint method : hints.methods.values()) {
                if (index++ > 0) {
                    json.append(",\n");
                }
                json.append("      {\"name\": \"").append(escape(method.name)).append("\", \"parameterTypes\": [");
                for (int i = 0; i < method.parameterTypes.size(); i++) {
                    if (i > 0) {
                        json.append(", ");
                    }
                    json.append("\"").append(escape(method.parameterTypes.get(i))).append("\"");
                }
                json.append("]}");
            }
            json.append("\n    ]");
        }
        json.append("\n  }");
    }

    private String proxyConfig() {
        StringBuilder json = new StringBuilder("[\n");
        int index = 0;
        for (List<String> group : proxies) {
            if (index++ > 0) {
                json.append(",\n");
            }
            json.append("  {\"interfaces\": [");
            for (int i = 0; i < group.size(); i++) {
                if (i > 0) {
                    json.append(", ");
                }
                json.append("\"").append(escape(group.get(i))).append("\"");
            }
            json.append("]}");
        }
        return json.append("\n]\n").toString();
    }

    private static String escape(String value) {
        StringBuilder text = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                text.append('\\');
            }
            text.append(c);
        }
        return text.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 单个类型的反射成员集合 */
    private static final class TypeHints {
        private boolean declaredConstructors;
        private final Map<String, Boolean> fields = new LinkedHashMap<>();
        private final Map<String, MethodHint> methods = new LinkedHashMap<>();
    }

    /** 方法签名：name + 参数类型 */
    private static final class MethodHint {
        private final String name;
        private final List<String> parameterTypes;

        private MethodHint(String name, List<String> parameterTypes) {
            this.name = name;
            this.parameterTypes = parameterTypes;
        }
    }
}
