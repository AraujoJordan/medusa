# Speed compared with Paparazzi

`python3 benchmark/bench.py run --n 50` generates a module with N composables, gives each a `@Preview` (Medusa) and a
Paparazzi test, and times `medusaVerify` / `verifyPaparazziDebug` and the record tasks with the Gradle build cache off.
Full tables: [`benchmark/RESULTS.md`](../benchmark/RESULTS.md). These were measured with `differ = "offbytwo"`, before odiff became the
default; they were not re-run. A comparison costs 3 to 5 ms (see [differs](differs.md)) against 30 to 60 ms of rendering per preview, so the
difference should be small, but that is an expectation, not a measurement.

Median seconds for one `./gradlew` call on an M1 Max, "verify, rendering forced to rerun":

| | 50 previews | 200 previews |
|---|---|---|
| Paparazzi, 1 fork | 7.0 | 15.4 |
| Paparazzi, 10 forks | 9.0 | 18.1 |
| Medusa | 9.2 | 13.7 |
| Medusa, `useDaemon = true` | **6.1** | **10.8** |
| Medusa, daemon, 2 render processes | 6.7 | 11.5 |

- Medusa pays a larger fixed cost per call (about 5 s without the daemon, 2 s with it) but about half the cost per extra
  preview (roughly 30 ms vs 56 ms here). Without the daemon it is **slower** than Paparazzi at 50 previews and about 11% faster
  at 200; with `useDaemon` it was 13% and 30% faster. Below a few dozen previews Paparazzi wins.
- More Paparazzi forks and more render processes were both **slower** here: each pays JVM or CLI start-up for little work.
- Caveats: one machine, 2-3 runs per cell, synthetic composables, and the tools do not render identical pixels (layoutlib
  `Pixel 5` frame vs the CLI's renderer), so this is time to a verdict, not fidelity. Paparazzi is an alpha (2.0.0-alpha05.1).

## Other measurements

- How many modules to verify at once, and the formula behind the default: [`benchmark/CONCURRENCY.md`](../benchmark/CONCURRENCY.md).
- Which differ flags which change, and how long a comparison takes: [`benchmark/DIFFERS.md`](../benchmark/DIFFERS.md).
- What the Android CLI does and costs (render and list timings, daemon, concurrent processes, variants, parameters): [`benchmark/SPIKE.md`](../benchmark/SPIKE.md).
