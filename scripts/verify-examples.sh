#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_CMD="${KANVAS_GRADLE:-gradle}"

examples=(
  "examples/kotlin-jvm-basic"
  "examples/compose-desktop-basic"
  "examples/compose-desktop-multimodule"
)

for example in "${examples[@]}"; do
  echo
  echo "==> Verifying $example"
  "$GRADLE_CMD" -p "$ROOT/$example" --no-daemon clean kanvasDoctor kanvasCompatibility kanvasBuild
done

echo
echo "All Kanvas examples built successfully."
