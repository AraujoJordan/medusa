#!/bin/bash
# Usage: run.sh <cpus> <memory, e.g. 8g> [-- command...]   Runs a command in the CI-like container with those limits.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
CPUS="$1"; MEM="$2"; shift 2; [ "${1:-}" = "--" ] && shift
docker image inspect medusa-ci >/dev/null 2>&1 || docker build --platform linux/amd64 -t medusa-ci "$HERE"
exec docker run --rm --platform linux/amd64 --cpus="$CPUS" --memory="$MEM" --memory-swap="$MEM" \
  -e FIXTURE="${FIXTURE:-scale8}" -v "$REPO":/repo:ro -v "$REPO/benchmark/results":/results \
  -v medusa-gradle-home:/gradle-home -v medusa-sdk:/sdk -v medusa-ws:/ws -v medusa-android:/root/.android -v medusa-medusa:/root/.medusa -v medusa-local:/root/.local \
  medusa-ci "$@"
