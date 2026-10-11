#!/usr/bin/env bash
# Rebuild the Kestrel dev runtime environment from scratch: JDK toolchain,
# vanilla server jar + libraries, datapack, classpath file.
#
# WHY THIS EXISTS: this environment used to live in /tmp (/tmp/vinner,
# /tmp/vlib, /tmp/packdump, /tmp/kestrel-play). systemd-tmpfiles ships
# "D /tmp" in /usr/lib/tmpfiles.d/tmp.conf, which EMPTIES /tmp on every
# boot. A reboot on 2026-09-14 destroyed the play world, the vanilla jar,
# the libraries, the datapack and the classpath file at once, and no
# procedure for rebuilding them was written down anywhere. It was moved to
# ~/kestrel-run on the assumption that was persistent.
#
# THAT ASSUMPTION WAS WRONG. On 2026-09-16 the box came back from a reboot
# with BOTH ~/kestrel-run AND ~/.local/toolchains/jdk-25.0.4.1+1 gone, even
# though the git checkout (including uncommitted working-tree diffs)
# survived on the same root filesystem. Best working theory: that host syncs
# a subset of $HOME in from a persistent machine, and anything written
# locally outside that sync scope does not survive a container/session
# recycle. Not fully confirmed; not worth re-diagnosing each time. The
# practical fix is this script: treat BOTH the JDK and the vanilla runtime
# as always-rebuild-don't-assume, and run it after any reboot AND at the
# start of any session before trusting either exists.
#
# Supports both aarch64 (the Raspberry Pi) and x86_64, each with its own
# pinned Temurin JDK tarball + sha256. Any other architecture is refused.
#
# A fresh machine needs: curl, unzip, and outbound network access to
# piston-data.mojang.com (server jar) and github.com/adoptium (JDK tarball).
set -euo pipefail

RUN_DIR="${RUN_DIR:-$HOME/kestrel-run}"
VERSION=26.2
# Pinned so a silent upstream version bump cannot change the build under us.
SERVER_SHA1=823e2250d24b3ddac457a60c92a6a941943fcd6a
SERVER_URL="https://piston-data.mojang.com/v1/objects/${SERVER_SHA1}/server.jar"

# JDK toolchain (core/build.gradle pins languageVersion 25; system java is
# too old / absent). Pinned to the exact Temurin build so a silent upstream
# bump can't change the build under us, same rationale as the server jar.
JDK_DIR="${JDK_DIR:-$HOME/.local/toolchains}"
JDK_VERSION="25.0.4.1+1"
JDK_HOME="$JDK_DIR/jdk-$JDK_VERSION"
JDK_ARCH="$(uname -m)"
case "$JDK_ARCH" in
  aarch64)
    JDK_SHA256=69df11a02cfa3ef7d7ca645e03edce6778ec090e100f6ae2b42097865730ac52
    JDK_ASSET_ARCH=aarch64
    ;;
  x86_64)
    JDK_SHA256=dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e
    JDK_ASSET_ARCH=x64
    ;;
  *)
    echo "[setup] unsupported architecture '$JDK_ARCH': this script only has pinned Temurin $JDK_VERSION builds for aarch64 and x86_64" >&2
    exit 1
    ;;
esac
JDK_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_${JDK_ASSET_ARCH}_linux_hotspot_25.0.4.1_1.tar.gz"

if [ ! -x "$JDK_HOME/bin/java" ]; then
  echo "[setup] JDK $JDK_VERSION missing at $JDK_HOME, installing"
  mkdir -p "$JDK_DIR"
  tmp_tar="$(mktemp)"
  curl -fsSL -o "$tmp_tar" "$JDK_URL"
  echo "$JDK_SHA256  $tmp_tar" | sha256sum -c -
  tar -xzf "$tmp_tar" -C "$JDK_DIR"
  rm -f "$tmp_tar"
  "$JDK_HOME/bin/java" -version
else
  echo "[setup] JDK $JDK_VERSION already present"
fi

command -v unzip >/dev/null || { echo "[setup] 'unzip' not found - install it (e.g. sudo apt-get install -y unzip) and rerun" >&2; exit 1; }

mkdir -p "$RUN_DIR"
cd "$RUN_DIR"

if [ ! -f server-bundler.jar ] || [ "$(sha1sum server-bundler.jar | cut -d' ' -f1)" != "$SERVER_SHA1" ]; then
  echo "[setup] downloading ${VERSION} server bundler"
  curl -fsSL -o server-bundler.jar "$SERVER_URL"
fi
got=$(sha1sum server-bundler.jar | cut -d' ' -f1)
[ "$got" = "$SERVER_SHA1" ] || { echo "[setup] sha1 mismatch: $got != $SERVER_SHA1" >&2; exit 1; }

echo "[setup] extracting server jar + libraries -> vinner/"
rm -rf vinner && mkdir -p vinner
( cd vinner && unzip -qo ../server-bundler.jar 'META-INF/versions/*' 'META-INF/libraries/*' )

echo "[setup] extracting vanilla datapack -> packdump/"
rm -rf packdump && mkdir -p packdump
( cd packdump && unzip -qo "../vinner/META-INF/versions/${VERSION}/server-${VERSION}.jar" 'data/*' )

echo "[setup] writing cp.txt"
SERVER_JAR="$RUN_DIR/vinner/META-INF/versions/${VERSION}/server-${VERSION}.jar"
LIBS=$(find "$RUN_DIR/vinner/META-INF/libraries" -name '*.jar' | sort | tr '\n' ':' | sed 's/:$//')
printf 'core/build/classes/java/main:%s:%s' "$SERVER_JAR" "$LIBS" > cp.txt

mkdir -p play
echo "[setup] done. jar=$SERVER_JAR"
echo "[setup] cp entries: $(tr ':' '\n' < cp.txt | wc -l)"
