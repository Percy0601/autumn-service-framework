package xyz.wewin.autumn.api.util;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.StringJoiner;

import lombok.extern.slf4j.Slf4j;

/**
 * 服务注册 IP / 组播出口网卡的统一选择器。
 *
 * <p>旧实现只按网卡 displayName 里是否含 Intel/Realtek 等厂商字样挑物理网卡，
 * Linux 上网卡名是 {@code ens33}/{@code eth0}，vendor 关键字永远匹配不到，
 * 最终退化成「枚举到的第一张有 IPv4 的网卡」。装了 Docker 的机器上
 * {@code docker0}(172.17.0.1) 常常排在前面，于是一个消费方根本连不上的网桥地址被注册进注册中心。</p>
 *
 * <p>现在的判定顺序：</p>
 * <ol>
 *   <li>显式指定：{@code application.host-ip} / {@code AUTUMN_HOST_IP} 直接作为注册 IP；
 *       {@code application.network-interface} 可以写网卡名、displayName 或本机 IPv4；</li>
 *   <li>按内核路由表探测出口 IP：UDP connect 到远端（默认 223.5.5.5:53，不发任何数据包），
 *       内核选出的源地址会被优先采用；</li>
 *   <li>候选打分：DNS/容器/隧道等虚拟网卡直接排除，物理网卡名、RFC1918 私网、
 *       {@code application.preferred-networks} 命中的 CIDR 加分，
 *       {@code application.ignored-interfaces} 追加排除；</li>
 *   <li>只剩虚拟网卡时降级使用（保证仍能注册），最后兜底 {@code InetAddress.getLocalHost()}。</li>
 * </ol>
 *
 * <p>注册 IP 与组播出口网卡共用这一套判定，避免「注册的 IP 在 A 网卡、组播包从 B 网卡出去」。</p>
 */
@Slf4j
public final class NetworkAddressResolver {

    /** 明确的容器 / 隧道 / 虚拟网卡前缀，注册 IP 绝不优先取这些网卡 */
    private static final List<String> VIRTUAL_PREFIXES = List.of(
            "docker", "br-", "bridge", "veth", "virbr", "cni", "flannel", "cali", "tunl", "vxlan",
            "lxcbr", "lxdbr", "podman", "cni-podman", "kube-", "nodelocaldns", "hassio",
            "tailscale", "zerotier", "utun", "awdl", "llw", "vmnet", "vboxnet", "anpi", "feth",
            "dummy", "ifb", "sit", "gretap", "gre", "ip6tnl", "ppp", "tun", "tap",
            "wg", "zt", "ham", "ipvlan", "macvlan", "vnet", "vif");

    /** 物理网卡 / bond 的常见命名前缀（Linux、macOS、Windows 的 displayName 由 vendor 关键字兜底） */
    private static final List<String> PHYSICAL_PREFIXES = List.of(
            "eth", "en", "em", "bond", "team", "wlan", "wlp", "wwan", "ib");

    /** 纯网桥 br0/br1 可能是宿主机真实上联网卡，降权但不硬排除 */
    private static final String SOFT_BRIDGE_PREFIX = "br";

    private static final String DEFAULT_PROBE_TARGET = "223.5.5.5:53";
    private static final int DEFAULT_PROBE_PORT = 53;

    private static final int SCORE_PROBE = 200;
    private static final int SCORE_PREFERRED_NETWORK = 100;
    private static final int SCORE_PHYSICAL = 50;
    private static final int SCORE_SITE_LOCAL = 20;
    private static final int SCORE_VENDOR = 10;
    private static final int SCORE_SOFT_BRIDGE = -40;
    private static final int SCORE_DOCKER_SEGMENT = -20;

    private static volatile Config config = Config.empty();
    private static volatile Resolution autoResolution;

    private NetworkAddressResolver() {
    }

