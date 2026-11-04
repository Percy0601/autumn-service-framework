package com.microapp.autumn.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author: baoxin.zhao
 * @date: 2024/10/15
 * @deprecated 自研组播协议事件，随 {@code MulticastRegistry} 一起废弃；组播注册发现改由
 * {@code autumn-cloud-extension} 的 {@code MulticastDiscovery} 实现。
 */
@Deprecated(since = "1.0.0", forRemoval = true)
@AllArgsConstructor
@Getter
public enum MulticastEventEnum {
    REGISTRY("registry", "multicast registry event"),
    SUBSCRIBE("subscribe", "multicast subscribe event"),
    SHUTDOWN("shutdown", "multicast shutdown event"),
    ;
    private String code;
    private String desc;

    public static MulticastEventEnum getByCode(String code) {
        for (MulticastEventEnum e: MulticastEventEnum.values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
