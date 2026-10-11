#!/usr/bin/env bash
# Prints a pinned server jar sha1 for CI to use, without duplicating either
# pin in the workflow file.
#
# Two different jars are involved:
#   - "bundler": the Mojang download itself (server.jar), pinned as
#     SERVER_SHA1 in setup-env.sh and verified there against the download.
#   - "extracted": the real nested server-26.2.jar that setup-env.sh
#     extracts from the bundler into vinner/META-INF/versions/26.2/. This is
#     a different file with a different hash. It is not itself downloaded,
#     but extraction from a fixed bundler is deterministic, so pinning its
#     hash here catches a bad or unexpected extraction the same way the
#     bundler pin catches a bad download.
#
# Usage: ci-server-jar-sha1.sh [bundler|extracted]  (default: bundler)
set -euo pipefail

MODE="${1:-bundler}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$MODE" in
  bundler)
    SHA1="$(grep -oP '^SERVER_SHA1=\K[0-9a-f]{40}' "$SCRIPT_DIR/setup-env.sh")"
    ;;
  extracted)
    # sha1 of vinner/META-INF/versions/26.2/server-26.2.jar as extracted by
    # setup-env.sh from the pinned bundler above. Verified identical on the
    # dev box and on an x86_64 GitHub Actions runner (run 36162490100).
    SHA1="86765a5899bd9c96461036a628796b4245715058"
    ;;
  *)
    echo "[ci-server-jar-sha1] unknown mode '$MODE': expected 'bundler' or 'extracted'" >&2
    exit 1
    ;;
esac

if [ -z "$SHA1" ]; then
  echo "[ci-server-jar-sha1] could not resolve sha1 for mode '$MODE'" >&2
  exit 1
fi

echo "$SHA1"
