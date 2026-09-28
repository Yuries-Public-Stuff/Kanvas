#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WAKE_DIR="${WAKE_DIR:-$ROOT/../wake}"
EXPECTED_BRANCH="main"
BACKEND="${KD_BACKEND:-vulkan}"
LAUNCH_STRICT="${KD_LAUNCH_STRICT:-0}"
REPORT_DIR="$ROOT/build/parity"
REPORT="$REPORT_DIR/wake-parity-linux.md"

mkdir -p "$REPORT_DIR"

status=0
build_status="NOT RUN"
wake_status="NOT RUN"
compat_status="NOT RUN"
strict_status="NOT LAUNCHED"
kotlin_sha="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
wake_sha="missing"
wake_branch="missing"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "This parity runner must be run on Linux."
  exit 2
fi

if [[ ! -d "$WAKE_DIR/.git" ]]; then
  echo "Wake checkout not found: $WAKE_DIR"
  echo "Set WAKE_DIR=/path/to/wake and rerun."
  exit 2
fi

wake_sha="$(git -C "$WAKE_DIR" rev-parse --short HEAD)"
wake_branch="$(git -C "$WAKE_DIR" branch --show-current)"

if [[ "$wake_branch" != "$EXPECTED_BRANCH" ]]; then
  echo "Warning: Wake is on '$wake_branch'; parity target is '$EXPECTED_BRANCH'."
  echo "Continuing without changing your checkout."
fi

echo "==> Kotlin Display Linux native/JVM build and tests"
(
  cd "$ROOT"
  ./scripts/build-linux.sh --clean
)
if [[ $? -eq 0 ]]; then
  build_status="PASS"
else
  build_status="FAIL"
  status=1
fi

echo
echo "==> Wake desktop unit/compile baseline"
(
  cd "$WAKE_DIR"
  ./gradlew :core:jvmTest :components:jvmTest :desktop:test :desktop:compileKotlin
)
if [[ $? -eq 0 ]]; then
  wake_status="PASS"
else
  wake_status="FAIL"
  status=1
fi

echo
echo "==> Wake strict takeover compatibility preflight"
(
  cd "$ROOT"
  ./scripts/kd-project.sh "$WAKE_DIR" \
    --compat \
    --backend "$BACKEND" \
    --target :desktop \
    --strict-renderer \
    --skip-runtime
)
if [[ $? -eq 0 ]]; then
  compat_status="PASS"
else
  compat_status="FAIL"
  status=1
fi

if [[ "$LAUNCH_STRICT" == "1" && "$status" -eq 0 ]]; then
  echo
  echo "==> Wake strict live GPU takeover"
  (
    cd "$ROOT"
    ./scripts/kd-project.sh "$WAKE_DIR" \
      --run \
      --backend "$BACKEND" \
      --target :desktop \
      --strict-renderer \
      --skip-runtime
  )
  if [[ $? -eq 0 ]]; then
    strict_status="RUN COMPLETED"
  else
    strict_status="FAIL"
    status=1
  fi
fi

latest_audit="$(ls -1t "$ROOT"/build/external-audit/*.log 2>/dev/null | head -n 1 || true)"
latest_capture="$(ls -1t "$ROOT"/build/external-capture/*.kdcap 2>/dev/null | head -n 1 || true)"

cat > "$REPORT" <<EOF
# Wake Linux parity run

| Field | Value |
| --- | --- |
| Host | $(uname -s) $(uname -m) |
| Kotlin Display commit | $kotlin_sha |
| Wake checkout | $WAKE_DIR |
| Wake branch | $wake_branch |
| Wake commit | $wake_sha |
| Backend | $BACKEND |
| Kotlin Display build/tests | **$build_status** |
| Wake unit/compile baseline | **$wake_status** |
| Strict compatibility preflight | **$compat_status** |
| Strict live run | **$strict_status** |
| Latest renderer audit | ${latest_audit:-none} |
| Latest Compose capture | ${latest_capture:-none} |

## Scope

This runner verifies the Linux X11/Vulkan native/JNI build, renderer/bridge/agent
tests, Wake's desktop compile/unit baseline, and strict Compose 1.8.2 takeover
preflight. Set KD_LAUNCH_STRICT=1 to launch the live zero-edit takeover.
EOF

echo
echo "Linux parity report: $REPORT"
exit "$status"
