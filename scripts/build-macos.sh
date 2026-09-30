#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/native/build"
ENV_FILE="$ROOT/.kanvas-macos.env"
CLEAN=0
SKIP_TESTS=0
ENABLE_VULKAN=0
GRADLE_LAUNCHER=""

usage() {
  cat <<'EOF'
Usage: ./scripts/build-macos.sh [--clean] [--skip-tests] [--vulkan] [--gradle-launcher PATH]

Default macOS build:
  - native C shared library
  - CTest
  - renderer JVM tests
  - Kotlin/Native macOS demo executable for the host CPU
  - Compose capture replay executable

Metal is the default macOS GPU backend. Vulkan is OFF by default until MoltenVK
is explicitly configured.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean) CLEAN=1 ;;
    --skip-tests) SKIP_TESTS=1 ;;
    --vulkan) ENABLE_VULKAN=1 ;;
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

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS is required."
  exit 1
fi

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

if ! xcode-select -p >/dev/null 2>&1; then
  echo "Missing Xcode Command Line Tools. Run ./scripts/setup-macos.sh first."
  exit 1
fi

for tool in cmake; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "Missing $tool. Run ./scripts/setup-macos.sh first."
    exit 1
  }
done

if command -v ninja >/dev/null 2>&1; then
  GENERATOR="Ninja"
else
  GENERATOR="Unix Makefiles"
fi

if [[ $CLEAN -eq 1 ]]; then
  rm -rf "$BUILD" "$ROOT/renderer/build"
fi

mkdir -p "$BUILD"

VULKAN_FLAG=OFF
if [[ $ENABLE_VULKAN -eq 1 ]]; then
  VULKAN_FLAG=ON
fi

echo "==> Configure native library ($(uname -m))"
cmake -S "$ROOT/native" -B "$BUILD" -G "$GENERATOR" \
  -DCMAKE_BUILD_TYPE=Debug \
  -DKD_ENABLE_VULKAN="$VULKAN_FLAG" \
  -DKD_REQUIRE_JNI=ON

echo "==> Build native library"
cmake --build "$BUILD" --config Debug

if [[ $SKIP_TESTS -eq 0 ]]; then
  echo "==> Native tests"
  ctest --test-dir "$BUILD" -C Debug --output-on-failure
fi

if [[ -n "$GRADLE_LAUNCHER" ]]; then
  [[ -f "$GRADLE_LAUNCHER" ]] || { echo "Gradle launcher not found: $GRADLE_LAUNCHER"; exit 1; }
  GRADLE="$GRADLE_LAUNCHER"
elif [[ -x "$ROOT/gradlew" ]]; then
  GRADLE="$ROOT/gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
else
  echo "Gradle is missing. Run ./scripts/setup-macos.sh first."
  exit 1
fi

ARCH="$(uname -m)"
case "$ARCH" in
  arm64|aarch64)
    TARGET_NAME="macosArm64"
    LINK_TASK=":renderer:linkDebugExecutableMacosArm64"
    REPLAY_LINK_TASK=":renderer:linkCaptureReplayDebugExecutableMacosArm64"
    ;;
  x86_64|amd64)
    TARGET_NAME="macosX64"
    LINK_TASK=":renderer:linkDebugExecutableMacosX64"
    REPLAY_LINK_TASK=":renderer:linkCaptureReplayDebugExecutableMacosX64"
    ;;
  *)
    echo "Unsupported macOS CPU architecture: $ARCH"
    exit 1
    ;;
esac

if [[ $SKIP_TESTS -eq 0 ]]; then
  echo "==> Kotlin JVM + Compose bridge tests"
  "$GRADLE" \
    :renderer:jvmTest \
    :compose-bridge:test \
    :compose-gpu-demo:classes \
    :integration-agent:test
fi

echo "==> Build Kanvas agent"
"$GRADLE" :integration-agent:jar

echo "==> Kotlin/Native host + capture replay executables"
"$GRADLE" "$LINK_TASK" "$REPLAY_LINK_TASK"

echo
echo "BUILD PASS"
echo "Native library : $BUILD/libkanvas_native.dylib"
echo "Host target    : $TARGET_NAME"
echo "Demo task      : $LINK_TASK"
echo "Replay task    : $REPLAY_LINK_TASK"
echo
echo "Next:"
echo "  ./scripts/run-macos-metal.sh"
