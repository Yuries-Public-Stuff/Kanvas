#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.kanvas-macos.env"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS is required."
  exit 1
fi

[[ -f "$ENV_FILE" ]] && source "$ENV_FILE"

case "$(uname -m)" in
  arm64|aarch64) TARGET="macosArm64" ;;
  x86_64|amd64) TARGET="macosX64" ;;
  *) echo "Unsupported architecture: $(uname -m)"; exit 1 ;;
esac

"$ROOT/scripts/build-macos.sh" --skip-tests

BIN_DIR="$ROOT/renderer/build/bin/$TARGET/debugExecutable"
APP="$(find "$BIN_DIR" -maxdepth 1 -type f -perm -111 2>/dev/null | head -n 1 || true)"
if [[ -z "$APP" ]]; then
  echo "Could not find Kotlin/Native demo under $BIN_DIR"
  exit 1
fi

export DYLD_LIBRARY_PATH="$ROOT/native/build:${DYLD_LIBRARY_PATH:-}"
echo "==> Launching Metal demo: $APP"
exec "$APP" --metal
