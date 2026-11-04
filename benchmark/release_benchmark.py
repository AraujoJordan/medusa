#!/usr/bin/env python3
"""Benchmark signed/local 0.2.0 artifacts on a disposable copy of the real fixture.
Requires JDK 21, Android SDK/CLI and 0.2.0 published to Maven Local. Both modes use --parallel.
An untimed warm-up immediately precedes each measurement, then three rotated rounds. Record means unchanged existing goldens.
"""
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
fixture = Path(tempfile.mkdtemp(prefix='medusa-release-bench-'))
shutil.copytree(root / 'fixtures/multimodule', fixture, dirs_exist_ok=True,
                ignore=shutil.ignore_patterns('build', '.gradle', '.kotlin'))
settings = fixture / 'settings.gradle.kts'
settings.write_text(settings.read_text().replace('  includeBuild("../../tools")',
    '  plugins { id("io.github.araujojordan.medusa") version "0.2.0" }').replace(
    '  repositories {', '  repositories {\n    mavenLocal()', 1))

def run(operation, automatic):
    start = time.perf_counter()
    command = ['./gradlew', f'medusa{operation}', '--parallel', '--configuration-cache', '--console=plain',
               f'-Pmedusa.autoAggregate={str(automatic).lower()}']
    process = subprocess.run(command, cwd=fixture, capture_output=True, text=True)
    elapsed = time.perf_counter() - start
    if process.returncode:
        raise RuntimeError(process.stdout + process.stderr)
    count = None
    if operation == 'Verify':
        reports = [ET.parse(f).getroot() for f in fixture.rglob('build/medusa/test-results/medusa.xml')]
        count = sum(int(r.attrib['tests']) for r in reports)
        assert count == 22, count
        assert sum(int(r.attrib['failures']) for r in reports) == 0
    return dict(operation=operation, automatic=automatic, seconds=elapsed, verified_images=count,
                configuration_cache_reused='Reusing configuration cache' in process.stdout)

for operation in ['Record', 'Verify']:
    for automatic in [False, True]:
        run(operation, automatic)
results = []
for iteration in range(3):
    for operation in ['Record', 'Verify']:
        for automatic in ([False, True] if iteration % 2 == 0 else [True, False]):
            run(operation, automatic)  # Changing a Gradle property can invalidate the configuration cache.
            result = run(operation, automatic)
            results.append(result)
            print(result, flush=True)
output = dict(version='0.2.0', images=22, rounds=3, build_cache=True, configuration_cache=True,
              parallel=True, daemon=True, differ='odiff', record_scenario='unchanged existing goldens', results=results)
(root / 'benchmark/RELEASE_BENCHMARK.json').write_text(json.dumps(output, indent=2) + '\n')
for operation in ['Record', 'Verify']:
    for automatic in [False, True]:
        print(operation, automatic, statistics.median(r['seconds'] for r in results
              if r['operation'] == operation and r['automatic'] == automatic))
