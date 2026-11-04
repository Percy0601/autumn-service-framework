package com.microapp.autumn.sample.boot.provider.controller;

import java.util.Map;
import java.util.Set;

import org.apache.thrift.TException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.microapp.autumn.sample.api.User;
import com.microapp.autumn.sample.boot.provider.service.SomeServiceConsumer;

import lombok.extern.slf4j.Slf4j;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/9
 */
@Slf4j
@RestController
@RequestMapping("/boot-sample-provider")
public class SomeController {
    @Autowired
    private SomeServiceConsumer someServiceConsumer;

    @GetMapping("/hello")
    public String hello() {
        log.info("===================");
        try {
            String result = someServiceConsumer.echo("213");
            return result;
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/greet")
    public String greet(@RequestParam(value = "name", defaultValue = "autumn") String name,
                        @RequestParam(value = "age", defaultValue = "18") int age) {
        try {
            return someServiceConsumer.greet(name, age);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/listUserIds")
    public Set<Integer> listUserIds() {
        try {
            return someServiceConsumer.listUserIds();
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/getUser")
    public User getUser(@RequestParam("userId") int userId) {
        try {
            return someServiceConsumer.getUser(userId);
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }
}
