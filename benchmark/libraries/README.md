# Six-library screenshot benchmark

The generated consumers share the composable bodies in [`../generate.py`](../generate.py), wrapped in
an opaque white, light-mode 320×640 dp frame. Each tool records its own reference images. The single-module
consumer has 50 screenshots in `:app`; the multi-module consumer has 10 each in `:app` and four libraries
that `:app` depends on. The same total workload is used in both layouts.

## Run

Set `JAVA_HOME` to JDK 21 and `ANDROID_HOME` to your Android SDK. Install the Android CLI for Medusa.
The fixture generator creates disposable projects outside this repository and refuses to overwrite them.

```sh
python3 benchmark/libraries/generate.py /tmp/screenshot-libraries
python3 benchmark/libraries/run.py /tmp/screenshot-libraries \
  --tools medusa,paparazzi,roborazzi,google --setup-only --output /tmp/host-controls.json
python3 benchmark/libraries/run.py /tmp/screenshot-libraries \
  --tools dropshots,shot --setup-only --output /tmp/device-controls.json
# Wait for all setup work to finish. Never run timed workloads concurrently.
python3 benchmark/libraries/run.py /tmp/screenshot-libraries \
  --tools medusa,paparazzi,roborazzi,google --skip-setup --output /tmp/host-times.json
python3 benchmark/libraries/run.py /tmp/screenshot-libraries \
  --tools dropshots,shot --skip-setup --output /tmp/device-times.json
```

For Shot and Dropshots, use one dedicated, already booted Android 35 Google APIs arm64 emulator.
The published configuration uses `-no-window -no-audio -no-snapshot -gpu swiftshader_indirect`,
`adb shell wm size 320x720`, and `adb shell wm density 160`. Disable window, transition and animator
scales through `adb shell settings put global <scale_name> 0`; unlock the device before testing.
Emulator boot is excluded. Installation, instrumentation, image transfer and the tools' comparison/reporting
steps are included. Do not run either device tool while another test is using the emulator.

## Measurement policy

- Wall-clock time of a complete Gradle record or verify invocation. Downloading dependencies and initial
  compilation happen during untimed setup. One full untimed round precedes three measured rounds.
  Project/tool order rotates between rounds. The headline is the median, with all observations retained.
- Record edits one composable in the app, then updates existing reference images. It includes incremental
  compilation. Verify uses unchanged sources but forces screenshot execution; it cannot reuse a cached verdict.
- Build and configuration caches are disabled in every project, including the nested builds used by the CLI.
  Gradle daemons are warm. Host-side tools use `--parallel --max-workers=3`, with one JUnit fork per module.
  Device tools run serially on one emulator. This is a controlled worker budget, not a search for each
  tool's maximum parallel throughput.
- Medusa uses its released 0.2.0 defaults, including the shared renderer daemon, odiff and automatic aggregation.
  The other libraries use their default comparison policy. Tolerances and renderer/platform internals differ;
  equal image dimensions and source code do not imply pixel-equivalent rendering or equivalent sensitivity.
- Every successful verification must report exactly 50 tests, zero failures/errors/skips, and newly written
  XML results. Every successful record must leave exactly 50 golden PNGs. Mismatch controls alter one screen,
  require an actual screenshot failure, restore the source, and require a passing verification.
- Shot compares on the host after instrumentation, so its mismatch control checks the screenshot-difference
  diagnostic as well as the nonzero exit; instrumentation XML alone can still show passing tests.
- Roborazzi stores actual/diff failure images beside references. These are excluded from golden counts.

## Compatibility choices

All fixtures use Compose BOM 2026.03.01. Medusa 0.2.0, Paparazzi 2.0.0-alpha05.1, Roborazzi 1.77.0
and Dropshots 0.6.0 use AGP 9.0.1 / Gradle 9.1.0 / Kotlin Compose compiler 2.3.20. Roborazzi uses
Robolectric 4.14.1 with native graphics and API 35. Google uses its recommended test-suite engine
0.0.1-alpha16 with AGP 9.5.0-alpha03 / Gradle 9.6.0. Shot 6.1.0 uses AGP 8.13.2 / Gradle 8.13 /
Kotlin 2.2.21 because its plugin relies on the older Android extension API.

Dropshots 0.6.0's plugin uses the test APK application ID for its device screenshot directory, while its
runtime defaults to the target application's ID. The fixtures explicitly configure `rootScreenshotDirectory`
and `recordScreenshots` through the public constructor to use the plugin's directory/marker. No library
implementation is patched, and normal pixel verification remains enabled.

Sources: [Roborazzi](https://github.com/takahirom/roborazzi/tree/1.77.0),
[Google test suites](https://developer.android.com/studio/preview/compose-screenshot-testing-with-testsuites),
[Shot](https://github.com/pedrovgs/Shot), [Dropshots](https://github.com/dropbox/dropshots/tree/0.6.0).

After collecting complete runs, generate the table and retain the raw observations:

```sh
python3 benchmark/libraries/report.py --measurements /tmp/host-times.json /tmp/device-times.json \
  --controls /tmp/host-controls.json /tmp/device-controls.json \
  --environment benchmark/libraries/environment.json --output benchmark/libraries
```

`environment.json` records the machine, SDK/CLI, emulator and toolchain versions. The published
[results.json](results.json) includes that environment and the harness/corpus hashes.
