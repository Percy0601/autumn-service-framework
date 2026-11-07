package xyz.wewin.autumn.sample.boot.provider.service;

import java.util.Map;
import java.util.Set;

import org.apache.thrift.TException;
import org.springframework.stereotype.Service;

import xyz.wewin.autumn.api.annotation.Export;
import xyz.wewin.autumn.sample.api.SomeService3;
import xyz.wewin.autumn.sample.api.User;

import lombok.extern.slf4j.Slf4j;

/**
 * extra provider service for multi-interface demo, exported via @Export
 *
 * @author: baoxin.zhao
 * @date: 2026/11/4
 */
@Slf4j
@Export
@Service
public class SomeServiceImpl3 implements SomeService3.Iface {

    @Override
    public String greet(String name, int age) throws TException {
        return "Hello, " + name + ", age=" + age;
    }

    @Override
    public boolean updateUser(User user, Map<String, String> attrs) throws TException {
        log.info("updateUser called: user={}, attrs={}", user, attrs);
        return user != null;
    }

    @Override
    public Set<Integer> listUserIds() throws TException {
        return Set.of(1, 2, 3);
    }
}
