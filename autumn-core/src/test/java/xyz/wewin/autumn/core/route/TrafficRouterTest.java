package xyz.wewin.autumn.core.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.wewin.autumn.api.config.ApplicationConfig;
import xyz.wewin.autumn.api.config.ConsumerConfig;
import xyz.wewin.autumn.api.extension.AttachmentContext;
import xyz.wewin.autumn.api.extension.TrafficRouter;

/**
 * 泳道路由语义测试：严格同泳道优先，其次基线 main，最后全量；并覆盖隐式参数 / 标签选择器解析。
 */
class TrafficRouterTest {

    @BeforeEach
    void setUp() {
        configure("", true, "traffic,X-Traffic");
    }

    @AfterEach
    void tearDown() {
        AttachmentContext.clear();
        configure("", true, "traffic,X-Traffic");
    }

    private static void configure(String traffic, boolean fallback, String parameterKeys) {
        Properties props = new Properties();
        props.setProperty("application.name", "traffic-test");
        props.setProperty("application.registry-type", "direct");
        props.setProperty("application.traffic-parameter", parameterKeys);
        props.setProperty("application.traffic-fallback", Boolean.toString(fallback));
        if (!traffic.isBlank()) {
            props.setProperty("application.traffic", traffic);
        }
        ApplicationConfig.getInstance().init(props);
    }

    private static ConsumerConfig instance(String ip, int port, String lane, String... labelPairs) {
        ConsumerConfig config = new ConsumerConfig();
        config.setName("svc");
        config.setIp(ip);
        config.setPort(port);
        config.setLabel(lane);
        Map<String, String> labels = new LinkedHashMap<>();
        if (lane != null) {
            labels.put(TrafficRouter.META_KEY, lane);
        }
        for (int i = 0; i + 1 < labelPairs.length; i += 2) {
            labels.put(labelPairs[i], labelPairs[i + 1]);
        }
        config.setLabels(labels);
        return config;
    }

    private static List<String> ips(List<ConsumerConfig> configs) {
        return configs.stream().map(ConsumerConfig::getIp).toList();
    }

    @Test
    void sameLaneWinsAndBaselineIsNotMixedIn() {
        List<ConsumerConfig> all = List.of(instance("10.0.0.1", 30880, null),
                instance("10.0.0.2", 30880, "gray"), instance("10.0.0.3", 30880, "blue"));

        assertEquals(List.of("10.0.0.2"), ips(TrafficRouter.of(Map.of("traffic", "gray")).select(all)));
        assertEquals(List.of("10.0.0.3"), ips(TrafficRouter.of(Map.of("traffic", "blue")).select(all)));
        assertEquals(List.of("10.0.0.1"), ips(TrafficRouter.of(Map.of()).select(all)),
                "基线流量只走未打标实例，不能误入灰度泳道");
    }

    @Test
    void missingLaneFallsBackToMainAndCanBeDisabled() {
        List<ConsumerConfig> all = List.of(instance("10.0.0.1", 30880, null),
                instance("10.0.0.3", 30880, "blue"));
        assertEquals(List.of("10.0.0.1"), ips(TrafficRouter.of(Map.of("traffic", "gray")).select(all)));

        configure("", false, "traffic,X-Traffic");
        assertTrue(TrafficRouter.of(Map.of("traffic", "gray")).select(all).isEmpty(),
                "关闭回退后，找不到同泳道实例应返回空集合");
    }

    @Test
    void fallsBackToAllWhenNeitherLaneNorBaselineExists() {
        List<ConsumerConfig> all = List.of(instance("10.0.0.3", 30880, "blue"));
        assertEquals(List.of("10.0.0.3"), ips(TrafficRouter.of(Map.of("traffic", "gray")).select(all)));
    }

    @Test
    void applicationTrafficIsTheDefaultLane() {
        configure("gray", true, "traffic,X-Traffic");
        List<ConsumerConfig> all = List.of(instance("10.0.0.1", 30880, null),
                instance("10.0.0.2", 30880, "gray"));
        assertEquals(List.of("10.0.0.2"), ips(TrafficRouter.of(Map.of()).select(all)));
        assertEquals("gray", TrafficRouter.of(Map.of()).lane());
        // 调用级隐式参数优先于应用级静态泳道
        assertEquals("blue", TrafficRouter.of(Map.of("traffic", "blue")).lane());
    }

    @Test
    void headerAliasAndCustomParameterKeys() {
        List<ConsumerConfig> all = List.of(instance("10.0.0.2", 30880, "gray"),
                instance("10.0.0.3", 30880, "blue"), instance("10.0.0.1", 30880, null));
        assertEquals(List.of("10.0.0.2"), ips(TrafficRouter.of(Map.of("X-Traffic", "gray")).select(all)));
        Map<String, String> lowerCaseHeader = new LinkedHashMap<>();
        lowerCaseHeader.put("x-traffic", "blue");
        assertEquals(List.of("10.0.0.3"), ips(TrafficRouter.of(lowerCaseHeader).select(all)));

        configure("", true, "lane");
        assertEquals(List.of("10.0.0.3"), ips(TrafficRouter.of(Map.of("lane", "blue")).select(all)));
        assertEquals("main", TrafficRouter.of(Map.of("traffic", "gray")).lane(),
                "自定义参数名生效后，默认参数名不再被识别");
    }

    @Test
    void labelSelectorsFilterWithinLaneAndDegradeGracefully() {
        List<ConsumerConfig> all = List.of(
                instance("10.0.0.1", 30880, "gray", "region", "cn"),
                instance("10.0.0.2", 30880, "gray", "region", "us"),
                instance("10.0.0.3", 30880, null, "region", "cn"));
        assertEquals(List.of("10.0.0.1"),
                ips(TrafficRouter.of(Map.of("traffic", "gray", "label.region", "cn")).select(all)));
        assertEquals(List.of("10.0.0.1", "10.0.0.2"),
                ips(TrafficRouter.of(Map.of("traffic", "gray", "label.region", "eu")).select(all)),
                "标签全部落空时回退到泳道候选集");
        assertEquals(Set.of("region"), TrafficRouter.of(Map.of("label.region", "cn")).labels().keySet());
    }

    @Test
    void currentRouteReadsThreadLocalAttachment() {
        AttachmentContext.put("traffic", "gray");
        assertEquals("gray", TrafficRouter.current().lane());
        assertEquals(Set.of("127.0.0.1:30880"),
                TrafficRouter.endpointKeys(List.of(instance("127.0.0.1", 30880, "gray"))));
    }
}
