# Autumn Framework 使用说明文档

> 版本：1.0.0-SNAPSHOT ｜ 适用 JDK 25+ ｜ 更新日期：2026-11-06

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
| `autumn-api` | `xyz.we-win.autumn:autumn-api` | 注解、配置类、工具类、控制面 Thrift 定义 |
| `autumn-protocol-extension` | `xyz.we-win.autumn:autumn-protocol-extension` | 协议扩展：隐式传参（field-0 attachment）、`TrafficRouter` 泳道路由、SPI 扩展点 |
| `autumn-compiler` | `xyz.we-win.autumn:autumn-compiler` | JSR-269 注解处理器 + FreeMarker 模板（编译期生成代码） |
| `autumn-core` | `xyz.we-win.autumn:autumn-core` | 运行时核心：`AutumnBootstrap`、Provider/Consumer、连接池、控制面实现 |
| `autumn-registry-extension` | `xyz.we-win.autumn:autumn-registry-extension` | 注册中心适配层：基于 `autumn-cloud-extension` 的 `ServiceRegistryClient`/`ServiceDiscoveryClient` 实现 `Registry`/`Discovery` SPI，支持 multicast / consul / nacos |
| `autumn-spring-boot-starter` | `xyz.we-win.autumn:autumn-spring-boot-starter` | Spring Boot 自动装配 |
| `autumn-sample-api` | `xyz.we-win.autumn:autumn-sample-api` | 示例 Thrift 接口 jar（`SomeService`） |

