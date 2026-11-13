#!/usr/bin/env bash
# Build the sample applications as GraalVM native images.
#
# Prerequisites:
#   - GraalVM JDK 25 with the native-image component (auto-detected, see below)
#   - Maven 3.9+ on PATH
#   - on macOS: Xcode Command Line Tools ("xcode-select --install");
#     any extra native library must be installed with Homebrew (brew install <lib>)
#   - on Windows: Visual Studio 2022 C++ toolchain, and the shell must have run
#     "...\VC\Auxiliary\Build\vcvars64.bat" (otherwise native-image reports
#     "Failed to automatically set up Windows build environment")
#   - a running Consul agent is only needed at runtime, not for the build
#
# JAVA_HOME / GraalVM discovery (in order):
#   1. JAVA_HOME / GRAALVM_HOME if they contain bin/native-image
#   2. the JDK owning native-image (or java) on PATH
#   3. standard install locations, including macOS <bundle>/Contents/Home
#   4. /usr/libexec/java_home (macOS)
# On macOS a bundle root such as .../graalvm-community-openjdk-25.0.1+8.1
# is accepted and normalized to .../Contents/Home automatically.
#
# Reflection / resource / dynamic-proxy metadata is generated at build time by the
# JSR-269 processors (META-INF/native-image/xyz.we-win.autumn/autumn-*-hints/) and by
# Spring AOT RuntimeHints; no -H:*ConfigurationResources flags and no hand-written
# reflect-config.json are needed.
#
# Usage:
#   ./scripts/build-native.sh                       # autumn-samples boot provider + consumer
#   MODULES=autumn-samples/autumn-sample-boot-provider ./scripts/build-native.sh
#   MODULES=autumn-samples/autumn-spring-cloud-examples/autumn-spring-cloud-examples-provider,autumn-samples/autumn-spring-cloud-examples/autumn-spring-cloud-examples-consumer ./scripts/build-native.sh
#   SKIP_INSTALL=1 ./scripts/build-native.sh         # reuse already installed snapshots
#   CHECK_ONLY=1 ./scripts/build-native.sh           # verify toolchain only, build nothing
#   FORCE_UPDATE=1 ./scripts/build-native.sh         # add -U to force remote metadata refresh
#   GRAALVM_HOME=/path/to/graalvm ./scripts/build-native.sh
#
# -U is OFF by default: all internal modules are freshly installed locally and
# every external dependency is a pinned release, so forcing a metadata refresh
# only adds network calls. It also triggers "Checksum validation failed, no
# checksums available" warnings on mirrors (e.g. maven.aliyun.com) that do not
# publish .sha1/.md5 for group-level maven-metadata.xml files.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

is_graalvm_home() {
  local home="$1"
  [ -n "$home" ] || return 1
  [ -x "$home/bin/java" ] || return 1
  # native-image is a symlink on macOS/Linux and a .cmd on Windows (Git Bash/MSYS)
  { [ -x "$home/bin/native-image" ] || [ -f "$home/bin/native-image.cmd" ]; } || return 1
  return 0
}

# Accept either the JDK home itself or a macOS .jdk bundle root.
normalize_home() {
  local home="${1%/}"
  [ -n "$home" ] || return 1
  if is_graalvm_home "$home"; then
    printf '%s\n' "$home"
    return 0
  fi
  if is_graalvm_home "$home/Contents/Home"; then
    printf '%s\n' "$home/Contents/Home"
    return 0
  fi
  return 1
}

detect_graalvm_home() {
  local cand home bin pattern cmd
  for cand in "${JAVA_HOME:-}" "${GRAALVM_HOME:-}"; do
    if home="$(normalize_home "$cand")"; then
      printf '%s\n' "$home"
      return 0
    fi
  done
  for cmd in native-image java; do
    if command -v "$cmd" >/dev/null 2>&1; then
      bin="$(cd "$(dirname "$(command -v "$cmd")")" && pwd)" || continue
      if home="$(normalize_home "$(dirname "$bin")")"; then
        printf '%s\n' "$home"
        return 0
      fi
    fi
  done
  for pattern in \
      /Library/Java/JavaVirtualMachines/graalvm*/Contents/Home \
      "$HOME"/Library/Java/JavaVirtualMachines/graalvm*/Contents/Home \
      "$HOME"/opt/graalvm*/Contents/Home \
      "$HOME"/opt/graalvm* \
      /opt/graalvm*/Contents/Home \
      /opt/graalvm* \
      /usr/lib/jvm/graalvm*; do
    for cand in $pattern; do
      if home="$(normalize_home "$cand")"; then
        printf '%s\n' "$home"
        return 0
      fi
    done
  done
  if [ -x /usr/libexec/java_home ]; then
    if cand="$(/usr/libexec/java_home -v 25 2>/dev/null)" && home="$(normalize_home "$cand")"; then
      printf '%s\n' "$home"
      return 0
    fi
  fi
  return 1
}

