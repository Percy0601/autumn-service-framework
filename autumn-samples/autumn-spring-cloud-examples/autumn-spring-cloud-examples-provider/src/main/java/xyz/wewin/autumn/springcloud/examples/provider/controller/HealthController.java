package xyz.wewin.autumn.springcloud.examples.provider.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consul 健康检查端点（对应配置中的 health-check-path=/health）。
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public String health() {
        return "UP";
    }
}
