#!/usr/bin/env bash
set -euo pipefail

KANVAS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INIT_SCRIPT="$KANVAS_ROOT/integration/kanvas.init.gradle"
MODE="build"
TARGET=""
BACKEND="auto"
TARGET_PROJECT=""
BUILD_TASK=""
RUN_TASK=""
PACKAGE_TASK=""
REF=""
AUDIT_RENDERER=1
STRICT_RENDERER=0
SKIP_RUNTIME=0
CLEAN_TARGET=0

usage() {
  cat <<'EOF'
Usage:
  ./scripts/kanvas-project.sh /path/to/kotlin-repo [options]

Options:
  --build                 Build the external repo (default)
  --run                   Run its desktop/JVM app task
  --package               Build a Compose Desktop app image with Kanvas
  --doctor                Only inspect/detect the repo
  --compat                Report Compose/Skiko dependency versions
  --target :desktop       Preferred Gradle project (e.g. :desktop, :composeApp)
  --backend NAME          auto|vulkan|opengl|d3d9|metal|gdi
  --build-task PATH       Exact external Gradle task, e.g. :desktop:build
  --run-task PATH         Exact external run task, e.g. :desktop:run
  --package-task PATH     Exact package task, e.g. :desktop:createDistributable
  --ref REF               Branch/tag/commit when the target is a Git URL
  --clean-target          Run clean before the selected action
  --audit-renderer        Record default Skia/Skiko renderer usage (run mode; default)
  --no-audit-renderer     Disable the renderer ownership audit
  --strict-renderer       Fail if any unsupported draw escapes GPU takeover
  --skip-runtime          Do not prebuild Kanvas host artifacts
  -h, --help              Show help

The external repository is not edited. Integration is injected with a Gradle
init script. Run mode enables live GPU takeover for supported Compose Desktop
Canvas operations and strict mode fails instead of silently falling back.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build) MODE="build" ;;
    --run) MODE="run" ;;
    --package) MODE="package" ;;
    --doctor) MODE="doctor" ;;
    --compat) MODE="compat" ;;
    --target) shift; TARGET_PROJECT="${1:-}" ;;
    --backend) shift; BACKEND="${1:-}" ;;
    --build-task) shift; BUILD_TASK="${1:-}" ;;
    --run-task) shift; RUN_TASK="${1:-}" ;;
    --package-task) shift; PACKAGE_TASK="${1:-}" ;;
    --ref) shift; REF="${1:-}" ;;
    --audit-renderer) AUDIT_RENDERER=1 ;;
    --no-audit-renderer) AUDIT_RENDERER=0 ;;
    --strict-renderer) AUDIT_RENDERER=1; STRICT_RENDERER=1 ;;
    --clean-target) CLEAN_TARGET=1 ;;
    --skip-runtime) SKIP_RUNTIME=1 ;;
    -h|--help) usage; exit 0 ;;
    -*)
      echo "Unknown option: $1"
      usage
      exit 2
      ;;
    *)
      if [[ -n "$TARGET" ]]; then
        echo "Only one target repository may be supplied."
        exit 2
      fi
      TARGET="$1"
      ;;
  esac
  shift
done

if [[ -z "$TARGET" ]]; then
  usage
  exit 2
fi

if [[ "$TARGET" =~ ^https?:// ]] || [[ "$TARGET" =~ ^git@ ]] || [[ "$TARGET" =~ \.git$ && ! -d "$TARGET" ]]; then
  command -v git >/dev/null 2>&1 || { echo "git is required to clone $TARGET"; exit 1; }
  URL="$TARGET"
  NAME="$(basename "${URL%.git}")"
  SAFE_REF="${REF:-default}"
  SAFE_REF="${SAFE_REF//[^A-Za-z0-9._-]/_}"
  CLONE_ROOT="$KANVAS_ROOT/build/external"
  TARGET="$CLONE_ROOT/$NAME-$SAFE_REF"
  mkdir -p "$CLONE_ROOT"
  if [[ ! -d "$TARGET/.git" ]]; then
    echo "==> Cloning $URL"
    if [[ -n "$REF" ]]; then
      git clone --branch "$REF" --single-branch "$URL" "$TARGET"
    else
      git clone "$URL" "$TARGET"
    fi
  else
    echo "==> Reusing cloned repository: $TARGET"
  fi
elif [[ -n "$REF" ]]; then
  echo "--ref is only used when the target is a Git URL; local checkouts are never switched."
fi

TARGET="$(cd "$TARGET" && pwd)"
if [[ ! -f "$TARGET/settings.gradle" && ! -f "$TARGET/settings.gradle.kts" ]]; then
  echo "Not a Gradle repository: $TARGET"
  exit 2
fi

case "$BACKEND" in
  auto|vulkan|opengl|d3d9|metal|gdi) ;;
  *) echo "Unsupported backend: $BACKEND"; exit 2 ;;
