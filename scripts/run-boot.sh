#!/usr/bin/env bash
#
# 前台单跑一个 Spring Boot 可执行 jar（java -jar）。
# native 版见 scripts/run-native.sh；一键起三个服务见 scripts/start.sh。
#
# Windows 上 java -jar target/xxx.jar 会独占锁定 jar，导致 mvn clean install 的
# repackage 失败，所以先把 jar 复制到 run/ 下再跑，多个实例也能共存。
#
# 用法:
#   ./scripts/run-boot.sh autumn-sample-boot-provider --server.port=18080
#   ./scripts/run-boot.sh autumn-samples/autumn-sample-boot-consumer --server.port=8081
#
set -euo pipefail

# IDE / 容器注入的 SERVER__PORT 会被 Spring 宽松绑定当成 server.port，覆盖配置文件
unset SERVER__PORT SERVER_PORT PORT 2>/dev/null || true

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="${1:?Usage: scripts/run-boot.sh <module-path|artifact-id> [java args / app args...]}"
shift

# 先按约定路径找；全仓 find 会扫 .git 和各模块 target（macOS 实测 60 秒），只作兜底
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

JAR="$(find "$MODULE_DIR/target" -maxdepth 1 -type f -name '*-exec.jar' -print -quit 2>/dev/null || true)"
if [[ -z "$JAR" ]]; then
    echo "no *-exec.jar found under $MODULE_DIR/target; run: mvn -DskipTests clean install" >&2
    exit 1
fi

JAVA_BIN="java"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
elif ! command -v java >/dev/null 2>&1; then
    echo "java not found; set JAVA_HOME or put java on PATH" >&2
    exit 1
fi

STAGE_DIR="$ROOT/run"
mkdir -p "$STAGE_DIR"
find "$STAGE_DIR" -maxdepth 1 -type f -name '*.jar' -mtime +1 -delete 2>/dev/null || true

STAGED="$STAGE_DIR/$(basename "${JAR%-exec.jar}")-$$.jar"
cp "$JAR" "$STAGED"
cleanup() { rm -f "$STAGED" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

echo "staged: $STAGED (source: $JAR)"
echo "java:   $JAVA_BIN"
"$JAVA_BIN" -jar "$STAGED" "$@"
