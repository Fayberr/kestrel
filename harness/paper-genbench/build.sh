#!/bin/bash
# usage: build.sh PAPER_DIR   (a Paper 26.2 server that has run once, so libraries/ exists)
# Builds genbench.jar into PAPER_DIR/plugins.
set -euo pipefail
paper=${1:?paper server dir}
here=$(cd "$(dirname "$0")" && pwd)
out=$(mktemp -d)
cp=$(find "$paper/libraries" -name '*.jar' | tr '\n' ':')
javac --release 25 -cp "$cp" -d "$out" "$here/src/dev/kestrel/genbench/GenBench.java"
cp "$here/plugin.yml" "$out/"
mkdir -p "$paper/plugins"
jar cf "$paper/plugins/genbench.jar" -C "$out" .
echo "built $paper/plugins/genbench.jar"
