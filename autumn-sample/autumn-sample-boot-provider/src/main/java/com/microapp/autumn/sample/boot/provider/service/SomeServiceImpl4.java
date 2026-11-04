package com.microapp.autumn.sample.boot.provider.service;

import java.util.Map;

import org.apache.thrift.TException;
import org.springframework.stereotype.Service;

import com.microapp.autumn.api.annotation.Export;
import com.microapp.autumn.sample.api.SomeService4;
import com.microapp.autumn.sample.api.User;
import com.microapp.autumn.sample.api.UserNotFoundException;

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
public class SomeServiceImpl4 implements SomeService4.Iface {

    @Override
    public User getUser(int userId) throws UserNotFoundException, TException {
        if (userId < 0) {
            throw new UserNotFoundException("user not found: " + userId);
        }
        User user = new User();
        user.setUserId(userId);
        user.setUsername("user-" + userId);
        return user;
    }

    @Override
    public Map<String, String> userInfo(User user) throws TException {
        return Map.of("userId", String.valueOf(user.getUserId()), "username", user.getUsername());
    }
}
