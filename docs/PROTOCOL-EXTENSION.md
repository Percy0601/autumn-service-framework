# autumn-protocol-extension：模块迁移与 Thrift 动态泛化调用 v1

## 1. 模块迁移

把 `autumn-api` 中 `com.microapp.autumn.api.extension` 的 7 个类整体迁入新模块
`autumn-protocol-extension`（`AttachableBinaryProtocol` / `AttachableProcessor` /
`AttachmentContext` / `AutumnFilter` / `JoinPoint` / `AutumnServiceAssembler` / `TrafficRouter`）。

- **包名保持不变**：SPI 文件名、注解处理器模板、native hints、30+ 处 import 全部零改动。
- `ServiceConfig` 中一行未使用的 `AttachableProcessor` import 已删除，否则
  `autumn-api` 与新模块会形成 Maven 模块循环依赖。
- 依赖方向：`autumn-protocol-extension -> autumn-api`（config 类），
  `autumn-core / registry-extension / compiler / spring-boot-autoconfigure` 显式依赖新模块，
  samples 通过传递依赖获得。
- `AttachableBinaryProtocol` 新增 `setExplicitAttachment(Map)`：网关运行在 WebFlux 事件循环上，
  不能靠 `ThreadLocal` 的 `AttachmentContext` 传隐式参数；显式注入不改变原有默认行为。

## 2. generic-thrift-client 调研结论

调研对象：`/Users/mac/work/project/generic-thrift-client`（waj89757/generic-thrift-client，2016 年）。

### 2.1 协议 IO 模型：可行

Thrift 的 wire 是与语言无关的顺序结构，只要拿到「方法名 + 参数字段 id/类型 + 返回值类型」
这份描述信息，就能完全绕开 IDL 编译生成的 stub：

```
CALL  : TMessage(method, CALL, seqid) + args struct{ field(id,type)=value ... } + STOP
REPLY : TMessage(method, REPLY, seqid) + result struct{ 0=success, 1..n=声明的异常 } + STOP
EXCEPTION : TMessage + TApplicationException
```

该框架的 `GenericTree` 描述树 + `TGenericClient` 用 `TProtocol` 原语直接 write/read 的思路
**证明了动态泛化调用在协议层是成立的**，这正是网关做 thrift 泛化路由所需要的基础。

### 2.2 原实现不能直接用于网关

| 问题 | 影响 |
| --- | --- |
| struct 参数分支只写 `writeStructBegin`，漏掉外层 `writeFieldBegin/End` | 含 struct 入参的方法 wire 格式错误，调用必然失败 |
| `read()` 按描述树**顺序**硬遍历，不按 field.id 定位 | optional 缺失、乱序、新增字段会整体错位 |
| 未知字段不 skip（代码里是 TODO / `System.out.println` 后 break） | 服务端升级后即解析失败 |
| 未区分 result 的 `id=0 success` 与 `id>0` 业务异常；基本类型返回值被包成 Map | 返回语义与 thrift 生成客户端不一致 |
| 用反射 `getClass().getMethod("writeString")` 调 TProtocol | 性能差，GraalVM native 需额外 hint |
| seqid 恒为 1、每次 open/close，无连接复用 | 无法用于网关高并发链路 |
| 元数据靠外部 MySQL 手工录入，无运行时 IDL 装载 | 新服务上线仍需人工介入，不够「动态」 |
| libthrift 0.9.3 / fastjson 1.2.9 / guava 18 / junit4 | 版本过旧，与 JDK 25 + libthrift 0.22 不兼容；本机无 thrift CLI，原工程无法构建 |
| 不支持 union / enum / binary / set / oneway / TMultiplexedProtocol / attachment | 覆盖不了真实服务 |

### 2.3 结论

**模型可行，实现需重写。** 按同样的「描述树 + TMessage + TProtocol 原语」模型，
在 `autumn-protocol-extension` 内落地一版现代、native 友好、可供网关直接使用的实现。

## 3. 动态泛化调用 v1

包：`com.microapp.autumn.protocol.generic`（不依赖 Spring / JSON 库，只依赖 libthrift + slf4j）。

