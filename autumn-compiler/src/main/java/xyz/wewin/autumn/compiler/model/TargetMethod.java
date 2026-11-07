package xyz.wewin.autumn.compiler.model;

import java.util.List;

import lombok.Data;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/16
 */
@Data
public class TargetMethod {
    private String name;
    private String returnType;
    private List<TargetVariable> variables;
    private List<String> exceptions;
}
