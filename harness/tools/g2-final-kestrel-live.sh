#!/usr/bin/env bash
set -euo pipefail

EXPECT_HEAD="${EXPECT_HEAD:?EXPECT_HEAD is required}"
OUT="${OUT:-/tmp/kestrel-g2-final-live}"
PORT="${PORT:-25692}"
SEED="${SEED:-4242424242}"
ROUTE_Y="${ROUTE_Y:-200}"

JHOME="${JHOME:-$HOME/.local/toolchains/jdk-25.0.4.1+1}"
VROOT="${VROOT:-$HOME/kestrel-run/vinner}"
VJ="${VJ:-$VROOT/META-INF/versions/26.2/server-26.2.jar}"
VL="${VL:-$VROOT/META-INF/libraries}"
DATAPACK="${DATAPACK:-$HOME/kestrel-run/packdump/data}"

test "$(git rev-parse HEAD)" = "$EXPECT_HEAD"
test -x "$JHOME/bin/java"
test -x "$JHOME/bin/jcmd"
test -f "$VJ"
test -d "$VL"
test -d "$DATAPACK"

export JAVA_HOME="$JHOME"
export PATH="$JHOME/bin:$PATH"

rm -rf "$OUT"
mkdir -p "$OUT/world/region"

python3 -m py_compile harness/tools/live-route-probe.py harness/tools/tp-bot.py
python3 harness/tools/live-route-probe.py self-test

timeout 360s ./core/gradlew -q -p core compileJava "-PvanillaJar=$VJ" "-PvanillaLibs=$VL"

CP="$PWD/core/build/classes/java/main:$VJ"
while IFS= read -r -d '' jar; do
    CP="$CP:$jar"
done < <(find "$VL" -type f -name '*.jar' -print0 | sort -z)

SERVER_LOG="$OUT/kestrel-server.log"
SERVER_ERR="$OUT/kestrel-server.err"
TICK_CSV="$OUT/kestrel-ticks.csv"
GC_LOG="$OUT/kestrel-gc.log"
RSS_FILE="$OUT/kestrel-rss-kib.txt"
NMT_FILE="$OUT/kestrel-nmt.txt"
HEAP_FILE="$OUT/kestrel-heap.txt"
CAPTURE="$OUT/kestrel-route.json"
PROBE_LOG="$OUT/kestrel-probe.log"
PROBE_ERR="$OUT/kestrel-probe.err"

GC_FLAG="-Xlog:gc*:file=$GC_LOG:time,uptime,level,tags"
"$JHOME/bin/java" \
    -Xms128M -Xmx256M -XX:+UseG1GC \
    -XX:G1PeriodicGCInterval=10000 \
    -XX:G1PeriodicGCSystemLoadThreshold=0 \
    -XX:+G1PeriodicGCInvokesConcurrent \
    -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 \
    -XX:NativeMemoryTracking=summary \
    "$GC_FLAG" \
    "-Dkestrel.datapack=$DATAPACK" \
    -Dkestrel.finalGateStrictMetadata=true \
    "-Dkestrel.benchmark.tickCsv=$TICK_CSV" \
    -cp "$CP" dev.kestrel.net.KestrelServer \
    "$OUT/world" "$PORT" "$SEED" \
    "--spawn=0,$ROUTE_Y,0" --no-save --mem-probe=5 \
    >"$SERVER_LOG" 2>"$SERVER_ERR" &
SERVER_PID=$!

