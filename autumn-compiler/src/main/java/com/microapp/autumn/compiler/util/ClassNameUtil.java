package com.microapp.autumn.compiler.util;

public class ClassNameUtil {

    public static String getSimpleClassName(String className) {
        int lastDot = className.lastIndexOf('.');
        String simpleClassName = className.substring(lastDot + 1);
        return simpleClassName;
    }

    public static String getPackageName(String className) {
        String packageName = "";
        int lastDot = className.lastIndexOf('.');
        if (lastDot > 0) {
            packageName = className.substring(0, lastDot);
        }
        return packageName;
    }

}
