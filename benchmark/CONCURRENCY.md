# How many modules should verify at once?

`medusa.maxConcurrentRenders` caps how many modules start the Android CLI at the same time. Every one runs its own nested
Gradle build, so they compete for CPU and memory. `ConcurrencyDefaults` picks the value from the machine:

```
byCpu    = round(logicalCores / 3)
byMemory = floor((memory * 0.6 - 1500 MB) / perBuildMb)          perBuildMb = max(900, heapMb / 2 + 600)
cap      = clamp(min(byCpu, byMemory), 1, 8)
```

`logicalCores` and `memory` are what the JVM reports, `heapMb` is the `-Xmx` in the project's `org.gradle.jvmargs` (nested builds read
it; 512 if absent). Override with `-Pmedusa.maxConcurrentRenders=N`. `./gradlew medusaSetup` prints the value chosen and why.

## Measured: 10 logical cores (8 performance + 2 efficiency), 64 GB, macOS

10 cores, 64 GB, 16 modules (3 previews each), 2026-11-03 21:19. `-Pmedusa.differ=yiq`.

| cap | cold s | warm s (median) | warm min-max | peak memory warm MB | peak processes | CPU busy % (mean/max, warm) |
|---|---|---|---|---|---|---|
| 1 | 101.4 | 88.6 | 88.5-88.7 | 1763 | 5 | 37 / 62 |
| 2 | 62.2 | 52.4 | 50.1-54.7 | 2555 | 8 | 67 / 95 |
| 3 | 56.2 | 41.5 | 38.6-44.5 | 2231 | 10 | 78 / 96 |
| 4 | 52.2 | 41.8 | 41.3-42.4 | 3737 | 14 | 86 / 100 |
| 6 | 58.7 | 45.8 | 44.3-47.3 | 5608 | 20 | 82 / 100 |
| 8 | 63.7 | 52.2 | 51.7-52.7 | 7646 | 26 | 79 / 100 |
| 10 | 67.5 | 55.6 | 54.0-57.3 | 8861 | 32 | 78 / 100 |
| 16 | 73.8 | 55.7 | 52.3-59.1 | 9139 | 32 | 79 / 100 |

(`python3 benchmark/scale_fixture.py 16 && python3 benchmark/concurrency.py run`; each cap: all Gradle daemons stopped, one cold run, two warm runs.)

What it shows:

- **The optimum is 3 to 4**, then it gets worse: 88 s with one at a time, 41 s with 3 or 4, 56 s with 10 (+33%) while using 9 GB.
- **One nested build is already multi-threaded**: with a single build at a time the machine is 37% busy (about 3.7 logical cores).
  That is where `/ 3` comes from; at 3 to 4 builds the CPU is saturated (78 to 86% mean, 100% peaks) and more only adds contention.
- **Memory is the other limit**: about 0.8 to 1 GB per extra concurrent build on this small project (processes: outer daemon,
  a nested build daemon, a Gradle client JVM and a helper per build). `perBuildMb` is deliberately higher (1.6 GB for a 2 GB heap),
  because a larger project's daemon grows toward its `-Xmx`.
- The formula gives `round(10 / 3) = 3` here, inside the measured optimum of 3 to 4.

## Another machine or CI: what is and is not verified

Verified, in a Linux container (`benchmark/docker/`, amd64, Docker Desktop):

- **The inputs follow the container's limits.** The JVM reports `--cpus` and `--memory` exactly: 1/2 GB, 2/4 GB, 4/6 GB and 8/7 GB
  containers reported 1/2048, 2/4096, 4/6144 and 8/7168 (cores/MB). The formula therefore sizes itself to a CI runner's allowance,
  not to its host.
- `medusaSetup` works on Linux (Android CLI through Google's installer, odiff `linux-x64` with its pinned checksum), and the CLI
  renders previews there.
- A 2 CPU / 4 GB container running the 8-module fixture used 2.6 GB at one build at a time, with no out-of-memory kills.

**Not verified: the speed curve on small machines.** I ran a sweep in 2 CPU and 4 CPU containers, but the container is amd64
emulated on an Apple Silicon Mac, so one run took over 20 minutes and I stopped after observing the first one. The formula
predicts 1 for 2 and 4 CPUs (and for 6 and 8 GB at a 2 GB heap), which follows from one build keeping about 3 cores busy,
but I have not measured that 2 would be slower on a 4 CPU machine. Other caveats:

- **Hyperthreading:** the constant was measured in logical cores on a machine without SMT. On a typical x86 CI machine a logical
  core is worth less, so `round(cores / 3)` is a little generous there, not conservative.
- **Efficiency cores** count as full cores in `availableProcessors()`.
- Project size matters: the measurements use 3 trivial previews per module. A module with a long compile keeps the CPU busy
  longer, which favours the same low values.

To check it on your CI: `python3 benchmark/concurrency.py run --fixture <fixtures/scale> --out result.json` works on Linux
(it reads the container's cgroup for CPU, memory and OOM kills), and `benchmark/docker/sweep.sh` runs it in containers.
