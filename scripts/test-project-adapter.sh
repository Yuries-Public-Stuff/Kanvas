#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -x "$ROOT/gradlew" ]]; then
  KD_GRADLE="$ROOT/gradlew"
elif command -v gradle >/dev/null 2>&1; then
  KD_GRADLE="$(command -v gradle)"
else
  echo "Gradle is required for the adapter smoke test."
  exit 1
fi

echo "==> Build renderer audit/capture agent"
"$KD_GRADLE" -p "$ROOT" :integration-agent:jar

echo
echo "==> Adapter doctor against Kotlin Display itself"
"$ROOT/scripts/kd-project.sh" "$ROOT" \
  --skip-runtime \
  --doctor \
  --build-task :renderer:jvmTest

echo
echo "==> Adapter build routing against Kotlin Display itself"
"$ROOT/scripts/kd-project.sh" "$ROOT" \
  --skip-runtime \
  --build \
  --build-task :renderer:jvmTest

echo
echo "External-project adapter smoke: PASS"
