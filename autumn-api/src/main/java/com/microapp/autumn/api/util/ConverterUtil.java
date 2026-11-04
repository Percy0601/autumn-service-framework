package com.microapp.autumn.api.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.microapp.autumn.api.Discovery;
import com.microapp.autumn.api.config.ApplicationConfig;
import com.microapp.autumn.api.config.ConsumerConfig;
import com.microapp.autumn.api.config.ProviderConfig;
import com.microapp.autumn.api.enums.MulticastEventEnum;


/**
 * @author: baoxin.zhao
 * @date: 2024/10/1
 */
public class ConverterUtil {
    public final static String CONSTANT_PROTOCOL= "_protocol_";
    public final static String CONSTANT_REGISTER_SERVICE = "_service_";
    private ConverterUtil() {

    }

    public static Map<String, String> getUrlParams(String url) {
        Map<String, String> map = new HashMap<>(0);
        if (Objects.isNull(url)) {
            return map;
        }
        String[] parts_1 = url.split("://");
        if(parts_1.length != 2) {
            return map;
        }
        String _protocol = parts_1[0];
        map.put(CONSTANT_PROTOCOL, _protocol);
        String parts_2 = parts_1[1];
        String queryString = handleUrlPath(map, parts_2);
        handleQueryString(map, queryString);
        return map;
    }

    private static String handleUrlPath(Map<String, String> map, String urlPath) {
        String[] params = urlPath.split("\\?");
        if(params.length > 1) {
            String part_1 = params[0];
            map.put(CONSTANT_REGISTER_SERVICE, part_1);
            String part_2 = params[1];
            return part_2;
        }

        return params[0];
    }

    private static void handleQueryString(Map<String, String> map, String queryString) {
        String[] params = queryString.split("&");
        for (int i = 0; i < params.length; i++) {
            String[] p = params[i].split("=");
            if (p.length == 2) {
                map.put(p[0], p[1]);
            }
        }
    }


    /**
     * @deprecated 自研组播协议报文，随 {@code MulticastRegistry} 一起废弃
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public static String shutdownRequest(ProviderConfig config) {
        String name = ApplicationConfig.getInstance().getName();
        String queryString = MulticastEventEnum.SHUTDOWN.getCode().concat("://")
                .concat(name)
                .concat("?");
        if(Objects.nonNull(config.getIp())) {
            queryString = queryString.concat("ip=")
                    .concat(config.getIp())
                    .concat("&");
        }
        if(Objects.nonNull(config.getPort())) {
            queryString = queryString.concat("port=")
                    .concat(config.getPort().toString())
                    .concat("&");
        }
        Set<String> services = SpiUtil.load(Discovery.class).services();
        if(services.size() > 0) {
            String services_str = String.join(",", services);
            queryString = queryString.concat("references=")
                    .concat(services_str)
                    .concat("&");
        }

        if(queryString.length() > 0) {
            queryString = queryString.substring(0, queryString.length() - 1);
        }
        return queryString;
    }

    /**
     * @deprecated 自研组播协议报文，随 {@code MulticastRegistry} 一起废弃
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public static String registryRequest(ProviderConfig config) {
        String name = ApplicationConfig.getInstance().getName();
        String queryString = MulticastEventEnum.REGISTRY.getCode().concat("://")
                .concat(name)
                .concat("?");
        if(Objects.nonNull(config.getIp())) {
            queryString = queryString.concat("ip=")
                    .concat(config.getIp())
                    .concat("&");
        }
        if(Objects.nonNull(config.getPort())) {
            queryString = queryString.concat("port=")
                    .concat(config.getPort().toString())
                    .concat("&");
        }
        Set<String> services = SpiUtil.load(Discovery.class).services();
        if(services.size() > 0) {
            String services_str = String.join(",", services);
            queryString = queryString.concat("references=")
                    .concat(services_str)
                    .concat("&");
        }
        if(queryString.length() > 0) {
            queryString = queryString.substring(0, queryString.length() - 1);
        }
        return queryString;
    }

    /**
     * @deprecated 自研组播协议报文，随 {@code MulticastRegistry} 一起废弃
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public static String subscribeRequest(ProviderConfig config) {
        String name = ApplicationConfig.getInstance().getName();
        String queryString = MulticastEventEnum.SUBSCRIBE.getCode().concat("://")
                .concat(name)
                .concat("?");
        if(Objects.nonNull(config.getIp())) {
            queryString = queryString.concat("ip=")
                    .concat(config.getIp())
                    .concat("&");
        }
        if(Objects.nonNull(config.getPort())) {
            queryString = queryString.concat("port=")
                    .concat(config.getPort().toString())
                    .concat("&");
        }
        Set<String> services = SpiUtil.load(Discovery.class).services();
        if(services.size() > 0) {
            String services_str = String.join(",", services);
            queryString = queryString.concat("references=")
                    .concat(services_str)
                    .concat("&");
        }
        if(queryString.length() > 0) {
            queryString = queryString.substring(0, queryString.length() - 1);
        }
        return queryString;
    }

    /**
     * @deprecated 自研组播协议报文解析，随 {@code MulticastRegistry} 一起废弃
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public static ConsumerConfig queryStringToProvider(String url) {
        Map<String, String> mapping = getUrlParams(url);
        if(mapping.isEmpty()) {
            return null;
        }
        ConsumerConfig config = new ConsumerConfig();
        if(mapping.containsKey("port")) {
            Integer port = Integer.valueOf(mapping.get("port"));
            config.setPort(port);
        }
        if(mapping.containsKey("ip")) {
            String ip = mapping.get("ip");
            config.setIp(ip);
        }
        if(mapping.containsKey("references")) {
            String services_str = mapping.get("references");
            if(Objects.nonNull(services_str) && services_str.length() > 1) {
                Set<String> references = new HashSet<>(Arrays.asList(services_str.split(",")));
                config.setReferences(references);
            }
        }
        if(mapping.containsKey(CONSTANT_REGISTER_SERVICE)) {
            String service = mapping.get(CONSTANT_REGISTER_SERVICE);
            config.setName(service);
        }
        return config;
    }
}