> 旧的 `autumn-registry-consul` 模块与 `autumn-core` 中的 `MulticastRegistry` 自研实现已删除，注册发现统一收敛到 [autumn-cloud-extension](https://github.com/Percy0601/autumn-cloud-extension)（纯 Java、零 Spring 依赖、直连 HTTP/组播实现，GraalVM Native 友好）。

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
│  注册中心适配  autumn-registry-extension (SPI: Registry/Discovery)  │
│  后端  multicast(默认) · consul · nacos  ← autumn-cloud-extension   │
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
        <groupId>xyz.we-win.autumn</groupId>
        <artifactId>autumn-spring-boot-starter</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
    <!-- 业务 Thrift 接口 jar（含 thrift 生成的 Iface/Processor/Client） -->
    <dependency>
        <groupId>xyz.we-win.autumn</groupId>
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
                <groupId>xyz.we-win.autumn</groupId>
                <artifactId>autumn-compiler</artifactId>
                <version>1.0.0-SNAPSHOT</version>
            </path>
            <path>
                <groupId>xyz.we-win.autumn</groupId>
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
import xyz.wewin.autumn.api.config.ServiceConfig;
import xyz.wewin.autumn.api.extension.AttachableProcessor;
import xyz.wewin.autumn.core.AutumnBootstrap;

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
import xyz.wewin.autumn.api.config.ReferenceConfig;
import xyz.wewin.autumn.api.enums.RegistryTypeEnum;
import xyz.wewin.autumn.core.pool.AutumnPool;
import xyz.wewin.autumn.core.pool.impl.ConcurrentBagEntry;
import xyz.wewin.autumn.core.server.AutumnConsumer;

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

### 4.2 四种注册中心模式（registry-type）

| 值 | 后端 | 适用场景 |
| --- | --- | --- |
| `multicast`（默认） | `MulticastBackend`（extension 组播） | 局域网 / 单机联调，无需外部依赖 |
| `consul` | `ConsulBackend`（extension HTTP 直连 Agent） | 多机 / 生产，TTL 健康检查 + 自动续约 |
| `nacos` | `NacosBackend`（extension OpenAPI 直连） | 多机 / 生产，临时实例心跳保活 |
| `direct` | —（不注册） | 直连 `ReferenceConfig.instances` / `@Reference(ipPort)` |

> 注册中心实现通过 SPI（`META-INF/services/xyz.wewin.autumn.api.Registry` / `Discovery`，由 `autumn-registry-extension` 提供）加载，`RegistryBackends` 按 `application.registry-type` 选择后端，进程内单例。三种后端均由 `autumn-cloud-extension-common` 提供，零 SDK 依赖（Consul/Nacos 走原生 HTTP API），GraalVM Native Image 友好。

### 4.3 生命周期

`AutumnBootstrap.serve()` 按序执行：

1. 启动 Thrift Provider 服务端（`TThreadedSelectorServer` + Worker 线程池）
2. 注册自己到注册中心（multicast 广播 / consul TTL / nacos 心跳）
3. 启动发现：订阅 watch 的服务（组播监听 / 注册中心轮询）
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

并自动在 `META-INF/services/xyz.wewin.autumn.api.extension.AutumnServiceAssembler` 注册。Spring Boot 的 `AutumnBeanPostProcessor` 优先通过 `ServiceLoader` 使用这些装配器导出服务，未生成时回退反射。

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
                    new TMultiplexedProtocol(new AttachableBinaryProtocol(entry.getEntry()),
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

生成类与 thrift 接口同包，Spring 注入路径会优先使用它（`AutumnBeanPostProcessor#createGeneratedProxy` 通过 `Class.forName` 取得并 `new _XxxProxy(service)`），也可直接 `new _SomeServiceProxy("training-b")` 使用。

#### Native Image 反射元数据（编译期生成 + AOT）

注解处理器除了生成上面两类类，还会把 native-image 需要的元数据在**编译期**直接写进
`target/classes/META-INF/native-image/xyz.we-win.autumn/autumn-{export,reference}-hints/`：

| 目录 | 内容 | 使用方 |
| --- | --- | --- |
| `autumn-export-hints/reflect-config.json` | 每个 `_XxxServiceRegister`（ServiceLoader 实例化）、`Xxx$Processor`（反射兜底） | `AutumnBeanPostProcessor#exportService` |
| `autumn-reference-hints/reflect-config.json` | `_XxxProxy`（`Class.forName` + `getConstructor(String)`）、`Xxx$Client`（JDK 代理兜底）、`@Reference` 字段（`allowWrite: true`）/ setter 方法 | `AutumnBeanPostProcessor#injectReference` / `#createProxy` |
| `autumn-reference-hints/proxy-config.json` | `Xxx$Iface` 动态代理接口组 | `Proxy.newProxyInstance` 兜底路径 |

要点：

- 这些 json 随模块 jar 打包，native-image 构建时自动扫描 classpath 合并，**不需要** `-H:ReflectionConfigurationResources` 之类的手工加载参数，也不需要在业务工程里维护反射清单。
- 反射元数据里的类名必须是**二进制名**（`SomeService$Iface`）；处理器用 `Elements#getBinaryName` 生成，避免写出源码形态 `SomeService.Iface` 而注册不到。
- `@Reference` 字段必须带 `allowWrite`，因为注入走 `field.setAccessible(true) + field.set(...)`。
- 同一个 `Iface` 被多处 `@Reference` 引用时，每个注入点的字段都会登记（`Iface` 维度的去重只影响代码生成，不影响元数据）。
- 处理器覆盖不到的部分在示例工程里这样处理（构建期产生，不落 json）：
  - 嵌入式 Tomcat：`TomcatWebServer#disableBindOnInit` 会反射调用 `AbstractProtocol#getProperty`，
    而 `tomcat-embed-core` 10.1.x 在 GraalVM reachability metadata 仓库里只能回落到 10.0.x 配置 →
    两个示例工程共用 `autumn-sample-boot-native-hints` 模块的 `TomcatNativeHints implements RuntimeHintsRegistrar`
    + `@ImportRuntimeHints`，声明这批 `allPublicMethods` / `allDeclaredConstructors` 类型与 `LocalStrings`
    资源包，Spring AOT 在 `process-aot` 阶段把它们并进 `<groupId>/<artifactId>/` 下的元数据。
  - 应用自己的 POJO 交给 Jackson 序列化：用 `@RegisterReflectionForBinding(User.class)`
    （见 `BootSampleBootstrap`），不要回到手写 json。
  - 注册中心：框架自带的 `AutumnRuntimeHints` 已登记 multicast 心跳帧的 Jackson record
    （`com.autumn.cloud.extension.core.multicast.MulticastDiscovery$Frame`、`InstanceRegistration`、
    `InstanceSnapshot`）。record 的 canonical 构造器是包级可见，必须用
    `INVOKE_DECLARED_CONSTRUCTORS`；缺注册时 native 下心跳帧能被发出但**全部反序列化失败**，
    表现为「实例注册成功、对方永远发现不到」。
- 结论：两个示例工程（provider / consumer）的 `src/main/resources/META-INF/native-image/` 都已清空，
  native 镜像只依赖「注解处理器生成的 hints + Spring AOT hints」。
  实测 provider 启动 0.095s，`/hello`（thrift 自环）、`/greet`、`/listUserIds`、`/getUser`
  （Jackson 序列化 POJO）全部 200；consumer 启动 0.088s，经 multicast 发现 provider 后
  `/boot-sample-consumer/hello` 返回 `Hello, 213 [node:30880] [tenant:consul-smoke]`，
  即 `@Reference`、生成代理与隐式传参在纯 native 环境下均可用。
- 排查手法：native 运行时报 `MissingReflectionRegistrationError` 时，异常本身会打印可直接粘贴的 json 片段，按上面三类归属补到对应位置即可；若日志里出现 `this appears to be a native image ... configure reflection for the class that is to be deserialized`，则是 Jackson 反序列化目标类缺构造器注册。

### 4.5 隐式传参（attachment）

框架在 thrift 请求消息头之后追加 `field-0` 的 `map<string,string>`，业务方法签名无需改动。生成代理（`_XxxProxy`）、Spring 运行时回退代理与控制面探测均已接入 `AttachableBinaryProtocol`。

```java
// 调用方：同一线程内设置，随 RPC 自动透传
AttachmentContext.put("tenant", "acme");
String result = someService.echo("hello");
AttachmentContext.clear();

// 服务端业务实现：读取隐式参数
String tenant = AttachmentContext.get("tenant");
Map<String, String> all = AttachmentContext.snapshot();
```

- 默认附带 `appId=15`、`appName=training-thrift` 与随机 `traceId` / `spanId`；调用方可覆盖同名键。
- 服务端由 `AttachableProcessor` 在请求结束时清理线程上下文，线程池复用不会串扰。
- **双向兼容**：旧客户端（`TBinaryProtocol`，无 field-0）可调用新服务端；新客户端可调用旧服务端（生成代码会跳过未知字段）。响应方向不写 attachment，不会覆盖调用方上下文。
- **GraalVM native**：读取侧用 `readFieldBegin()` 顺带解析 field-0，不再反射访问 `TFramedTransport` / `TIOStreamTransport` 私有字段，无需额外反射 hint。
- 自动化用例见 `autumn-core/src/test/java/com/microapp/autumn/core/extension/ImplicitAttachmentTest.java`（协议往返、双向兼容、响应 field-0、真实 TCP + multiplexed processor 透传）。

### 4.6 泳道路由（按隐式参数筛选注册中心实例）

隐式参数不只是透传业务上下文，还直接驱动服务发现。语义与 `autumn-cloud-extension` 的
`TrafficSelector` 对齐：实例元数据 `traffic` 为泳道标签，未打标即基线泳道 `main`。

```java
// 入口（HTTP 网关 / 测试 / 业务代码）：本次调用走 gray 泳道
AttachmentContext.put("traffic", "gray");
try {
    return someService.echo("hello");
} finally {
    AttachmentContext.clear();
}
```

- **筛选规则**：优先精确命中同泳道实例；该泳道没有实例时回退 `main`，基线也不存在才回退全量，
  避免泳道误配导致无实例可用。基线请求只走基线实例，不会被灰度实例稀释。
  `application.traffic-fallback=false` 可关闭回退（找不到同泳道实例即无可用实例）。
- **全链路**：`traffic` 随 `field-0` 透传到服务端，服务端继续调用下游时 `AttachmentContext` 已被
  替换为同一份隐式参数，自动沿用同一泳道，无需业务代码重复设置。
- **任意标签**：`AttachmentContext.put("label.region", "cn")` 会要求实例元数据 `region=cn`，多个标签
  为 AND 关系；标签全部不匹配时回退到泳道候选集。自定义标签由 provider 侧
  `application.labels.<key>` / `autumn.labels.<key>` 写入注册元数据。
- **参数名**：默认识别 `traffic` 与 `X-Traffic`（大小写不敏感），可用
  `application.traffic-parameter` / `autumn.traffic-parameter` 改成任意逗号分隔的键名列表。
- **连接池一致性**：`ConcurrentBag` 借用连接时会跳过非本次候选实例的空闲连接（结束后原样放回队列），
  扩容只针对候选实例建连；池被其它泳道空闲连接占满时优先释放它们腾出名额，避免连接复用把流量打错泳道。
- **静态默认泳道**：未带隐式参数时回退到应用级 `application.traffic` / `autumn.traffic`；
  两者都没有就是基线 `main`。注意这与旧版"同泳道 + 基线混合返回"不同，现在按调用参数严格分流。
- 示例入口：`GET /boot-sample-consumer/hello?traffic=gray`。自动化用例见
  `TrafficRouterTest`、`TrafficRoutingBagTest`、`ExtensionRegistryDiscoveryTest`。

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
| `application.registry-type` | `autumn.registry-type` | `multicast` | 注册中心类型：`multicast` / `consul` / `nacos` / `direct` |
| `application.multicast.ip` | `autumn.multicast.ip` | `224.0.0.1` | 多播组播地址 |
| `application.multicast.port` | `autumn.multicast.port` | `5555` | 多播端口 |
| `application.multicast.network-interface` | `autumn.multicast.network-interface` | 自动选择 | 多播绑定的网卡名（多网卡环境显式指定） |
| `application.traffic` | `autumn.traffic` | 空 | 泳道标识，写入实例元数据 `traffic`；无隐式参数时作为应用默认泳道 |
| `application.traffic-parameter` | `autumn.traffic-parameter` | `traffic,X-Traffic` | 承载泳道的隐式参数名（逗号分隔，按优先级） |
| `application.traffic-fallback` | `autumn.traffic-fallback` | `true` | 同泳道无实例时是否回退基线泳道 `main` |
| `application.labels.<key>` | `autumn.labels.<key>` | 空 | 注册时附加的实例标签，供 `label.<key>` 隐式参数筛选 |
| `registry.discovery-interval` | `autumn.discovery-interval` | `5000` | 发现轮询间隔（毫秒，consul/nacos 后端） |

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

#### Consumer 级（Spring Boot 前缀 `autumn.consumer.*`）

| Spring Boot 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `autumn.consumer.pool-timeout` | `10000` | 连接池借用超时（毫秒） |
| `autumn.consumer.connection-timeout` | `1000` | TCP 建连超时（毫秒） |
| `autumn.consumer.socket-timeout` | `3000` | 读响应超时（毫秒） |

> 原生 API 通过 `ReferenceConfig` 设置同样的三项，未显式配置时默认借用 `1s` / 建连 `1s` / 读响应 `3s`。
> **建连（TCP connect）耗时计入借用超时预算**：客户端先启动、服务端尚未监听时，调用会在借用超时内失败返回，不会卡在建连上。

#### Consul 级（原生前缀 `consul.*`，Spring Boot 前缀 `autumn.consul.*`）

| 原生配置项 | Spring Boot 配置项 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `consul.register` | `autumn.consul.register` | `true` | 是否注册到 Consul |
| `consul.discovery` | `autumn.consul.discovery` | `true` | 是否启用 Consul 发现 |
| `consul.instance-id` | `autumn.consul.instance-id` | `{name}:{ip}:{port}` | 实例 ID |
| `consul.health-check-interval` | `autumn.consul.health-check-interval` | `10` | TTL 健康检查周期（秒，自动按 TTL/3 续约） |
| `consul.host` | `autumn.consul.host` | `127.0.0.1` | Consul Agent 地址 |
| `consul.port` | `autumn.consul.port` | `8500` | Consul Agent HTTP 端口 |
| `consul.scheme` | `autumn.consul.scheme` | `http` | `http` / `https` |
| `consul.token` | `autumn.consul.token` | 空 | ACL Token（`X-Consul-Token` 请求头） |
| `consul.namespace` | `autumn.consul.namespace` | 空 | Consul Enterprise Namespace（`X-Consul-Namespace` 请求头，社区版忽略） |

#### Nacos 级（原生前缀 `nacos.*`，Spring Boot 前缀 `autumn.nacos.*`）

| 原生配置项 | Spring Boot 配置项 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `nacos.server-addr` | `autumn.nacos.server-addr` | `127.0.0.1:8848` | Nacos OpenAPI 地址 |
| `nacos.namespace` | `autumn.nacos.namespace` | 空（public） | 命名空间 ID |
| `nacos.group` | `autumn.nacos.group` | `DEFAULT` | 分组名；与其他标准 Nacos 客户端互通时按需设为 `DEFAULT_GROUP` |
| `nacos.access-token` | `autumn.nacos.access-token` | 空 | 鉴权 Token |
| `nacos.beat-interval` | `autumn.nacos.beat-interval` | `5000` | 临时实例心跳间隔（毫秒） |

> `autumn-registry-extension` 已随 starter 传递引入（`autumn-cloud-extension-common`），选择 consul / nacos 后端**无需**额外依赖。

#### 编程配置（`ReferenceConfig`）

| 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `setName(name)` | — | 引用服务名（注册中心服务名） |
| `setInterfaceClass(Iface/Client)` | — | 接口类（thrift 生成的 Client 或 Iface） |
| `setRegistryTypeEnum(...)` | — | `MULTICAST` / `CONSUL` / `NACOS` / `DIRECT` |
| `setInstances(List<ConsumerConfig>)` | — | 直连实例列表（DIRECT 模式） |
| `setPoolTimeout(ms)` | `1000` | 连接池借用超时（Spring Boot 默认 `10000`） |
| `setSocketTimeout(ms)` | `3000` | 读响应超时（Spring Boot 默认 `3000`） |
| `setConnectionTimeout(ms)` | `1000` | 建连超时（Spring Boot 默认 `1000`） |
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

**Nacos 模式 Provider（`application.properties`）**

```properties
spring.application.name=training-nacos
autumn.name=training-nacos
autumn.registry-type=nacos
autumn.nacos.server-addr=127.0.0.1:8848
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

三种后端均由 `autumn-registry-extension` 适配 `autumn-cloud-extension` 实现，注册时实例元数据统一携带 `protocol=thrift`、`application`、`traffic`（泳道标识）以及 `application.labels.*` 自定义标签，发现侧通过 `ExtensionDiscovery` 定时刷新（间隔 `registry.discovery-interval`，默认 5s）并同步连接池。快照保留所有泳道实例，`getInstances(name, parameters)` 按本次调用的隐式参数筛选（详见 4.6）。

### 6.1 多播注册中心（默认）

- 组播地址 `224.0.0.1:5555`，纯 JDK `DatagramChannel` 实现，无外部依赖，单机/局域网联调开箱即用。
- **注册/心跳**：启动时广播实例信息（JSON 编码），此后周期性重发续期；进程退出时广播 bye 报文触发对端即时摘除。
- **过期**：实例超过有效期未续期即从本地缓存移除。
- **平台差异**：Windows 上 socket 绑定通配地址、其他平台绑定组播地址（BSD 语义下同主机多进程共享组播端口）。
- **边界**：多播受限于同一网段/广播域，跨网段请使用 Consul / Nacos。

### 6.2 Consul 注册中心

`registry-type=consul` 后（依赖随 starter 传递，无需额外引入）：

- **注册**：`PUT /v1/agent/service/register`，注册 TTL Check（`Check.TTL = {health-check-interval}s`，`DeregisterCriticalServiceAfter` 为 TTL 的 3 倍），客户端自动按 TTL/3 续约（`PUT /v1/agent/check/pass/service:{id}`）。
- **注销**：JVM ShutdownHook 调 `PUT /v1/agent/service/deregister/{instanceId}`。
- **发现**：`GET /v1/health/service/{name}?passing=true` 按 `discovery-interval` 轮询，只保留 passing 实例。
- **鉴权**：`consul.token` → `X-Consul-Token`；`consul.namespace` → `X-Consul-Namespace`（Enterprise）；`consul.scheme` 支持 https。

> 提前启动本地 Consul：`consul agent -dev`（默认 127.0.0.1:8500）。

### 6.3 Nacos 注册中心

`registry-type=nacos` 后：

- **注册**：`POST /nacos/v1/ns/instance`（临时实例），客户端按 `nacos.beat-interval` 发送心跳保活。
- **发现**：`GET /nacos/v1/ns/instance/list` 轮询实例列表，标记非健康实例自动摘除。
- **隔离**：`nacos.namespace`（命名空间 ID）+ `nacos.group` 双维度隔离；开启鉴权时配置 `nacos.access-token`。

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
cd autumn-samples/autumn-sample-boot-provider
mvn spring-boot:run

# 终端 2：启动 Consumer
cd autumn-samples/autumn-sample-boot-consumer
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

# 启动 Spring Boot sample（可执行 jar 的运行副本放到 run/，不锁 target/）
./scripts/run-boot.sh autumn-sample-boot-provider --server.port=18080
```

- JDK 25+、Maven 3.8+
- 可执行 jar 为 `target/*-exec.jar`（`exec` classifier），主 jar 为普通 jar；重复 `package` 不会二次 repackage，也不产生 `*.jar.original`
- 模块间通过 reactor 构建，`annotationProcessorPaths` 中的 SNAPSHOT 坐标会优先从 reactor 解析

## 11. 常见问题

**Q1：Provider 注册的 IP 不对（如 127.0.0.1 / 容器内网 IP）？**

显式配置 `provider.ip`（或 `autumn.provider.ip`）为对外可达地址。网卡选择统一由 `CommonUtil.getNetIf(preferred)` 完成：优先显式 `application.multicast.network-interface`，否则用注册 IP 反查持有该 IP 的网卡，保证注册 IP 与组播出口一致；未指定时退回物理网卡优先的启发式选择。

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

框架调用面已零反射（JSR-269 生成代码 + SPI），注册发现实现零 SDK 依赖（原生 HTTP / JDK 组播）。native-image 需要的反射 / 动态代理 / 资源元数据在**构建期**全部就绪，仓库里没有手写 hints：

- `@Export` / `@Reference` 注解处理器在编译期写出 `META-INF/native-image/xyz.we-win.autumn/autumn-{export,reference}-hints/`（装配器、`_XxxProxy`、`Xxx$Client`、注入字段 `allowWrite`、`Iface` 动态代理组），随 jar 打包，`native-image` 自动合并，无需 `-H:*ConfigurationResources`（详见 [4.4](#44-编译期代码生成jsr-269)）；
- 框架已知的资源、SPI 契约与注册中心 Jackson record 由 `AutumnRuntimeHints`（`@ImportRuntimeHints`）在 Spring AOT 阶段登记；
- 三方 / 应用侧反射用 `RuntimeHintsRegistrar`、`@RegisterReflectionForBinding` 补齐（示例：共用模块 `autumn-sample-boot-native-hints` 的 `TomcatNativeHints`、`BootSampleBootstrap`）。

两个示例工程都已在本机以 GraalVM 25（Oracle GraalVM 25.4.4.1.1+1.1）native 镜像跑通：

- `autumn-sample-boot-provider`：冷启动 0.08s，`/hello`（进程内 thrift 自环调用）、`/greet`、`/listUserIds`、`/getUser`（Jackson 序列化 thrift POJO）全部 200。
- `autumn-sample-boot-consumer`：冷启动 0.088s，`-Pnative` 已在 pom 里配好 profile（`native-maven-plugin`，产物 `autumn-sample-boot-consumer-native.exe`）；同机 multicast 双 native 进程下发现 provider 后 `GET /boot-sample-consumer/hello` → `Hello, 213 [node:30880] [tenant:consul-smoke]`，即生成代理 + `@Reference` 注入 + attachment 透传在纯 native 环境可用。

尚未接入 native 构建的 CI。

**Q6：切换 / 删除注册中心实现后行为没变？**

SPI 文件来自 `META-INF/services`，删除或变更后**必须 `mvn clean`** 再构建：`target/classes` 里残留的旧 SPI 文件仍会被 `ServiceLoader` 装配，导致旧实现继续生效。这也是本框架推荐统一使用 `mvn clean install` 的原因。

**Q7：Windows 上组播报 `Cannot assign requested address`？**

旧版自研组播实现存在该问题，现已由 `autumn-cloud-extension` 修复（Windows 绑定通配地址）。若仍出现，请确认 `autumn-cloud-extension-common` 版本 ≥ 1.0.0-SNAPSHOT（2026-11-04 之后构建）。

**Q8：Windows 上 `mvn clean install` 报 jar 被其它进程占用、`spring-boot:repackage` 失败？**

根因是直接用 `java -jar target/xxx.jar` 启动：运行中的 JVM 会独占该 jar。现已两步规避：

1. `spring-boot-maven-plugin` 统一配置 `exec` classifier，可执行包输出为 `target/*-exec.jar`，不再替换主 jar（顺带消除 `*.jar.original` 残留与二次 repackage）；
2. 用 `./scripts/run-boot.sh <模块名> [参数...]` 启动，脚本把可执行 jar 复制到 `run/` 下的进程级唯一副本再运行，`target/` 始终无锁，随时可以重新构建；多实例（Consul 双 provider）也互不冲突。

副本在进程退出时自动删除；被强杀留下的副本会在下次启动时清理。请勿再直接 `java -jar target/*.jar`。

## 12. 当前状态与 Roadmap

### 已完成

- [x] Spring Boot Starter 接入（自动装配 / 注解处理 / 生命周期）
- [x] 注册发现收敛至 `autumn-cloud-extension`（multicast / consul / nacos 三后端，旧自研实现已删除）
- [x] JSR-269 编译期代码生成链路（`_XxxServiceRegister` / `_XxxProxy` + SPI）
- [x] 连接池（ConcurrentBag）与控制面 API

### 规划中

- [ ] `AutumnFilter` 过滤器链接入（鉴权、限流、链路透传）
- [ ] 控制面治理工具（devops CLI / 控制台：服务列表、上下线、路由）
- [ ] `ConsumerConfig.checkFail` 熔断与优雅降级
- [ ] 直连 / Consul 模式下 `@Reference` 的配置增强
- [ ] GraalVM Native Image 示例工程与 CI 验证
- [ ] module-info（JPMS）完善与 `provides` 声明
