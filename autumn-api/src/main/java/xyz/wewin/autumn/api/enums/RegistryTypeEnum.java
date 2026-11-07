package xyz.wewin.autumn.api.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author: baoxin.zhao
 * @date: 2024/10/15
 */
@AllArgsConstructor
@Getter
public enum RegistryTypeEnum {
    CONSUL("consul", "consul registry discovery type!"),
    NACOS("nacos", "nacos registry discovery type!"),
    MULTICAST("multicast", "multicast registry discovery type!"),
    DIRECT("direct", "direct ip:port registry discovery type!"),
    ;
    private String code;
    private String desc;

    public static RegistryTypeEnum getByCode(String code) {
        for (RegistryTypeEnum e: RegistryTypeEnum.values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
