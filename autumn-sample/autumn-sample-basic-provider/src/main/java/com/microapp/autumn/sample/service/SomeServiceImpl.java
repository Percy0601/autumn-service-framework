package com.microapp.autumn.sample.service;

import java.util.List;

import org.apache.thrift.TException;

import com.microapp.autumn.sample.api.SomeService;
import com.microapp.autumn.sample.api.User;
import lombok.extern.slf4j.Slf4j;

/**
 * @author: baoxin.zhao
 * @date: 2024/10/8
 */
@Slf4j
public class SomeServiceImpl implements SomeService.Iface {
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
