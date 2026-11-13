# 一键启动与测试 URL 速查

> 启动脚本后直接点下面的链接验证，不用再翻代码找端点。
> 所有地址默认 `127.0.0.1`；等脚本打印「已启动」再访问。
>
> 实测基准：2026-11-13，macOS 14 + GraalVM CE 25.0.1（JDK 25）/ Maven 3.9.12。
> 第 3.1～3.3 节的链接在 **consul + java -jar** 与 **multicast + native** 两种模式下均实测 HTTP 200；
> 第 3.4 节的 spring-cloud examples 不在一键脚本内，需单独启动。

---

## 1. 启动脚本一览

### 一键起三个服务（provider + consumer，consul 模式另起 Consul）

| 注册中心 | 运行时 | Linux / macOS | Windows |
| --- | --- | --- | --- |
| Consul | java -jar | `./scripts/start.sh` | `scripts\start.bat` |
| Consul | GraalVM native | `./scripts/start-native.sh` | `scripts\start-native.bat` |
| multicast | java -jar | `./scripts/start-multicast.sh` | `scripts\start-multicast.bat` |
| multicast | GraalVM native | `./scripts/start-multicast-native.sh` | `scripts\start-multicast-native.bat` |

> multicast 模式**不启动也不检查 Consul**，组播由框架自带 `MulticastBackend` 完成。
> 四个 `.sh` 都支持 `--service provider|consumer`、`-- --app.args`，见第 5 节。

### 单服务前台运行

| 运行时 | Linux / macOS | 示例 |
| --- | --- | --- |
| java -jar | `./scripts/run-boot.sh <module> [args]` | `./scripts/run-boot.sh autumn-sample-boot-provider --server.port=18080` |
| GraalVM native | `./scripts/run-native.sh <module> [args]` | `./scripts/run-native.sh autumn-sample-boot-provider --server.port=18080` |

### 构建 / 停止

| 动作 | Linux / macOS | Windows |
| --- | --- | --- |
| 构建 jar（全量 install） | `mvn -B -DskipTests install` | `scripts\build.bat` |
| 构建 native 镜像 | `./scripts/build-native.sh` | `scripts\build.bat` |
| 只验证工具链不构建 | `CHECK_ONLY=1 ./scripts/build-native.sh` | — |
| 停止服务 | `./scripts/stop.sh`（默认连 Consul 一起停） | `scripts\stop.bat`（含 Consul 用 `/all`） |
| 保留 Consul 只停应用 | `./scripts/stop.sh --keep-consul` | `scripts\stop.bat` |

---

## 2. 默认端口

| 端口 | 服务 | 说明 |
| --- | --- | --- |
| `8500` | Consul HTTP API / UI | **仅 consul 模式**；multicast 模式不监听 |
| `8080` | boot-provider HTTP | Spring Boot Tomcat |
| `8081` | boot-consumer HTTP | Spring Boot Tomcat |
| `30880` | provider thrift RPC | `autumn.provider.port` |
| `30881` | consumer thrift RPC | `autumn.provider.port` |
| `8083` / `8084` | spring-cloud examples provider / consumer | 需单独启动，不在一键脚本内 |
| UDP `224.0.0.1:5555` | 组播注册发现 | multicast 模式使用 |

脚本会显式传 `--server.port` / `--autumn.provider.port` 钉死端口，避免 `SERVER__PORT` 之类环境变量通过 Spring 宽松绑定覆盖配置。

---

## 3. 测试 URL（可直接点开）

### 3.1 主示例 —— 任一 `start*.sh` / `start*.bat` 启动后都适用

| 链接 | 说明 | 实测响应示例 |
| --- | --- | --- |
| <http://127.0.0.1:8081/boot-sample-consumer/hello> | **全链路冒烟**：consumer 经注册中心发现 provider 并发起 thrift 调用 | `Hello, 213 [node:30880] [tenant:consul-smoke] [time:...]` |
| <http://127.0.0.1:8080/boot-sample-provider/hello> | provider 侧同样经注册中心自调用 | `Hello, 213 [node:30880] [tenant:null] [time:...]` |
| <http://127.0.0.1:8080/boot-sample-provider/greet?name=autumn&age=18> | 带参 thrift 调用 | `Hello, autumn, age=18` |
| <http://127.0.0.1:8080/boot-sample-provider/listUserIds> | 集合返回 | `[1,2,3]` |
| <http://127.0.0.1:8080/boot-sample-provider/getUser?userId=1> | POJO → JSON | `{"userId":1,"username":"user-1",...}` |

响应里的 `[node:30880]` 就是命中的 provider thrift 端口；多实例部署时用它判断负载均衡是否生效。

### 3.2 泳道 / 隐式参数（consumer 入口）

| 链接 | 说明 |
| --- | --- |
| <http://127.0.0.1:8081/boot-sample-consumer/hello?traffic=gray> | 路由到 `gray` 泳道；没有 gray 实例时回退 `main`，响应仍是 `node:30880` |
| <http://127.0.0.1:8081/boot-sample-consumer/hello?X-Traffic=gray> | 同上，`X-Traffic` 别名 |
| <http://127.0.0.1:8081/boot-sample-consumer/hello?label.region=cn> | 按任意实例标签筛选 |

这些是隐式参数，随调用链透传，不改动 RPC 方法签名。

### 3.3 Consul 相关（仅 consul 模式）

