package com.microapp.autumn.sample.boot.provider.service;

import java.util.List;

import org.apache.thrift.TException;
import org.springframework.stereotype.Service;

import com.microapp.autumn.api.annotation.Export;
import com.microapp.autumn.sample.api.SomeService2;
import com.microapp.autumn.sample.api.User;

import lombok.extern.slf4j.Slf4j;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/10
 */
@Slf4j
@Export
@Service
public class SomeServiceImpl2 implements SomeService2.Iface {

    @Override
    public String echo2(String msg) throws TException {
        return "msg2";
    }

    @Override
    public int addUser2(User user) throws TException {
        return 30;
    }

    @Override
    public List<User> findUserByIds2(List<Integer> idList) throws TException {
        return List.of();
    }
}
