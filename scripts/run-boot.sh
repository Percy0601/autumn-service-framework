#!/usr/bin/env bash
# Launch a Spring Boot sample from a staged copy of its executable jar.
#
# Why: on Windows, `java -jar target/xxx.jar` holds an exclusive lock on that
# archive, so a concurrent `mvn clean install` fails in spring-boot:repackage.
# Staging outside target/ with a per-process name keeps the build tree unlocked,
# and lets multiple instances (e.g. the two-provider Consul smoke test) coexist.
#
# Usage:
#   ./scripts/run-boot.sh autumn-sample-boot-provider --server.port=18080
#   ./scripts/run-boot.sh autumn-sample/autumn-sample-boot-consumer --server.port=8081
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="${1:?Usage: scripts/run-boot.sh <module-path|artifact-id> [java args / app args...]}"
shift

if [[ "$MODULE" == */* ]]; then
    MODULE_DIR="$ROOT/$MODULE"
else
    MODULE_DIR="$(find "$ROOT" -type d -name "$MODULE" -not -path '*/target/*' -print -quit)"
fi
[[ -n "$MODULE_DIR" && -d "$MODULE_DIR" ]] || { echo "module not found: $MODULE" >&2; exit 1; }

JAR="$(find "$MODULE_DIR/target" -maxdepth 1 -type f -name '*-exec.jar' -print -quit)"
if [[ -z "$JAR" ]]; then
    echo "no *-exec.jar found under $MODULE_DIR/target; run: mvn -DskipTests clean install" >&2
    exit 1
fi

STAGE_DIR="$ROOT/run"
mkdir -p "$STAGE_DIR"
# Drop stale copies from previous hard-killed runs (never touch today's copies).
find "$STAGE_DIR" -maxdepth 1 -type f -name '*.jar' -mtime +1 -delete 2>/dev/null || true

STAGED="$STAGE_DIR/$(basename "${JAR%-exec.jar}")-$$.jar"
cp "$JAR" "$STAGED"
cleanup() { rm -f "$STAGED" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

echo "staged: $STAGED (source: $JAR)"
java -jar "$STAGED" "$@"
