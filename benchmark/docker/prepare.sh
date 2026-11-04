#!/bin/bash
# Runs inside the container (see run.sh). Copies the repo without host build outputs, installs the tools Medusa needs
# with Medusa's own `medusaSetup`, and installs the SDK platform. Idempotent: volumes keep everything between runs.
set -euo pipefail
mkdir -p /ws /sdk
FIXTURE="${FIXTURE:-scale}"
# cp, not tar: GNU tar fails with "Function not implemented" on directories of the bind mount under amd64 emulation.
rm -rf /ws/tools /ws/fixtures /ws/benchmark
mkdir -p /ws/tools /ws/fixtures /ws/benchmark
cp -R /repo/tools/. /ws/tools/
cp -R "/repo/fixtures/$FIXTURE" /ws/fixtures/
cp -R /repo/benchmark/. /ws/benchmark/
find /ws -type d \( -name build -o -name .gradle -o -name .kotlin \) -prune -exec rm -rf {} +
cd "/ws/fixtures/$FIXTURE"
printf 'sdk.dir=/sdk\n' > local.properties
# Goldens are recorded here (the host's are macOS renders), so start without any.
find . -name '*.png' -path '*src*' -delete
echo "=== medusaSetup (installs the Android CLI and odiff for linux-x64)"
./gradlew --console=plain medusaSetup -Pmedusa.differ=odiff 2>&1 | grep -vE "^$|Downloading|warning|WARNING" | tail -15
ANDROID=/root/.local/bin/android
echo "=== Android SDK"
$ANDROID --no-metrics sdk list 2>&1 | grep -iE "android-37|platforms;android-3[67]|build-tools;3[56]" | head -8 || true
