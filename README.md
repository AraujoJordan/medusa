# Medusa

Screenshot tests for Jetpack Compose `@Preview`s, rendered by the [Android CLI](https://developer.android.com/tools/agents/android-cli/overview)
(`android preview list` / `android preview render`). No device, emulator or Android Studio, and no test code to write:
every `@Preview` is a test. Images are compared with **odiff** by default (Medusa installs it for you), or with Paparazzi's own
comparison code, an in-process port of odiff, or ImageMagick.

Golden images are stored **next to the Kotlin file that declares the preview**:

```
ui/components/
  Button.kt
  Button_PrimaryButtonPreview.png            # one @Preview
  Button_PrimaryButtonLabelsPreview_0.png    # @PreviewParameter or @PreviewLightDark / @PreviewFontScale / ...:
  Button_PrimaryButtonLabelsPreview_1.png    #   one image per value, numbered in declaration order
  Button_PrimaryButtonPreview.semantics.txt  # only with accessibilitySnapshots = true
```

## Install

```kotlin
// settings.gradle.kts
pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}

// build.gradle.kts of a module with previews
plugins {
  id("com.android.library") // or com.android.application
  id("io.github.araujojordan.medusa") version "0.1.0"
}
```

Published as `io.github.araujojordan:medusa-gradle-plugin` and `io.github.araujojordan:medusa-core` (Maven Central, once the
release has been approved there; see `RELEASING.md`). Until then, or for any tag, use JitPack:

```kotlin
// settings.gradle.kts
pluginManagement {
  repositories { maven("https://jitpack.io"); google(); mavenCentral(); gradlePluginPortal() }
  resolutionStrategy {
    eachPlugin {
      if (requested.id.id == "io.github.araujojordan.medusa") {
        useModule("com.github.AraujoJordan.medusa:medusa-gradle-plugin:${requested.version}")
      }
    }
  }
}
```

Run `./gradlew medusaSetup` once (see Setup), then `./gradlew medusaRecord`.

## Use

```kotlin
// build.gradle.kts of an Android app or library module
plugins {
  id("com.android.application")
  id("io.github.araujojordan.medusa")
}

medusa {
  // maxPercentDifference = 0.01       // share of pixels (0-100) allowed to differ; Paparazzi's default
  // differ = "odiff"                  // odiff (default) | yiq | magick | offbytwo | pixelperfect | mssim | sift | flip | de2000
  // autoInstall = false               // do not download odiff when it is missing (offline or locked-down CI)
  // fallbackToYiq = false             // fail instead of using the built-in yiq differ when odiff is unavailable
  // odiff { threshold = 0.1; antialiasing = true; ignoreRegions = listOf("0:0-100:24") }
  // magick { metric = "AE"; fuzz = "2%" }
  // accessibilitySnapshots = true     // also snapshot the accessibility tree
  // useDaemon = true                  // keep the CLI's renderer warm between runs
  // renderParallelism = 1             // concurrent render processes
  // compareParallelism = <cores>      // images compared at once
  // androidCliPath = "/path/to/android"
  // includeDependentModules = true    // also previews of modules this one depends on
  // verifyOnCheck = false             // do not run medusaVerify as part of `check`
  // sourceDirs.from("more/dirs")      // in addition to the debug variant's sources reported by AGP
}
```

`differ`, `maxPercentDifference`, `useDaemon`, `renderParallelism`, `compareParallelism`, `includeDependentModules`,
`accessibilitySnapshots` and the differ options can also be set from the command line, for example
`./gradlew medusaVerify -Pmedusa.differ=odiff -Pmedusa.odiff.threshold=0` (`medusa.yiq.*`, `medusa.odiff.*`, `medusa.magick.*`).

## Setup

`./gradlew medusaSetup` checks what Medusa needs on this machine and installs what is missing, for your platform and your user only
(no sudo). It is safe to run again.

| Tool | What `medusaSetup` does | Without running it |
|---|---|---|
| **Android CLI** | runs Google's official installer for the platform (the `curl \| bash` of the [download page](https://developer.android.com/tools/agents/android-cli/archive), `~/.local/bin/android`) | the build stops and says to run `medusaSetup`. It is never installed as a side effect of verifying, because Google's script is not pinned to a checksum |
| **odiff** (the default differ) | downloads the **pinned** release (4.5.0) for the platform from GitHub (macOS and Linux, arm64 and x64, Windows), checks its **SHA-256**, installs `~/.medusa/bin/odiff` and runs `odiff --version` once | installs itself the first time it is needed, unless `autoInstall = false`. If that is impossible (offline, a platform odiff does not publish, a checksum mismatch) Medusa **warns and compares with the built-in `yiq` differ**, which counts the same differing pixels, or fails if you set `fallbackToYiq = false` |
| ImageMagick (`differ = "magick"`) | only tells you where it found it | `brew install imagemagick` |

An `odiff` already on `PATH` (for example from Homebrew), in `~/.medusa/bin`, or at `odiff { binary = "..." }` is used and never replaced; a
path you configured that does not exist is an error, not a reason to install something else. For CI, cache `~/.medusa` (or
`-Pmedusa.installDir=`), or install odiff yourself and set `-Pmedusa.autoInstall=false`.

| Task | What it does | Paparazzi equivalent |
|---|---|---|
| `./gradlew medusaRecord` | Render all previews, save as goldens | `recordPaparazziDebug` |
| `./gradlew medusaVerify` | Render and compare with goldens (also part of `check`) | `verifyPaparazziDebug` |
| `./gradlew medusaCleanRecord` | Delete goldens, then record | `cleanRecordPaparazziDebug` |
| `./gradlew medusaDelete` | Delete goldens | `deletePaparazziSnapshots` |
| `./gradlew medusaList` | Show previews and where their goldens live | – |

Pick previews with `--preview` on any task. It is repeatable and accepts the full name, `Class.Name`, the simple name, or a glob:

```sh
./gradlew :sample:medusaRecord --preview=PrimaryButtonPreview
./gradlew :sample:medusaVerify --preview='*Button*' --preview=MainScreenPreview
```

On failure, `build/medusa/failures/` holds `delta-<name>.png` (Paparazzi's image: expected | delta | actual) and
`<name>_actual.png`; `build/medusa/report/index.html` is the report and `build/medusa/test-results/medusa.xml` is JUnit XML
for CI. `medusaVerify` also warns about golden images that no preview produces any more (only on a full run).

## Choosing how images are compared

`differ` selects the algorithm. Every one produces the same failure image (expected | diff | actual) and report.

| `differ` | What it is | `maxPercentDifference` means |
|---|---|---|
| `odiff` (**default**) | the external [odiff](https://github.com/dmtrKovalenko/odiff) binary (installed by Medusa, see Setup), through a pool of `odiff --server` processes | share of pixels over `odiff.threshold` |
| `yiq` | in-process Kotlin port of odiff's algorithm (YIQ colour distance, optional antialiasing detection); also what `odiff` falls back to | share of pixels over `yiq.threshold` |
| `magick` | external ImageMagick `compare` (`brew install imagemagick`) | `AE`: share of pixels over `fuzz`; `RMSE`, `MAE`, `DSSIM`: normalised error in percent; `PSNR`: needs at least `minPsnr` dB |
| `offbytwo`, `pixelperfect`, `mssim`, `sift`, `flip`, `de2000` | Paparazzi's own differs, copied verbatim; `offbytwo` is Paparazzi's default | Paparazzi's rule: summed channel difference |

Paparazzi's tolerance and odiff's behave differently (see below), so verdicts are not interchangeable: if you move an existing project from
Paparazzi, `differ = "offbytwo"` gives its exact rule.

**Paparazzi's rule.** `tools/medusa-core/src/main/kotlin/app/cash/paparazzi/` is Paparazzi's `Differ` and its six differs, copied
verbatim from a pinned upstream commit (Apache-2.0, `tools/medusa-core/PAPARAZZI_VENDORED.md`). The pass/fail rule is
Paparazzi's `ImageUtils.assertImageSimilar`: a difference above `maxPercentDifference` (default **0.01**) fails, and so does a
width or height that differs by 2px or more. `UpstreamParityTest` runs the released Paparazzi jar and Medusa on the same pairs
and requires the same verdict.

**`yiq` is odiff without the binary.** `YiqVsOdiffParityTest` requires exactly the same differing-pixel count as the installed
odiff 4.5.0 on 96 combinations (thresholds 0 to 0.3, antialiasing on and off, shifted and recoloured antialiased text, alpha).
The shipped 4.5.0 truncates its fixed-point colour coefficients although its source rounds them, and the port follows the binary.

Things that will surprise you (all measured, see `benchmark/DIFFERS.md` and `benchmark/SPIKE.md`):

- **odiff's default threshold of 0.1 hides colour drift completely** (this is now Medusa's default) (a +3 level change on every pixel reports 0 differing
  pixels) while `threshold = 0` flags 98.7% of them. It is a cliff, not a gradual tolerance. Paparazzi's `offbytwo` lets 1 to 2 levels
  through and fails 3.
- **The default tolerance is tiny**: 0.01% of a 339x210 image is about 7 whole pixels, so a handful of changed pixels passes.
- **External tools fail any size change.** ImageMagick compares different-size images silently (a 1px wider image can pass), so Medusa
  checks the size first and reports `size changed from AxB to CxD`. Paparazzi's differs tolerate 1px.
- `magick` ignores exit codes (PSNR of identical images exits 1) and reads the printed value. Its `RMSE`/`MAE`/`DSSIM` are far
  more sensitive to isolated pixels than `AE`, so give them their own `maxPercentDifference`.
- Paparazzi's `sift` rejects identical **flat-colour** images, in the released jar too (pinned by a test). Real previews are not flat.
- A comparison costs 3 to 5 ms in process or via `odiff --server` (8 ms with one odiff process per image) and about 21 ms with
  ImageMagick, on 339x210 images (timed pair includes drawing the failure image), against roughly 30 to 60 ms of rendering per preview.

## How it renders

```
android preview render --daemon? --fqn=... (one batch)
        |  each PNG is picked up the moment its closing IEND chunk is on disk
        v
     Channel  -->  N comparers on Dispatchers.Default  -->  verdicts / goldens written
```

Rendering is not waited for: while the CLI is still producing images, the finished ones are already being compared.
With `--info` Medusa logs when the first image was compared and when the last one was rendered. Measured on a real module,
the CLI's fixed cost per call (about 5s without `--daemon`, 2s with it) dwarfs the roughly 50ms per extra preview, so
Medusa renders everything in **one** call by default; `renderParallelism` > 1 starts several calls at once, which works
but pays that fixed cost per call. See `benchmark/SPIKE.md` for the measurements.

## Speed compared with Paparazzi

`python3 benchmark/bench.py run --n 50` generates a module with N composables, gives each a `@Preview` (Medusa) and a
Paparazzi test, and times `medusaVerify` / `verifyPaparazziDebug` and the record tasks with the Gradle build cache off.
Full tables: `benchmark/RESULTS.md`. These were measured with `differ = "offbytwo"`, before odiff became the default; they were not
re-run. A comparison costs 3 to 5 ms (see below) against 30 to 60 ms of rendering per preview, so the difference should be small, but that is
an expectation, not a measurement. Median seconds for one `./gradlew` call on an M1 Max, "verify, rendering forced to rerun":

| | 50 previews | 200 previews |
|---|---|---|
| Paparazzi, 1 fork | 7.0 | 15.4 |
| Paparazzi, 10 forks | 9.0 | 18.1 |
| Medusa | 9.2 | 13.7 |
| Medusa, `useDaemon = true` | **6.1** | **10.8** |
| Medusa, daemon, 2 render processes | 6.7 | 11.5 |

- Medusa pays a larger fixed cost per call (about 5s without the daemon, 2s with it) but about half the cost per extra
  preview (roughly 30 ms vs 56 ms here). Without the daemon it is **slower** than Paparazzi at 50 previews and about 11% faster
  at 200; with `useDaemon` it was 13% and 30% faster. Below a few dozen previews Paparazzi wins.
- More Paparazzi forks and more render processes were both **slower** here: each pays JVM or CLI start-up for little work.
- Caveats: one machine, 2-3 runs per cell, synthetic composables, and the tools do not render identical pixels (layoutlib
  `Pixel 5` frame vs the CLI's renderer), so this is time to a verdict, not fidelity. Paparazzi is an alpha (2.0.0-alpha05.1).

## Multi-module builds

Apply `dev.medusa` in every module that has previews (a convention plugin works). From the root, `./gradlew medusaVerify` runs
in all of them and each golden sits next to its own source, also in modules whose directory differs from their project path.
Checked on `fixtures/multimodule` (`:app`, `:feature:home`, `:core:designsystem`, a remapped `:legacy`, a Compose module without
previews, a module without Compose).

- Medusa gives the CLI the module's **Gradle path** (`:feature:home`). The CLI resolves directories only for top-level projects, which
  is why v2 failed on nested modules.
- Modules without previews or without Compose report `0 preview(s)`; they do not fail.
- Each task starts a CLI process with its own nested Gradle build (about 800 MB each). `medusa.maxConcurrentRenders` (default: chosen from the machine's cores, memory and
  the project's Gradle heap, see `benchmark/CONCURRENCY.md`; 3 on a 10 core Mac, 1 on a 2 or 4 CPU runner) caps how many run at once
  under `--parallel`. Measured on 16 modules: 88 s with 1, 41 s with 3 or 4, 56 s with 10 and 9 GB. `medusaSetup` prints the value.
  `useDaemon = true` and the aggregator mode below help more.
- **Aggregator mode:** `medusa { includeDependentModules = true }` on `:app` makes **one** CLI call (`list --all`) for the module
  and its project dependencies: 6.8 s (3.8 s with the daemon) for 21 previews, against 27 s for verifying the six modules one by one (22 previews:
  `:legacy` is not a dependency of `:app`), and goldens still go beside the sources of whichever module declares the preview. Use it on one module and not on the libraries it covers.
- `./gradlew medusaVerify --preview=X` from the root skips modules without a match (and says so); `:feature:home:medusaVerify
  --preview=X` fails if nothing matches. A filter matching nothing in the whole build passes silently, with one "Skipped" line per module.
- Directories named `external` or `third_party`, and `.gradle`, are excluded by the CLI's `--all`.

## Previews supported

Checked against the real CLI with `sample/.../PreviewParams.kt`:

| `@Preview` feature | Result |
|---|---|
| `device` (`PIXEL_5`, `PIXEL_TABLET`, ...), `widthDp`, `heightDp` | works |
| `fontScale`, `locale`, `apiLevel`, night `uiMode`, `backgroundColor` | works |
| `@PreviewLightDark`, `@PreviewFontScale`, repeated `@Preview`, `@PreviewParameter` | works, one golden per image |
| **Custom multipreview annotations**, also nested, also defined in another module | works (the CLI expands them from the compiled classes); `sample/.../CustomAnnotations.kt` and `fixtures/multimodule` |
| a custom multipreview with `@PreviewParameter` | entries x values images, numbered `_0` to `_N` |
| `showSystemUi` | no visible effect in the render |
| RTL layout direction | locale is applied; direction not verified |

## Notes and limits

- Images are cropped to their content by the renderer, so a layout change shows up as a size change.
- **Build variants cannot be chosen:** the CLI always builds the debug variant. Its Gradle plugin registers a task per variant, but
  the CLI requests only the debug one and hands it the manifest path; running a release task by hand fails for lack of that
  option. (`preview` has no variant option; the text after `?` in its reference selects a sub-project.) Medusa reads the debug
  build type's source sets from AGP.
- **No animations:** the CLI renders single frames and has no clock control, so there is no equivalent of Paparazzi's
  animation (APNG) snapshots.
- Accessibility snapshots are Medusa's own normalised text format (`.semantics.txt`, from the CLI's component tree), not
  Paparazzi's rendered accessibility legend.
- Golden names use the CLI's declaration-order index, not the preview's `name`, because the list returns names in a
  different order than the renders. Reordering the annotations inside a custom multipreview therefore renumbers its goldens:
  run `medusaCleanRecord`.
- Two previews with the same function name in one file (different classes or objects) get the class name in their golden names
  (`Screens_Light_Preview.png`); all other names are unchanged, and the name does not depend on `--preview`.
- The CLI sends usage metrics to Google unless disabled. Medusa always passes `--no-metrics`.

## Layout

```
tools/medusa-core/          Kotlin/JVM, no Gradle API: CLI wrapper, golden paths, vendored Paparazzi differs, YIQ/odiff/ImageMagick comparers, pipeline, report
tools/medusa-gradle-plugin/ Gradle plugin `io.github.araujojordan.medusa`: tasks and extension; TestKit tests use a fake `android` script
sample/                     Example Compose app with previews and recorded goldens
fixtures/multimodule/       Six-module build with custom annotations and a remapped module directory
benchmark/                  SPIKE.md (measurements), generate.py + bench.py (Medusa vs Paparazzi), RESULTS.md,
                            differs.py (which differ flags what), DIFFERS.md
```

`tools/` is a composite build included from `settings.gradle.kts` via `pluginManagement { includeBuild("tools") }`.
Run its tests with `cd tools && ./gradlew test`.
