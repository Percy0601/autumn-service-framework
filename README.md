# Autumn Service Framework

基于 Apache Thrift 的轻量服务治理框架：服务注册发现、连接池、健康检查、泳道路由；支持 Spring Boot Starter 与原生 Java API，兼容 GraalVM Native Image。

**稳定版 1.0.0-RELEASE 已发布至 Maven Central**（`xyz.we-win.autumn`），要求 JDK 25+。

```xml
<dependency>
    <groupId>xyz.we-win.autumn</groupId>
    <artifactId>autumn-spring-boot-starter</artifactId>
    <version>1.0.0-RELEASE</version>
</dependency>
```

```java
@Component
@Export
public class SomeServiceImpl implements SomeService.Iface { /* ... */ }

@Reference(refer = "training-b")
private SomeService.Iface someService;
```

- [使用说明](docs/USER_GUIDE.md)：接入、配置项总表、原生 API、示例运行、构建与 FAQ
- [协议扩展](docs/PROTOCOL-EXTENSION.md)：隐式传参、泳道路由、动态泛化调用
- [性能压测](docs/BENCHMARK.md)

```bash
mvn -DskipTests clean install
./scripts/run-boot.sh autumn-sample-boot-provider --server.port=18080
./scripts/run-boot.sh autumn-sample-boot-consumer --server.port=8081
```

Apache License 2.0
