# Autumn Service Framework

基于 **Apache Thrift** 的轻量级服务治理框架：数据面走 Thrift 原生 RPC，控制面提供**服务注册、服务发现、连接池、健康检查、控制面 API** 等治理能力，支持原生 Java API 与 Spring Boot Starter 两种接入方式，面向 GraalVM Native Image 友好设计。

```
┌──────────────────────────────────────────────────────────────┐
│  接入层  Spring Boot (@Export/@Reference)  /  原生 Java API   │
├──────────────────────────────────────────────────────────────┤
│  控制面  Registry(注册) · Discovery(发现) · AutumnPool(连接池)│
│          · 健康检查 / 实例摘除 · ControlApi 运维接口          │
├──────────────────────────────────────────────────────────────┤
│  注册发现  autumn-registry-extension (SPI 适配层)             │
│            └─ autumn-cloud-extension: multicast · consul ·   │
│               nacos · direct（零 SDK 依赖，纯 HTTP / JDK 组播）│
├──────────────────────────────────────────────────────────────┤
│  数据面  Apache Thrift (TSocket + TFramedTransport +         │
│          TBinaryProtocol + TMultiplexedProtocol)             │
└──────────────────────────────────────────────────────────────┘
```

## 特性

- **轻量内核**：核心仅依赖 `apache-thrift`，注册中心实现零 SDK 依赖（Consul / Nacos 走原生 HTTP API，组播走 JDK `DatagramChannel`）
- **零反射调用**：`@Export` / `@Reference` 由 JSR-269 注解处理器在**编译期**生成装配类与代理类，未走处理器时自动回退运行时动态代理
- **高性能**：`TThreadedSelectorServer` 线程池服务端 + HikariCP 风格连接池（`ConcurrentBag`），按服务隔离、borrow / requite 借还模型，支持动态伸缩
- **多后端注册发现**：`multicast`（默认，开箱即用）/ `consul`（TTL 续约）/ `nacos`（临时实例心跳）/ `direct`（点对点直连）
- **治理能力**：泳道标识（traffic）随实例元数据注册，支持灰度路由；每进程内置控制面 Thrift API（health / 运行信息 / 引用查询）
- **云原生**：数据面 / 控制面分离，SPI + 资源声明齐备，为 GraalVM Native Image 做准备

## 模块

