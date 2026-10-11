#!/usr/bin/env bash
# Kestrel release zip: one-time setup.
#
# Downloads the two things this zip cannot contain and writes the classpath file
# the launcher reads:
#   1. Temurin JDK 25, but only when the machine has no Java 25 of its own.
#      Pinned by sha256.
#   2. Mojang's pinned 26.2 server bundler, pinned by sha1, then the nested
#      server jar, libraries and datapack are extracted from it exactly the way
#      the repository's scripts/setup-env.sh does.
#
# Neither file can be redistributed inside the zip, which is why the first run
# needs network access once. Idempotent: re-running reuses anything already
# correct and only re-extracts what is missing.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME="$HERE/runtime"
VERSION=26.2
SERVER_SHA1=823e2250d24b3ddac457a60c92a6a941943fcd6a
SERVER_URL="https://piston-data.mojang.com/v1/objects/${SERVER_SHA1}/server.jar"

case "$(uname -s)" in
  Linux) ;;
  *)
    echo "[setup] this script only handles Linux. On Windows use start.bat." >&2
    echo "[setup] On any other platform, install Temurin 25 and run the server with" >&2
    echo "[setup] the classpath and flags in runtime/cp.txt (see README.txt)." >&2
    exit 1
    ;;
esac

# Temurin 25.0.4.1+1: the same build the repository pins in scripts/setup-env.sh.
# The hashes below are Adoptium's own published checksums for that release, and
# they match the two Linux hashes in setup-env.sh.
JDK_TAG="jdk-25.0.4.1%2B1"
JDK_FILE_VERSION="25.0.4.1_1"
case "$(uname -m)" in
  aarch64|arm64)
    JDK_ASSET=aarch64
    JDK_SHA256=69df11a02cfa3ef7d7ca645e03edce6778ec090e100f6ae2b42097865730ac52
    ;;
  x86_64|amd64)
    JDK_ASSET=x64
    JDK_SHA256=dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e
    ;;
  *)
    echo "[setup] no pinned JDK for architecture '$(uname -m)'." >&2
    echo "[setup] Install Temurin 25 yourself, then set KESTREL_JAVA=/path/to/java and re-run." >&2
    exit 1
    ;;
esac
JDK_URL="https://github.com/adoptium/temurin25-binaries/releases/download/${JDK_TAG}/OpenJDK25U-jdk_${JDK_ASSET}_linux_hotspot_${JDK_FILE_VERSION}.tar.gz"

has_java25() {
  "$1" -version 2>&1 | grep -q '"25'
}

mkdir -p "$RUNTIME"

# 1. JDK, only when none is already usable.
if [ -x "$RUNTIME/jdk/bin/java" ]; then
  echo "[setup] bundled JDK already present"
elif [ -n "${KESTREL_JAVA:-}" ] && has_java25 "$KESTREL_JAVA"; then
  echo "[setup] using KESTREL_JAVA=$KESTREL_JAVA"
elif command -v java >/dev/null 2>&1 && has_java25 java; then
  echo "[setup] using the Java 25 already on PATH"
else
  echo "[setup] downloading Temurin $JDK_FILE_VERSION ($JDK_ASSET), about 200 MB"
  tmp_jdk="$(mktemp)"
  curl -fsSL -o "$tmp_jdk" "$JDK_URL"
  echo "$JDK_SHA256  $tmp_jdk" | sha256sum -c -
  mkdir -p "$RUNTIME/jdk"
  tar -xzf "$tmp_jdk" -C "$RUNTIME/jdk" --strip-components=1
  rm -f "$tmp_jdk"
  "$RUNTIME/jdk/bin/java" -version
fi

# 2. Vanilla server jar, libraries and datapack.
command -v unzip >/dev/null 2>&1 || {
  echo "[setup] 'unzip' is required to extract the server jar." >&2
  echo "[setup] Install it (Debian/Ubuntu: sudo apt-get install -y unzip) and re-run." >&2
  exit 1
}

cd "$RUNTIME"
if [ ! -f server-bundler.jar ] || [ "$(sha1sum server-bundler.jar | cut -d' ' -f1)" != "$SERVER_SHA1" ]; then
  echo "[setup] downloading the pinned $VERSION server bundler (about 60 MB)"
  curl -fsSL -o server-bundler.jar "$SERVER_URL"
fi
got="$(sha1sum server-bundler.jar | cut -d' ' -f1)"
[ "$got" = "$SERVER_SHA1" ] || {
  echo "[setup] sha1 mismatch on the server bundler: got $got, expected $SERVER_SHA1" >&2
  exit 1
}

echo "[setup] extracting the server jar and libraries"
rm -rf vinner && mkdir -p vinner
( cd vinner && unzip -qo ../server-bundler.jar 'META-INF/versions/*' 'META-INF/libraries/*' )

echo "[setup] extracting the vanilla datapack"
rm -rf packdump && mkdir -p packdump
( cd packdump && unzip -qo "../vinner/META-INF/versions/${VERSION}/server-${VERSION}.jar" 'data/*' )

mkdir -p play

SERVER_JAR="$RUNTIME/vinner/META-INF/versions/${VERSION}/server-${VERSION}.jar"
LIBS="$(find "$RUNTIME/vinner/META-INF/libraries" -name '*.jar' | sort | tr '\n' ':' | sed 's/:$//')"
printf '%s:%s:%s' "$HERE/classes" "$SERVER_JAR" "$LIBS" > "$RUNTIME/cp.txt"

echo "[setup] done. Start the server with ./start.sh"
echo "[setup] classpath entries: $(tr ':' '\n' < "$RUNTIME/cp.txt" | wc -l)"
