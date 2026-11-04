# Speed compared with Paparazzi

Latest isolated measurement (50 previews, five runs per scenario, M1 Max, JDK 21, build cache off;
Gradle and Medusa renderer daemons warm, odiff default, Paparazzi one fork):

| Task | Medusa | Paparazzi | Less elapsed time |
|---|---|---|---|
| Verify, rendering forced | 4.8 s | 6.9 s | 30% |
| Clean build then verify | 5.6 s | 7.6 s | 27% |
| Record after editing one composable | 5.1 s | 7.7 s | 34% |

These measurements do **not** establish a 2× speedup or a win at every suite size. The existing synthetic fixture
uses different device framing and night-mode settings between renderers: this measures time to a verdict, not equivalent
image fidelity. Clean build does not mean a cold renderer. Other tools were not benchmarked.
Raw measurements: [50-preview results](../benchmark/results/N50.json); full tables: [benchmark results](../benchmark/RESULTS.md).

Daemon reuse is now enabled by default (`useDaemon = false` opts out). Source reads are reused within discovery,
clean-record discovers once, polling visits expected output directories, and comparison tools initialize only when needed.
Record still preserves goldens within the configured tolerance; verify still renders previews on each invocation.

Historical 200-preview numbers in RESULTS.md predate these changes; they are not a fresh validation.

## Rendering and comparison overlap

The pipeline already has independent render and comparison workers:

```text
preview batches → render workers → buffered completed-image queue → comparison workers → report
```

The queue carries file paths, not decoded images. Comparison starts as soon as a complete PNG is available.
`renderParallelism` and `compareParallelism` control the stages separately. Extra render processes each pay
CLI startup; adding another queue alone does not remove that cost.

A follow-up 50-preview experiment (M1 Max, JDK 21, warm daemon, odiff, build cache enabled,
one warm-up per configuration and three rotated measured rounds) gave:

| Comparison workers | Median verify wall time |
|---|---|
| 1 | 5.20 s |
| 2 | 5.19 s |
| 10 | 5.31 s |

Across these runs, processing all images used 116–140 ms of summed worker time and the pipeline drained
within 0–1 ms after the render stream closed. Discovery used about 1.3 s and the render stream about
3.2 s. Comparison is already hidden behind rendering in this workload; these small timing differences
are insufficient to change global worker defaults, especially for expensive differs or large failing images.

With `--info`, Medusa now reports processing worker-time, ready-to-processing latency (including queue
backpressure), time spent sending, and post-render drain separately. Worker sums overlap and must not be
added to wall-clock rendering time. Render-stream duration includes CLI startup/build/output collection,
not just rasterization. Processing includes comparison and any failure artifacts or accessibility checks.

Reproduce with `python3 benchmark/generate.py 50` then `python3 benchmark/queues.py` using JDK 21.
Raw results: [QUEUE_RESULTS.json](../benchmark/QUEUE_RESULTS.json).

Medusa 0.2.0 reduces redundant CLI calls across modules with automatic aggregation, described below.
Discovery metadata caching remains an opportunity: a cache must account for dependency, resource,
annotation and toolchain changes before it can safely skip discovery.

## Reducing repeated CLI builds across modules

A follow-up probe on 2026-11-04 used Android CLI 1.1.16717390, JDK 21, M1 Max,
warm Gradle/renderer daemons and enabled Gradle caches. It covered the same 21 previews in
`:app`, `:feature:home` and `:core:designsystem` of the multi-module fixture.
Round zero was warm-up; medians below use three measured rounds.

| Discovery + rendering strategy | Median seconds |
|---|---|
| Three modules sequentially | 8.95 |
| Three modules concurrently | 6.26 |
| One app call including dependencies | 3.22 |

All 21 preview identities matched between sequential and aggregated runs. All 21 rendered PNGs
had identical SHA-256 hashes across every strategy and round. The independent `:legacy` module
is excluded from **all** three strategies. These timings exclude Medusa comparison/reporting and
outer Gradle startup; they are not end-to-end verify timings. Parallel timings ranged from 6.00 to
8.13 seconds, so this small experiment does not establish a universal speedup.

Reproduce using JDK 21: `python3 benchmark/aggregation.py`, then
`python3 benchmark/aggregation.py --parallel`. Raw results:
[aggregation](../benchmark/AGGREGATION_RESULTS.json) and
[concurrent modules](../benchmark/PARALLEL_AGGREGATION_RESULTS.json).

Medusa 0.2.0 automatically groups compatible, uniquely owned app/dependency modules for root tasks
and `check`, preserving per-module goldens and reports. Shared libraries, incompatible settings and
opted-out modules remain standalone. Explicitly qualified module tasks retain their own rendering
context. App resources and dependency versions can change a library preview; review migration failures
before re-recording, or set `autoAggregate = false`. See [multi-module behavior](multi-module.md).
The explicit `includeDependentModules = true` mode remains available and opts that module out of
automatic grouping.

A captured warm `preview list` build showed 21 up-to-date tasks, configuration-cache reuse and
686 ms spent invoking Gradle. CLI discovery still cost roughly 1.3 seconds overall in the earlier
50-preview probe. Thus a perfect discovery shortcut could save at most roughly 1.3 seconds there;
this is an upper bound, not an implemented or measured cache speedup.

A source-only discovery cache is unsafe. Gradle-generated classes, dependency annotations, resources,
classpath resolution, build configuration and CLI versions can affect results. A robust next design would
let Gradle refresh the relevant compiled outputs and fingerprint those outputs/classpaths/options before
reusing discovery metadata. It must still render and compare on verify, and invalidate on added/deleted
previews, provider/multipreview changes, dependency changes and clean builds. The installed public CLI
exposes no skip-build or prepared-manifest option for list/render, so eliminating the nested build entirely
requires a supported upstream interface or a separately maintained integration. The bundled renderer is
an authenticated service; invoking it directly is not a supported standalone replacement.

## 0.2.0 end-to-end release measurement

The signed 0.2.0 Maven Local artifacts were tested in a separate consumer build, without `includeBuild`.
The six-module fixture has 22 previews, including the independent `:legacy` preview. Both modes use
`--parallel`, the shared renderer daemon, odiff and Gradle caches. An untimed warm-up immediately
precedes each measurement; all measured invocations reused the configuration cache. Three rotated
rounds on M1 Max / JDK 21 / Android CLI 1.1.16717390 gave these medians:

| Root task | Automatic aggregation off | 0.2.0 default | Less elapsed time |
|---|---|---|---|
| Record unchanged existing goldens | 8.34 s | 6.67 s | 20% |
| Verify, rendering every preview | 9.43 s | 6.83 s | 28% |

Every verification checked all 22 images with zero failures. These include outer Gradle startup,
discovery, rendering, comparison and reporting; they are separate from the CLI-only probe above and
from the 50-preview Paparazzi comparison. Record timings cover unchanged goldens, not an edited source.
Small samples and different project shapes limit generalization.

Reproduce after publishing 0.2.0 to Maven Local: `python3 benchmark/release_benchmark.py` with JDK 21.
[Raw release measurements](../benchmark/RELEASE_BENCHMARK.json).

Release validation also exercises app resource overrides, standalone library rendering, per-module
failure artifacts, configuration-cache reuse, changed resources and newly added preview classes after
daemon warm-up. Run `python3 benchmark/release_validation.py` with JDK 21 and the Android SDK/CLI.
Expected failing verifications are recorded as failures in the [raw validation results](../benchmark/RELEASE_VALIDATION.json).
