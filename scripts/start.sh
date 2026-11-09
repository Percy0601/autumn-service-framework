#!/usr/bin/env bash
#
# 启动 Consul + autumn 示例服务（后台运行，PID 记到 run/pids，日志写到 run/logs）。
#
# 与 Windows 的 scripts/start.ps1 行为对齐：
#   - Consul 已经在 8500 上就绪（/v1/status/leader 可用），或端口已被占用 / 已有 consul agent 进程时
#     **不再重复启动**，直接复用
#   - 每个服务一个后台进程，PID 写在 run/pids/<service>.pid，stop.sh 一键停
#   - jar 先复制到 run/staged 再跑，target 里的产物不会被运行中的进程占用
#   - 只想前台单跑一个服务，仍可用 scripts/run-boot.sh
#
# 用法:
#   ./scripts/start.sh                          # consul + provider + consumer
#   ./scripts/start.sh --service provider       # 只起 provider
#   ./scripts/start.sh --skip-consul            # 复用已在跑的 consul
#   ./scripts/start.sh --runtime native         # 跑 build-native.sh 产出的 native 镜像
#   ./scripts/start.sh --profile ""             # 不传 spring profile
#   ./scripts/start.sh --service provider -- --server.port=18080 --autumn.provider.port=30882
#                                               # -- 之后的参数原样传给应用
#
# 环境变量: CONSUL_HOME(默认 /opt/consul)、JAVA_HOME、APP_ARGS(空格分隔的附加参数)
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/run"
PID_DIR="$RUN_DIR/pids"
LOG_DIR="$RUN_DIR/logs"
STAGE_DIR="$RUN_DIR/staged"

SERVICE="both"
RUNTIME="jar"
PROFILE="consul"
CONSUL_HOME="${CONSUL_HOME:-/opt/consul}"
JAVA_HOME_OPT="${JAVA_HOME:-}"
SKIP_CONSUL=0
APP_ARGS="${APP_ARGS:-}"
EXTRA_ARGS=()

info() { printf '    %s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }

usage() {
    sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--service)  SERVICE="${2:?--service 缺少取值}"; shift 2 ;;
        -r|--runtime)  RUNTIME="${2:?--runtime 缺少取值}"; shift 2 ;;
        --profile)     [[ $# -ge 2 ]] || die '--profile 缺少取值（不要 profile 就传空字符串 ""）'
                       PROFILE="$2"; shift 2 ;;
        --java-home)   JAVA_HOME_OPT="${2:?--java-home 缺少取值}"; shift 2 ;;
        --consul-home) CONSUL_HOME="${2:?--consul-home 缺少取值}"; shift 2 ;;
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
    if command -v ss >/dev/null 2>&1; then
        ss -lntH "sport = :$port" 2>/dev/null | grep -q .
    elif command -v lsof >/dev/null 2>&1; then
        lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
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

