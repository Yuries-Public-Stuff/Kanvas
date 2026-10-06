#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/native/build-release-check"

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

if [[ -x "$ROOT/gradlew" ]]; then
  GRADLE="$ROOT/gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
else
  echo "Gradle is required."
  exit 1
fi

run_gradle() {
  if [[ -x "$GRADLE" ]]; then
    "$GRADLE" "$@"
  else
    bash "$GRADLE" "$@"
  fi
}

echo "==> Plugin tests"
run_gradle -p "$ROOT/gradle-plugin" test --stacktrace

CMAKE_ARGS=(
  -S "$ROOT/native"
  -B "$BUILD"
  -G "$GENERATOR"
  -DCMAKE_BUILD_TYPE=Release
  -DBUILD_TESTING=ON
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
    echo "Unsupported Unix host."
    exit 1
    ;;
esac

echo "==> Native build"
cmake "${CMAKE_ARGS[@]}"
cmake --build "$BUILD"

echo "==> Native tests"
if [[ "$(uname -s)" == "Linux" ]] && command -v xvfb-run >/dev/null 2>&1; then
  xvfb-run -a ctest --test-dir "$BUILD" --output-on-failure
else
  ctest --test-dir "$BUILD" --output-on-failure
fi

echo "==> Agent"
run_gradle -p "$ROOT" :integration-agent:test :integration-agent:jar --stacktrace

echo "Kanvas release check passed."
