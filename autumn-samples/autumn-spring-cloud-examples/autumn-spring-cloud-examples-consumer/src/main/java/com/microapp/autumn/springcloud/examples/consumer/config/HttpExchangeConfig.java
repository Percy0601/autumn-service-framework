package com.microapp.autumn.springcloud.examples.consumer.config;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.util.UriComponentsBuilder;

import com.microapp.autumn.springcloud.examples.api.UserClient;

import reactor.core.publisher.Mono;

/**
 * 手工装配 HttpExchange 代理 + 自定义 lb:// 负载均衡过滤器。
 *
 * <p>说明一：Spring Cloud Commons 4.3.3 的 LoadBalancerUriTools.computeScheme 存在缺陷——
 * 当 ServiceInstance.getScheme() 为 null（Consul 注册的实例即如此）且原始 URI 为
 * lb:// 时，会把 scheme 继承为 "lb"，导致 URI 替换后仍为 lb://…，WebClient 报
 * "invalid URI scheme lb"。因此这里手工解析实例并重建 URI。</p>
 *
 * <p>说明二（GraalVM Native / AOT）：Spring Cloud 的 {@code LoadBalancerClientFactory}
 * 继承 {@code NamedContextFactory}，会在运行时创建子应用上下文；Boot AOT 下该上下文是
 * {@code GenericApplicationContext}，而 {@code NamedContextFactory#registerBeans} 断言它
 * 必须实现 {@code AnnotationConfigRegistry}，native 镜像中首次 choose 即抛
 * {@code IllegalArgumentException}。因此这里直接用 {@link DiscoveryClient}（Consul 真实
 * 服务发现）+ 原子轮询改写 URI，既保持每次请求轮询（RoundRobin）的负载均衡语义，
 * 又避开动态子上下文，使 JIT 与 Native 两种运行模式行为一致。</p>
 */
@Configuration
public class HttpExchangeConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpExchangeConfig.class);

    @Bean
    public UserClient userClient(DiscoveryClient discoveryClient) {
        // 直接用 WebClient.builder() 而非注入 Boot 的 WebClient.Builder，
        // 避免 @LoadBalanced 自动注入的框架过滤器叠加干扰（其 URI 替换存在 scheme 缺陷）。
        WebClient wc = WebClient.builder()
                .baseUrl("lb://autumn-sc-provider")
                .filter(lbExchangeFilter(discoveryClient))
                .build();
        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(wc))
                .build();
        return factory.createClient(UserClient.class);
    }

    /**
     * 将 lb://serviceId/... 解析为真实实例地址后继续转发。
     * 每次请求重新拉取实例列表并原子轮询一个实例，Native 下无需创建子应用上下文。
     */
    private ExchangeFilterFunction lbExchangeFilter(DiscoveryClient discoveryClient) {
        AtomicInteger cursor = new AtomicInteger();
        return (ClientRequest request, ExchangeFunction next) -> {
            URI url = request.url();
            String serviceId = url.getHost();
            if (!"lb".equalsIgnoreCase(url.getScheme()) || serviceId == null) {
                return next.exchange(request);
            }
            List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
            if (instances == null || instances.isEmpty()) {
                log.warn("no available instance for service {}", serviceId);
                return Mono.error(new IllegalStateException("no available instance for service " + serviceId));
            }
            ServiceInstance instance = instances.get(
                    Math.floorMod(cursor.getAndIncrement(), instances.size()));
            URI target = UriComponentsBuilder.fromUri(url)
                    .scheme(instance.isSecure() ? "https" : "http")
                    .host(instance.getHost())
                    .port(instance.getPort())
                    .build()
                    .toUri();
            ClientRequest newRequest = ClientRequest.create(request.method(), target)
                    .headers(headers -> headers.addAll(request.headers()))
                    .body(request.body())
                    .build();
            return next.exchange(newRequest);
        };
    }
}
