package xyz.wewin.autumn.api.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 复现「Ubuntu 虚拟机装 Docker 后被注册成 172.17.0.1」的多网卡场景。
 * 全部用模拟网卡注入，不依赖测试机真实网卡，CI / native 构建机上结果稳定。
 */
class NetworkAddressResolverTest {

    /** 探测关闭，纯打分路径，断言可复现 */
    private static final NetworkAddressResolver.Config DEFAULT =
            new NetworkAddressResolver.Config(null, null, List.of(), List.of(), "off");

    private static NetworkAddressResolver.Interface nic(String name, String... ips) {
        return new NetworkAddressResolver.Interface(name, name, false, false, true, false, List.of(ips), null);
    }

    private static NetworkAddressResolver.Interface virtualNic(String name, String ip) {
        // Linux 上 docker0 这类 bridge 的 isVirtual() 其实是 false，靠网卡名识别；
        // veth* 则通常被 JDK 标为 virtual，两种情况都要覆盖
        return new NetworkAddressResolver.Interface(name, name, false, true, true, false, List.of(ip), null);
    }

    private static NetworkAddressResolver.Interface loopback() {
        return new NetworkAddressResolver.Interface("lo", "lo", true, false, true, false,
                List.of("127.0.0.1"), null);
    }

    @AfterEach
    void reset() {
        NetworkAddressResolver.configure(NetworkAddressResolver.Config.empty());
        NetworkAddressResolver.clearCache();
        System.clearProperty("autumn.host-ip");
    }

    @Test
    void dockerBridgeNeverWinsOverPhysicalNic() {
        List<NetworkAddressResolver.Interface> nics = List.of(
                loopback(),
                nic("docker0", "172.17.0.1"),
                nic("br-8f3a1c2d", "172.18.0.1"),
                virtualNic("veth9a1b2c3", "172.19.0.1"),
                nic("virbr0", "192.168.122.1"),
                nic("ens33", "192.168.0.102"));

        NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(nics, DEFAULT, null, null);

        assertEquals("192.168.0.102", result.ip(), "装了 Docker 后不能注册成 docker0 网桥地址");
        assertEquals("ens33", result.interfaceName());
    }

    @Test
    void commonContainerAndTunnelInterfacesAreExcluded() {
        List<String> virtualNames = List.of("docker0", "docker_gwbridge", "br-a1b2", "bridge100", "veth0001",
                "virbr0", "cni0", "flannel.1", "cali123", "tun0", "tap0", "tailscale0",
                "wg0", "utun3", "awdl0", "vmnet1", "vboxnet0", "podman0", "kube-bridge",
                "dummy0", "ppp0", "lxdbr0", "vxlan-calico");
        for (String name : virtualNames) {
            List<NetworkAddressResolver.Interface> nics = List.of(
                    nic(name, "10.9.9.9"), nic("eth0", "192.168.0.10"));
            NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(nics, DEFAULT, null, null);
            assertEquals("192.168.0.10", result.ip(), "虚拟网卡应被排除: " + name);
        }
    }

    @Test
    void outboundRouteProbeTakesPriority() {
        List<NetworkAddressResolver.Interface> nics = List.of(
                nic("eth0", "10.1.2.3"), nic("ens33", "192.168.0.102"));

        NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(nics, DEFAULT, "10.1.2.3", null);

        assertEquals("10.1.2.3", result.ip(), "内核选出的出口 IP 应优先于网卡名打分");
        assertTrue(result.reason().contains("route-probe"));
    }

    @Test
    void preferredCidrWins() {
        NetworkAddressResolver.Config config = new NetworkAddressResolver.Config(null, null,
                List.of("192.168.1.0/24"), List.of(), "off");
        List<NetworkAddressResolver.Interface> nics = List.of(
                nic("eth0", "10.1.2.3"), nic("eth1", "192.168.1.5"));

        NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(nics, config, null, null);

        assertEquals("192.168.1.5", result.ip());
        assertEquals("eth1", result.interfaceName());
    }

    @Test
    void ignoredInterfacesAreSkipped() {
        NetworkAddressResolver.Config config = new NetworkAddressResolver.Config(null, null,
                List.of(), List.of("eth0"), "off");
        List<NetworkAddressResolver.Interface> nics = List.of(
                nic("eth0", "192.168.0.10"), nic("eth1", "192.168.0.11"));

        NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(nics, config, null, null);

        assertEquals("192.168.0.11", result.ip());
    }

    @Test
    void explicitInterfaceIsRespectedEvenWhenVirtual() {
        NetworkAddressResolver.Config config = new NetworkAddressResolver.Config(null, "docker0",
                List.of(), List.of(), "off");
        List<NetworkAddressResolver.Interface> nics = List.of(
                nic("docker0", "172.17.0.1"), nic("ens33", "192.168.0.102"));

        NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(nics, config, null, null);

        assertEquals("172.17.0.1", result.ip(), "用户显式指定时必须尊重");
        assertEquals("docker0", result.interfaceName());
    }

    @Test
    void explicitIpIsRespectedAndResolvesItsInterface() {
        List<NetworkAddressResolver.Interface> nics = List.of(
                nic("docker0", "172.17.0.1"), nic("ens33", "192.168.0.102"));

        NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(nics, DEFAULT, null, "192.168.0.102");

        assertEquals("192.168.0.102", result.ip());
        assertEquals("ens33", result.interfaceName());
        assertEquals("ens33", NetworkAddressResolver.networkInterfaceName(nics, "192.168.0.102", DEFAULT));
    }