if ! GRAALVM_HOME_DETECTED="$(detect_graalvm_home)"; then
  cat >&2 <<'EOF'
ERROR: no GraalVM with the native-image component found.

Set one of these (bundle root or JDK home both work on macOS):
  export JAVA_HOME=/Users/mac/opt/graalvm-community-openjdk-25.0.1+8.1/Contents/Home
  export GRAALVM_HOME=/Users/mac/opt/graalvm-community-openjdk-25.0.1+8.1
  export PATH="$JAVA_HOME/bin:$PATH"

Note: adding GraalVM's bin to PATH alone is NOT enough for this script on some
shells; JAVA_HOME must be exported (or let the script auto-detect it).
EOF
  exit 1
fi

# Export so that Maven and the native:compile fork both use this exact JDK.
export JAVA_HOME="$GRAALVM_HOME_DETECTED"
export GRAALVM_HOME="$GRAALVM_HOME_DETECTED"
export PATH="$JAVA_HOME/bin:$PATH"

if [ "$(uname -s)" = "Darwin" ] && ! xcode-select -p >/dev/null 2>&1; then
  echo "ERROR: Xcode Command Line Tools required: xcode-select --install" >&2
  echo "       (extra native libs, if any, should be installed with: brew install <lib>)" >&2
  exit 1
fi

JAVA_VERSION_LINE="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
JAVA_MAJOR="$(printf '%s\n' "$JAVA_VERSION_LINE" | sed -E 's/.*version "([0-9]+).*/\1/')"
echo "GraalVM: $JAVA_VERSION_LINE"
echo "         $JAVA_HOME"
echo "native-image: $("$JAVA_HOME/bin/native-image" --version 2>&1 | head -1)"
if ! command -v mvn >/dev/null 2>&1; then
  cat >&2 <<'EOF'
ERROR: Maven 3.9+ not found on PATH.
  export PATH="/Users/mac/opt/apache-maven-3.9.12/bin:$PATH"
  # or: brew install maven
EOF
  exit 1
fi
echo "Maven: $(mvn -v 2>&1 | head -1)"
if [ "$JAVA_MAJOR" != "25" ]; then
  echo "WARNING: this project targets JDK 25, detected JDK $JAVA_MAJOR (build may fail)" >&2
fi

if [ "${CHECK_ONLY:-0}" = "1" ]; then
  echo "CHECK_ONLY=1: toolchain OK, nothing built."
  exit 0
fi

MODULES="${MODULES:-autumn-samples/autumn-sample-boot-provider,autumn-samples/autumn-sample-boot-consumer}"

MVN_UPDATE=""
if [ "${FORCE_UPDATE:-0}" = "1" ]; then
  MVN_UPDATE="-U"
fi

if [ "${SKIP_INSTALL:-0}" != "1" ]; then
  echo "[1/2] install all modules (JDK 25)"
  mvn -B ${MVN_UPDATE:-} -DskipTests clean install
fi

echo "[2/2] AOT + native-image compile: $MODULES"
mvn -B ${MVN_UPDATE:-} -Pnative -DskipTests -pl "$MODULES" native:compile

echo "native images:"
for m in ${MODULES//,/ }; do
  # POSIX-friendly: GNU find's -printf is unavailable on BSD/macOS find.
  while IFS= read -r f; do
    if [ -f "$f" ] && [ -x "$f" ]; then
      printf '  %s\n' "$f"
    fi
  done < <(find "$m/target" -maxdepth 1 -type f -print 2>/dev/null)
done
