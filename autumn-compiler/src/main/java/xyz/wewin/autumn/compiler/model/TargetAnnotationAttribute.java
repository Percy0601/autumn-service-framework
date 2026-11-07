package xyz.wewin.autumn.compiler.model;

import lombok.Data;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/13
 */
@Data
public class TargetAnnotationAttribute {
    private String returnType;
    private String name;
    private String defaultValue;
    private String value;
}
