package xyz.wewin.autumn.api.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** 装载注册 IP / 网卡选择参数，需在 {@code ProviderConfig.init} 探测 IP 之前调用 */
    public static void configureNetwork(Properties properties) {
        NetworkAddressResolver.configure(properties);
    }

    /**
     * 注册到服务中心的主机 IP。多网卡 / Docker 网桥的判定统一交给
     * {@link NetworkAddressResolver}，此处保留原有 API 供上层调用。
     */
    public static String getHostIpAddress() {
        return NetworkAddressResolver.hostIpAddress();
    }

    public static NetworkInterface getNetIf() {
        return getNetIf(null);
    }

    /**
     * 统一网卡选择：显式指定的网卡名 / 注册 IP 优先命中，保证「注册 IP」与「组播出口」在同一张网卡。
     *
     * @param preferred 网卡名、displayName 或该网卡上的 IPv4 地址，可为空
     */
    public static NetworkInterface getNetIf(String preferred) {
        return NetworkAddressResolver.networkInterface(preferred);
    }

    /**
     * 供注册中心使用的网卡名（MulticastConfig.networkInterface 按 NetworkInterface.getByName 解析）。
     * 回环地址会退回自动选出的物理网卡，避免组播绑到 lo。
     */
    public static String resolveNetworkInterface(String preferred) {
        return NetworkAddressResolver.networkInterfaceName(preferred);
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
