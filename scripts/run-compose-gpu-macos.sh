#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.kotlin-display-macos.env"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS is required."
  exit 1
fi

[[ -f "$ENV_FILE" ]] && source "$ENV_FILE"

"$ROOT/scripts/build-macos.sh" --skip-tests

if [[ -x "$ROOT/gradlew" ]]; then
  GRADLE="$ROOT/gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
else
  echo "Gradle is missing."
  exit 1
fi

export DYLD_LIBRARY_PATH="$ROOT/native/build:${DYLD_LIBRARY_PATH:-}"

echo "==> Compose runtime -> Kotlin Display DisplayList -> Metal"
exec "$GRADLE" -p "$ROOT"   -Dkotlin.display.home="$ROOT"   -Dkotlin.display.backend=metal   :compose-gpu-demo:run
