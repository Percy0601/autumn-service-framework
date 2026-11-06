package com.microapp.autumn.api.extension;

import lombok.Data;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/6
 */
@Data
public class JoinPoint {
    private String method;
    private String clazz;
    private Object[] args;


}
