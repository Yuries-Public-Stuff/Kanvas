#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/native/build"
GRADLE_LAUNCHER=""
BUILD_TYPE="Release"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --gradle-launcher)
      shift
      GRADLE_LAUNCHER="${1:-}"
      ;;
    --build-type)
      shift
      BUILD_TYPE="${1:-}"
      ;;
    *)
      echo "Unknown option: $1"
      exit 2
      ;;
  esac
  shift
done

command -v cmake >/dev/null 2>&1 || { echo "cmake is required."; exit 1; }
command -v java >/dev/null 2>&1 || { echo "JDK 17+ is required."; exit 1; }

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ "$(uname -s)" == "Darwin" ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)"
  else
    JAVA_BIN="$(readlink -f "$(command -v java)")"
    export JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
  fi
fi

if command -v ninja >/dev/null 2>&1; then
  GENERATOR="Ninja"
elif command -v make >/dev/null 2>&1; then
  GENERATOR="Unix Makefiles"
else
  echo "ninja or make is required."
  exit 1
fi

CMAKE_ARGS=(
  -S "$ROOT/native"
  -B "$BUILD"
  -G "$GENERATOR"
  -DCMAKE_BUILD_TYPE="$BUILD_TYPE"
  -DBUILD_TESTING=OFF
  -DKD_REQUIRE_JNI=ON
)

case "$(uname -s)" in
  Linux)
    CMAKE_ARGS+=(-DKD_REQUIRE_VULKAN=ON)
    ;;
  Darwin)
    CMAKE_ARGS+=(-DKD_ENABLE_VULKAN=OFF)
    ;;
  *)
    echo "Unsupported host."
    exit 1
    ;;
esac

cmake "${CMAKE_ARGS[@]}"
cmake --build "$BUILD" --target kanvas_native

if [[ -n "$GRADLE_LAUNCHER" ]]; then
  GRADLE="$GRADLE_LAUNCHER"
elif [[ -x "$ROOT/gradlew" ]]; then
  GRADLE="$ROOT/gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
else
  echo "Gradle is required to build the Kanvas agent."
  exit 1
fi

if [[ -x "$GRADLE" ]]; then
  "$GRADLE" -p "$ROOT" :integration-agent:jar
else
  bash "$GRADLE" -p "$ROOT" :integration-agent:jar
fi
echo "Kanvas runtime ready."
