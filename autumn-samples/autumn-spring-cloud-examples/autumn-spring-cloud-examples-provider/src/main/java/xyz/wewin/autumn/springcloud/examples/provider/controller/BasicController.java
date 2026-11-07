package xyz.wewin.autumn.springcloud.examples.provider.controller;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基础探活接口，便于直接浏览器验证 provider 是否在线。
 */
@RestController
public class BasicController {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @GetMapping("/hello")
    public String hello(String name) {
        if (!StringUtils.hasText(name)) {
            name = "world";
        }
        log.info("===hello===: {}", name);
        return "autumn-sc-provider: ".concat(name);
    }
}
