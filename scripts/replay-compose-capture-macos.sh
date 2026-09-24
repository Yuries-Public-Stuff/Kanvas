#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.kotlin-display-macos.env"

if [[ $# -lt 1 ]]; then
  echo "Usage: ./scripts/replay-compose-capture-macos.sh path/to/file.kdcap"
  exit 2
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS is required."
  exit 1
fi

[[ -f "$ENV_FILE" ]] && source "$ENV_FILE"

CAPTURE="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
case "$(uname -m)" in
  arm64|aarch64) TARGET="macosArm64" ;;
  x86_64|amd64) TARGET="macosX64" ;;
  *) echo "Unsupported architecture: $(uname -m)"; exit 1 ;;
esac

"$ROOT/scripts/build-macos.sh" --skip-tests

BIN_DIR="$ROOT/renderer/build/bin/$TARGET/captureReplayDebugExecutable"
APP="$(find "$BIN_DIR" -maxdepth 1 -type f -perm -111 2>/dev/null | head -n 1 || true)"
if [[ -z "$APP" ]]; then
  echo "Could not find capture replay executable under $BIN_DIR"
  exit 1
fi

export DYLD_LIBRARY_PATH="$ROOT/native/build:${DYLD_LIBRARY_PATH:-}"
echo "==> Replaying $CAPTURE through Metal"
exec "$APP" "$CAPTURE" --backend=metal
