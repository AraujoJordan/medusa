# Setup, configuration and tasks

## Setup

`./gradlew medusaSetup` checks what Medusa needs on this machine and installs what is missing, for your platform and your user only
(no sudo). It is safe to run again, and it prints the concurrency it chose (see [multi-module builds](multi-module.md)).

| Tool | What `medusaSetup` does | Without running it |
|---|---|---|
| **Android CLI** | runs Google's official installer for the platform (the `curl \| bash` of the [download page](https://developer.android.com/tools/agents/android-cli/archive), `~/.local/bin/android`) | the build stops and says to run `medusaSetup`. It is never installed as a side effect of verifying, because Google's script is not pinned to a checksum |
| **odiff** (the default differ) | downloads the **pinned** release (4.5.0) for the platform from GitHub (macOS and Linux, arm64 and x64, Windows), checks its **SHA-256**, installs `~/.medusa/bin/odiff` and runs `odiff --version` once | installs itself the first time it is needed, unless `autoInstall = false`. If that is impossible (offline, a platform odiff does not publish, a checksum mismatch) Medusa **warns and compares with the built-in `yiq` differ**, which counts the same differing pixels, or fails if you set `fallbackToYiq = false` |
| ImageMagick (`differ = "magick"`) | only tells you where it found it | `brew install imagemagick` |

An `odiff` already on `PATH` (for example from Homebrew), in `~/.medusa/bin`, or at `odiff { binary = "..." }` is used and never replaced; a
path you configured that does not exist is an error, not a reason to install something else. For CI, cache `~/.medusa` (or
`-Pmedusa.installDir=`), or install odiff yourself and set `-Pmedusa.autoInstall=false`.

## Tasks

| Task | What it does | Paparazzi equivalent |
|---|---|---|
| `./gradlew medusaRecord` | Render all previews, save as goldens | `recordPaparazziDebug` |
| `./gradlew medusaVerify` | Render and compare with goldens (also part of `check`) | `verifyPaparazziDebug` |
| `./gradlew medusaCleanRecord` | Delete goldens, then record | `cleanRecordPaparazziDebug` |
| `./gradlew medusaDelete` | Delete goldens | `deletePaparazziSnapshots` |
| `./gradlew medusaList` | Show previews and where their goldens live | none |
| `./gradlew medusaSetup` | Check and install the Android CLI and the differ | none |

Pick previews with `--preview` on any task. It is repeatable and accepts the full name, `Class.Name`, the simple name, or a glob:

```sh
./gradlew :sample:medusaRecord --preview=PrimaryButtonPreview
./gradlew :sample:medusaVerify --preview='*Button*' --preview=MainScreenPreview
```

On failure, `build/medusa/failures/` holds `delta-<name>.png` (Paparazzi's image: expected | delta | actual) and
`<name>_actual.png`; `build/medusa/report/index.html` is the report and `build/medusa/test-results/medusa.xml` is JUnit XML
for CI. `medusaVerify` also warns about golden images that no preview produces any more (only on a full run).

## Configuration

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
  // autoAggregate = false             // opt out of app-host grouping for root commands and check
  // useDaemon = false                 // opt out of the default persistent renderer
  // renderParallelism = 1             // concurrent render processes
  // compareParallelism = <cores>      // images compared at once
  // androidCliPath = "/path/to/android"
  // includeDependentModules = true    // also previews of modules this one depends on
  // verifyOnCheck = false             // do not run medusaVerify as part of `check`
  // sourceDirs.from("more/dirs")      // in addition to the debug variant's sources reported by AGP
}
```

`differ`, `maxPercentDifference`, `useDaemon`, `autoAggregate`, `renderParallelism`, `compareParallelism`, `includeDependentModules`,
`accessibilitySnapshots` and the differ options can also be set from the command line, for example
`./gradlew medusaVerify -Pmedusa.differ=odiff -Pmedusa.odiff.threshold=0` (`medusa.yiq.*`, `medusa.odiff.*`, `medusa.magick.*`).

How the differs behave, and which one to pick: [differs](differs.md).
