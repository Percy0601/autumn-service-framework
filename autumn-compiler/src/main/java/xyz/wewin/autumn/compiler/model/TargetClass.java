package xyz.wewin.autumn.compiler.model;

import java.util.List;

import lombok.Data;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/13
 */
@Data
public class TargetClass {
    private String name;
    private String fullName;
    private String packageName;
    private List<String> implementList;
    private List<TargetAnnotation> annotations;
    private List<TargetMethod> methods;
}