    /** 网卡选择配置；{@code List} 字段均为不可变副本 */
    public record Config(String hostIp, String interfaceName, List<String> preferredNetworks,
                         List<String> ignoredInterfaces, String probeTarget) {

        public Config {
            preferredNetworks = Objects.isNull(preferredNetworks) ? List.of() : List.copyOf(preferredNetworks);
            ignoredInterfaces = Objects.isNull(ignoredInterfaces) ? List.of() : List.copyOf(ignoredInterfaces);
            probeTarget = Objects.isNull(probeTarget) || probeTarget.isBlank() ? DEFAULT_PROBE_TARGET : probeTarget.trim();
        }

        static Config empty() {
            return new Config(null, null, List.of(), List.of(), DEFAULT_PROBE_TARGET);
        }
    }

    /** 网卡快照，{@code raw} 在纯逻辑单测里为 null */
    public record Interface(String name, String displayName, boolean loopback, boolean virtual,
                            boolean up, boolean pointToPoint, List<String> ipv4, NetworkInterface raw) {

        public Interface {
            ipv4 = Objects.isNull(ipv4) ? List.of() : List.copyOf(ipv4);
            displayName = Objects.isNull(displayName) ? name : displayName;
        }
    }

    /** 选择结果；{@code ip} 为 null 表示本机确实没有可用 IPv4 */
    public record Resolution(String ip, String interfaceName, String reason, Interface source) {
    }

    private record Scored(int score, int order, String ip, Interface source, String tags) {
    }

    /**
     * 从 autumn 配置装载选择参数。系统属性 / 环境变量作为 classpath 配置的补充覆盖，
     * 便于容器、虚拟机部署时不改配置文件就能指定注册 IP。
     */
    public static void configure(Properties properties) {
        Properties props = Objects.isNull(properties) ? new Properties() : properties;
        String hostIp = pick(props.getProperty("application.host-ip"),
                System.getProperty("autumn.host-ip"), System.getenv("AUTUMN_HOST_IP"));
        String interfaceName = pick(props.getProperty("application.network-interface"),
                System.getProperty("autumn.network-interface"), System.getenv("AUTUMN_NETWORK_INTERFACE"));
        String preferred = pick(props.getProperty("application.preferred-networks"),
                System.getProperty("autumn.preferred-networks"), System.getenv("AUTUMN_PREFERRED_NETWORKS"));
        String ignored = pick(props.getProperty("application.ignored-interfaces"),
                System.getProperty("autumn.ignored-interfaces"), System.getenv("AUTUMN_IGNORED_INTERFACES"));
        String probeTarget = pick(props.getProperty("application.network-probe-target"),
                System.getProperty("autumn.network-probe-target"), System.getenv("AUTUMN_NETWORK_PROBE_TARGET"));
        if (Objects.isNull(probeTarget)) {
            // 注册中心在远端时，用它的地址做路由探测最贴合真实调用链路；本机地址一律跳过，避免走 lo
            String registryHost = pick(props.getProperty("consul.host"), registryHost(props.getProperty("nacos.server-addr")));
            if (Objects.nonNull(registryHost) && !isLocalTarget(registryHost)) {
                probeTarget = registryHost + ":" + DEFAULT_PROBE_PORT;
            }
        }
        Config next = new Config(hostIp, interfaceName, split(preferred), split(ignored), probeTarget);
        config = next;
        autoResolution = null;
        log.debug("autumn network selector configured:{}", next);
    }

    /** 测试 / 运行时显式指定配置 */
    public static void configure(Config custom) {
        config = Objects.isNull(custom) ? Config.empty() : custom;
        autoResolution = null;
    }

    public static Config currentConfig() {
        return config;
    }

    /** 清空自动探测缓存（网卡热插拔、测试用例之间调用） */
    public static void clearCache() {
        autoResolution = null;
    }

