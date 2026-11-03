package com.microapp.autumn.springcloud.examples.provider.controller;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.microapp.autumn.springcloud.examples.api.UserDto;

import reactor.core.publisher.Mono;

/**
 * provider 侧实现用户服务，路由与 api 模块 UserClient 的契约路径一致：
 * GET /users/{id}、POST /users。
 *
 * 注意：Spring MVC（Boot 3.5 / Spring Framework 6.2）不支持用客户端注解
 * {@code @HttpExchange} 映射服务端路由，因此这里使用标准 MVC 注解声明。
 */
@RestController
public class UserApiController {

    private final Map<Long, UserDto> store = new ConcurrentHashMap<>();

    public UserApiController() {
        store.put(1L, new UserDto(1L, "Alice", "alice@test.com"));
        store.put(2L, new UserDto(2L, "Bob", "bob@test.com"));
    }

    @GetMapping("/users/{id}")
    public Mono<UserDto> getById(@PathVariable("id") Long id) {
        return Mono.justOrEmpty(store.get(id));
    }

    @PostMapping("/users")
    public Mono<Void> create(@RequestBody UserDto dto) {
        store.put(dto.getId(), dto);
        return Mono.empty();
    }
}
