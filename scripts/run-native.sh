#!/usr/bin/env bash
#
# 前台单跑一个 GraalVM native 镜像（build-native.sh 的产物）。
# jar 版见 scripts/run-boot.sh；一键起三个服务见 scripts/start-native.sh。
#
# 用法:
#   ./scripts/run-native.sh autumn-sample-boot-provider --server.port=18080
#   ./scripts/run-native.sh autumn-samples/autumn-sample-boot-consumer --server.port=8081
#
set -euo pipefail

# IDE 注入的 SERVER__PORT 会被 Spring 宽松绑定当成 server.port
unset SERVER__PORT SERVER_PORT PORT 2>/dev/null || true

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="${1:?Usage: scripts/run-native.sh <module-path|artifact-id> [app args...]}"
shift

if [[ "$MODULE" == */* ]]; then
    MODULE_DIR="$ROOT/$MODULE"
elif [[ -d "$ROOT/autumn-samples/$MODULE" ]]; then
    MODULE_DIR="$ROOT/autumn-samples/$MODULE"
elif [[ -d "$ROOT/$MODULE" ]]; then
    MODULE_DIR="$ROOT/$MODULE"
else
    MODULE_DIR="$(find "$ROOT" -maxdepth 4 \
        \( -name .git -o -name target -o -name node_modules -o -name .workbuddy \) -prune -o \
        -type d -name "$MODULE" -print -quit 2>/dev/null || true)"
fi
[[ -n "$MODULE_DIR" && -d "$MODULE_DIR" ]] || { echo "module not found: $MODULE" >&2; exit 1; }

EXE="$MODULE_DIR/target/$(basename "$MODULE_DIR")-native"
[[ -x "$EXE" ]] || {
    echo "no native image found: $EXE; run: ./scripts/build-native.sh" >&2
    exit 1
}

echo "native: $EXE"
cd "$MODULE_DIR"
exec "$EXE" "$@"
