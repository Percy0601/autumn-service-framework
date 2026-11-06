package com.microapp.autumn.sample.boot.consumer.service;

import org.apache.thrift.TException;
import org.springframework.stereotype.Service;

import com.microapp.autumn.api.annotation.Reference;
import com.microapp.autumn.api.util.AutumnException;
import com.microapp.autumn.sample.api.SomeService;

import lombok.extern.slf4j.Slf4j;

/**
 * consumer role, @Reference(refer = "training-b") discover provider via multicast registry
 *
 * @author: baoxin.zhao
 * @date: 2026/11/3
 */
@Slf4j
@Service
public class SomeServiceConsumer {

    @Reference(refer = "training-b")
    private SomeService.Iface someService;

    public String echo(String msg) throws TException {
        int maxRetry = 10;
        for (int i = 0; i < maxRetry; i++) {
            try {
                return someService.echo(msg);
            } catch (RuntimeException e) {
                if (i == maxRetry - 1) {
                    throw e;
                }
                log.warn("autumn reference echo fail, retry. exception:", e);
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new AutumnException("autumn reference echo fail after retry");
    }
}
