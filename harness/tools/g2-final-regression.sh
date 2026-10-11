#!/usr/bin/env bash
set -euo pipefail

SCOPE="${G2_FINAL_SCOPE:-full}"
EXPECT_HEAD="${EXPECT_HEAD:-$(git rev-parse HEAD)}"
JHOME="${JHOME:-$HOME/.local/toolchains/jdk-25.0.4.1+1}"
VROOT="${VROOT:-$HOME/kestrel-run/vinner}"
VJ="${VJ:-$VROOT/META-INF/versions/26.2/server-26.2.jar}"
VL="${VL:-$VROOT/META-INF/libraries}"

test "$(git rev-parse HEAD)" = "$EXPECT_HEAD"
test -x "$JHOME/bin/java"
test -f "$VJ"
test -d "$VL"

export JAVA_HOME="$JHOME"
export PATH="$JHOME/bin:$PATH"

echo "G2_FINAL_HEAD=$(git rev-parse HEAD)"
echo "G2_FINAL_SCOPE=$SCOPE"
git diff --check

timeout 300s ./core/gradlew -q -p core compileJava \
  "-PvanillaJar=$VJ" "-PvanillaLibs=$VL"

critical_checks=(
  dev.kestrel.net.AsyncChunkSenderCheck
  dev.kestrel.net.LightHandoffCheck
  dev.kestrel.net.AsyncLiveOrderParityCheck
)
full_checks=(
  dev.kestrel.worldgen.AsyncChunkGeneratorCheck
  dev.kestrel.worldgen.LightSnapshotStabilityCheck
  dev.kestrel.net.GeneratedMetadataReloadCheck
  dev.kestrel.net.TerrainOverwriteCheck
)

run_check() {
  local check="$1"
  echo "=== $check ==="
  timeout 300s ./core/gradlew -q -p core checkRepro \
    "-PvanillaJar=$VJ" "-PvanillaLibs=$VL" "-Pcheck=$check"
  echo "CHECK_PASS=$check"
}

for check in "${critical_checks[@]}"; do
  run_check "$check"
done

if [ "$SCOPE" = "full" ]; then
  for check in "${full_checks[@]}"; do
    run_check "$check"
  done
elif [ "$SCOPE" != "critical" ]; then
  echo "unknown G2_FINAL_SCOPE=$SCOPE" >&2
  exit 2
fi

timeout 60s python3 harness/tools/live-route-probe.py self-test
timeout 60s python3 harness/tools/tp-bot.py --self-test

echo "G2_FINAL_REGRESSION=PASS"
