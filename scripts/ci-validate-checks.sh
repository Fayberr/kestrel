#!/usr/bin/env bash
# Validates a comma separated list of checkRepro check names against the
# names known to core/build.gradle's checkClassMap. Used by the "gates"
# workflow to reject a typo'd workflow_dispatch "checks" input with a
# clear failure instead of letting checkRepro fail later with a confusing
# class-not-found error, or (for a name it happens to resolve some other
# way) silently running the wrong thing.
#
# Usage: ci-validate-checks.sh "Name1,Name2,..."
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_GRADLE="$SCRIPT_DIR/../core/build.gradle"

LIST="${1:-}"
if [ -z "$LIST" ]; then
  echo "[ci-validate-checks] empty check list" >&2
  exit 1
fi

KNOWN="$(grep -oP "^\s*'\K[A-Za-z0-9]+(?=':)" "$BUILD_GRADLE")"
if [ -z "$KNOWN" ]; then
  echo "[ci-validate-checks] could not read any check names from $BUILD_GRADLE" >&2
  exit 1
fi

fail=0
IFS=',' read -ra NAMES <<< "$LIST"
for name in "${NAMES[@]}"; do
  trimmed="$(echo "$name" | xargs)"
  if [ -z "$trimmed" ]; then
    echo "[ci-validate-checks] empty entry in check list '$LIST'" >&2
    fail=1
    continue
  fi
  if ! grep -qxF "$trimmed" <<< "$KNOWN"; then
    echo "[ci-validate-checks] unknown check name '$trimmed' (not in core/build.gradle checkClassMap)" >&2
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  exit 1
fi

echo "[ci-validate-checks] all check names valid: $LIST"
