package com.microapp.autumn.compiler.model;

import lombok.Data;

import java.util.List;

@Data
public class ReferenceEntry {
    private String referApplication;
    private String localApplication;
    private String interfaceName;
    private Integer minConnection;
    private Integer maxConnection;
    private Integer idleConnection;
    private String targetPackage;
    private List<MethodElement> methodElements;
}
