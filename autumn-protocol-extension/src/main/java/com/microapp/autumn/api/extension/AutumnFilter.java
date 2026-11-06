package com.microapp.autumn.api.extension;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/6
 */

public interface AutumnFilter {

    String name();

    void handleBefore(JoinPoint joinPoint);

    <T> void handleAfter(T result);
}