resolve_consul_bin() {
    local candidate
    for candidate in "$CONSUL_HOME/consul" "$CONSUL_HOME/bin/consul"; do
        if [[ -x "$candidate" ]]; then printf '%s\n' "$candidate"; return 0; fi
    done
    if command -v consul >/dev/null 2>&1; then
        command -v consul; return 0
    fi
    for candidate in /opt/consul*/consul /opt/*/consul; do
        if [[ -x "$candidate" ]]; then printf '%s\n' "$candidate"; return 0; fi
    done
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
        local bin
        if ! bin="$(resolve_consul_bin)"; then
            die "找不到 consul 可执行文件：$CONSUL_HOME 下没有，/opt/consul* 和 PATH 里也没有（用 --consul-home 指定）"
        fi
        info "启动 $bin agent -dev -bind=127.0.0.1 -client=127.0.0.1"
        nohup "$bin" agent -dev -bind=127.0.0.1 -client=127.0.0.1 -log-level=info \
            >"$LOG_DIR/consul.log" 2>&1 &
        echo $! >"$PID_DIR/consul.pid"
        info "PID $! 已记录到 run/pids/consul.pid，日志 run/logs/consul.log"
    fi

    if ! wait_consul_ready; then
        [[ -f "$LOG_DIR/consul.log" ]] && tail -n 30 "$LOG_DIR/consul.log" || true
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
}

start_app() {
    local name="$1" pid_file pid jar staged log java_bin exe pid_file_dir
    app_meta "$name"
    build_app_args
    pid_file="$PID_DIR/$name.pid"

    # 已经有活着实例就不再起第二个
    pid="$(read_pid "$pid_file")"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        info "$name 已在运行（PID $pid），跳过；重启请先 ./scripts/stop.sh"
        return 0
    fi
    rm -f "$pid_file"

    if [[ "$APP_ARGS" != *"--server.port"* && "${EXTRA_ARGS[*]:-}" != *"--server.port"* ]] && port_listening "$SERVER_PORT"; then
        die "端口 $SERVER_PORT 已被占用（$name 可能已经在跑）。先 ./scripts/stop.sh"
    fi
    if [[ "$APP_ARGS" != *"--autumn.provider.port"* && "${EXTRA_ARGS[*]:-}" != *"--autumn.provider.port"* ]] && port_listening "$THRIFT_PORT"; then
        die "端口 $THRIFT_PORT 已被占用。先 ./scripts/stop.sh"
    fi

    log="$LOG_DIR/$name-$(date +%Y%m%d-%H%M%S).log"

    if [[ "$RUNTIME" == "native" ]]; then
        exe="$ROOT/$MODULE/target/$IMAGE"
        [[ -x "$exe" ]] || die "找不到 native 镜像：$exe（先跑 ./scripts/build-native.sh）"
        info "启动 native: $exe"
        (
            cd "$ROOT/$MODULE"
            nohup "$exe" "${APP_LAUNCH_ARGS[@]+"${APP_LAUNCH_ARGS[@]}"}" >"$log" 2>&1 &
            echo $! >"$pid_file"
        )
    else
        jar="$(find "$ROOT/$MODULE/target" -maxdepth 1 -type f -name '*-exec.jar' -print -quit 2>/dev/null || true)"
        [[ -n "$jar" ]] || die "找不到 $MODULE/target/*-exec.jar（先构建：mvn -DskipTests clean install）"

        # 只清理一天前的残留副本，当天的副本可能正被别的实例使用
        find "$STAGE_DIR" -maxdepth 1 -type f -name '*.jar' -mtime +1 -delete 2>/dev/null || true
        staged="$STAGE_DIR/$(basename "${jar%-exec.jar}")-$$.jar"
        cp "$jar" "$staged"

        if ! java_bin="$(resolve_java)"; then
            die '找不到 java：设置 JAVA_HOME 或用 --java-home 指定'
        fi
        info "启动 jar: $staged（源 $jar）"
        (
            cd "$ROOT/$MODULE"
            nohup "$java_bin" -jar "$staged" "${APP_LAUNCH_ARGS[@]+"${APP_LAUNCH_ARGS[@]}"}" >"$log" 2>&1 &
            echo $! >"$pid_file"
        )
    fi

    pid="$(read_pid "$pid_file")"
    [[ -n "$pid" ]] || die "$name 没有拿到进程号，看 $log"
    sleep 3
    if ! kill -0 "$pid" 2>/dev/null; then
        tail -n 30 "$log" 2>/dev/null || true
        rm -f "$pid_file"
        die "$name 启动失败（退出码见日志），日志：$log"
    fi

    info "$name 已启动：PID $pid，http://127.0.0.1:$SERVER_PORT，日志 $log"
    LAST_PID="$pid"
    LAST_LOG="$log"
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
        printf '    %-9s PID %-7s %s\n' "$name" "${pid:-?}" "http://127.0.0.1:$SERVER_PORT"
    done
    printf '\n    一键停止：./scripts/stop.sh（默认停这两个应用；--with-consul 连 Consul 一起停）\n'
}

# ---------- 主流程 ----------

step '准备'
mkdir -p "$PID_DIR" "$LOG_DIR" "$STAGE_DIR"
info "仓库：$ROOT"
info "运行时：$RUNTIME，profile：${PROFILE:-<无>}"

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
