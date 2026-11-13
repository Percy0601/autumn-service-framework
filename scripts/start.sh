#!/usr/bin/env bash
#
# java -jar 一键启动：Consul + provider + consumer（后台，PID 记到 run/pids，日志 run/logs）。
# 与 Windows 的 scripts/start.bat 对齐。要跑 GraalVM native 镜像请用 scripts/start-native.sh。
#
# 用法:
#   ./scripts/start.sh                          # consul + provider + consumer
#   ./scripts/start.sh --service provider       # 只起 provider
#   ./scripts/start.sh --skip-consul            # 复用已在跑的 consul
#   ./scripts/start.sh --profile ""             # 不传 spring profile
#   ./scripts/start.sh --service provider -- --server.port=18080 --autumn.provider.port=30882
#   ./scripts/start.sh --runtime native         # 兼容旧用法，等价于 start-native.sh
#
# 前提: consul 已加入 PATH；java 在 PATH 或设置 JAVA_HOME
#
# 几个必须保留的处理（改脚本前先看）：
#  1) IDE / 容器常注入 SERVER__PORT 之类的环境变量，Spring Boot 宽松绑定会把它当成
#     server.port，覆盖配置里的 8080/8081，导致两个服务抢同一端口。这里清掉环境变量，
#     并显式传 --server.port / --autumn.provider.port（命令行参数优先级最高）。
#  2) 后台进程用独立会话启动：macOS 上父 shell 退出发的 SIGHUP 会被 Consul 自己接管
#     （Go 程序覆盖 nohup 的 SIG_IGN）并退出，单纯 nohup 不够。
#  3) 必须等端口真正 LISTEN 才报成功；只 sleep + kill -0 会把 "APPLICATION FAILED TO
#     START" 报成启动成功。
#  4) macOS 自带 bash 3.2 会把紧跟 $VAR 的中文多字节字符算进变量名，所有中文旁边的
#     变量一律写成 ${VAR}。
#
set -euo pipefail

unset SERVER__PORT SERVER_PORT PORT 2>/dev/null || true

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/run"
PID_DIR="$RUN_DIR/pids"
LOG_DIR="$RUN_DIR/logs"
STAGE_DIR="$RUN_DIR/staged"

SERVICE="both"
RUNTIME="jar"
PROFILE="consul"
JAVA_HOME_OPT="${JAVA_HOME:-}"
SKIP_CONSUL=0
APP_ARGS="${APP_ARGS:-}"
EXTRA_ARGS=()

info() { printf '    %s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }

usage() {
    sed -n '2,/^set -euo pipefail/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' | sed '$d'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--service)  SERVICE="${2:?--service 缺少取值}"; shift 2 ;;
        -r|--runtime)  RUNTIME="${2:?--runtime 缺少取值}"; shift 2 ;;
        --profile)     [[ $# -ge 2 ]] || die '--profile 缺少取值（不要 profile 就传空字符串 ""）'
                       PROFILE="$2"; shift 2 ;;
        --java-home)   JAVA_HOME_OPT="${2:?--java-home 缺少取值}"; shift 2 ;;
        --skip-consul) SKIP_CONSUL=1; shift ;;
        -h|--help)     usage; exit 0 ;;
        --)            shift; EXTRA_ARGS=("$@"); break ;;
        *)             die "未知参数: $1（用 --help 查看用法）" ;;
    esac
done

[[ "$RUNTIME" == "jar" || "$RUNTIME" == "native" ]] || die "--runtime 只支持 jar / native"
case "$SERVICE" in
    both|provider|consumer|consul) ;;
    *) die "--service 只支持 both / provider / consumer / consul" ;;
esac

# ---------- 小工具 ----------

