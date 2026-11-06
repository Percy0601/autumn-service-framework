#!/usr/bin/env bash
# Build the sample applications as GraalVM native images.
#
# Prerequisites:
#   - GraalVM JDK 25 with the native-image component (JAVA_HOME must point at it)
#   - Maven 3.9+ on PATH
#   - on Windows: Visual Studio 2022 C++ toolchain, and the shell must have run
#     "...\VC\Auxiliary\Build\vcvars64.bat" (otherwise native-image reports
#     "Failed to automatically set up Windows build environment")
#   - a running Consul agent is only needed at runtime, not for the build
#
# Reflection / resource / dynamic-proxy metadata is generated at build time by the
# JSR-269 processors (META-INF/native-image/com.micro-app/autumn-*-hints/) and by
# Spring AOT RuntimeHints; no -H:*ConfigurationResources flags and no hand-written
# reflect-config.json are needed.
#
# Usage:
#   ./scripts/build-native.sh                       # autumn-samples boot provider + consumer
#   MODULES=autumn-samples/autumn-sample-boot-provider ./scripts/build-native.sh
#   MODULES=autumn-samples/autumn-spring-cloud-examples/autumn-spring-cloud-examples-provider,autumn-samples/autumn-spring-cloud-examples/autumn-spring-cloud-examples-consumer ./scripts/build-native.sh
#   SKIP_INSTALL=1 ./scripts/build-native.sh         # reuse already installed snapshots
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

: "${JAVA_HOME:?JAVA_HOME must point to a GraalVM installation}"
if [ ! -x "$JAVA_HOME/bin/native-image" ]; then
  echo "native-image not found at $JAVA_HOME/bin/native-image" >&2
  exit 1
fi
echo "GraalVM: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo "native-image: $($JAVA_HOME/bin/native-image --version 2>&1 | head -1)"

MODULES="${MODULES:-autumn-samples/autumn-sample-boot-provider,autumn-samples/autumn-sample-boot-consumer}"

if [ "${SKIP_INSTALL:-0}" != "1" ]; then
  echo "[1/2] install all modules (JDK 25)"
  mvn -o -B -DskipTests clean install
fi

echo "[2/2] AOT + native-image compile: $MODULES"
mvn -o -B -Pnative -DskipTests -pl "$MODULES" native:compile

echo "native images:"
for m in ${MODULES//,/ }; do
  find "$m/target" -maxdepth 1 -type f -perm -u+x -printf "  %p\n" 2>/dev/null || true
done
