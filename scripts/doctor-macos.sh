#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    printf "OK   %s\n" "$name"
  else
    printf "MISS %s\n" "$name"
    FAIL=1
  fi
}

echo "Kotlin Display macOS build doctor"
echo "---------------------------------"
check "macOS" test "$(uname -s)" = "Darwin"
check "Xcode CLT" xcode-select -p
check "CMake" cmake --version
check "C compiler" xcrun --find clang
check "Gradle" gradle --version
check "JDK 21" /usr/libexec/java_home -v 21
check "CMakeLists" test -f "$ROOT/native/CMakeLists.txt"
check "renderer module" test -f "$ROOT/renderer/build.gradle.kts"

echo
echo "Architecture: $(uname -m)"
exit "$FAIL"
