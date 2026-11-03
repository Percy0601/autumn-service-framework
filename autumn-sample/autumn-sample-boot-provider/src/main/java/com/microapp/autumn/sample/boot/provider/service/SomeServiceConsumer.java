package com.microapp.autumn.sample.boot.provider.service;

import org.apache.thrift.TException;
import org.springframework.stereotype.Service;

import com.microapp.autumn.api.annotation.Reference;
import com.microapp.autumn.api.util.AutumnException;
import com.microapp.autumn.sample.api.SomeService;

import lombok.extern.slf4j.Slf4j;

/**
 * consumer role inside provider app, direct connect to local thrift server for self-contained e2e demo.
 *
 * @author: baoxin.zhao
 * @date: 2026/11/3
 */
@Slf4j
@Service
public class SomeServiceConsumer {

    @Reference(refer = "training-b", ipPort = "127.0.0.1:30880")
    private SomeService.Iface someService;

    public String echo(String msg) throws TException {
        int maxRetry = 3;
        for (int i = 0; i < maxRetry; i++) {
            try {
                return someService.echo(msg);
            } catch (RuntimeException e) {
                if (i == maxRetry - 1) {
                    throw e;
                }
                log.warn("autumn reference echo fail, retry. exception:", e);
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new AutumnException("autumn reference echo fail after retry");
    }
}