    /** 注册到服务中心的主机 IP，进程内只解析一次 */
    public static String hostIpAddress() {
        String explicit = trimToNull(config.hostIp());
        if (Objects.nonNull(explicit)) {
            return explicit;
        }
        Resolution resolution = autoResolution();
        return Objects.isNull(resolution) ? null : resolution.ip();
    }

    /**
     * 组播出口网卡名：显式指定优先，否则用传入的注册 IP 反查；
     * 命中回环网卡（本地用 127.0.0.1 注册时）改回自动选出的物理网卡，避免组播绑到 lo。
     */
    public static String networkInterfaceName(String preferred) {
        return networkInterfaceName(interfaces(), preferred, config);
    }

    /** 纯逻辑重载，供单元测试验证「注册 IP 与组播出口网卡一致」 */
    static String networkInterfaceName(List<Interface> interfaces, String preferred, Config selection) {
        Interface matched = matchExplicit(interfaces, preferred);
        if (Objects.nonNull(matched) && !matched.loopback()) {
            return matched.name();
        }
        // 显式注册 IP 可能是 NAT/EIP（未绑本机网卡），或本地测试用的 127.0.0.1，
        // 此时组播出口仍要落在一张真实网卡上：临时去掉 interfaceName/hostIp 约束，只按网卡打分。
        Config autoConfig = new Config(null, null, selection.preferredNetworks(),
                selection.ignoredInterfaces(), selection.probeTarget());
        Resolution auto = select(interfaces, autoConfig,
                probeOutboundAddress(interfaces, autoConfig), null);
        if (Objects.nonNull(auto.source())) {
            return auto.source().name();
        }
        return Objects.isNull(matched) ? null : matched.name();
    }

    /** 返回持有 preferred（网卡名 / displayName / IPv4）的网卡对象 */
    public static NetworkInterface networkInterface(String preferred) {
        Interface matched = matchExplicit(interfaces(), preferred);
        if (Objects.nonNull(matched)) {
            return matched.raw();
        }
        Resolution auto = autoResolution();
        return Objects.isNull(auto.source()) ? null : auto.source().raw();
    }

    /** 诊断用：一次列出本机所有带 IPv4 的网卡及开关状态 */
    public static String describe() {
        return describe(interfaces());
    }

    private static String describe(List<Interface> interfaces) {
        StringJoiner joiner = new StringJoiner(", ");
        int count = 0;
        for (Interface item : interfaces) {
            if (item.ipv4().isEmpty() || count++ >= 12) {
                continue;
            }
            joiner.add(item.name() + "[" + String.join("/", item.ipv4()) + "]"
                    + (item.up() ? "" : "(down)")
                    + (item.virtual() ? "(virtual)" : "")
                    + (item.loopback() ? "(loopback)" : ""));
        }
        return joiner.toString();
    }

    private static Resolution autoResolution() {
        return autoResolution(interfaces());
    }

    private static Resolution autoResolution(List<Interface> interfaces) {
        Resolution cached = autoResolution;
        if (Objects.nonNull(cached)) {
            return cached;
        }
        synchronized (NetworkAddressResolver.class) {
            cached = autoResolution;
            if (Objects.isNull(cached)) {
                String probeIp = probeOutboundAddress(interfaces, config);
                cached = select(interfaces, config, probeIp, null);
                if (Objects.isNull(cached.ip())) {
                    cached = fallbackLocalHost();
                }
                autoResolution = cached;
                log.info("autumn host address selected: ip={}, interface={}, reason={}, probe={}; interfaces: {}",
                        cached.ip(), cached.interfaceName(), cached.reason(), probeIp, describe(interfaces));
                if (Objects.isNull(cached.source())) {
                    log.warn("autumn host address has no matching interface, check network config "
                            + "or set application.host-ip / provider.ip explicitly");
                }
            }
            return cached;
        }
    }

