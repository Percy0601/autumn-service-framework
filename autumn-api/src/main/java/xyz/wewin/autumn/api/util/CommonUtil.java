package xyz.wewin.autumn.api.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CommonUtil {


    private static Map<String, Properties> mapping = null;
    private CommonUtil() {

    }
    static {
        mapping = new ConcurrentHashMap<>();
    }


    public static String getConnectionPath(String service, String ipPort) {
        String interfacePath = service.concat("/")
                .concat(ipPort);
        return interfacePath;
    }

    public static String getHostIpAddress() {
        NetworkInterface netInterface = getNetIf();
        if(Objects.isNull(netInterface)) {
            // fallback to localhost when no eligible network interface found
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                log.warn("get local host address exception:", e);
                return null;
            }
        }
        Enumeration<InetAddress> addresses = netInterface.getInetAddresses();
        while (addresses.hasMoreElements()) {
            InetAddress ip = addresses.nextElement();
            if (ip != null) {
                if (ip instanceof Inet4Address) {
                    return ip.getHostAddress();
                }
            }
        }
        return null;
    }

    public static NetworkInterface getNetIf() {
        return getNetIf(null);
    }

    /**
     * 统一网卡选择：显式指定的网卡名 / 注册 IP 优先命中，保证「注册 IP」与「组播出口」在同一张网卡。
     * 未指定时退回启发式（优先物理网卡，否则第一张可用网卡）。
     *
     * @param preferred 网卡名、displayName 或该网卡上的 IPv4 地址，可为空
     */
    public static NetworkInterface getNetIf(String preferred) {
        boolean explicit = Objects.nonNull(preferred) && !preferred.isBlank();
        NetworkInterface fallback = null;
        try {
            Enumeration<NetworkInterface> allNetInterfaces = NetworkInterface.getNetworkInterfaces();
            while (allNetInterfaces.hasMoreElements()) {
                NetworkInterface netInterface = allNetInterfaces.nextElement();
                if (netInterface.isLoopback() || netInterface.isVirtual() || !netInterface.isUp()) {
                    continue;
                }
                if (explicit && (preferred.equals(netInterface.getName())
                        || preferred.equals(netInterface.getDisplayName()))) {
                    return netInterface;
                }
                Enumeration<InetAddress> addresses = netInterface.getInetAddresses();
                boolean hasIpv4 = false;
                boolean ipMatches = false;
                while (addresses.hasMoreElements()) {
                    InetAddress ip = addresses.nextElement();
                    if (!(ip instanceof Inet4Address)) {
                        continue;
                    }
                    hasIpv4 = true;
                    if (explicit && (preferred.equals(ip.getHostAddress()) || preferred.equals(ip.getHostName()))) {
                        ipMatches = true;
                    }
                }
                if (!hasIpv4) {
                    continue;
                }
                if (ipMatches) {
                    return netInterface;
                }
                if (netInterface.getDisplayName().contains("Intel")
                        || netInterface.getDisplayName().contains("Realtek")
                        || netInterface.getDisplayName().contains("Atheros")
                        || netInterface.getDisplayName().contains("Broadcom")) {
                    return netInterface;
                }
                // remember first eligible interface as fallback(e.g. macOS en0 with no vendor keyword)
                if (Objects.isNull(fallback)) {
                    fallback = netInterface;
                }
            }
        } catch (SocketException e) {
            log.warn("get network interfaces exception:", e);
        }
        return fallback;
    }

    /**
     * 供注册中心使用的网卡名（MulticastConfig.networkInterface 按 NetworkInterface.getByName 解析）。
     */
    public static String resolveNetworkInterface(String preferred) {
        NetworkInterface netInterface = getNetIf(preferred);
        return Objects.isNull(netInterface) ? null : netInterface.getName();
    }

    /**
     * read config<br>
     * <pre>
     *     1、classpath（含 spring boot 可执行 jar 的 nested 资源）
     *     2、${autumn.config.dir:-/opt/autumn}/
     * </pre>
     * @param filename
     * @return 找不到时返回空配置，不抛异常
     */
    public static Properties readClasspath(String filename) {
        Properties cached = mapping.get(filename);
        if(Objects.nonNull(cached)) {
            return cached;
        }

        Properties properties = new Properties();
        URL url = CommonUtil.class.getClassLoader().getResource(filename);
        if(Objects.isNull(url)) {
            String configDir = System.getProperty("autumn.config.dir", "/opt/autumn");
            Path fallback = Path.of(configDir, filename);
            if(Files.isReadable(fallback)) {
                try (Reader reader = Files.newBufferedReader(fallback)) {
                    properties.load(reader);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                log.warn("autumn config not found, classpath and {} both miss: {}", fallback, filename);
            }
        } else {
            // 统一走 openStream：普通 jar 的 Path.of(uri) 会抛 FileSystemNotFoundException，nested jar 只有 stream 稳定可用
            try (InputStream in = url.openStream();
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        mapping.put(filename, properties);

        return properties;
    }
}
