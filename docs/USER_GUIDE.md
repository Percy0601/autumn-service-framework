# Autumn Framework 使用说明文档

> 版本：1.0.0-SNAPSHOT ｜ 适用 JDK 21+ ｜ 更新日期：2026-11-03

本手册覆盖 Autumn Framework（秋实框架）的接入、运行机制与全部配置项，包含：

- [1. 概述](#1-概述)
- [2. 模块结构与架构](#2-模块结构与架构)
- [3. 快速开始](#3-快速开始)
- [4. 核心机制](#4-核心机制)
- [5. 配置文档](#5-配置文档)
- [6. 注册中心详解](#6-注册中心详解)
- [7. 连接池与调用模型](#7-连接池与调用模型)
- [8. 控制面与运维](#8-控制面与运维)
- [9. 示例工程运行指南](#9-示例工程运行指南)
- [10. 构建与发布](#10-构建与发布)
- [11. 常见问题](#11-常见问题)
- [12. 当前状态与 Roadmap](#12-当前状态与-roadmap)

---

## 1. 概述

Autumn Framework 是一个基于 **Apache Thrift** 的服务治理框架，定位是补齐 Thrift 生态的**控制面**能力（服务注册、服务发现、连接管理、健康检查、发布订阅），数据面仍由 Thrift 原生 RPC 承担。

核心设计：

- **数据面 / 控制面分离**：数据面 = Thrift RPC（`TBinaryProtocol` + `TMultiplexedProtocol` 多路复用），控制面 = 注册中心 + 连接池 + 生命周期管理。
- **两套接入 API**：
  - 原生 Java API（`autumn-core`，不依赖 Spring）
  - Spring Boot Starter（`autumn-spring-boot`，注解驱动）
- **零反射调用（JSR-269）**：`@Export` / `@Reference` 在编译期生成装配类与代理类，运行时通过 SPI 发现，Native Image 友好；未走注解处理器时自动回退运行时动态代理。

## 2. 模块结构与架构

| 模块 | 坐标 | 职责 |
| --- | --- | --- |
| `autumn-api` | `com.micro-app:autumn-api` | 注解、配置类、扩展点、工具类、控制面 Thrift 定义 |
| `autumn-compiler` | `com.micro-app:autumn-compiler` | JSR-269 注解处理器 + FreeMarker 模板（编译期生成代码） |
| `autumn-core` | `com.micro-app:autumn-core` | 运行时核心：`AutumnBootstrap`、Provider/Consumer、连接池、多播注册中心、控制面实现 |
| `autumn-registry-consul` | `com.micro-app:autumn-registry-consul` | Consul 注册中心实现 |
| `autumn-spring-boot-starter` | `com.micro-app:autumn-spring-boot-starter` | Spring Boot 自动装配 |
| `autumn-sample-api` | `com.micro-app:autumn-sample-api` | 示例 Thrift 接口 jar（`SomeService`） |

```
┌──────────────────────────────────────────────────────────────┐
│  Spring Boot Application         原生 Java Application        │
│  @Export / @Reference             ServiceConfig/ReferenceConfig│
├──────────────────────────────────────────────────────────────┤
│  接入层  AutumnBeanPostProcessor  AutumnBootstrap              │
├──────────────────────────────────────────────────────────────┤
│  控制面  Registry(注册) · Discovery(发现) · AutumnPool(连接池)  │
│          · 心跳/过期/健康检查 · 控制面API(devops)               │
├──────────────────────────────────────────────────────────────┤
│  注册中心实现  MulticastRegistry(默认) · ConsulRegistry         │
├──────────────────────────────────────────────────────────────┤
│  数据面  Apache Thrift (TSocket + TFramedTransport +          │
│          TBinaryProtocol + TMultiplexedProtocol)              │
└──────────────────────────────────────────────────────────────┘
```

## 3. 快速开始

### 3.1 Spring Boot Starter 方式（推荐）

**第 1 步：引入依赖**

```xml
<dependencies>
    <dependency>
        <groupId>com.micro-app</groupId>
        <artifactId>autumn-spring-boot-starter</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
    <!-- 业务 Thrift 接口 jar（含 thrift 生成的 Iface/Processor/Client） -->
    <dependency>
        <groupId>com.micro-app</groupId>
        <artifactId>autumn-sample-api</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

**第 2 步：配置 `application.properties`**

```properties
spring.application.name=training-b
autumn.name=training-b
autumn.registry-type=multicast
autumn.multicast.ip=224.0.0.1
autumn.multicast.port=5555
autumn.provider.enabled=true
autumn.provider.port=30880
```

> 说明：`autumn.name` 与 `spring.application.name` 建议保持一致，作为注册中心的服务名。

**第 3 步：导出服务（Provider）**

```java
@Component
@Export
public class SomeServiceImpl implements SomeService.Iface {

    @Override
    public String echo(String msg) throws TException {
        return "Hello, " + msg;
    }
}
```

**第 4 步：注入引用（Consumer）**

```java
@Component
public class SomeConsumer {

    @Reference(refer = "training-b")
    private SomeService.Iface someService;

    public String call(String msg) throws TException {
        return someService.echo(msg);
    }
}
```

**第 5 步：开启编译期代码生成（可选，推荐）**

在 `pom.xml` 的 `maven-compiler-plugin` 中声明注解处理器：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
            </path>
            <path>
                <groupId>com.micro-app</groupId>
                <artifactId>autumn-compiler</artifactId>
                <version>1.0.0-SNAPSHOT</version>
            </path>
            <path>
                <groupId>com.micro-app</groupId>
                <artifactId>autumn-api</artifactId>
                <version>1.0.0-SNAPSHOT</version>
            </path>
            <path>
                <groupId>org.freemarker</groupId>
                <artifactId>freemarker</artifactId>
                <version>2.3.34</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

> 注意：`autumn-compiler` 处理器在运行时需要加载 `autumn-api` 中的注解类以及 `freemarker` 模板引擎，因此 `annotationProcessorPaths` 中必须同时声明 `autumn-compiler`、`autumn-api`、`freemarker`，否则编译报 `NoClassDefFoundError`（如 `com/microapp/autumn/api/annotation/Reference`、`freemarker/template/Configuration`）。

### 3.2 原生 Java API 方式

不依赖 Spring，直接使用 `autumn-core`。

**Provider**

```java
import com.microapp.autumn.api.config.ServiceConfig;
import com.microapp.autumn.api.extension.AttachableProcessor;
import com.microapp.autumn.core.AutumnBootstrap;

public class ProviderMain {
    public static void main(String[] args) {
        ServiceConfig<SomeService.Iface> cfg = new ServiceConfig<>();
        cfg.setInterfaceClass(SomeService.Iface.class);
        cfg.setRef(new AttachableProcessor(new SomeService.Processor<>(new SomeServiceImpl())));
        AutumnBootstrap.getInstance().service(cfg).serve();
    }
}
```

**Consumer**

```java
import com.microapp.autumn.api.config.ReferenceConfig;
import com.microapp.autumn.api.enums.RegistryTypeEnum;
import com.microapp.autumn.core.pool.AutumnPool;
import com.microapp.autumn.core.pool.impl.ConcurrentBagEntry;
import com.microapp.autumn.core.server.AutumnConsumer;

public class ConsumerMain {
    public static void main(String[] args) throws Exception {
        // 启动控制面（注册中心心跳 / 发现 / 健康检查）
        AutumnBootstrap.getInstance().serve();

        ReferenceConfig<SomeService.Client> rc = new ReferenceConfig<>();
        rc.setName("training-a");
        rc.setInterfaceClass(SomeService.Client.class);
        rc.setRegistryTypeEnum(RegistryTypeEnum.MULTICAST);
        rc.setPoolTimeout(10000L);
        rc.setSocketTimeout(3000L);
        AutumnConsumer.provider().reference(rc);

        // 调用
        ConcurrentBagEntry entry = AutumnPool.getInstance().borrow("training-a");
        if (entry == null) {
            throw new IllegalStateException("no available instance, service: training-a");
        }
        try {
            SomeService.Iface client = new SomeService.Client(
                    new TMultiplexedProtocol(new TBinaryProtocol(entry.getEntry()),
                            SomeService.Iface.class.getName()));
            System.out.println(client.echo("autumn"));
        } finally {
            AutumnPool.getInstance().requite(entry);
        }
    }
}
```

## 4. 核心机制

### 4.1 注解 `@Export` / `@Reference`

| 注解 | 目标 | 作用 |
| --- | --- | --- |
| `@Export` | 类 | 将 Bean 导出为 Thrift 服务（自动匹配实现的 `Xxx.Iface` 接口） |
| `@Reference` | 字段 / 方法 | 注入远程服务代理，`refer` 指定注册中心服务名，`ipPort` 指定直连地址 |

`@Reference` 参数：

```java
@Reference(refer = "training-b", ipPort = "127.0.0.1:30880")
```

- `refer`：注册中心里的服务名，缺省用接口名
- `ipPort`：非空时走 **DIRECT 直连**（`ip:port`），跳过注册中心

### 4.2 三种注册中心模式（registry-type）

| 值 | 注册实现 | 发现实现 | 适用场景 |
| --- | --- | --- | --- |
| `multicast`（默认） | `MulticastRegistry` | `MulticastDiscovery` | 局域网 / 单机联调，无需外部依赖 |
| `consul` | `ConsulRegistry` | `ConsulDiscovery` | 多机 / 生产，需要引入 `autumn-registry-consul` |
| `direct` | — | 直连 `ReferenceConfig.instances` | 单点直连，`@Reference(ipPort)` |

> 注册中心实现通过 SPI（`META-INF/services/com.microapp.autumn.api.Registry` / `Discovery`）加载，多实现并存时取 ServiceLoader 返回的第一个。

### 4.3 生命周期

`AutumnBootstrap.serve()` 按序执行：

1. 启动 Thrift Provider 服务端（`TThreadedSelectorServer` + Worker 线程池）
2. 注册自己到注册中心（multicast 报文 / consul agent）
3. 启动发现：订阅 watch 的服务（多播监听 / consul 轮询）
4. 启动健康检查与实例过期清理
5. 注册 JVM ShutdownHook（注销注册中心实例）

Spring Boot 场景由 `AutumnLifecycle` 在上下文启动后 / 关闭前自动调用 `serve()` / `close()`。

### 4.4 编译期代码生成（JSR-269）

启用注解处理器后，编译期生成两类零反射代码（可通过 `target/generated-sources` 查看）：

**`@Export` → `_<ImplSimpleName>ServiceRegister`**

```java
public final class _SomeServiceImplServiceRegister implements AutumnServiceAssembler {
    public boolean supports(Class<?> implClass) {
        return implClass == SomeServiceImpl.class;
    }
    public ServiceConfig<?> build(Object impl) {
        ServiceConfig<SomeService.Iface> cfg = new ServiceConfig<>();
        cfg.setInterfaceClass(SomeService.Iface.class);
        cfg.setRef(new AttachableProcessor(new SomeService.Processor<>((SomeService.Iface) impl)));
        return cfg;
    }
}
```

并自动在 `META-INF/services/com.microapp.autumn.api.extension.AutumnServiceAssembler` 注册。Spring Boot 的 `AutumnBeanPostProcessor` 优先通过 `ServiceLoader` 使用这些装配器导出服务，未生成时回退反射。

**`@Reference` → `_<OuterSimpleName>Proxy`**

```java
public final class _SomeServiceProxy implements SomeService.Iface {
    private final String service;
    public _SomeServiceProxy(String service) { this.service = service; }

    @Override
    public String echo(String msg) throws TException {
        ConcurrentBagEntry entry = null;
        try {
            entry = AutumnPool.getInstance().borrow(service);
            if (entry == null) {
                throw new AutumnException("autumn reference no available instance, service: " + service);
            }
            SomeService.Client client = new SomeService.Client(
                    new TMultiplexedProtocol(new TBinaryProtocol(entry.getEntry()),
                            SomeService.Iface.class.getName()));
            return client.echo(msg);
        } finally {
            if (entry != null) {
                AutumnPool.getInstance().requite(entry);
            }
        }
    }
}
```

生成类与 thrift 接口同包，可直接 `new _SomeServiceProxy("training-b")` 使用，也可等待后续版本接入 Spring 注入。

## 5. 配置文档

### 5.1 配置来源与加载优先级

原生 API 的配置加载顺序（`ApplicationConfig` / `ProviderConfig`）：

1. 系统属性 `spring.application.name`（作为应用名）
2. classpath 下的 `{spring.application.name}.properties`
3. classpath 下的 `application.properties`
4. classpath 未找到时回退目录 `/opt/autumn/{文件名}.properties`

Spring Boot 场景：`autumn.*` 前缀配置由 `AutumnProperties` 绑定，`AutumnBeanPostProcessor` 在启动时将其映射为原生配置项（`application.*` / `provider.*` / `consul.*`）并注入，优先于 classpath 配置文件。

### 5.2 全部配置项总表

#### 应用级（原生前缀 `application.*`，Spring Boot 前缀 `autumn.*`）

| 原生配置项 | Spring Boot 配置项 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `application.name` / `spring.application.name` | `autumn.name` | 空 | 应用名，注册中心服务名 |
| `application.registry-type` | `autumn.registry-type` | `multicast` | 注册中心类型：`multicast` / `consul` / `direct` |
| `application.multicast.ip` | `autumn.multicast.ip` | `224.0.0.1` | 多播组播地址 |
| `application.multicast.port` | `autumn.multicast.port` | `5555` | 多播端口 |

#### Provider 级（原生前缀 `provider.*`，Spring Boot 前缀 `autumn.provider.*`）

| 原生配置项 | Spring Boot 配置项 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `provider.enabled` | `autumn.provider.enabled` | `true` | 是否启动 Thrift 服务端 |
| `provider.port` | `autumn.provider.port` | `30880` | 服务端监听端口 |
| `provider.min-threads` | `autumn.provider.min-threads` | `0` | Worker 线程池最小线程数 |
| `provider.max-threads` | `autumn.provider.max-threads` | `1` | Worker 线程池最大线程数 |
| `provider.worker-keep-alive-time` | `autumn.provider.worker-keep-alive-time` | `60` | 工作线程空闲保活（秒） |
| `provider.thread-queue-size` | `autumn.provider.thread-queue-size` | `10` | 工作队列容量 |
| `provider.timeout` | `autumn.provider.timeout` | `3` | 服务处理超时（秒） |
| `provider.ip` | `autumn.provider.ip` | 自动探测 | 服务绑定/注册 IP；多网卡或探测异常时建议显式配置 |

> IP 自动探测逻辑：优先匹配网卡名含 `Intel` / `Realtek` / `Atheros` / `Broadcom` 的网卡；匹配不到时回退到第一个已启用、非回环、非虚拟且有 IPv4 的网卡；仍失败则回退 `InetAddress.getLocalHost()`。

#### Consul 级（原生前缀 `consul.*`，Spring Boot 前缀 `autumn.consul.*`）

| 原生配置项 | Spring Boot 配置项 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `consul.register` | `autumn.consul.register` | `true` | 是否注册到 Consul |
| `consul.discovery` | `autumn.consul.discovery` | `true` | 是否启用 Consul 发现 |
| `consul.instance-id` | `autumn.consul.instance-id` | `{name}:{ip}:{port}` | 实例 ID |
| `consul.health-check-interval` | `autumn.consul.health-check-interval` | `10` | 健康检查间隔（秒，注册时写入 TCP Check） |
| `consul.host` | `autumn.consul.host` | `127.0.0.1` | Consul Agent 地址 |
| `consul.port` | `autumn.consul.port` | `8500` | Consul Agent HTTP 端口 |
| `consul.namespace` | `autumn.consul.namespace` | 空 | Consul Namespace（通过 `X-Consul-Namespace` 请求头透传） |

> 使用 Consul 需额外引入：

```xml
<dependency>
    <groupId>com.micro-app</groupId>
    <artifactId>autumn-registry-consul</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

#### 编程配置（`ReferenceConfig`）

| 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `setName(name)` | — | 引用服务名（注册中心服务名） |
| `setInterfaceClass(Iface/Client)` | — | 接口类（thrift 生成的 Client 或 Iface） |
| `setRegistryTypeEnum(...)` | — | `MULTICAST` / `CONSUL` / `DIRECT` |
| `setInstances(List<ConsumerConfig>)` | — | 直连实例列表（DIRECT 模式） |
| `setPoolTimeout(ms)` | `1000` | 连接池借用超时（Spring Boot 注入默认 `10000`） |
| `setSocketTimeout(ms)` | — | Socket 读超时（Spring Boot 注入默认 `3000`） |
| `setConnectionTimeout(ms)` | — | 连接建立超时 |
| `setConverter(Function)` | — | 协议到 Client 的转换器 |

### 5.3 完整示例配置

**Spring Boot Provider（`application.properties`）**

```properties
spring.application.name=training-b
server.port=8080

# autumn
autumn.name=training-b
autumn.registry-type=multicast
autumn.multicast.ip=224.0.0.1
autumn.multicast.port=5555
autumn.provider.enabled=true
autumn.provider.port=30880
autumn.provider.max-threads=8
autumn.provider.thread-queue-size=64
autumn.provider.timeout=3
```

**Spring Boot Consumer（`application.properties`）**

```properties
spring.application.name=consumer
server.port=8081

autumn.name=consumer
autumn.registry-type=multicast
autumn.multicast.ip=224.0.0.1
autumn.multicast.port=5555
autumn.provider.enabled=false
```

**Consul 模式 Provider（`application.properties`）**

```properties
spring.application.name=training-consul
autumn.name=training-consul
autumn.registry-type=consul
autumn.consul.host=127.0.0.1
autumn.consul.port=8500
autumn.provider.port=30880
```

**原生 API 配置（`application.properties`）**

```properties
application.name=training-a
application.registry-type=multicast
application.multicast.ip=224.0.0.1
application.multicast.port=5555
provider.enabled=true
provider.port=30880
```

## 6. 注册中心详解

### 6.1 多播注册中心（默认）

- 组播地址 `224.0.0.1:5555`，无外部依赖，单机/局域网联调开箱即用。
- **报文格式**：
  - 注册：`registry://{appName}?ip={ip}&port={port}&references={a,b,c}`
  - 订阅：`subscribe://{appName}`
  - 退订：`unsubscribe://{appName}`
- **心跳**：每 5s 重发注册报文，续期实例。
- **过期**：实例 10s 未续期则标记不可用；`ConsumerConfig.version` 累计 3 个版本未更新则删除实例。
- **健康检查**：每 5s 轮询已连接实例，TCP 连接失败累计 `checkFail`，超限则移除。
- **边界**：多播受限于同一网段/广播域，跨网段请使用 Consul。

### 6.2 Consul 注册中心

引入 `autumn-registry-consul` 并将 `registry-type` 设为 `consul` 后：

- **注册**：`PUT /v1/agent/service/register`，请求体含 TCP 健康检查（`Check.TCP = {ip}:{port}`，Interval 10s，Timeout 5s，`DeregisterCriticalServiceAfter` 10m），`replace-existing-check=true`。
- **注销**：JVM ShutdownHook 调 `DELETE /v1/agent/service/deregister/{instanceId}`。
- **发现**：`GET /v1/health/service/{name}?passing=true` 每 5s 轮询一次，只保留通过健康检查的实例到本地缓存。
- **健康检查**：由 Consul Agent 承担 TCP 检查，`passing=true` 过滤天然保证可用性，本地无需二次轮询。

> 提前启动本地 Consul：`consul agent -dev`（默认 127.0.0.1:8500）。

## 7. 连接池与调用模型

- 每个被引用的服务对应一个 `ConcurrentBag`（HikariCP 风格的连接袋），由 `AutumnPool` 统一管理。
- **连接构成**：`TSocket` + `TFramedTransport` + `TBinaryProtocol` + `TMultiplexedProtocol`（以接口全名路由到服务端 multiplexed processor）。
- **借用/归还**：`borrow(service)` 带超时（默认 1s，可用 `ReferenceConfig.setPoolTimeout` 调整），用完必须 `requite(entry)` 归还；`borrow` 返回 `null` 表示无可用实例（未注册或全部耗尽）。
- **动态伸缩**：`borrow` 时若活跃连接达到上限，会向 `Discovery.getInstances(name)` 拉取实例并扩容建连；实例消失时通过健康检查回收。
- **容量**：单实例最大活跃连接 500，`ConsumerConfig.connections` 默认 10。
- **直连模式**：`@Reference(ipPort)` 或 `ReferenceConfig.instances` 指定固定实例，不走注册中心发现。

## 8. 控制面与运维

每个 Provider 进程内置一个控制面 Thrift 服务（`ControlApi.Iface`，`TThreadedSelectorServer` 提供），注册名即接口全名，方法：

| 方法 | 说明 |
| --- | --- |
| `health()` | 进程健康状态 |
| `applicationInfo()` | 应用信息（名称、JVM、架构、注册中心类型） |
| `providerInfo()` | Provider 运行信息（端口、线程池、绑定 IP） |
| `referenceConfig()` | 当前引用配置列表 |
| `check()` | 快速存活检查 |

后续版本规划基于控制面提供治理工具（服务列表、调用链、上下线开关）。

## 9. 示例工程运行指南

| 模块 | 端口 | 角色 |
| --- | --- | --- |
| `autumn-sample-boot-provider` | HTTP 8080 / Thrift 30880 | Spring Boot Provider（`autumn.name=training-b`） |
| `autumn-sample-boot-consumer` | HTTP 8081 | Spring Boot Consumer（`@Reference(refer="training-b")`） |
| `autumn-sample-basic-provider` | Thrift 30880 | 原生 Java Provider（测试类驱动） |
| `autumn-sample-basic-consumer` | — | 原生 Java Consumer（测试类驱动） |

**Spring Boot 联调**

```bash
# 终端 1：启动 Provider
cd autumn-sample/autumn-sample-boot-provider
mvn spring-boot:run

# 终端 2：启动 Consumer
cd autumn-sample/autumn-sample-boot-consumer
mvn spring-boot:run

# 触发一次 RPC（Consumer 的 HTTP 接口）
curl 'http://localhost:8081/boot-sample-consumer/hello?name=autumn'
# 期望返回：Hello, autumn
```

**原生 API 联调（JUnit 测试）**

- `autumn-sample-basic-provider`：`TrainingProviderTest`（注册并启动 Provider）
- `autumn-sample-basic-consumer`：`TrainingConsumerTest`（引用并调用）、`MulticastDiscoveryTest`（多播发现）

> 多播模式同一台机器可直接互通；跨机器需确保组播可达，或改用 Consul。

## 10. 构建与发布

```bash
# 全量构建（跳过测试）
mvn -DskipTests clean install

# 仅编译
mvn -DskipTests compile

# 跑全部测试（含编译期生成链路集成测试）
mvn test
```

- JDK 21+、Maven 3.8+
- 模块间通过 reactor 构建，`annotationProcessorPaths` 中的 SNAPSHOT 坐标会优先从 reactor 解析

## 11. 常见问题

**Q1：Provider 注册的 IP 不对（如 127.0.0.1 / 容器内网 IP）？**

显式配置 `provider.ip`（或 `autumn.provider.ip`）为对外可达地址。IP 探测顺序：网卡名匹配（Intel/Realtek/Atheros/Broadcom）→ 第一个启用非回环网卡 → localhost。

**Q2：编译报 `NoClassDefFoundError`（如 `com/microapp/autumn/api/annotation/Reference`、`freemarker/template/Configuration`）？**

`maven-compiler-plugin` 的 `annotationProcessorPaths` 中必须同时声明 `autumn-compiler`、`autumn-api` 与 `freemarker`（处理器运行时需要加载注解类与模板引擎），见 [3.1 第 5 步](#31-spring-boot-starter-方式推荐)。

**Q3：Consumer 调用返回"no available instance"？**

- 检查 Provider 是否已启动（`provider.enabled=true`、端口未被占用）
- 多播模式下两端 `multicast.ip/port` 一致、同一网段
- Consul 模式下 `consul.host/port` 可达，且 Consul 中服务为 passing 状态（`curl http://127.0.0.1:8500/v1/health/service/{name}?passing=true`）
- `@Reference(refer)` 与 Provider 的 `autumn.name` 一致

**Q4：Consul 模式注册失败？**

确认 Consul Agent 已启动（`consul agent -dev`）；查看日志中 `consul register result`；`consul.register=false` 可关闭注册。

**Q5：能否用 Native Image？**

框架调用面已零反射（JSR-269 生成代码 + SPI），但 Native Image 全链路（含 Spring AOT、反射配置收集）仍在验证中，暂不用于生产。

## 12. 当前状态与 Roadmap

### 已完成

- [x] Spring Boot Starter 接入（自动装配 / 注解处理 / 生命周期）
- [x] Consul 注册中心（注册 / 注销 / 发现 / 健康过滤）
- [x] JSR-269 编译期代码生成链路（`_XxxServiceRegister` / `_XxxProxy` + SPI）
- [x] 多播注册中心（心跳 / 过期 / 健康检查 / 发布订阅）
- [x] 连接池（ConcurrentBag）与控制面 API

### 规划中

- [ ] `AutumnFilter` 过滤器链接入（鉴权、限流、链路透传）
- [ ] 控制面治理工具（devops CLI / 控制台：服务列表、上下线、路由）
- [ ] `ConsumerConfig.checkFail` 熔断与优雅降级
- [ ] 直连 / Consul 模式下 `@Reference` 的配置增强
- [ ] GraalVM Native Image 示例工程与 CI 验证
- [ ] module-info（JPMS）完善与 `provides` 声明
