# Thrift vs HTTP 协议性能对比压测报告

> 仓库：autumn-service-framework · 生成时间：2026-11-03 · 工具：`autumn-sample-benchmarker`

## 1. 结论速览

- **吞吐（QPS）**：thrift 约为 HTTP 的 **3.2x ~ 4.5x**，并发越高优势越明显（32 并发下 133,981 vs 29,791 ≈ 4.5x）。
- **延迟（p50）**：thrift 约为 HTTP 的 **1/3.5 ~ 1/4.9**（0.038ms vs 0.132ms 起，32 并发 0.211ms vs 1.035ms）。
- 全部 8 轮、每轮 50,000 次请求，成功率 100%（thrift 服务端将 `provider.max-threads` 由默认 1 调至 32 后无失败）。

## 2. 压测方法

| 项 | 说明 |
|---|---|
| 业务负载 | 对等服务：`echo("hello")` 字符串回显（thrift `SomeService.echo` vs HTTP `GET /echo?msg=hello`） |
| thrift 服务端 | autumn 框架 provider（`TThreadedSelectorServer` + `TFramedTransport` + `TBinaryProtocol` + `TMultiplexedProcessor`），端口 30880 |
| http 服务端 | Spring Cloud provider（Boot 3.5.6 / Tomcat 默认线程池），端口 8083 |
| 客户端 | 每线程独立长连接（thrift 独立 `TSocket`，http 独立 `HttpClient` keep-alive），连接全程复用，公平对比 |
| 协议细节 | thrift 走 framed + binary + multiplexed（服务名 `SomeService$Iface`），http 走 HTTP/1.1 + JSON 响应 |
| 计量 | 每轮先预热后计时；QPS = 请求数 / 墙钟时长；延迟分位基于单次调用耗时（ns）排序 |
| 硬件 | 本机 macOS（MacBook Pro A2141，i9-9750H/32G），loopback，Java 25.0.5 |

## 3. 压测结果（每轮 50,000 次请求，均 100% 成功）

| 协议 | 并发 | QPS | avg(ms) | p50(ms) | p95(ms) | p99(ms) | max(ms) |
|---|---|---|---|---|---|---|---|
| thrift | 1 | 19,710 | 0.050 | 0.038 | 0.110 | 0.179 | 8.146 |
| http | 1 | 6,212 | 0.160 | 0.132 | 0.313 | 0.461 | 4.128 |
| thrift | 8 | 102,297 | 0.074 | 0.066 | 0.132 | 0.191 | 3.114 |
| http | 8 | 26,868 | 0.294 | 0.275 | 0.429 | 0.575 | 6.582 |
| thrift | 16 | 120,873 | 0.116 | 0.105 | 0.195 | 0.256 | 2.005 |
| http | 16 | 34,977 | 0.453 | 0.434 | 0.644 | 0.826 | 6.152 |
| thrift | 32 | 133,981 | 0.220 | 0.211 | 0.330 | 0.405 | 2.153 |
| http | 32 | 29,791 | 1.067 | 1.035 | 1.380 | 1.830 | 12.243 |

**提速倍数（thrift / http）**

| 并发 | QPS 倍数 | p50 提速 |
|---|---|---|
| 1 | 3.17x | 3.47x |
| 8 | 3.81x | 4.17x |
| 16 | 3.46x | 4.13x |
| 32 | 4.50x | 4.91x |

## 4. 观察与结论

1. **协议开销是主要差异来源**：thrift 二进制协议 + 固定帧头、无 HTTP 头/解析开销；HTTP 每次请求需组装与解析请求行/首部、状态行，且响应为 JSON 文本。echo 这种小负载下协议开销占比被放大，thrift 优势最明显。
2. **并发扩展性**：thrift 从 1 线程到 32 线程 QPS 提升约 6.8x，HTTP 仅提升约 4.8x，thrift 服务端（selector 模型）在高并发下更能压出吞吐。
3. **latency 稳定性**：thrift 的 p99 始终低于 0.5ms（32 并发 0.405ms），http 在 32 并发时 p99 达 1.83ms；thrift 长尾更小。
4. **服务端默认配置注意**：autumn 框架 `provider.max-threads` 默认 **1**，压测 32 并发会出现约 44% 失败（服务端线程池饱和）。生产/压测前按需调大（如 `autumn.provider.max-threads=32`）。

## 5. 复现方式

```bash
# 0) 项目根目录构建一次（产出 target/*-exec.jar）
mvn -DskipTests clean install

# 1) 启动 thrift provider（30880 thrift / 8080 http 控制面）
./scripts/run-boot.sh autumn-sample-boot-provider

# 2) 启动 http provider（8083）
./scripts/run-boot.sh autumn-spring-cloud-examples-provider

# 3) 运行 benchmarker（输出表格 + /tmp/benchmark_result.txt）
cd autumn-samples/autumn-sample-benchmarker
mvn package -DskipTests
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/bm_cp.txt
java -cp "target/autumn-sample-benchmarker.jar:$(cat /tmp/bm_cp.txt)" \
  com.microapp.autumn.sample.benchmarker.BenchmarkRunner \
  --thrift-host 127.0.0.1 --thrift-port 30880 \
  --http-url "http://127.0.0.1:8083/echo?msg=hello" \
  --threads 1,8,16,32 --requests 50000
```

## 6. 局限与后续

- 本测试为**单机 loopback**、**小 payload（echo 字符串）**场景，代表协议本身开销差异；复杂对象/大 payload/跨机网络环境下，序列化成本与网络 RTT 占比会变化，需另行对比（如 `addUser`/`findUserByIds` 方法）。
- HTTP 侧未启用 HTTP/2、未开启连接复用优化之外的任何调优；thrift 侧未调整 selector 线程数等。
- 若需更精细结论，可增加：大对象方法对比、远程主机对比、HTTP/2 对比、不同序列化协议（TCompactProtocol）对比。