    @Test
    void unboundExplicitIpStillLeavesMulticastOnPhysicalNic() {
        List<NetworkAddressResolver.Interface> nics = List.of(
                nic("docker0", "172.17.0.1"), nic("ens33", "192.168.0.102"));

        NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(nics, DEFAULT, null, "203.0.113.7");

        assertEquals("203.0.113.7", result.ip(), "NAT/EIP 未绑定本机网卡也允许注册");
        assertNull(result.interfaceName());
        assertEquals("ens33", NetworkAddressResolver.networkInterfaceName(nics, "203.0.113.7", DEFAULT));
    }

    @Test
    void loopbackRegistrationDoesNotBindMulticastToLo() {
        List<NetworkAddressResolver.Interface> nics = List.of(
                loopback(), nic("docker0", "172.17.0.1"), nic("ens33", "192.168.0.102"));

        assertEquals("ens33", NetworkAddressResolver.networkInterfaceName(nics, "127.0.0.1", DEFAULT));
    }

    @Test
    void softBridgeLosesToPhysicalNic() {
        List<NetworkAddressResolver.Interface> nics = List.of(
                nic("br0", "192.168.1.1"), nic("eth0", "192.168.1.2"));

        NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(nics, DEFAULT, null, null);

        assertEquals("192.168.1.2", result.ip());
    }

    @Test
    void unusableAddressesAreSkipped() {
        List<NetworkAddressResolver.Interface> nics = List.of(
                loopback(),
                nic("eth0", "169.254.1.2"),
                nic("eth1", "0.0.0.0"),
                nic("enp0s3", "192.168.0.9"));

        NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(nics, DEFAULT, null, null);

        assertEquals("192.168.0.9", result.ip());
    }

    @Test
    void onlyVirtualInterfacesLeftStillReturnsAnAddress() {
        List<NetworkAddressResolver.Interface> nics = List.of(loopback(), nic("docker0", "172.17.0.1"));

        NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(nics, DEFAULT, null, null);

        assertEquals("172.17.0.1", result.ip(), "只剩网桥时降级使用，不能让注册直接失败");
        assertTrue(result.reason().contains("virtual-only fallback"));
    }

    @Test
    void noInterfaceAtAllFallsBackToLoopback() {
        NetworkAddressResolver.Resolution result = NetworkAddressResolver.select(List.of(loopback()), DEFAULT, null, null);

        assertEquals("127.0.0.1", result.ip());
        assertTrue(result.reason().contains("loopback fallback"));
    }

    @Test
    void cidrMatching() {
        assertTrue(NetworkAddressResolver.matchesCidr("192.168.0.102", "192.168.0.0/24"));
        assertTrue(NetworkAddressResolver.matchesCidr("10.1.2.3", "10.0.0.0/8"));
        assertTrue(NetworkAddressResolver.matchesCidr("172.16.5.6", "172.16.0.0/12"));
        assertTrue(NetworkAddressResolver.matchesCidr("192.168.0.102", "192.168.0.102"));
        assertFalse(NetworkAddressResolver.matchesCidr("192.168.1.1", "192.168.0.0/24"));
        assertFalse(NetworkAddressResolver.matchesCidr("192.168.0.102", "192.168.0.0/33"));
        assertFalse(NetworkAddressResolver.matchesCidr("not-an-ip", "192.168.0.0/24"));
    }

    @Test
    void applicationPropertiesAreLoadedIntoSelector() {
        Properties props = new Properties();
        props.setProperty("application.host-ip", "192.168.0.102");
        props.setProperty("application.network-interface", "ens33");
        props.setProperty("application.preferred-networks", "192.168.0.0/24, 10.0.0.0/8");
        props.setProperty("application.ignored-interfaces", "veth,docker");
        props.setProperty("application.network-probe-target", "off");

        NetworkAddressResolver.configure(props);

        NetworkAddressResolver.Config config = NetworkAddressResolver.currentConfig();
        assertEquals("192.168.0.102", config.hostIp());
        assertEquals("ens33", config.interfaceName());
        assertEquals(List.of("192.168.0.0/24", "10.0.0.0/8"), config.preferredNetworks());
        assertEquals(List.of("veth", "docker"), config.ignoredInterfaces());
        assertEquals("off", config.probeTarget());
        assertEquals("192.168.0.102", NetworkAddressResolver.hostIpAddress());
    }

    @Test
    void systemPropertySuppliesHostIpForContainerDeployment() {
        System.setProperty("autumn.host-ip", "10.9.8.7");

        NetworkAddressResolver.configure(new Properties());

        assertEquals("10.9.8.7", NetworkAddressResolver.hostIpAddress());
    }

    @Test
    void selectionIsStableRegardlessOfEnumerationOrder() {
        List<NetworkAddressResolver.Interface> nics = new ArrayList<>(List.of(
                nic("docker0", "172.17.0.1"), nic("ens33", "192.168.0.102")));

        NetworkAddressResolver.Resolution first = NetworkAddressResolver.select(nics, DEFAULT, null, null);
        nics.sort((a, b) -> b.name().compareTo(a.name()));
        NetworkAddressResolver.Resolution reversed = NetworkAddressResolver.select(nics, DEFAULT, null, null);

        assertEquals(first.ip(), reversed.ip());
    }
}
