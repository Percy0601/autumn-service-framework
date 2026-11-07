package xyz.wewin.autumn.api.extension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import xyz.wewin.autumn.api.config.ApplicationConfig;
import xyz.wewin.autumn.api.config.ConsumerConfig;

/**
 * 泳道路由：把隐式参数（thrift field-0 attachment）翻译成注册中心实例筛选条件。
 *
 * <p>语义与 {@code autumn-cloud-extension} 的 {@code Traffic} / {@code TrafficSelector} 对齐：
 * <ul>
 *   <li>实例标签取元数据 {@code traffic}，未打标（null / 空白）视作基线泳道 {@code main}；</li>
 *   <li>优先精确命中同泳道实例，同泳道无实例时回退 {@code main}，基线也不存在才回退全量，
 *       避免泳道配置错误导致无可用实例（{@code application.traffic-fallback=false} 可关闭回退）；</li>
 *   <li>基线请求只命中基线实例，不会误入灰度泳道；灰度请求也不会被基线实例稀释。</li>
 * </ul>
 *
 * <p>调用方通过 {@code AttachmentContext.put("traffic", "gray")} 指定泳道，参数名可由
 * {@code application.traffic-parameter} 配置（默认兼容 {@code traffic} 与 HTTP 侧的 {@code X-Traffic}）。
 * 隐式参数随调用链透传，服务端继续向下游发起 RPC 时会自动沿用同一泳道，形成全链路泳道。</p>
 *
 * <p>除泳道外还支持按任意实例标签筛选：{@code AttachmentContext.put("label.region", "cn")}
 * 要求实例元数据 {@code region=cn}；多个标签是 AND 关系，全部不满足时回退到泳道候选集。</p>
 */
public final class TrafficRouter {

    /** 实例元数据中的泳道 key，对齐 autumn-cloud-extension 的 {@code Traffic.META_KEY} */
    public static final String META_KEY = "traffic";
    /** 基线泳道，对齐 {@code Traffic.DEFAULT} */
    public static final String DEFAULT_LANE = "main";
    /** 隐式参数默认 key */
    public static final String DEFAULT_PARAMETER = "traffic";
    /** 与 HTTP 侧泳道头同名，便于网关把 {@code X-Traffic} 透传成隐式参数 */
    public static final String HEADER_PARAMETER = "X-Traffic";
    /** 任意标签选择器的隐式参数前缀 */
    public static final String LABEL_PREFIX = "label.";

    private TrafficRouter() {
    }

    /** 空 / 空白泳道归一化为基线泳道。 */
    public static String normalize(String value) {
        return Objects.isNull(value) || value.isBlank() ? DEFAULT_LANE : value.trim();
    }

    /**
     * 泳道筛选，语义与 {@code TrafficSelector.select} 一致：严格同泳道优先，其次基线，最后全量。
     */
    public static <T> List<T> selectByLane(List<T> instances, String lane,
                                            Function<T, String> laneOf, boolean fallbackToMain) {
        if (Objects.isNull(instances) || instances.isEmpty()) {
            return List.of();
        }
        String target = normalize(lane);
        List<T> matched = filter(instances, target, laneOf);
        if (!matched.isEmpty()) {
            return matched;
        }
        if (DEFAULT_LANE.equals(target)) {
            return fallbackToMain ? List.copyOf(instances) : List.of();
        }
        if (!fallbackToMain) {
            return List.of();
        }
        List<T> main = filter(instances, DEFAULT_LANE, laneOf);
        return main.isEmpty() ? List.copyOf(instances) : main;
    }

    private static <T> List<T> filter(List<T> instances, String lane, Function<T, String> laneOf) {
        return instances.stream()
                .filter(instance -> normalize(laneOf.apply(instance)).equals(lane))
                .toList();
    }

    /** 解析当前线程隐式参数（即本次调用的路由条件）。 */
    public static Route current() {
        return of(AttachmentContext.snapshot());
    }

