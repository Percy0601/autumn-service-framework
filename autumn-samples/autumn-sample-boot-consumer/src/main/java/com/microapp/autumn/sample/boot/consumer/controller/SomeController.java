package com.microapp.autumn.sample.boot.consumer.controller;

import java.util.Map;

import org.apache.thrift.TException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.microapp.autumn.api.extension.AttachmentContext;
import com.microapp.autumn.sample.boot.consumer.service.SomeServiceConsumer;

import lombok.extern.slf4j.Slf4j;

/**
 * @author: baoxin.zhao
 * @date: 2026/11/3
 */
@Slf4j
@RestController
@RequestMapping("/boot-sample-consumer")
public class SomeController {

    @Autowired
    private SomeServiceConsumer someServiceConsumer;

    /**
     * ?traffic=gray 把本次调用路由到 gray 泳道实例；?label.region=cn 按任意实例标签筛选。
     * 这些值都是隐式参数，随调用链透传，不改动 RPC 方法签名。
     */
    @GetMapping("/hello")
    public String hello(@RequestParam Map<String, String> query) {
        log.info("===================");
        AttachmentContext.put("tenant", "consul-smoke");
        query.forEach((key, value) -> {
            if (value != null && !value.isBlank()
                    && (key.equalsIgnoreCase("traffic") || key.equalsIgnoreCase("X-Traffic")
                        || key.regionMatches(true, 0, "label.", 0, "label.".length()))) {
                AttachmentContext.put(key, value.trim());
            }
        });
        try {
            return someServiceConsumer.echo("213");
        } catch (TException e) {
            throw new RuntimeException(e);
        } finally {
            AttachmentContext.clear();
        }
    }

}
