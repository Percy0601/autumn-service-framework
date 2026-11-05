#!/usr/bin/env bash
# Build the sample applications as GraalVM native images.
#
# Prerequisites:
#   - GraalVM JDK 25 with the native-image component (JAVA_HOME must point at it)
#   - Maven 3.9+ on PATH
#   - a running Consul agent is only needed at runtime, not for the build
#
# Usage:
#   ./scripts/build-native.sh                       # spring-cloud-examples provider + consumer
#   MODULES=autumn-sample/autumn-sample-boot-provider ./scripts/build-native.sh
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

MODULES="${MODULES:-autumn-spring-cloud-examples/autumn-spring-cloud-examples-provider,autumn-spring-cloud-examples/autumn-spring-cloud-examples-consumer}"

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
