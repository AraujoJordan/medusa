#!/bin/bash
# Runs the concurrency sweep in CI-like containers with CPU and memory limits (see README of benchmark/docker).
# Each scenario: ./run.sh <cpus> <memory> -- python3 concurrency.py ...; results land in benchmark/results/conc-*.json.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
sweep() {  # name cpus mem caps
  echo "=== $1 ($2 cpus, $3)  caps $4  $(date +%T)"
  "$HERE/run.sh" "$2" "$3" -- python3 /ws/benchmark/concurrency.py run --fixture /ws/fixtures/scale8 \
    --out "/results/conc-$1.json" --caps "$4" --warm 1 --gradle-arg=-Dorg.gradle.vfs.watch=false 2>&1 | cut -c1-260
}
sweep 2cpu-4g 2 4g 1,2,3
sweep 4cpu-6g 4 6g 1,2,3,4
sweep 4cpu-3g 4 3g 1,2,4
echo "=== done $(date +%T)"
