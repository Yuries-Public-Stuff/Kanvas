#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This setup script is for macOS."
  exit 1
fi

if ! xcode-select -p >/dev/null 2>&1; then
  echo "Xcode Command Line Tools are required."
  xcode-select --install || true
  echo "Finish the Apple installer and rerun this script."
  exit 1
fi

if command -v brew >/dev/null 2>&1; then
  BREW="$(command -v brew)"
elif [[ -x /opt/homebrew/bin/brew ]]; then
  BREW=/opt/homebrew/bin/brew
elif [[ -x /usr/local/bin/brew ]]; then
  BREW=/usr/local/bin/brew
else
  echo "Homebrew is required for the one-command setup."
  echo "Install it from https://brew.sh and rerun."
  exit 1
fi

echo "==> Installing build dependencies"
"$BREW" install cmake ninja gradle
if ! /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
  "$BREW" install --cask temurin@21
fi

JAVA_HOME_VALUE="$(/usr/libexec/java_home -v 21)"
cat > "$ROOT/.kanvas-macos.env" <<EOF
export JAVA_HOME="$JAVA_HOME_VALUE"
export PATH="\$JAVA_HOME/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH"
EOF

chmod +x "$ROOT/scripts/build-macos.sh" "$ROOT/scripts/test-wake-parity-macos.sh" 2>/dev/null || true

echo
echo "macOS build environment is ready."
echo "Run:"
echo "  ./scripts/build-macos.sh"
