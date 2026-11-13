# Autumn Service Framework

基于 Apache Thrift 的轻量服务治理框架：服务注册发现、连接池、健康检查、泳道路由；支持 Spring Boot Starter 与原生 Java API，兼容 GraalVM Native Image。

**稳定版 1.0.0-RELEASE 已发布至 Maven Central**（`xyz.we-win.autumn`），要求 JDK 25+。

```xml
<dependency>
    <groupId>xyz.we-win.autumn</groupId>
    <artifactId>autumn-spring-boot-starter</artifactId>
    <version>1.1.0-SNAPSHOT</version>
</dependency>
```

```xml
<!-- 1.1.0 起 starter 自带 JSR-269 处理器（autumn-compiler + freemarker + autumn-api），
     不用配 annotationProcessorPaths；JDK 23+ 默认关闭注解处理，这一行负责打开它，
     否则 @Export/@Reference 的代码生成不会发生。
     仍用 1.0.0-RELEASE 的话，starter 不带处理器，要在 maven-compiler-plugin 的
     annotationProcessorPaths 里自行声明（见使用说明 3.1 第 5 步）。 -->
<properties>
    <maven.compiler.proc>full</maven.compiler.proc>
</properties>
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