| 链接 | 说明 | 实测响应 |
| --- | --- | --- |
| <http://127.0.0.1:8500/ui/> | Consul Web UI | HTML 200 |
| <http://127.0.0.1:8500/v1/status/leader> | 集群 leader / 健康检查 | `"127.0.0.1:8300"` |
| <http://127.0.0.1:8500/v1/catalog/services> | 已注册服务列表 | `consul` / `provider-consul` / `consumer-consul`，标签 `traffic=main` |
| <http://127.0.0.1:8500/v1/health/service/provider-consul> | provider 健康实例 | JSON 数组 |
| <http://127.0.0.1:8500/v1/health/service/consumer-consul> | consumer 健康实例 | JSON 数组 |

> multicast 模式访问 8500 会失败（502 / 连接被拒），这是**预期行为**，不是故障。

### 3.4 spring-cloud examples（需单独启动，本次未实测）

端口来自各自的 `application.properties`：`provider=8083`、`consumer=8084`。这些模块不在一键脚本里，通常用状态虚拟机上的多实例负载均衡脚本部署。

| 链接 | 方法 | 说明 |
| --- | --- | --- |
| <http://127.0.0.1:8083/hello> | GET | provider 直连 |
| <http://127.0.0.1:8083/health> | GET | 健康检查 |
| <http://127.0.0.1:8083/echo> | GET | echo |
| <http://127.0.0.1:8083/users/1> | GET | 返回带 `[node:8083]` 的用户对象 |
| <http://127.0.0.1:8083/users> | POST | 新建用户（JSON body） |
| <http://127.0.0.1:8084/consumer/hello?name=autumn> | GET | consumer 直连 |
| <http://127.0.0.1:8084/consumer/get-user?id=1> | GET | 经 Consul + LoadBalancer 调 provider |
| <http://127.0.0.1:8084/consumer/create-user> | POST | 经负载均衡创建用户（JSON body） |

---

## 4. 一键冒烟（复制整段）

```bash
BASE=http://127.0.0.1
curl -fsS "$BASE:8081/boot-sample-consumer/hello"; echo
curl -fsS "$BASE:8080/boot-sample-provider/hello"; echo
curl -fsS "$BASE:8080/boot-sample-provider/greet?name=autumn&age=18"; echo
curl -fsS "$BASE:8080/boot-sample-provider/listUserIds"; echo
curl -fsS "$BASE:8080/boot-sample-provider/getUser?userId=1"; echo
# 仅 consul 模式：
curl -fsS "$BASE:8500/v1/catalog/services"; echo
```

Windows PowerShell：

```powershell
Invoke-RestMethod http://127.0.0.1:8081/boot-sample-consumer/hello
Invoke-RestMethod http://127.0.0.1:8080/boot-sample-provider/getUser?userId=1
Invoke-RestMethod http://127.0.0.1:8500/v1/catalog/services   # 仅 consul 模式
```

---

## 5. 常用变体

```bash
# 只起一个服务
./scripts/start.sh --service provider
./scripts/start-multicast-native.sh --service consumer

# 换端口（-- 之后原样传给应用；脚本识别后不会重复追加默认端口）
./scripts/start.sh --service provider -- --server.port=18080 --autumn.provider.port=30882

# multicast 指定组播地址 / 出口网卡（多网卡、装了 Docker 的机器）
MULTICAST_INTERFACE=en0 MULTICAST_PORT=5555 ./scripts/start-multicast.sh
set MULTICAST_INTERFACE=Ethernet && scripts\start-multicast.bat

# 用环境变量切运行时（等价于直接调用 *-native 脚本）
RUNTIME=native ./scripts/start-multicast.sh
set RUNTIME=native && scripts\start-multicast.bat

# 强制刷新 Maven 依赖（默认已关闭 -U 以避免 checksum 告警）
FORCE_UPDATE=1 ./scripts/build-native.sh

# 只构建指定模块
MODULES=autumn-samples/autumn-sample-boot-provider ./scripts/build-native.sh
```

---

## 6. 排错速查

| 现象 | 处理 |
| --- | --- |
| `Port 8080/8081 already in use` | `./scripts/stop.sh`（Windows：`scripts\stop.bat`），再确认端口释放 |
| 8500 返回 502 / 连接被拒 | multicast 模式本来就不起 Consul；需要 UI 就用 `start.sh` / `start-native.sh` |
| 服务起了但端口是 50126 之类 | IDE 注入了 `SERVER__PORT`；脚本已 `unset` 并显式传端口，请确认用的是仓库里的新脚本 |
| `找不到 native 镜像` | 先跑 `./scripts/build-native.sh`（Windows：`scripts\build.bat`） |
| 日志里 `registry:consul` 但想要组播 | 用 `start-multicast*.sh/.bat`，或确认 `--spring.profiles.active=multicast` |
| 组播发现不到实例 | 指定出口网卡：`MULTICAST_INTERFACE=en0 ./scripts/start-multicast.sh` |
| Windows 批处理 `goto`/label 异常 | `.bat` 必须 CRLF 行尾 + `chcp 65001`（仓库内已是 CRLF） |
| 启动脚本报 `unbound variable` | macOS bash 3.2 的中文变量名问题，已修；不要把手写脚本里的 `${VAR}` 改回 `$VAR` |

日志位置：`run/logs/<service>-<时间戳>.log`；PID：`run/pids/<service>.pid`（均被 `.gitignore` 忽略）。

---

## 7. 相关文档

- [用户指南](USER_GUIDE.md) — 配置项、泳道路由、native 构建、发布流程
- [协议扩展](PROTOCOL-EXTENSION.md) — Thrift 泛化调用
- [压测](BENCHMARK.md) — 基准测试