esac

GRADLE_KIND="binary"
if [[ -x "$TARGET/gradlew" ]]; then
  GRADLE="$TARGET/gradlew"
elif [[ -f "$TARGET/gradlew" ]]; then
  GRADLE="$TARGET/gradlew"
  GRADLE_KIND="shell"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
else
  echo "No Gradle wrapper or system Gradle found in target."
  exit 1
fi

run_gradle() {
  if [[ "$GRADLE_KIND" == "shell" ]]; then
    bash "$GRADLE" "$@"
  else
    "$GRADLE" "$@"
  fi
}

if [[ $SKIP_RUNTIME -eq 0 ]]; then
  "$KANVAS_ROOT/scripts/build-runtime.sh" \
    --gradle-launcher "$GRADLE"
fi

COMMON=(
  "-I" "$INIT_SCRIPT"
  "-Dkanvas.home=$KANVAS_ROOT"
  "-Dkanvas.backend=$BACKEND"
)

NEEDS_AGENT=0
if [[ "$MODE" == "package" ]]; then
  NEEDS_AGENT=1
elif [[ "$MODE" == "run" && $AUDIT_RENDERER -eq 1 ]]; then
  NEEDS_AGENT=1
fi

if [[ $NEEDS_AGENT -eq 1 ]]; then
  if [[ -x "$KANVAS_ROOT/gradlew" ]]; then
    KANVAS_GRADLE="$KANVAS_ROOT/gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    KANVAS_GRADLE="$(command -v gradle)"
  else
    KANVAS_GRADLE="$GRADLE"
  fi

  AGENT_JAR="$KANVAS_ROOT/integration-agent/build/libs/kanvas-agent.jar"
  if [[ ! -f "$AGENT_JAR" ]]; then
    if [[ -x "$KANVAS_GRADLE" ]]; then
      "$KANVAS_GRADLE" -p "$KANVAS_ROOT" :integration-agent:jar
    else
      bash "$KANVAS_GRADLE" -p "$KANVAS_ROOT" :integration-agent:jar
    fi
  fi
  if [[ ! -f "$AGENT_JAR" ]]; then
    echo "Kanvas agent was not built: $AGENT_JAR"
    exit 1
  fi
  COMMON+=("-Dkanvas.agentJar=$AGENT_JAR")
fi

if [[ "$MODE" == "run" && $AUDIT_RENDERER -eq 1 ]]; then
  AUDIT_DIR="$KANVAS_ROOT/build/external-audit"
  CAPTURE_DIR="$KANVAS_ROOT/build/external-capture"
  mkdir -p "$AUDIT_DIR" "$CAPTURE_DIR"
  AUDIT_NAME="$(basename "$TARGET")"
  STAMP="$(date +%Y%m%d-%H%M%S)"
  AUDIT_PATH="$AUDIT_DIR/${AUDIT_NAME}-$STAMP.log"
  CAPTURE_PATH="$CAPTURE_DIR/${AUDIT_NAME}-$STAMP.kdcap"
  COMMON+=("-Dkanvas.auditPath=$AUDIT_PATH")
  COMMON+=("-Dkanvas.capturePath=$CAPTURE_PATH")
  COMMON+=("-Dkanvas.capture=true")
  COMMON+=("-Dkanvas.takeover=true")
  COMMON+=("-Dkanvas.strictRenderer=$([[ $STRICT_RENDERER -eq 1 ]] && echo true || echo false)")
  echo "Renderer audit : $AUDIT_PATH"
  echo "Compose capture: $CAPTURE_PATH"
  echo "GPU takeover   : enabled"
fi
[[ -n "$TARGET_PROJECT" ]] && COMMON+=("-Dkanvas.target=$TARGET_PROJECT")
[[ -n "$BUILD_TASK" ]] && COMMON+=("-Dkanvas.buildTask=$BUILD_TASK")
[[ -n "$RUN_TASK" ]] && COMMON+=("-Dkanvas.runTask=$RUN_TASK")
[[ -n "$PACKAGE_TASK" ]] && COMMON+=("-Dkanvas.packageTask=$PACKAGE_TASK")

cd "$TARGET"

if [[ $CLEAN_TARGET -eq 1 ]]; then
  run_gradle "${COMMON[@]}" clean
fi

case "$MODE" in
  doctor) TASK="kanvasDoctor" ;;
  compat) TASK="kanvasCompatibility" ;;
  build) TASK="kanvasBuild" ;;
  run) TASK="kanvasRun" ;;
  package) TASK="kanvasPackage" ;;
esac

echo
echo "==> Kanvas adapter"
echo "Target : $TARGET"
echo "Action : $TASK"
echo "Backend: $BACKEND"
[[ -n "$TARGET_PROJECT" ]] && echo "Project: $TARGET_PROJECT"
echo

run_gradle "${COMMON[@]}" "$TASK" --stacktrace --no-configuration-cache
