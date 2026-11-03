package com.microapp.autumn.compiler.model;

import java.util.List;

import lombok.Data;

@Data
public class ExportEntry {
    private String name;
    /**
     * implementation class full name, e.g. com.microapp.autumn.sample.service.SomeServiceImpl
     */
    private String implementName;
    /**
     * thrift Iface interface full name, e.g. com.microapp.autumn.sample.api.SomeService.Iface
     */
    private String interfaceName;
    private String proxyTypeName;
    private String proxyBeanName;
    private List<TargetMethod> methods;

}