| 组件 | 职责 |
| --- | --- |
| `ThriftType` / `TypeSpec` | 运行时类型描述树，基本类型 + struct/list/set/map/enum/binary/void |
| `MethodSpec` / `ServiceSpec` | 方法参数（带 field id / requiredness）、success、声明异常、oneway |
| `IdlParser` | 递归下降解析 thrift IDL 子集，运行时产出 `ServiceSpec`（不需要 thrift 编译器） |
| `ServiceRegistry` | 并发注册表，支持运行时热加载/替换描述符 |
| `ThriftCodec` | 无反射编解码：按 field id 写 args、读 result，未知字段 skip，类型不符显式报错 |
| `GenericThriftClient` | 任意 `TProtocol` 上的 send/recv，seqid 递增，支持连接复用 |
| `GenericThriftInvoker` | 网关门面：framed binary / multiplexed / field-0 attachment / 超时 / 同步+异步 |
| `GenericRequest` / `GenericResponse` | JSON 友好出入参，`asMap()` 可直接序列化 |

```java
ServiceRegistry registry = new ServiceRegistry();
registry.registerIdl(idlText);                 // 运行时热加载，无 stub、无需重启
GenericThriftInvoker invoker = new GenericThriftInvoker(registry);

GenericRequest req = GenericRequest.of(host, port, "HelloWorldService", "sayHello")
        .setArguments(Map.of("username", "tom"))
        .attachment("tenant", "acme")          // HTTP header -> thrift field-0
        .setMultiplexed(true);

GenericResponse resp = invoker.invoke(req);    // 阻塞
Map<String, Object> json = resp.asMap();      // {success, data|errorType/message/error, costMillis}
```

已验证（20 个测试，含 11 个真实 TCP 往返）：全基本类型 / 嵌套 struct / list / set / map、
struct 入参与返回、optional 与未知字段、乱序字段、业务异常、`TApplicationException`、
seqid 校验、oneway、attachment 透传、multiplexed 分发、IDL 热注册、异步门面。

### 已知限制（v1）

- 每次调用新建连接（网关需接连接池 / Netty 异步客户端）。
- 仅 binary + framed（可关 framed）；未支持 compact/JSON 协议。
- IDL 子集：跳过 `include` / `typedef` / `const` / `senum` / `service extends`；要求类型先定义后使用。
- 无鉴权、重试、熔断、限流、metrics；描述符无版本管理与校验。

## 4. 集成到 autumn-gateway（下一步）

网关现状：Spring Boot 4.1.1 + Spring Cloud Gateway(WebFlux) + JDK 25。
`autumn-protocol-extension` 不含 Spring 依赖，可作为普通 jar 直接引入（同时带上 `autumn-api`）。

建议新增一个 `ThriftGenericRouteFilterFactory`（或 `GlobalFilter`）：

1. **路由识别**：route metadata `protocol=thrift` 或 `thrift://` scheme；HTTP 路径解析出
   `service` / `method`，body(JSON) 转 `Map<String,Object>` 作为 arguments。
2. **描述符**：控制面/注册中心下发 IDL 文本 → `ServiceRegistry.registerIdl(idl)` 热更新，
   网关无需重新编译或重启即可路由新上线的 thrift 服务。
3. **服务发现与路由**：复用 `TrafficRouter.Route.select(...)` 做泳道/标签筛选，
   `X-Traffic` 等 HTTP header 映射为 `GenericRequest.attachment(...)`，
   由 `AttachableBinaryProtocol` 写成 thrift field-0，服务端与下游链路可继续透传。
4. **线程模型**：thrift 客户端是阻塞 IO，必须离开事件循环：
   ```java
   return Mono.fromCallable(() -> invoker.invoke(req))
              .subscribeOn(Schedulers.boundedElastic())
              .flatMap(resp -> writeJson(exchange, resp.asMap()));
   ```
   或 `invoker.invokeAsync(req, boundedElasticExecutor)`。
5. **响应/异常**：`GenericResponse.asMap()` → JSON；`APPLICATION` / `SERVICE` / `TRANSPORT`
   分别映射为 502 / 业务错误体 / 503，避免把 Java 异常泄漏到网关边缘。

这样网关对上游 HTTP 与 thrift 保持同一套路由、泳道、鉴权与可观测模型，
区别仅在「最后一跳」的协议适配。

## 5. 验证命令

```bash
mvn -B -pl autumn-protocol-extension -am test   # 新模块 20 个测试
mvn -B test                                     # 全仓库回归
```
