package com.microapp.autumn.springcloud.examples.consumer.controller;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.microapp.autumn.springcloud.examples.api.UserClient;
import com.microapp.autumn.springcloud.examples.api.UserDto;

/**
 * consumer 入口：/consumer/get-user 经 Consul 发现 + LoadBalancer 调用 provider。
 */
@RestController
@RequestMapping("/consumer")
public class BasicController {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private UserClient userClient;

    @GetMapping("/hello")
    public String hello(String name) {
        if (!StringUtils.hasText(name)) {
            name = "world";
        }
        log.info("===hello===: {}", name);
        return "autumn-sc-consumer: ".concat(name);
    }

    @GetMapping("/get-user")
    public UserDto getUser(@RequestParam Long id) {
        log.info("===user-client===: {}", (userClient == null));
        return userClient.getById(id).block(Duration.ofSeconds(1L));
    }

    @PostMapping("/create-user")
    public UserDto createUser(@RequestBody UserDto dto) {
        userClient.create(dto).block(Duration.ofSeconds(1L));
        return dto;
    }
}
