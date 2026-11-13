#!/usr/bin/env bash
#
# 一键停止 autumn 示例服务（默认停 provider + consumer 两个后台进程）。
#
# 进程号来源，三重兜底（跟 Windows 的 scripts/stop.ps1 一致）：
#   1) run/pids/<service>.pid 里记录的 PID
#   2) 命令行反查：pgrep -f 'autumn-sample-boot-...'（pid 文件被清掉、手动起的也能找到）
#   3) Consul：run/pids/consul.pid → 8500 端口占用者（--all-consul 时按 'consul agent' 全杀）
#
# 用法:
#   ./scripts/stop.sh                 # 只停 provider + consumer
#   ./scripts/stop.sh --with-consul   # 连 Consul 一起停
#   ./scripts/stop.sh --consul-only   # 只停 Consul
#   ./scripts/stop.sh --all-consul    # Consul 全杀（含非本脚本起的实例）
#   ./scripts/stop.sh --dry-run       # 只列出将结束的进程
#
# 先 TERM，20 次 ×0.5s 还没退再 KILL，最后回查端口。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="$ROOT/run/pids"

DO_APPS=1
DO_CONSUL=0
ALL_CONSUL=0
DRY_RUN=0

info() { printf '    %s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apps-only)   DO_APPS=1; DO_CONSUL=0; shift ;;
        --consul-only) DO_APPS=0; DO_CONSUL=1; shift ;;
        --with-consul|--all) DO_CONSUL=1; shift ;;
        --all-consul)  DO_CONSUL=1; ALL_CONSUL=1; shift ;;
        --dry-run|-n)  DRY_RUN=1; shift ;;
        -h|--help)     sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             die "未知参数: $1（用 --help 查看用法）" ;;
    esac
done

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

port_owner() {
    local port="$1"
    # macOS 没有 ss，用 lsof -t 直接拿监听进程的 PID
    if command -v lsof >/dev/null 2>&1; then
        # lsof 无匹配时返回 1，pipefail 会把整条管道判失败，必须用 || true 兜住
        lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true
    elif command -v ss >/dev/null 2>&1; then
        ss -lntp "sport = :$port" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -n 1 | cut -d= -f2 || true
    fi
}

read_pid() {
    local file="$1"
    [[ -f "$file" ]] && cat "$file" 2>/dev/null || true
}

# TERM → 等 → KILL
stop_pid() {
    local pid="$1" label="$2" i
    [[ -n "$pid" ]] || return 0
    if ! kill -0 "$pid" 2>/dev/null; then
        return 0
    fi
    if [[ "$DRY_RUN" == "1" ]]; then
        info "[dry-run] 将结束 $label (PID $pid)"
        return 0
    fi
    info "结束 $label (PID $pid)"
    kill "$pid" 2>/dev/null || true
    for i in $(seq 1 20); do
        kill -0 "$pid" 2>/dev/null || return 0
        sleep 0.5
    done
    info "$label (PID $pid) 没响应 TERM，改用 KILL"
    kill -9 "$pid" 2>/dev/null || true
    sleep 0.2
}

STOPPED=0

stop_apps() {
    local name pid_file pid pid_list

    step '应用'
    for name in provider consumer; do
        pid_file="$PID_DIR/$name.pid"
        pid="$(read_pid "$pid_file")"
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            stop_pid "$pid" "$name"
            STOPPED=$((STOPPED + 1))
        elif [[ -n "$pid" ]]; then
            info "$name 记录的 PID $pid 已经不在了，清理 pid 文件"
        fi
        if [[ "$DRY_RUN" != "1" ]]; then
            rm -f "$pid_file"
        fi
    done

    # 兜底：pid 文件丢了 / 手动起的实例，按命令行找
    pid_list="$(pgrep -f 'autumn-sample-boot-(provider|consumer)' 2>/dev/null || true)"
    if [[ -n "$pid_list" ]]; then
        local extra
        for extra in $pid_list; do
            stop_pid "$extra" "$(ps -p "$extra" -o args= 2>/dev/null | cut -c1-60 || echo 'autumn-sample')"
            STOPPED=$((STOPPED + 1))
        done
    fi
}

stop_consul() {
    local pid_file="$PID_DIR/consul.pid" pid pid_list

    step 'Consul'
    pid="$(read_pid "$pid_file")"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        stop_pid "$pid" 'consul'
        STOPPED=$((STOPPED + 1))
    fi

    if [[ "$ALL_CONSUL" == "1" ]]; then
        pid_list="$(pgrep -x consul 2>/dev/null || true)"
        if [[ -n "$pid_list" ]]; then
            for pid in $pid_list; do
                stop_pid "$pid" 'consul agent'
                STOPPED=$((STOPPED + 1))
            done
        fi
    else
        pid="$(port_owner 8500)"
        if [[ -n "$pid" ]]; then
            stop_pid "$pid" 'consul (8500)'
            STOPPED=$((STOPPED + 1))
        fi
    fi

    if [[ "$DRY_RUN" != "1" ]]; then
        rm -f "$pid_file"
    fi
}

if [[ "$DO_APPS" == "1" ]]; then
    stop_apps
fi
if [[ "$DO_CONSUL" == "1" ]]; then
    stop_consul
fi

if [[ "$STOPPED" -eq 0 ]]; then
    step '没有找到运行中的进程'
    info '应用与 Consul 都没有在跑，或者已经被停掉了'
fi

if [[ "$DRY_RUN" == "1" ]]; then
    step 'dry-run 结束，未做任何修改'
    exit 0
fi

# 回查端口
step '回查端口'
PORTS=()
if [[ "$DO_APPS" == "1" ]]; then
    PORTS+=(8080 8081 30880 30881)
fi
if [[ "$DO_CONSUL" == "1" ]]; then
    PORTS+=(8500)
fi

busy=0
if [[ "${#PORTS[@]}" -gt 0 ]]; then
    for _ in $(seq 1 10); do
        busy=0
        for p in "${PORTS[@]}"; do
            if port_listening "$p"; then busy=1; fi
        done
        [[ "$busy" -eq 0 ]] && break
        sleep 1
    done
fi

if [[ "$busy" -eq 0 ]]; then
    printf '    端口全部已释放。\n'
else
    for p in "${PORTS[@]}"; do
        if port_listening "$p"; then
            printf '    端口 %s 仍被占用：PID %s\n' "$p" "$(port_owner "$p" || true)"
        fi
    done
    printf '    有端口没放开，可能需要更高权限再执行一次。\n' >&2
    exit 1
fi
