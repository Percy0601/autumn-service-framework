package com.microapp.autumn.sample.boot.provider.service;

import java.util.List;

import org.apache.thrift.TException;
import org.springframework.stereotype.Service;

import com.microapp.autumn.api.annotation.Export;
import com.microapp.autumn.api.annotation.Reference;
import com.microapp.autumn.sample.api.SomeService;
import com.microapp.autumn.sample.api.User;

import lombok.extern.slf4j.Slf4j;

/**
 * provider service, exported by autumn starter automatically via @Export
 *
 * @author: baoxin.zhao
 * @date: 2024/11/10
 */
@Slf4j
@Export
@Service
public class SomeServiceImpl implements SomeService.Iface {

    /**
     * watch consumer app name, used by multicast registry mutual discovery topology
     */
    @Reference(refer = "consumer")
    SomeService.Iface someService;

    @Override
    public String echo(String msg) throws TException {
        return "Hello, " + msg;
    }

    @Override
    public int addUser(User user) throws TException {
        return 1;
    }

    @Override
    public List<User> findUserByIds(List<Integer> idList) throws TException {
        return List.of();
    }
}
