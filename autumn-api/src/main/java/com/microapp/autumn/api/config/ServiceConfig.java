package com.microapp.autumn.api.config;

import org.apache.thrift.TProcessor;

import lombok.Data;

/**
 * @author: baoxin.zhao
 * @date: 2024/10/8
 */
@Data
public class ServiceConfig<T> {
    private Class<?> interfaceClass;
    private transient TProcessor ref;
}
