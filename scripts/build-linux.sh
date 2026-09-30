#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/native/build"
CLEAN=0
SKIP_TESTS=0
SKIP_KOTLIN=0
GRADLE_LAUNCHER=""

usage() {
  cat <<'EOF'
Usage: ./scripts/build-linux.sh [--clean] [--skip-tests] [--skip-kotlin] [--gradle-launcher PATH]

Builds the Linux X11/Vulkan native runtime and JNI bridge, runs native tests,
and builds the JVM renderer/Compose bridge/integration agent artifacts.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean) CLEAN=1 ;;
    --skip-tests) SKIP_TESTS=1 ;;
    --skip-kotlin) SKIP_KOTLIN=1 ;;
    --gradle-launcher)
      shift
      GRADLE_LAUNCHER="${1:-}"
      [[ -n "$GRADLE_LAUNCHER" ]] || { echo "--gradle-launcher requires a path."; exit 2; }
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1"; usage; exit 2 ;;
  esac
  shift
done

[[ "$(uname -s)" == "Linux" ]] || {
  echo "build-linux.sh must be run on Linux."
  exit 2
}

command -v cmake >/dev/null 2>&1 || { echo "cmake is required."; exit 1; }
command -v java >/dev/null 2>&1 || { echo "JDK 17+ is required."; exit 1; }

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="$(readlink -f "$(command -v java)")"
  export JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
fi

if command -v ninja >/dev/null 2>&1; then
  GENERATOR="Ninja"
elif command -v make >/dev/null 2>&1; then
  GENERATOR="Unix Makefiles"
else
  echo "Install Ninja or make."
  exit 1
fi

if command -v cc >/dev/null 2>&1; then
  CC_BIN="$(command -v cc)"
elif command -v gcc >/dev/null 2>&1; then
  CC_BIN="$(command -v gcc)"
elif command -v clang >/dev/null 2>&1; then
  CC_BIN="$(command -v clang)"
else
  echo "A C compiler is required."
  exit 1
fi

if [[ $CLEAN -eq 1 ]]; then
  rm -rf "$BUILD"
fi

cmake   -S "$ROOT/native"   -B "$BUILD"   -G "$GENERATOR"   -DCMAKE_BUILD_TYPE=Debug   -DCMAKE_C_COMPILER="$CC_BIN"   -DKD_REQUIRE_JNI=ON   -DKD_REQUIRE_VULKAN=ON

cmake --build "$BUILD"

if [[ $SKIP_TESTS -eq 0 ]]; then
  ctest --test-dir "$BUILD" --output-on-failure
fi

if [[ $SKIP_KOTLIN -eq 1 ]]; then
  echo "Linux native/JNI build finished. Kotlin build skipped."
  exit 0
fi

if [[ -n "$GRADLE_LAUNCHER" ]]; then
  [[ -f "$GRADLE_LAUNCHER" ]] || { echo "Gradle launcher not found: $GRADLE_LAUNCHER"; exit 1; }
  GRADLE="$GRADLE_LAUNCHER"
elif [[ -x "$ROOT/gradlew" ]]; then
  GRADLE="$ROOT/gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
else
  echo "Gradle wrapper/system Gradle is required for JVM artifacts."
  exit 1
fi

"$GRADLE" -p "$ROOT"   :renderer:jvmTest   :compose-bridge:test   :compose-gpu-demo:classes   :integration-agent:test   :integration-agent:jar

echo "Linux native/JNI and Kotlin builds finished."