| 模块 | 说明 |
| --- | --- |
| `autumn-api` | 注解、配置类、扩展点（SPI）、控制面 Thrift 定义 |
| `autumn-compiler` | JSR-269 注解处理器 + FreeMarker 模板（编译期生成代码） |
| `autumn-core` | 核心运行时：`AutumnBootstrap`、Provider / Consumer、连接池、控制面实现 |
| `autumn-registry-extension` | 注册中心适配层：基于 [autumn-cloud-extension](https://github.com/Percy0601/autumn-cloud-extension) 实现 `Registry` / `Discovery` SPI |
| `autumn-spring-boot` | Spring Boot Starter（自动装配 / 注解处理 / 生命周期） |
| `autumn-sample` | 示例工程：Thrift 接口 jar + 原生 API / Spring Boot 的 Provider & Consumer |

## 快速开始（Spring Boot）

**1. 引入依赖**

```xml
<dependency>
    <groupId>com.micro-app</groupId>
    <artifactId>autumn-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**2. 配置 `application.properties`**

```properties
spring.application.name=training-b
autumn.name=training-b
autumn.registry-type=multicast        # multicast / consul / nacos / direct
autumn.provider.port=30880
```

**3. 导出服务 & 注入引用**

```java
@Component
@Export
public class SomeServiceImpl implements SomeService.Iface { /* ... */ }

@Component
public class SomeConsumer {
    @Reference(refer = "training-b")          // ipPort="127.0.0.1:30880" 则直连
    private SomeService.Iface someService;
}
```

原生 Java API（无 Spring）用法、编译期代码生成配置、示例工程运行指南见 **[使用说明文档](docs/USER_GUIDE.md)**。

## 配置项汇总

Spring Boot 场景统一使用 `autumn.*` 前缀（由 `AutumnProperties` 绑定）；原生 API 场景使用下表"原生前缀"列的键（classpath `application.properties` 加载）。默认值两侧一致。

### 应用与注册中心（`autumn.*` ｜ 原生前缀 `application.*` / `registry.*`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `autumn.name` | 空（回退 `spring.application.name`） | 应用名，即注册中心服务名 |
| `autumn.registry-type` | `multicast` | 注册中心类型：`multicast` / `consul` / `nacos` / `direct` |
| `autumn.multicast.ip` | `224.0.0.1` | 组播地址（multicast 后端） |
| `autumn.multicast.port` | `5555` | 组播端口（multicast 后端） |
| `autumn.multicast.network-interface` | 自动选择 | 组播绑定网卡名，多网卡环境建议显式指定 |
| `autumn.traffic` | 空 | 泳道标识，写入实例元数据，供灰度 / 泳道路由 |
| `autumn.discovery-interval` | `5000` | 发现轮询间隔，毫秒（consul / nacos 后端） |

### Provider 服务端（`autumn.provider.*` ｜ 原生前缀 `provider.*`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `autumn.provider.enabled` | `true` | 是否启动 Thrift 服务端（纯消费端设为 `false`） |
| `autumn.provider.port` | `30880` | Thrift 服务端监听端口 |
| `autumn.provider.ip` | 自动探测 | 注册 IP；多网卡 / 容器环境建议显式配置 |
| `autumn.provider.min-threads` | `0` | Worker 线程池最小线程数 |
| `autumn.provider.max-threads` | `1` | Worker 线程池最大线程数 |
| `autumn.provider.worker-keep-alive-time` | `60` | Worker 空闲保活，秒 |
| `autumn.provider.thread-queue-size` | `10` | 工作队列容量 |
| `autumn.provider.timeout` | `3` | 服务处理超时，秒 |

### Consul（`registry-type=consul` 时生效，`autumn.consul.*` ｜ 原生前缀 `consul.*`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `autumn.consul.host` / `port` | `127.0.0.1` / `8500` | Consul Agent 地址与 HTTP 端口 |
| `autumn.consul.scheme` | `http` | `http` / `https` |
| `autumn.consul.token` | 空 | ACL Token（请求头 `X-Consul-Token`） |
| `autumn.consul.namespace` | 空 | Enterprise Namespace（请求头 `X-Consul-Namespace`，社区版忽略） |
| `autumn.consul.instance-id` | `{name}:{ip}:{port}` | 实例 ID |
| `autumn.consul.health-check-interval` | `10` | TTL 健康检查周期，秒（自动按 TTL/3 续约） |
| `autumn.consul.register` / `discovery` | `true` / `true` | 是否注册 / 是否启用发现 |

### Nacos（`registry-type=nacos` 时生效，`autumn.nacos.*` ｜ 原生前缀 `nacos.*`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `autumn.nacos.server-addr` | `127.0.0.1:8848` | Nacos OpenAPI 地址 |
| `autumn.nacos.namespace` | `public` | 命名空间 ID |
| `autumn.nacos.group` | `DEFAULT` | 分组名；与其他标准 Nacos 客户端互通时按需设为 `DEFAULT_GROUP` |
| `autumn.nacos.access-token` | 空 | 鉴权 Token（开启鉴权时必填） |
| `autumn.nacos.beat-interval` | `5000` | 临时实例心跳间隔，毫秒 |

> 编程式配置（`ReferenceConfig` 的 poolTimeout / socketTimeout / instances 等）与配置加载优先级详见 [使用说明文档](docs/USER_GUIDE.md#5-配置文档)。

## 构建

JDK 25+（source/target/release 与 JSR 269 处理器均已统一到 25），Maven 3.8+：

```bash
mvn clean install          # 全量构建 + 测试
mvn -DskipTests clean install
# GraalVM native image：先 install，再单独编译 sample provider
mvn -f autumn-sample/autumn-sample-boot-provider/pom.xml -Pnative native:compile
```

> native 编译使用 GraalVM 25 作为 `JAVA_HOME`（`native-image` 由 `native-maven-plugin` 调用）；
> Windows 下还需要 Visual Studio 2022 C++ 工具链（`cl.exe` / `link.exe` 在 `PATH`，`INCLUDE` / `LIB` 指向 MSVC 与 Windows SDK）。
> 自定义反射与资源元数据位于 `META-INF/native-image/com.micro-app/autumn-native-hints/`
> （Spring Boot AOT 会覆盖 `<groupId>/<artifactId>/` 同名配置，因此自定义 hints 放在独立目录并由 `native` profile 显式加载）。

> 涉及 SPI（`META-INF/services`）变更后务必 `clean`，避免 `target/classes` 残留旧装配文件。

## Roadmap

- [x] Spring Boot Starter、JSR-269 编译期代码生成、ConcurrentBag 连接池
- [x] 注册发现收敛至 autumn-cloud-extension（multicast / consul / nacos / direct）
- [ ] 控制面治理工具（服务列表、上下线开关、路由、devops CLI）
- [ ] `AutumnFilter` 过滤器链（鉴权、限流、链路透传）、熔断降级
- [ ] GraalVM Native Image 示例工程与 CI 验证
- [ ] module-info（JPMS）完善
