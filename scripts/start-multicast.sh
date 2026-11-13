#!/usr/bin/env bash
#
# multicast 注册发现模式一键启动（Linux / macOS）：provider + consumer，不启动 Consul。
# 组播由框架自带 MulticastBackend 实现，不需要任何外部注册中心。
#
# 用法:
#   ./scripts/start-multicast.sh
#   ./scripts/start-multicast.sh --service provider
#   RUNTIME=native ./scripts/start-multicast.sh
#   MULTICAST_IP=224.0.0.1 MULTICAST_PORT=5555 ./scripts/start-multicast.sh
#   MULTICAST_INTERFACE=en0 ./scripts/start-multicast.sh   # 多网卡时指定组播出口
#   ./scripts/start-multicast.sh -- --server.port=18080
#
# 说明:
#   - --spring.profiles.active=multicast 加载 application-multicast.properties
#   - 同时显式传 --autumn.registry-type=multicast：native 镜像 AOT 编译时可能没有
#     这个 profile 资源，命令行参数优先级最高，jar / native 都能生效
#   - 端口钉死、独立会话保活、环境变量清理、就绪等待等全部复用 start.sh
#
# 停止: ./scripts/stop.sh --keep-consul（multicast 模式没有 Consul）
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

REGISTRY_ARGS="--autumn.registry-type=multicast"
if [[ -n "${MULTICAST_IP:-}" ]]; then
    REGISTRY_ARGS="$REGISTRY_ARGS --autumn.multicast.ip=${MULTICAST_IP}"
fi
if [[ -n "${MULTICAST_PORT:-}" ]]; then
    REGISTRY_ARGS="$REGISTRY_ARGS --autumn.multicast.port=${MULTICAST_PORT}"
fi
if [[ -n "${MULTICAST_INTERFACE:-}" ]]; then
    REGISTRY_ARGS="$REGISTRY_ARGS --autumn.network-interface=${MULTICAST_INTERFACE}"
fi

export APP_ARGS="${APP_ARGS:-} $REGISTRY_ARGS"

ARGS=(--no-consul --profile multicast)
if [[ -n "${RUNTIME:-}" ]]; then
    ARGS+=(--runtime "$RUNTIME")
fi
ARGS+=("$@")

exec "$HERE/start.sh" "${ARGS[@]}"
