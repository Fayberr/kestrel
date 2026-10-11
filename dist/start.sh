#!/usr/bin/env bash
# Launch a Kestrel server from this release zip.
#
#   ./start.sh                          world runtime/play/world, port 25565
#   ./start.sh --port 25566             (KESTREL_PORT=25566 also works)
#   KESTREL_MEMORY=4G ./start.sh        bigger heap
#   ./start.sh --online                 require Mojang authentication
#
# Offline mode is the default: it is what the project's own live server runs, and
# it lets any client join without a Mojang session. Do not expose an offline-mode
# server to the internet, because anyone can then connect under any name. Pass
# --online to authenticate instead.
#
# Everything after the known flags is handed to the server unchanged, so any
# KestrelServer flag works here too (run it with no arguments to see the list).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

MEMORY="${KESTREL_MEMORY:-2G}"
WORLD="${KESTREL_WORLD:-$HERE/runtime/play/world}"
PORT="${KESTREL_PORT:-25565}"
AUTH="--offline"

PASSTHROUGH=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --port|--world|--memory)
      if [ "$#" -lt 2 ]; then
        echo "[kestrel] $1 needs a value" >&2
        exit 2
      fi
      case "$1" in
        --port) PORT="$2" ;;
        --world) WORLD="$2" ;;
        --memory) MEMORY="$2" ;;
      esac
      shift 2
      ;;
    --online)
      AUTH="--online"
      shift
      ;;
    --offline)
      AUTH="--offline"
      shift
      ;;
    *)
      PASSTHROUGH+=("$1")
      shift
      ;;
  esac
done

if [ ! -f "$HERE/runtime/cp.txt" ]; then
  echo "[kestrel] first run: setting up the runtime (this needs network access once)"
  "$HERE/setup.sh"
fi

if [ -n "${KESTREL_JAVA:-}" ]; then
  JAVA="$KESTREL_JAVA"
elif [ -x "$HERE/runtime/jdk/bin/java" ]; then
  JAVA="$HERE/runtime/jdk/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
else
  echo "[kestrel] no Java found. Run ./setup.sh, or install Temurin 25 and set KESTREL_JAVA." >&2
  exit 1
fi

if ! "$JAVA" -version 2>&1 | grep -q '"25'; then
  echo "[kestrel] warning: $JAVA is not Java 25. Kestrel is built for 25 and may fail to start." >&2
fi

mkdir -p "$WORLD"

# glibc keeps up to 8 * cores malloc arenas per process by default, which shows
# up as RSS the JVM never asked for. The project's own live server pins this.
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"

SAVING="on"
for arg in ${PASSTHROUGH[@]+"${PASSTHROUGH[@]}"}; do
  if [ "$arg" = "--no-save" ]; then
    SAVING="off (--no-save)"
  fi
done

echo "[kestrel] java:  $("$JAVA" -version 2>&1 | head -1)"
echo "[kestrel] build: $(head -1 "$HERE/BUILD-COMMIT.txt" 2>/dev/null || echo unknown)"
echo "[kestrel] world: $WORLD"
echo "[kestrel] port:  $PORT ($AUTH, saving $SAVING)"

exec "$JAVA" -Xmx"$MEMORY" \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="$HERE/oom.hprof" \
  -XX:+UseG1GC \
  -XX:G1PeriodicGCInterval=10000 -XX:G1PeriodicGCSystemLoadThreshold=0 \
  -XX:+G1PeriodicGCInvokesConcurrent \
  -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 \
  -Dkestrel.datapack="$HERE/runtime/packdump/data" \
  -cp "$(cat "$HERE/runtime/cp.txt")" \
  dev.kestrel.net.KestrelServer "$WORLD" "$PORT" "$AUTH" ${PASSTHROUGH[@]+"${PASSTHROUGH[@]}"}
