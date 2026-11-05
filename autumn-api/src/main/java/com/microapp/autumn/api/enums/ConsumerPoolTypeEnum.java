package com.microapp.autumn.api.enums;

import lombok.Getter;

@Getter
public enum ConsumerPoolTypeEnum {
    SERVICE("SERVICE", "pool of service"),
    INTERFACE("INTERFACE", "pool of interface"),

    ;
    private String code;
    private String desc;
    ConsumerPoolTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

}