cleanup() {
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null || true
        for _ in $(seq 1 50); do
            kill -0 "$SERVER_PID" 2>/dev/null || break
            sleep 0.1
        done
        kill -9 "$SERVER_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

python3 - "$PORT" "$SERVER_PID" <<'PY'
import os,socket,sys,time
port=int(sys.argv[1]); pid=int(sys.argv[2])
deadline=time.monotonic()+180
while time.monotonic()<deadline:
    try:
        os.kill(pid,0)
    except ProcessLookupError:
        raise SystemExit("Kestrel exited before listening")
    s=socket.socket()
    s.settimeout(.25)
    try:
        s.connect(("127.0.0.1",port))
        s.close()
        print("KESTREL_LISTEN=PASS")
        raise SystemExit(0)
    except OSError:
        s.close()
        time.sleep(.25)
raise SystemExit("Kestrel listen timeout")
PY

set +e
timeout 360s python3 harness/tools/live-route-probe.py capture \
    --host 127.0.0.1 --port "$PORT" --name KesFinalRoute \
    --out "$CAPTURE" \
    --origin-x 0.5 --origin-z 0.5 --route-y "$ROUTE_Y" \
    --dx 64 --dz 64 --score-radius 2 --view-distance 2 \
    --step 0.45 --interval 0.05 --settle 20 --tail 20 --join-timeout 120 \
    --ascend-to-route-y \
    >"$PROBE_LOG" 2>"$PROBE_ERR" &
PROBE_PID=$!
set -e

while kill -0 "$PROBE_PID" 2>/dev/null; do
    if [ -r "/proc/$SERVER_PID/status" ]; then
        awk '/^VmRSS:/ {print $2}' "/proc/$SERVER_PID/status" >> "$RSS_FILE"
    fi
    sleep 1
done
set +e
wait "$PROBE_PID"
PROBE_RC=$?
set -e
echo "KESTREL_PROBE_RC=$PROBE_RC"
cat "$PROBE_LOG"
if [ "$PROBE_RC" -ne 0 ]; then
    cat "$PROBE_ERR" >&2 || true
    exit "$PROBE_RC"
fi

for _ in $(seq 1 12); do
    if [ -r "/proc/$SERVER_PID/status" ]; then
        awk '/^VmRSS:/ {print $2}' "/proc/$SERVER_PID/status" >> "$RSS_FILE"
    fi
    sleep 1
done

"$JHOME/bin/jcmd" "$SERVER_PID" VM.native_memory summary >"$NMT_FILE" 2>&1 || true
"$JHOME/bin/jcmd" "$SERVER_PID" GC.heap_info >"$HEAP_FILE" 2>&1 || true

if grep -E 'final live gate:|async chunk generation failed' "$SERVER_LOG"; then
    echo "STRICT_METADATA_OR_ASYNC_FAILURE=1" >&2
    exit 40
fi

cleanup
trap - EXIT
wait "$SERVER_PID" 2>/dev/null || true

# A single player-driven Vanilla world is not a deterministic exact oracle:
# identical Vanilla 26.2 routes at this seed produced different persisted
# final blocks in 53/65 scored chunks even with ActiveProcessorCount=2.
# Exact semantic parity stays gated by matched-order FeaturesTraceReplay.
# The live/network gate instead requires Kestrel itself to reproduce the
# exact final block/biome/light payloads across a second fresh boot.
REPEAT_OUT="$OUT/repeat"
REPEAT_PORT=$((PORT + 1))
REPEAT_CAPTURE="$REPEAT_OUT/kestrel-route-repeat.json"
REPEAT_LOG="$REPEAT_OUT/kestrel-server.log"
REPEAT_ERR="$REPEAT_OUT/kestrel-server.err"
REPEAT_PROBE_LOG="$REPEAT_OUT/kestrel-probe.log"
REPEAT_PROBE_ERR="$REPEAT_OUT/kestrel-probe.err"
REPEAT_TICK_CSV="$REPEAT_OUT/kestrel-ticks.csv"
REPEAT_GC_LOG="$REPEAT_OUT/kestrel-gc.log"
rm -rf "$REPEAT_OUT"
mkdir -p "$REPEAT_OUT/world/region"

REPEAT_GC_FLAG="-Xlog:gc*:file=$REPEAT_GC_LOG:time,uptime,level,tags"
"$JHOME/bin/java" \
    -Xms128M -Xmx256M -XX:+UseG1GC \
    -XX:G1PeriodicGCInterval=10000 \
    -XX:G1PeriodicGCSystemLoadThreshold=0 \
    -XX:+G1PeriodicGCInvokesConcurrent \
    -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 \
    -XX:NativeMemoryTracking=summary \
    "$REPEAT_GC_FLAG" \
    "-Dkestrel.datapack=$DATAPACK" \
    -Dkestrel.finalGateStrictMetadata=true \
    "-Dkestrel.benchmark.tickCsv=$REPEAT_TICK_CSV" \
    -cp "$CP" dev.kestrel.net.KestrelServer \
    "$REPEAT_OUT/world" "$REPEAT_PORT" "$SEED" \
    "--spawn=0,$ROUTE_Y,0" --no-save --mem-probe=5 \
    >"$REPEAT_LOG" 2>"$REPEAT_ERR" &
REPEAT_PID=$!

cleanup_repeat() {
    if kill -0 "$REPEAT_PID" 2>/dev/null; then
        kill "$REPEAT_PID" 2>/dev/null || true
        for _ in $(seq 1 50); do
            kill -0 "$REPEAT_PID" 2>/dev/null || break
            sleep 0.1
        done
        kill -9 "$REPEAT_PID" 2>/dev/null || true
    fi
}
trap cleanup_repeat EXIT

python3 - "$REPEAT_PORT" "$REPEAT_PID" <<'PYWAIT'
import os,socket,sys,time
port=int(sys.argv[1]); pid=int(sys.argv[2])
deadline=time.monotonic()+180
while time.monotonic()<deadline:
    try:
        os.kill(pid,0)
    except ProcessLookupError:
        raise SystemExit("Kestrel repeat exited before listening")
    s=socket.socket(); s.settimeout(.25)
    try:
        s.connect(("127.0.0.1",port)); s.close()
        raise SystemExit(0)
    except OSError:
        s.close(); time.sleep(.25)
raise SystemExit("Kestrel repeat listen timeout")
PYWAIT

set +e
timeout 360s python3 harness/tools/live-route-probe.py capture \
    --host 127.0.0.1 --port "$REPEAT_PORT" --name KesFinalRepeat \
    --out "$REPEAT_CAPTURE" \
    --origin-x 0.5 --origin-z 0.5 --route-y "$ROUTE_Y" \
    --dx 64 --dz 64 --score-radius 2 --view-distance 2 \
    --step 0.45 --interval 0.05 --settle 20 --tail 20 --join-timeout 120 \
    --ascend-to-route-y \
    >"$REPEAT_PROBE_LOG" 2>"$REPEAT_PROBE_ERR"
REPEAT_PROBE_RC=$?
set -e
echo "KESTREL_REPEAT_PROBE_RC=$REPEAT_PROBE_RC"
cat "$REPEAT_PROBE_LOG"
if [ "$REPEAT_PROBE_RC" -ne 0 ]; then
    cat "$REPEAT_PROBE_ERR" >&2 || true
    exit "$REPEAT_PROBE_RC"
fi
if grep -E 'final live gate:|async chunk generation failed' "$REPEAT_LOG"; then
    echo "REPEAT_STRICT_METADATA_OR_ASYNC_FAILURE=1" >&2
    exit 41
fi

cleanup_repeat
trap - EXIT
wait "$REPEAT_PID" 2>/dev/null || true

python3 harness/tools/live-route-probe.py compare \
    "$CAPTURE" "$REPEAT_CAPTURE" --min-light 50 \
    | tee "$OUT/route-repeat-compare.json"
echo "KESTREL_LIVE_REPEAT=PASS"

BENCH_LOG="$OUT/async-benchmark.log"
timeout 900s "$JHOME/bin/java" "-Dkestrel.datapack=$DATAPACK" -cp "$CP" \
    dev.kestrel.net.AsyncChunkGenBenchmark >"$BENCH_LOG" 2>&1
cat "$BENCH_LOG"

python3 - "$OUT" <<'PY'
from pathlib import Path
import csv,json,math,re,statistics,sys
root=Path(sys.argv[1])

def pct(vals,p):
    vals=sorted(vals)
    if not vals:
        return 0.0
    return vals[min(len(vals)-1,max(0,math.ceil(len(vals)*p)-1))]

work=[]; queue=[]
with (root/"kestrel-ticks.csv").open(newline="",errors="replace") as f:
    for row in csv.DictReader(f):
        if int(row["players"]) > 0:
            work.append(int(row["workNanos"])/1e6)
            queue.append(int(row["asyncQueueDepth"]))

rss=[int(x)/1024 for x in (root/"kestrel-rss-kib.txt").read_text().split()]
gc=(root/"kestrel-gc.log").read_text(errors="replace")
pauses=[float(x) for x in re.findall(r"Pause[^\n]*?([0-9]+(?:\.[0-9]+)?)ms",gc)]

bench=(root/"async-benchmark.log").read_text(errors="replace")
# AsyncChunkGenBenchmark formats decimals using the host locale. Normalize the
# German bench PC comma separator before parsing the current human-readable rows.
bench_norm=bench.replace(",", ".")
def match(pattern):
    m=re.search(pattern,bench_norm)
    if not m:
        raise SystemExit("could not parse AsyncChunkGenBenchmark output: "+pattern)
    return m

m_tick=match(r"tick-thread work per simulated tick INCLUDING publication n=(\d+) mean=([0-9.]+)ms p50=([0-9.]+)ms p95=([0-9.]+)ms max=([0-9.]+)ms")
m_lat=match(r"request -> published completion latency n=(\d+) mean=([0-9.]+)ms p50=([0-9.]+)ms p95=([0-9.]+)ms max=([0-9.]+)ms")
m_gen=match(r"worker ensureChunk cost n=(\d+) mean=([0-9.]+)ms p50=([0-9.]+)ms p95=([0-9.]+)ms max=([0-9.]+)ms")
m_pub=match(r"tick publication cost per completed result n=(\d+) mean=([0-9.]+)ms p50=([0-9.]+)ms p95=([0-9.]+)ms max=([0-9.]+)ms")
m_queue=match(r"queue depth mean=([0-9.]+) p50=(\d+) p95=(\d+) max=(\d+) cap=(\d+)")
m_wall=match(r"wall all (\d+) chunks: ([0-9.]+)s")
chunks=int(m_wall.group(1)); wall_s=float(m_wall.group(2))

full_pauses=[]
periodic_full_pauses=[]
for line in gc.splitlines():
    m=re.search(r"Pause[^\n]*?([0-9]+(?:\.[0-9]+)?)ms",line)
    if m and "Pause Full" in line:
        ms=float(m.group(1))
        full_pauses.append(ms)
        if "G1 Periodic Collection" in line:
            periodic_full_pauses.append(ms)

capture=json.loads((root/"kestrel-route.json").read_text())
summary={
    "head": capture.get("head"),
    "route": {
        "score_chunks": len(capture["score_chunks"]),
        "missing": len(capture["missing_score_chunks"]),
        "chunk_packets": capture["chunk_packets"],
        "unique_chunks": capture["unique_chunks"],
        "batch_acks": capture["batch_acks"],
        "corrections": len(capture["corrections"]),
        "duration_s": capture["duration_s"],
    },
    "tick": {
        "player_ticks": len(work),
        "work_ms_p50": pct(work,.50),
        "work_ms_p95": pct(work,.95),
        "work_ms_max": max(work) if work else 0.0,
        "queue_max": max(queue) if queue else 0,
        "queue_p95": pct(queue,.95),
    },
    "rss_mib": {
        "samples": len(rss),
        "p50": statistics.median(rss),
        "min": min(rss),
        "max": max(rss),
    },
    "gc": {
        "pause_events": len(pauses),
        "pause_ms_p50": pct(pauses,.50),
        "pause_ms_p95": pct(pauses,.95),
        "pause_ms_max": max(pauses) if pauses else 0.0,
        "pause_ms_total": sum(pauses),
        "full_pause_events": len(full_pauses),
        "full_pause_ms_p95": pct(full_pauses,.95),
        "full_pause_ms_max": max(full_pauses) if full_pauses else 0.0,
        "periodic_full_pause_events": len(periodic_full_pauses),
        "periodic_full_pause_ms_max": max(periodic_full_pauses) if periodic_full_pauses else 0.0,
    },
    "async_benchmark": {
        "chunks": chunks,
        "wall_s": wall_s,
        "throughput_chunks_s": chunks/wall_s,
        "request_publish_ms_p50": float(m_lat.group(3)),
        "request_publish_ms_p95": float(m_lat.group(4)),
        "request_publish_ms_max": float(m_lat.group(5)),
        "generation_ms_p50": float(m_gen.group(3)),
        "generation_ms_p95": float(m_gen.group(4)),
        "generation_ms_max": float(m_gen.group(5)),
        "publish_tick_ms_p50": float(m_pub.group(3)),
        "publish_tick_ms_p95": float(m_pub.group(4)),
        "publish_tick_ms_max": float(m_pub.group(5)),
        "tick_work_ms_p50": float(m_tick.group(3)),
        "tick_work_ms_p95": float(m_tick.group(4)),
        "tick_work_ms_max": float(m_tick.group(5)),
        "queue_mean": float(m_queue.group(1)),
        "queue_p50": int(m_queue.group(2)),
        "queue_p95": int(m_queue.group(3)),
        "queue_max": int(m_queue.group(4)),
        "queue_cap": int(m_queue.group(5)),
    },
}
(root/"live-summary.json").write_text(json.dumps(summary,indent=2,sort_keys=True))
print("=== FINAL LIVE METRICS ===")
print(json.dumps(summary,indent=2,sort_keys=True))
PY

echo "KESTREL_FINAL_LIVE=PASS"
