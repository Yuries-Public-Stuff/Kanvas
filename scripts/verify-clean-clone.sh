#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_URL="${1:-$(git -C "$ROOT" remote get-url origin)}"
REF="${2:-$(git -C "$ROOT" rev-parse HEAD)}"
TMP="$(mktemp -d)"

cleanup() {
  rm -rf "$TMP"
}
trap cleanup EXIT

echo "Cloning $REPO_URL"
git clone --quiet "$REPO_URL" "$TMP/Kanvas"
git -C "$TMP/Kanvas" checkout --quiet "$REF"

echo "Verifying clean checkout at $REF"
"$TMP/Kanvas/scripts/verify-examples.sh"