    /** 解析给定隐式参数；未指定泳道时回退到应用级 {@code application.traffic}，最后回退 {@code main}。 */
    public static Route of(Map<String, String> parameters) {
        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (Objects.nonNull(parameters)) {
            parameters.forEach((key, value) -> {
                if (Objects.nonNull(key) && Objects.nonNull(value)) {
                    values.put(key, value);
                }
            });
        }
        ApplicationConfig config = ApplicationConfig.getInstance();
        String lane = null;
        for (String key : parameterKeys(config)) {
            String value = values.get(key);
            if (Objects.nonNull(value) && !value.isBlank()) {
                lane = value.trim();
                break;
            }
        }
        if (Objects.isNull(lane)) {
            lane = config.getTraffic();
        }

        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key.length() > LABEL_PREFIX.length()
                    && key.regionMatches(true, 0, LABEL_PREFIX, 0, LABEL_PREFIX.length())) {
                String name = key.substring(LABEL_PREFIX.length()).trim();
                String value = entry.getValue().trim();
                if (!name.isEmpty() && !value.isEmpty()) {
                    labels.put(name, value);
                }
            }
        }
        return new Route(normalize(lane), Map.copyOf(labels), !Boolean.FALSE.equals(config.getTrafficFallback()));
    }

    private static List<String> parameterKeys(ApplicationConfig config) {
        String configured = config.getTrafficParameter();
        String raw = Objects.isNull(configured) || configured.isBlank()
                ? DEFAULT_PARAMETER + "," + HEADER_PARAMETER : configured;
        List<String> keys = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                keys.add(part.trim());
            }
        }
        return keys.isEmpty() ? List.of(DEFAULT_PARAMETER) : keys;
    }

    /** 候选实例的 {@code ip:port} 集合，供连接池判断某条连接是否属于当前泳道。 */
    public static Set<String> endpointKeys(List<ConsumerConfig> instances) {
        if (Objects.isNull(instances) || instances.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (ConsumerConfig instance : instances) {
            if (Objects.nonNull(instance) && Objects.nonNull(instance.getIp())
                    && Objects.nonNull(instance.getPort())) {
                keys.add(endpointKey(instance.getIp(), instance.getPort()));
            }
        }
        return keys;
    }

    public static String endpointKey(String ip, Integer port) {
        return ip + ":" + port;
    }

    /** 一次调用的路由条件（不可变）。 */
    public static final class Route {

        private final String lane;
        private final Map<String, String> labels;
        private final boolean fallbackToMain;

        private Route(String lane, Map<String, String> labels, boolean fallbackToMain) {
            this.lane = lane;
            this.labels = labels;
            this.fallbackToMain = fallbackToMain;
        }

        public String lane() {
            return lane;
        }

        public Map<String, String> labels() {
            return labels;
        }

        public boolean fallbackToMain() {
            return fallbackToMain;
        }

        /** 是否显式指定了非基线泳道或标签选择器（供日志 / 诊断使用）。 */
        public boolean explicit() {
            return !DEFAULT_LANE.equals(lane) || !labels.isEmpty();
        }

        /** 在注册中心实例上应用泳道 + 标签筛选，返回本次调用可用的实例。 */
        public List<ConsumerConfig> select(List<ConsumerConfig> instances) {
            List<ConsumerConfig> byLane = selectByLane(instances, lane, ConsumerConfig::getLabel, fallbackToMain);
            if (labels.isEmpty() || byLane.isEmpty()) {
                return byLane;
            }
            List<ConsumerConfig> matched = byLane.stream().filter(this::matchesLabels).toList();
            // 标签条件全部落空时回退到泳道候选集，避免因误配标签导致整条链路无实例
            return matched.isEmpty() ? byLane : matched;
        }

        private boolean matchesLabels(ConsumerConfig instance) {
            Map<String, String> metadata = instance.getLabels();
            if (Objects.isNull(metadata) || metadata.isEmpty()) {
                return false;
            }
            for (Map.Entry<String, String> selector : labels.entrySet()) {
                String actual = lookup(metadata, selector.getKey());
                if (Objects.isNull(actual) || !actual.equalsIgnoreCase(selector.getValue())) {
                    return false;
                }
            }
            return true;
        }

        private static String lookup(Map<String, String> metadata, String key) {
            String direct = metadata.get(key);
            if (Objects.nonNull(direct)) {
                return direct;
            }
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return "Route{lane=" + lane + ", labels=" + labels + ", fallbackToMain=" + fallbackToMain + '}';
        }
    }
}
