package com.microapp.autumn.compiler.model;

import lombok.Data;

import java.util.List;

@Data
public class MethodElement {
    private String name;
    private String returnType;
    private List<String> paramTypes;
}
