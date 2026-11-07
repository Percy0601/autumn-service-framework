package xyz.wewin.autumn.springcloud.examples.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import reactor.core.publisher.Mono;

/**
 * provider 与 consumer 共享的 HTTP 服务契约。
 *
 * provider 侧：{@code @RestController implements UserClient}，由 Spring MVC 自动映射路由；
 * consumer 侧：通过 {@code @ImportHttpServices} + HttpServiceProxyFactory 生成声明式客户端，
 *             经 Consul 服务发现 + LoadBalancer 以 {@code lb://} 方式调用。
 */
@HttpExchange("/users")
public interface UserClient {

    @GetExchange("/{id}")
    Mono<UserDto> getById(@PathVariable("id") Long id);

    @PostExchange
    Mono<Void> create(@RequestBody UserDto dto);
}
