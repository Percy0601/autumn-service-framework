package com.microapp.autumn.sample.boot.consumer.controller;

import org.apache.thrift.TException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping("/hello")
    public String hello() {
        log.info("===================");
        // 隐式传参：不改动 RPC 方法签名，随调用链路透传到 provider
        AttachmentContext.put("tenant", "consul-smoke");
        try {
            return someServiceConsumer.echo("213");
        } catch (TException e) {
            throw new RuntimeException(e);
        } finally {
            AttachmentContext.clear();
        }
    }

}
