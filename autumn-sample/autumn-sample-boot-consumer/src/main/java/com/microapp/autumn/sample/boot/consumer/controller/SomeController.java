package com.microapp.autumn.sample.boot.consumer.controller;

import org.apache.thrift.TException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        try {
            String result = someServiceConsumer.echo("213");
            return result;
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }

}