port_listening() {
    local port="$1"
    if command -v lsof >/dev/null 2>&1; then
        lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
    elif command -v ss >/dev/null 2>&1; then
        ss -lntH "sport = :$port" 2>/dev/null | grep -q .
    else
        (exec 3<>"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1
    fi
}

consul_ready() {
    if command -v curl >/dev/null 2>&1; then
        local leader
        leader="$(curl -fsS --max-time 2 "http://127.0.0.1:8500/v1/status/leader" 2>/dev/null || true)"
        [[ "$leader" == *\"* ]]
    else
        port_listening 8500
    fi
}

wait_consul_ready() {
    local i
    for i in $(seq 1 80); do
        consul_ready && return 0
        sleep 0.5
    done
    return 1
}

# 在独立会话里启动，返回真实服务 PID（python 进程 exec 后 PID 不变）。
launch_detached() {
    local log="$1"; shift
    if command -v python3 >/dev/null 2>&1; then
        python3 -c '
import os, sys
log = sys.argv[1]
cmd = sys.argv[2:]
try:
    os.setsid()
except OSError:
    pass
fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
os.dup2(fd, 1)
os.dup2(fd, 2)
os.close(fd)
os.execvp(cmd[0], cmd)
' "$log" "$@" >/dev/null 2>&1 &
    else
        nohup "$@" >"$log" 2>&1 &
    fi
    printf '%s\n' "$!"
}

wait_app_ready() {
    local port="$1" pid="$2" log="$3" i
    for i in $(seq 1 80); do
        if ! kill -0 "$pid" 2>/dev/null; then
            printf '!! 进程 %s 提前退出，日志尾部：\n' "$pid" >&2
            tail -n 40 "$log" >&2 || true
            return 1
        fi
        if port_listening "$port"; then return 0; fi
        sleep 0.5
    done
    printf '!! 等待 40 秒后端口 %s 仍未监听，日志尾部：\n' "$port" >&2
    tail -n 40 "$log" >&2 || true
    return 1
}

resolve_java() {
    if [[ -n "$JAVA_HOME_OPT" && -x "$JAVA_HOME_OPT/bin/java" ]]; then
        printf '%s\n' "$JAVA_HOME_OPT/bin/java"; return 0
    fi
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
        printf '%s\n' "${JAVA_HOME}/bin/java"; return 0
    fi
    if command -v java >/dev/null 2>&1; then
        command -v java; return 0
    fi
    return 1
}

read_pid() {
    local file="$1"
    [[ -f "$file" ]] && cat "$file" 2>/dev/null || true
}

# ---------- Consul ----------

start_consul() {
    step 'Consul'
    mkdir -p "$PID_DIR" "$LOG_DIR"

    if [[ "$SKIP_CONSUL" == "1" ]]; then
        if consul_ready; then
            info '指定了 --skip-consul，复用已在运行的 Consul（127.0.0.1:8500）'
            return 0
        fi
        die '--skip-consul 但 8500 上没有就绪的 Consul 在跑'
    fi

    if consul_ready; then
        info 'Consul 已就绪（127.0.0.1:8500），不重复启动'
        return 0
    fi

    if port_listening 8500 || pgrep -x consul >/dev/null 2>&1; then
        info '检测到 8500 已被占用或已有 consul agent 进程，跳过启动，等待其就绪...'
    else
        command -v consul >/dev/null 2>&1 \
            || die 'PATH 里找不到 consul，请把 consul 所在目录加入 PATH 后重试'
        local consul_bin pid
        consul_bin="$(command -v consul)"
        info "启动 ${consul_bin} agent -dev -bind=127.0.0.1 -client=127.0.0.1"
        pid="$(launch_detached "$LOG_DIR/consul.log" "$consul_bin" agent -dev \
            -bind=127.0.0.1 -client=127.0.0.1 -log-level=info)"
        printf '%s\n' "$pid" >"$PID_DIR/consul.pid"
        info "PID ${pid} 已记录到 run/pids/consul.pid，日志 run/logs/consul.log"
    fi

    if ! wait_consul_ready; then
        [[ -f "$LOG_DIR/consul.log" ]] && tail -n 30 "$LOG_DIR/consul.log" >&2 || true
        die 'Consul 40 秒内没有就绪，日志见上'
    fi
    info 'Consul 就绪：http://127.0.0.1:8500/ui'
}

# ---------- 应用 ----------

app_meta() {
    case "$1" in
        provider)
            MODULE="autumn-samples/autumn-sample-boot-provider"
            IMAGE="autumn-sample-boot-provider-native"
            SERVER_PORT=8080
            THRIFT_PORT=30880
            ;;
        consumer)
            MODULE="autumn-samples/autumn-sample-boot-consumer"
            IMAGE="autumn-sample-boot-consumer-native"
            SERVER_PORT=8081
            THRIFT_PORT=30881
            ;;
        *) die "未知服务：$1" ;;
    esac
}

build_app_args() {
    APP_LAUNCH_ARGS=()
    if [[ -n "$PROFILE" && "$APP_ARGS" != *"--spring.profiles.active="* ]]; then
        APP_LAUNCH_ARGS+=("--spring.profiles.active=$PROFILE")
    fi
    if [[ -n "$APP_ARGS" ]]; then
        # shellcheck disable=SC2206
        APP_LAUNCH_ARGS+=($APP_ARGS)
    fi
    if [[ "${#EXTRA_ARGS[@]}" -gt 0 ]]; then
        APP_LAUNCH_ARGS+=("${EXTRA_ARGS[@]}")
    fi

    local a has_http=0 has_thrift=0
    APP_HTTP_PORT="$SERVER_PORT"
    APP_THRIFT_PORT="$THRIFT_PORT"
    for a in "${APP_LAUNCH_ARGS[@]}"; do
        case "$a" in
            --server.port=*)           has_http=1;   APP_HTTP_PORT="${a#*=}" ;;
            --autumn.provider.port=*)  has_thrift=1; APP_THRIFT_PORT="${a#*=}" ;;
        esac
    done
    # 命令行参数优先级高于环境变量与配置文件，显式钉死端口
    if [[ "$has_http" -eq 0 ]]; then
        APP_LAUNCH_ARGS+=("--server.port=$SERVER_PORT")
    fi
    if [[ "$has_thrift" -eq 0 ]]; then
        APP_LAUNCH_ARGS+=("--autumn.provider.port=$THRIFT_PORT")
    fi
}

