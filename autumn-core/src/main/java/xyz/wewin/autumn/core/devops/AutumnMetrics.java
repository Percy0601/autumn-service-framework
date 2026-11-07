package xyz.wewin.autumn.core.devops;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 轻量调用指标（无第三方依赖，native-image 友好）：按服务记录借用/成功归还/丢弃次数。
 * 通过控制面 providerInfo() 暴露摘要，供运维与回归验证读取。
 */
public final class AutumnMetrics {

    private static final Map<String, Counters> SERVICES = new ConcurrentHashMap<>();

    private AutumnMetrics() {
    }

    private static final class Counters {
        private final LongAdder borrowed = new LongAdder();
        private final LongAdder succeeded = new LongAdder();
        private final LongAdder discarded = new LongAdder();
    }

    public static void borrowed(String service) {
        counters(service).borrowed.increment();
    }

    public static void requited(String service) {
        counters(service).succeeded.increment();
    }

    public static void discarded(String service) {
        counters(service).discarded.increment();
    }

    private static Counters counters(String service) {
        return SERVICES.computeIfAbsent(service == null ? "-" : service, key -> new Counters());
    }

    public static String summary() {
        if (SERVICES.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        SERVICES.forEach((service, c) -> builder.append(service)
                .append("={borrowed=").append(c.borrowed.sum())
                .append(", succeeded=").append(c.succeeded.sum())
                .append(", discarded=").append(c.discarded.sum())
                .append("}, "));
        if (builder.length() > 2) {
            builder.setLength(builder.length() - 2);
        }
        return builder.append('}').toString();
    }
}
