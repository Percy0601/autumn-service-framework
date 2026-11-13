#!/usr/bin/env bash
#
# GraalVM native 一键启动：Consul + provider + consumer（后台）。
# 跑的是 build-native.sh 产出的 target/*-native 可执行文件，不经过 java -jar。
# java -jar 版见 scripts/start.sh。
#
# 用法:
#   ./scripts/start-native.sh                       # consul + provider + consumer
#   ./scripts/start-native.sh --service provider    # 只起 provider
#   ./scripts/start-native.sh --skip-consul         # 复用已在跑的 Consul
#   ./scripts/start-native.sh --profile ""          # 不传 spring profile
#   ./scripts/start-native.sh --service provider -- --server.port=18080
#
# 前提: consul 在 PATH；镜像不存在时先跑 ./scripts/build-native.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$HERE/start.sh" --runtime native "$@"
