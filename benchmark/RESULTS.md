# Medusa vs Paparazzi benchmark

Machine: Apple M1 Max, 10 cores, macOS-27.0.1-arm64-arm-64bit-Mach-O, 64 GB
Android CLI 1.1.16717390

Wall-clock seconds of one `./gradlew` call, median (min-max) over the measured runs, Gradle daemon warm.
Both tools render the same generated composables (`benchmark/generate.py`).

**Reading these numbers.** One machine, 2-3 measured runs per cell, synthetic composables, Gradle build cache off.
Each call includes about 1-2 s of Gradle start-up for both tools. The tools do not render identical pixels:
Paparazzi renders with layoutlib in a `Pixel 5` frame, the Android CLI with its own renderer on `medium_phone`,
so this compares time to a verdict, not image fidelity. Paparazzi is 2.0.0-alpha05.1, Medusa is unreleased.

## 50 previews (2026-11-03 18:35, 3 measured runs)

**verify, nothing changed (rendering forced to rerun)**

| configuration | seconds | previews/s (median) |
|---|---|---|
| medusa | 9.2  (9.1-9.3) | 5.5 |
| paparazzi-1 | 7.0  (6.9-7.0) | 7.2 |
| paparazzi-10 | 9.0  (8.9-9.1) | 5.6 |
| medusa-daemon | 6.1  (6.0-6.3) | 8.2 |
| medusa-daemon-render2 | 6.7  (6.6-8.4) | 7.5 |

**cold: `clean`, then verify**

| configuration | seconds | previews/s (median) |
|---|---|---|
| medusa | 10.2  (10.1-10.9) | 4.9 |
| paparazzi-1 | 8.2  (7.9-9.0) | 6.1 |
| paparazzi-10 | 10.0  (9.9-10.0) | 5.0 |
| medusa-daemon | 7.0  (6.8-7.2) | 7.2 |
| medusa-daemon-render2 | 7.1  (7.0-7.2) | 7.0 |

**record after editing one composable**

| configuration | seconds | previews/s (median) |
|---|---|---|
| medusa | 9.2  (9.2-9.5) | 5.4 |
| paparazzi-1 | 7.9  (7.9-8.0) | 6.3 |
| paparazzi-10 | 9.5  (9.4-9.7) | 5.2 |
| medusa-daemon | 6.4  (6.4-6.6) | 7.8 |
| medusa-daemon-render2 | 6.9  (6.8-8.8) | 7.3 |

## 200 previews (2026-11-03 18:44, 2 measured runs)

**verify, nothing changed (rendering forced to rerun)**

| configuration | seconds | previews/s (median) |
|---|---|---|
| medusa | 13.7  (13.7-13.7) | 14.6 |
| paparazzi-1 | 15.4  (15.2-15.6) | 13.0 |
| paparazzi-10 | 18.1  (18.0-18.3) | 11.0 |
| medusa-daemon | 10.8  (10.8-10.9) | 18.5 |
| medusa-daemon-render2 | 11.5  (11.4-11.6) | 17.4 |

**cold: `clean`, then verify**

| configuration | seconds | previews/s (median) |
|---|---|---|
| medusa | 16.6  (16.2-17.0) | 12.0 |
| paparazzi-1 | 18.6  (17.7-19.5) | 10.7 |
| paparazzi-10 | 19.6  (18.4-20.7) | 10.2 |
| medusa-daemon | 12.4  (12.1-12.7) | 16.2 |
| medusa-daemon-render2 | 13.1  (13.0-13.3) | 15.2 |

**record after editing one composable**

| configuration | seconds | previews/s (median) |
|---|---|---|
| medusa | 14.3  (14.2-14.3) | 14.0 |
| paparazzi-1 | 18.0  (17.9-18.0) | 11.1 |
| paparazzi-10 | 19.6  (19.6-19.7) | 10.2 |
| medusa-daemon | 11.3  (10.8-11.8) | 17.7 |
| medusa-daemon-render2 | 11.9  (11.6-12.3) | 16.8 |

