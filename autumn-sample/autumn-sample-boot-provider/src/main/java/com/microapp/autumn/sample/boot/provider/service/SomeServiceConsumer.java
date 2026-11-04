package com.microapp.autumn.sample.boot.provider.service;

import java.util.Set;

import org.apache.thrift.TException;
import org.springframework.stereotype.Service;

import com.microapp.autumn.api.annotation.Reference;
import com.microapp.autumn.api.util.AutumnException;
import com.microapp.autumn.sample.api.SomeService;
import com.microapp.autumn.sample.api.SomeService3;
import com.microapp.autumn.sample.api.SomeService4;
import com.microapp.autumn.sample.api.User;
import com.microapp.autumn.sample.api.UserNotFoundException;

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

    @Reference(refer = "training-b", ipPort = "127.0.0.1:30880")
    private SomeService3.Iface someService3;

    @Reference(refer = "training-b", ipPort = "127.0.0.1:30880")
    private SomeService4.Iface someService4;

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

    public String greet(String name, int age) throws TException {
        return someService3.greet(name, age);
    }

    public Set<Integer> listUserIds() throws TException {
        return someService3.listUserIds();
    }

    public User getUser(int userId) throws TException, UserNotFoundException {
        return someService4.getUser(userId);
    }
}