    /**
     * 纯逻辑选择，供单元测试注入模拟网卡。分数高者优先，同分保持枚举顺序以保证结果稳定。
     */
    static Resolution select(List<Interface> interfaces, Config selection, String probeIp, String preferred) {
        List<Interface> all = Objects.isNull(interfaces) ? List.of() : interfaces;
        Config cfg = Objects.isNull(selection) ? Config.empty() : selection;
        String explicit = trimToNull(Objects.nonNull(preferred) ? preferred : cfg.interfaceName());
        if (Objects.nonNull(explicit)) {
            Resolution byConfig = selectExplicit(all, explicit);
            if (Objects.nonNull(byConfig)) {
                return byConfig;
            }
            log.warn("autumn network interface '{}' not found on this host, fallback to auto detection", explicit);
        }

        List<Scored> scored = collect(all, cfg, probeIp, false, false);
        String source = "auto";
        if (scored.isEmpty()) {
            // 机器上只有 docker0/veth 这类网卡时仍要给出一个可注册地址，不让注册流程直接失败
            scored = collect(all, cfg, probeIp, true, false);
            source = "virtual-only fallback";
        }
        if (scored.isEmpty()) {
            scored = collect(all, cfg, probeIp, true, true);
            source = "loopback fallback";
        }
        if (scored.isEmpty()) {
            return new Resolution(null, null, "no usable ipv4 address", null);
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::order));
        Scored best = scored.getFirst();
        return new Resolution(best.ip(), best.source().name(),
                source + "(score=" + best.score() + ":" + best.tags() + ")", best.source());
    }

    private static Resolution selectExplicit(List<Interface> interfaces, String explicit) {
        if (isIpv4Literal(explicit)) {
            for (Interface item : interfaces) {
                if (item.ipv4().contains(explicit)) {
                    return new Resolution(explicit, item.name(), "explicit ipv4", item);
                }
            }
            // 可能是 NAT/EIP：未绑在本机网卡上也尊重用户配置
            return new Resolution(explicit, null, "explicit ipv4 (not bound locally)", null);
        }
        for (Interface item : interfaces) {
            if (explicit.equalsIgnoreCase(item.name()) || explicit.equalsIgnoreCase(item.displayName())) {
                String ip = firstUsable(item.ipv4());
                if (Objects.nonNull(ip)) {
                    return new Resolution(ip, item.name(), "explicit interface " + item.name(), item);
                }
            }
        }
        return null;
    }

    private static Interface matchExplicit(List<Interface> interfaces, String preferred) {
        String explicit = trimToNull(preferred);
        if (Objects.isNull(explicit) || interfaces.isEmpty()) {
            return null;
        }
        for (Interface item : interfaces) {
            if (explicit.equalsIgnoreCase(item.name()) || explicit.equalsIgnoreCase(item.displayName())
                    || item.ipv4().contains(explicit)) {
                return item;
            }
        }
        return null;
    }

    private static List<Scored> collect(List<Interface> interfaces, Config cfg, String probeIp,
                                        boolean allowVirtual, boolean allowLoopback) {
        List<Scored> scored = new ArrayList<>();
        int order = 0;
        for (Interface item : interfaces) {
            int index = order++;
            if (!allowLoopback && (item.loopback() || !item.up() || item.pointToPoint())) {
                continue;
            }
            if (!allowVirtual && (item.virtual() || isVirtualName(item.name())
                    || isIgnored(item.name(), cfg.ignoredInterfaces()))) {
                continue;
            }
            for (String ip : item.ipv4()) {
                if (!usable(ip) && !(allowLoopback && ip.startsWith("127."))) {
                    continue;
                }
                int score = 0;
                StringJoiner tags = new StringJoiner("+");
                if (Objects.nonNull(probeIp) && probeIp.equals(ip)) {
                    score += SCORE_PROBE;
                    tags.add("route-probe");
                }
                if (matchesAny(ip, cfg.preferredNetworks())) {
                    score += SCORE_PREFERRED_NETWORK;
                    tags.add("preferred-network");
                }
                if (isPhysicalName(item.name())) {
                    score += SCORE_PHYSICAL;
                    tags.add("physical");
                }
                if (isSiteLocal(ip)) {
                    score += SCORE_SITE_LOCAL;
                    tags.add("site-local");
                }
                if (isVendorName(item.displayName())) {
                    score += SCORE_VENDOR;
                    tags.add("vendor");
                }
                if (item.name().toLowerCase(Locale.ROOT).startsWith(SOFT_BRIDGE_PREFIX)) {
                    score += SCORE_SOFT_BRIDGE;
                    tags.add("bridge");
                }
                if (isDockerSegment(ip)) {
                    score += SCORE_DOCKER_SEGMENT;
                    tags.add("docker-segment");
                }
                scored.add(new Scored(score, index, ip, item, tags.toString()));
            }
        }
        return scored;
    }

    /**
     * UDP connect 只让内核查路由表选源地址，不发送任何数据包；目标不可达时返回 null，
     * 由打分逻辑兜底，因此离线机器也能正常选出网卡。
     */
    private static String probeOutboundAddress(List<Interface> interfaces, Config cfg) {
        String target = trimToNull(cfg.probeTarget());
        if (Objects.isNull(target) || "off".equalsIgnoreCase(target) || "false".equalsIgnoreCase(target)
                || "none".equalsIgnoreCase(target)) {
            return null;
        }
        String[] parts = parseTarget(target);
        if (Objects.isNull(parts)) {
            parts = parseTarget(DEFAULT_PROBE_TARGET);
        }
        if (Objects.isNull(parts)) {
            return null;
        }
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        if (isLocalAddress(host, interfaces)) {
            String[] fallback = parseTarget(DEFAULT_PROBE_TARGET);
            if (Objects.isNull(fallback)) {
                return null;
            }
            host = fallback[0];
            port = Integer.parseInt(fallback[1]);
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress remote = InetAddress.getByName(host);
            if (remote.isLoopbackAddress() || remote.isAnyLocalAddress()) {
                return null;
            }
            socket.connect(remote, port);
            InetAddress local = socket.getLocalAddress();
            if (local instanceof Inet4Address && !local.isLoopbackAddress() && !local.isAnyLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception e) {
            log.debug("autumn outbound probe skipped, target={}:{}, error:{}", host, port, e.getMessage());
        }
        return null;
    }

    private static String[] parseTarget(String target) {
        String value = target.trim();
        String host = value;
        int port = DEFAULT_PROBE_PORT;
        int colon = value.lastIndexOf(':');
        if (colon > 0) {
            host = value.substring(0, colon);
            try {
                port = Integer.parseInt(value.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return host.isBlank() || port <= 0 || port > 65535 ? null
                : new String[]{host, String.valueOf(port)};
    }

    private static boolean isLocalAddress(String host, List<Interface> interfaces) {
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
            return true;
        }
        for (Interface item : interfaces) {
            if (item.ipv4().contains(host)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalTarget(String host) {
        if (Objects.isNull(host) || host.isBlank() || "localhost".equalsIgnoreCase(host)) {
            return true;
        }
        return host.startsWith("127.") || "0.0.0.0".equals(host);
    }

    private static Resolution fallbackLocalHost() {
        try {
            InetAddress local = InetAddress.getLocalHost();
            if (local instanceof Inet4Address) {
                return new Resolution(local.getHostAddress(), null,
                        "fallback InetAddress.getLocalHost()", null);
            }
        } catch (Exception e) {
            log.debug("autumn getLocalHost fallback failed: {}", e.getMessage());
        }
        return new Resolution(null, null, "no usable ipv4 address", null);
    }

    private static List<Interface> interfaces() {
        List<Interface> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            if (Objects.isNull(enumeration)) {
                return result;
            }
            while (enumeration.hasMoreElements()) {
                NetworkInterface netInterface = enumeration.nextElement();
                List<String> ipv4 = new ArrayList<>();
                Enumeration<InetAddress> addresses = netInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address) {
                        ipv4.add(address.getHostAddress());
                    }
                }
                result.add(new Interface(netInterface.getName(), netInterface.getDisplayName(),
                        netInterface.isLoopback(), netInterface.isVirtual(), netInterface.isUp(),
                        netInterface.isPointToPoint(), ipv4, netInterface));
            }
        } catch (SocketException e) {
            log.warn("autumn enumerate network interfaces failed: {}", e.getMessage());
        }
        return result;
    }

    private static boolean isVirtualName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : VIRTUAL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPhysicalName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : PHYSICAL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIgnored(String name, List<String> ignored) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : ignored) {
            String value = prefix.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && lower.startsWith(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVendorName(String displayName) {
        if (Objects.isNull(displayName)) {
            return false;
        }
        return displayName.contains("Intel") || displayName.contains("Realtek")
                || displayName.contains("Atheros") || displayName.contains("Broadcom");
    }

    private static boolean usable(String ip) {
        long value = ipv4ToLong(ip);
        if (value < 0) {
            return false;
        }
        int first = (int) (value >>> 24);
        int second = (int) ((value >>> 16) & 0xff);
        return first != 0 && first != 127 && first < 224 && !(first == 169 && second == 254);
    }

    private static boolean isSiteLocal(String ip) {
        long value = ipv4ToLong(ip);
        if (value < 0) {
            return false;
        }
        int first = (int) (value >>> 24);
        int second = (int) ((value >>> 16) & 0xff);
        return first == 10 || first == 192 && second == 168 || first == 172 && second >= 16 && second <= 31;
    }

    /** docker0 默认网段，只降权不硬排除（真实内网也可能使用该段） */
    private static boolean isDockerSegment(String ip) {
        long value = ipv4ToLong(ip);
        if (value < 0) {
            return false;
        }
        return (int) (value >>> 24) == 172 && (int) ((value >>> 16) & 0xff) == 17;
    }

    private static boolean matchesAny(String ip, List<String> cidrs) {
        for (String cidr : cidrs) {
            if (matchesCidr(ip, cidr)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesCidr(String ip, String cidr) {
        String value = cidr.trim();
        int slash = value.indexOf('/');
        String network = slash < 0 ? value : value.substring(0, slash);
        int prefix = 32;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(value.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (prefix < 0 || prefix > 32) {
                return false;
            }
        }
        long address = ipv4ToLong(ip);
        long base = ipv4ToLong(network);
        if (address < 0 || base < 0) {
            return false;
        }
        long mask = prefix == 0 ? 0L : (0xffff_ffffL << (32 - prefix)) & 0xffff_ffffL;
        return (address & mask) == (base & mask);
    }

    private static long ipv4ToLong(String ip) {
        if (Objects.isNull(ip)) {
            return -1L;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return -1L;
        }
        long value = 0L;
        for (String part : parts) {
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) {
                    return -1L;
                }
                value = (value << 8) | octet;
            } catch (NumberFormatException e) {
                return -1L;
            }
        }
        return value;
    }

    private static boolean isIpv4Literal(String value) {
        return ipv4ToLong(value) >= 0;
    }

    private static String firstUsable(List<String> addresses) {
        for (String address : addresses) {
            if (usable(address)) {
                return address;
            }
        }
        return null;
    }

    private static String registryHost(String serverAddr) {
        if (Objects.isNull(serverAddr) || serverAddr.isBlank()) {
            return null;
        }
        String first = serverAddr.split(",")[0].trim();
        int colon = first.lastIndexOf(':');
        return colon > 0 ? first.substring(0, colon) : first;
    }

    private static List<String> split(String value) {
        if (Objects.isNull(value) || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private static String pick(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (Objects.nonNull(trimmed)) {
                return trimmed;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (Objects.isNull(value) || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.trim();
    }
}
