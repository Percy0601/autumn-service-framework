package com.microapp.autumn.springcloud.examples.provider.controller;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>负载均衡可观测性：每次调用打印命中的实例端口，并在响应 name 上附加
 * {@code [node:<port>]} 标记，便于 consumer 侧验证请求是否分散到多个 provider 实例。</p>
 *
 * 注意：Spring MVC（Boot 3.5 / Spring Framework 6.2）不支持用客户端注解
 * {@code @HttpExchange} 映射服务端路由，因此这里使用标准 MVC 注解声明。
 */
@RestController
public class UserApiController {

    private static final Logger log = LoggerFactory.getLogger(UserApiController.class);

    private final Map<Long, UserDto> store = new ConcurrentHashMap<>();

    @Value("${server.port}")
    private int serverPort;

    public UserApiController() {
        store.put(1L, new UserDto(1L, "Alice", "alice@test.com"));
        store.put(2L, new UserDto(2L, "Bob", "bob@test.com"));
    }

    @GetMapping("/users/{id}")
    public Mono<UserDto> getById(@PathVariable("id") Long id) {
        log.info("[load-balance-hit] GET /users/{} served by node:{}", id, serverPort);
        UserDto found = store.get(id);
        if (found == null) {
            return Mono.empty();
        }
        return Mono.just(new UserDto(found.getId(),
                found.getName() + " [node:" + serverPort + "]", found.getEmail()));
    }

    @PostMapping("/users")
    public Mono<Void> create(@RequestBody UserDto dto) {
        log.info("[load-balance-hit] POST /users served by node:{}", serverPort);
        store.put(dto.getId(), dto);
        return Mono.empty();
    }
}
