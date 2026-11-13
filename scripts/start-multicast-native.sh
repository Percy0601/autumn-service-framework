#!/usr/bin/env bash
#
# GraalVM native 版 multicast 启动（Linux / macOS）：provider + consumer，不启动 Consul。
# 跑 build-native.sh 产出的 target/*-native 可执行文件，不经过 java -jar。
# jar 版见 scripts/start-multicast.sh。
#
# 用法:
#   ./scripts/start-multicast-native.sh
#   ./scripts/start-multicast-native.sh --service provider
#   MULTICAST_INTERFACE=en0 ./scripts/start-multicast-native.sh
#   ./scripts/start-multicast-native.sh -- --server.port=18080
#
# 前提: 镜像不存在时先跑 ./scripts/build-native.sh
# 停止: ./scripts/stop.sh --keep-consul（multicast 模式没有 Consul）
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$HERE/start-multicast.sh" --runtime native "$@"
