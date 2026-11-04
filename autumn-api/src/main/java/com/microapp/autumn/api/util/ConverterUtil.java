package com.microapp.autumn.api.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
}
