package xyz.wewin.autumn.compiler.model;

import java.util.List;

import lombok.Data;

@Data
public class ExportEntry {
    private String name;
    /**
     * implementation class full name, e.g. xyz.wewin.autumn.sample.service.SomeServiceImpl
     */
    private String implementName;
    /**
     * thrift Iface interface full name, e.g. xyz.wewin.autumn.sample.api.SomeService.Iface
     */
    private String interfaceName;
    private String proxyTypeName;
    private String proxyBeanName;
    private List<TargetMethod> methods;

}