start_app() {
    local name="$1" pid_file pid jar staged log java_bin exe
    app_meta "$name"
    build_app_args
    pid_file="$PID_DIR/$name.pid"

    pid="$(read_pid "$pid_file")"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        info "$name 已在运行（PID ${pid}），跳过；重启请先 ./scripts/stop.sh"
        return 0
    fi
    rm -f "$pid_file"

    if port_listening "$APP_HTTP_PORT"; then
        die "端口 ${APP_HTTP_PORT} 已被占用（$name 可能已经在跑）。先 ./scripts/stop.sh"
    fi
    if port_listening "$APP_THRIFT_PORT"; then
        die "端口 ${APP_THRIFT_PORT} 已被占用。先 ./scripts/stop.sh"
    fi

    log="$LOG_DIR/$name-$(date +%Y%m%d-%H%M%S).log"

    if [[ "$RUNTIME" == "native" ]]; then
        exe="$ROOT/$MODULE/target/$IMAGE"
        [[ -x "$exe" ]] || die "找不到 native 镜像：${exe}（先跑 ./scripts/build-native.sh）"
        info "启动 native: ${exe}"
        pid="$(cd "$ROOT/$MODULE" && launch_detached "$log" "$exe" "${APP_LAUNCH_ARGS[@]}")"
    else
        jar="$(find "$ROOT/$MODULE/target" -maxdepth 1 -type f -name '*-exec.jar' -print -quit 2>/dev/null || true)"
        [[ -n "$jar" ]] || die "找不到 $MODULE/target/*-exec.jar（先构建：mvn -DskipTests clean install）"

        find "$STAGE_DIR" -maxdepth 1 -type f -name '*.jar' -mtime +1 -delete 2>/dev/null || true
        staged="$STAGE_DIR/$(basename "${jar%-exec.jar}")-$$.jar"
        cp "$jar" "$staged"

        java_bin="$(resolve_java)" || die '找不到 java：设置 JAVA_HOME 或用 --java-home 指定'
        info "启动 jar: ${staged}（源 ${jar}）"
        pid="$(cd "$ROOT/$MODULE" && launch_detached "$log" "$java_bin" -jar "$staged" "${APP_LAUNCH_ARGS[@]}")"
    fi

    [[ -n "$pid" ]] || die "$name 没有拿到进程号，看 $log"
    printf '%s\n' "$pid" >"$pid_file"

    if ! wait_app_ready "$APP_HTTP_PORT" "$pid" "$log"; then
        rm -f "$pid_file"
        die "$name 启动失败，日志：$log"
    fi

    info "$name 已启动：PID ${pid}，http://127.0.0.1:${APP_HTTP_PORT}，日志 ${log}"
}

print_summary() {
    step '已启动'
    local consul_pid name pid
    consul_pid="$(read_pid "$PID_DIR/consul.pid")"
    if port_listening 8500; then
        printf '    %-9s PID %-7s %s\n' 'consul' "${consul_pid:-?}" 'http://127.0.0.1:8500/ui'
    fi
    for name in "$@"; do
        pid="$(read_pid "$PID_DIR/$name.pid")"
        app_meta "$name"
        printf '    %-9s PID %-7s %s\n' "$name" "${pid:-?}" "http://127.0.0.1:${SERVER_PORT}"
    done
    printf '\n    一键停止：./scripts/stop.sh（默认停这两个应用；--with-consul 连 Consul 一起停）\n'
}

# ---------- 主流程 ----------

step '准备'
mkdir -p "$PID_DIR" "$LOG_DIR" "$STAGE_DIR"
info "仓库：${ROOT}"
info "运行时：${RUNTIME}，profile：${PROFILE:-<无>}"

if [[ "$SERVICE" == "consul" ]]; then
    start_consul
    exit 0
fi

if [[ "$SERVICE" == "both" ]]; then
    SERVICES=(provider consumer)
else
    SERVICES=("$SERVICE")
fi

start_consul

for name in "${SERVICES[@]}"; do
    step "$name"
    start_app "$name"
done

print_summary "${SERVICES[@]}"
