#!/usr/bin/env bash
set -euo pipefail

KD_SOURCE="${KD_SOURCE:-/kd-src}"
TARGET_SOURCE="${KD_TARGET_SOURCE:-/target-src}"
OUT="${KD_OUT:-/out}"
TARGET_PROJECT="${KD_TARGET_PROJECT:-:desktop}"
PACKAGE_TASK="${KD_PACKAGE_TASK:-${TARGET_PROJECT}:createDistributable}"

WORK_ROOT="/work/kanvas-package"
KD_WORK="$WORK_ROOT/kanvas"
TARGET_WORK="$WORK_ROOT/target"

rm -rf "$WORK_ROOT"
mkdir -p "$KD_WORK" "$TARGET_WORK" "$OUT"

copy_source() {
  local source="$1"
  local destination="$2"
  (
    cd "$source"
    tar --exclude='./.git' --exclude='./.gradle' --exclude='./build' --exclude='*/build' -cf - .
  ) | (
    cd "$destination"
    tar -xf -
  )
}

echo "==> Copy Kanvas source into Linux build workspace"
copy_source "$KD_SOURCE" "$KD_WORK"
echo "==> Copy target source into Linux build workspace"
copy_source "$TARGET_SOURCE" "$TARGET_WORK"

# Normalize CRLF in the copied workspace.
if [[ -f "$KD_WORK/gradlew" ]]; then
  sed -i 's/\r$//' "$KD_WORK/gradlew"
fi
if [[ -d "$KD_WORK/scripts" ]]; then
  find "$KD_WORK/scripts" -type f -name '*.sh' -exec sed -i 's/\r$//' {} +
fi
if [[ -f "$TARGET_WORK/gradlew" ]]; then
  sed -i 's/\r$//' "$TARGET_WORK/gradlew"
fi

chmod +x "$KD_WORK/gradlew" 2>/dev/null || true
chmod +x "$KD_WORK/scripts/"*.sh 2>/dev/null || true
chmod +x "$TARGET_WORK/gradlew" 2>/dev/null || true

echo "==> Build Linux x64 Kanvas native/JNI runtime"
(
  cd "$KD_WORK"
  ./scripts/build-linux.sh --clean --skip-tests --skip-kotlin
)

echo "==> Build Kanvas integration agent"
(
  cd "$KD_WORK"
  if [[ -x ./gradlew ]]; then
    ./gradlew --no-daemon :integration-agent:jar
  else
    gradle --no-daemon :integration-agent:jar
  fi
)

NATIVE_LIB="$KD_WORK/native/build/libkanvas_native.so"
AGENT_JAR="$KD_WORK/integration-agent/build/libs/kanvas-agent.jar"
[[ -f "$NATIVE_LIB" ]] || { echo "Linux native library missing: $NATIVE_LIB"; exit 1; }
[[ -f "$AGENT_JAR" ]] || { echo "Integration agent missing: $AGENT_JAR"; exit 1; }

echo "==> Build target Linux portable app image"
(
  cd "$TARGET_WORK"
  if [[ -f ./gradlew ]]; then
    bash ./gradlew --no-daemon "$PACKAGE_TASK"
  else
    gradle --no-daemon "$PACKAGE_TASK"
  fi
)

BIN_DIR="$(find "$TARGET_WORK" -type d -path '*/build/compose/binaries/main/app/*/bin' -print -quit)"
if [[ -z "$BIN_DIR" ]]; then
  echo "Could not find a Compose Desktop app image after $PACKAGE_TASK."
  echo "Expected */build/compose/binaries/main/app/*/bin"
  exit 1
fi

APP_DIR="$(dirname "$BIN_DIR")"
APP_NAME="$(basename "$APP_DIR")"
KD_APP_DIR="$APP_DIR/lib/app/kanvas"
mkdir -p "$KD_APP_DIR"
cp "$NATIVE_LIB" "$KD_APP_DIR/"
cp "$AGENT_JAR" "$KD_APP_DIR/"

echo "==> Inject Kanvas into packaged JVM launch config"
CFG_COUNT=0
while IFS= read -r -d '' cfg; do
  tmp="$cfg.kd"
  awk '
    BEGIN { inserted = 0 }
    /^\[JavaOptions\]$/ {
      print
      print "java-options=-javaagent:$APPDIR/kanvas/kanvas-agent.jar"
      print "java-options=-Dkanvas.nativeLibrary=$APPDIR/kanvas/libkanvas_native.so"
      print "java-options=-Dkanvas.backend=vulkan"
      print "java-options=-Dkanvas.takeover=true"
      print "java-options=-Dkanvas.capture=true"
      print "java-options=-Dkanvas.strictRenderer=true"
      inserted = 1
      next
    }
    { print }
    END {
      if (!inserted) {
        print ""
        print "[JavaOptions]"
        print "java-options=-javaagent:$APPDIR/kanvas/kanvas-agent.jar"
        print "java-options=-Dkanvas.nativeLibrary=$APPDIR/kanvas/libkanvas_native.so"
        print "java-options=-Dkanvas.backend=vulkan"
        print "java-options=-Dkanvas.takeover=true"
        print "java-options=-Dkanvas.capture=true"
        print "java-options=-Dkanvas.strictRenderer=true"
      }
    }
  ' "$cfg" > "$tmp"
  mv "$tmp" "$cfg"
  CFG_COUNT=$((CFG_COUNT + 1))
done < <(find "$APP_DIR/lib/app" -maxdepth 1 -type f -name '*.cfg' -print0)

if [[ "$CFG_COUNT" -eq 0 ]]; then
  echo "No jpackage .cfg found under $APP_DIR/lib/app"
  exit 1
fi

cat > "$APP_DIR/run-kanvas.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAUNCHER="$(find "$ROOT/bin" -maxdepth 1 -type f -perm -u+x -print -quit)"
if [[ -z "$LAUNCHER" ]]; then
  echo "No packaged application launcher found under $ROOT/bin"
  exit 1
fi
if ! ldconfig -p 2>/dev/null | grep -q 'libvulkan\.so\.1'; then
  echo "Warning: Vulkan loader libvulkan.so.1 was not found."
fi
exec "$LAUNCHER" "$@"
EOF
chmod +x "$APP_DIR/run-kanvas.sh"

cat > "$APP_DIR/KANVAS-LINUX.txt" <<'EOF'
This is a portable Linux x86_64 build with Kanvas takeover embedded.

Requirements:
- x86_64 Linux
- X11 or XWayland
- Vulkan loader and working Vulkan GPU driver
- application-specific system libraries

For Yuri Player / dev-shit-ig, install VLC / LibVLC from the Linux distribution.

Launch:
  ./run-kanvas.sh

The original packaged executable is also inside bin/.
Kanvas backend: Vulkan
Strict renderer mode: enabled
EOF

SAFE_NAME="$(printf '%s' "$APP_NAME" | tr ' /' '--' | tr -cd 'A-Za-z0-9._-')"
ARCHIVE="$OUT/${SAFE_NAME}-linux-x64-kanvas.tar.gz"
echo "==> Create portable archive"
tar -C "$(dirname "$APP_DIR")" -czf "$ARCHIVE" "$APP_NAME"
echo
echo "LINUX PACKAGE READY"
echo "$ARCHIVE"
