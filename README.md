<h1 align="center">🐍 Medusa</h1>

<p align="center"><b>Your <code>@Preview</code>s are already your tests. Medusa just checks them.</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.araujojordan/medusa-gradle-plugin"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.araujojordan/medusa-gradle-plugin"></a>
  <a href="https://github.com/AraujoJordan/medusa/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/AraujoJordan/medusa/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://jitpack.io/#AraujoJordan/medusa"><img alt="JitPack" src="https://jitpack.io/v/AraujoJordan/medusa.svg"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Needs the Android CLI" src="https://img.shields.io/badge/needs-Android%20CLI-3ddc84">
</p>

<p align="center"><img src="docs/media/hero.png" alt="Goldens recorded by Medusa from the sample app's previews" width="720"></p>

Screenshot tests for Jetpack Compose, rendered by Google's [Android CLI](https://developer.android.com/tools/agents/android-cli/overview)
(`android preview render`). No device, no emulator, no Android Studio, and **no test code**: every `@Preview` you already wrote is a test.
Golden images live **right next to the Kotlin file** that declares the preview.

## 🤔 But why??

Because you already wrote the preview. Paparazzi and Roborazzi want a test class per screen. Google's own tool wants your previews in a
second source set. Shot and Dropshots want an emulator in CI. Medusa asks the Android CLI which `@Preview`s exist, renders them, and
compares the pictures with last time's. That's it. That's the library.

## ⚡ 30 seconds

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

```bash
./gradlew medusaSetup    # once: installs the Android CLI and odiff for you (no sudo)
./gradlew medusaRecord   # render every @Preview, save the goldens
./gradlew medusaVerify   # render again and compare (also runs as part of `check`)
```

```
ui/components/
  Button.kt
  Button_PrimaryButtonPreview.png            # one @Preview
  Button_PrimaryButtonLabelsPreview_0.png    # @PreviewParameter, @PreviewLightDark, ...: one image per value
  Button_PrimaryButtonLabelsPreview_1.png
```

<details>
<summary>Using JitPack instead of Maven Central</summary>

Every tag is also built by JitPack, with the tag as the version and the group `com.github.AraujoJordan.medusa`:

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
// build.gradle.kts: id("io.github.araujojordan.medusa") version "v0.1.0"
```

JitPack also needs `maven("https://jitpack.io")` in `dependencyResolutionManagement { repositories { ... } }`, because the plugin depends on `medusa-core`.
</details>

Everything else (tasks, `--preview` filters, the `medusa { }` block) is in [docs/setup.md](docs/setup.md).

## 🖼️ What a failure looks like

<p align="center"><img src="docs/media/failure.png" alt="expected, diff and actual images side by side" width="720"></p>

Left to right: expected, diff, actual (Paparazzi's own failure image format). Here a dark theme sneaked into a button:

```
odiff: 24343 of 71190 pixels differ (34.194%, allowed 0.010%)
```

The images land in `build/medusa/failures/`, with an HTML report in `build/medusa/report/` and JUnit XML for CI.

## ✨ Features

- **Zero test code.** Multipreviews, `@PreviewLightDark`, `@PreviewParameter` and your own custom preview annotations all work ([how](docs/how-it-works.md)).
- **Fast pipeline.** Rendering never waits for comparing: finished images are compared while the CLI is still rendering ([numbers](docs/benchmarks.md)).
- **Pick your comparer.** odiff by default (installed and checksum-verified for you), or Paparazzi's own differs, an in-process port, or ImageMagick ([differs](docs/differs.md)).
- **Multi-module.** One command from the root, each golden beside its own source, and the parallelism is chosen from your cores and memory ([multi-module](docs/multi-module.md)).
- **Accessibility snapshots** as an opt-in text file next to each golden.
- **Honest about its limits**: [what works and what was verified where](docs/limitations.md).

## ⚖️ Medusa vs the rest

Checked on 2026-11-04 from each project's own repo and docs. Details, sources and "pick X if" advice: [docs/comparison.md](docs/comparison.md).

| | You write | Needs a device | Goldens | Status |
|---|---|---|---|---|
| **Medusa** | nothing, `@Preview`s are the tests | no | next to the `.kt` file | 0.1.0 |
| **[Paparazzi](https://github.com/cashapp/paparazzi)** | a JUnit test per screen | no | `src/test/snapshots` | 2.0.0-alpha05.1 |
| **[Roborazzi](https://github.com/takahirom/roborazzi)** | a Robolectric test (preview generator is experimental) | no | `build/outputs/roborazzi` by default | 1.77.0 |
| **[Google's Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing)** | `@PreviewTest` in a separate source set | no | `src/<suite>.../reference/` | standalone plugin unmaintained, replacement experimental |
| **[Shot](https://github.com/pedrovgs/Shot)** | an instrumented test per screen | **yes** | recorded with `-Precord` | 6.1.0 |
| **[Dropshots](https://github.com/dropbox/dropshots)** | an instrumented test per screen | **yes** | in the repository | 0.6.0 |

What the others do that Medusa **doesn't**: XML Views, per-test device configuration and animation snapshots (Paparazzi); Robolectric
interaction, GIFs and video (Roborazzi); IDE gutter icons and an in-IDE diff viewer (Google); real-device rendering (Shot, Dropshots).
Pick Medusa if you have Compose previews and don't want to write tests for them. Pick something else if you need any of the above.

## 🏎️ Performance

The only rival I benchmarked head to head is Paparazzi (same generated composables, one `./gradlew` call, build cache off, M1 Max,
median seconds, "verify, rendering forced to rerun"):

| | 50 previews | 200 previews |
|---|---|---|
| Paparazzi (1 fork) | 7.0 s | 15.4 s |
| Medusa | 9.2 s | 13.7 s |
| Medusa with `useDaemon = true` | **6.1 s** | **10.8 s** |

So: Medusa pays a bigger fixed cost per call but about half the cost per extra preview (roughly 30 ms vs 56 ms). With the daemon it was
**13% faster at 50 previews and 30% faster at 200**; without it, slower at 50 and about 11% faster at 200. **Below a few dozen
previews, Paparazzi wins.**

The caveats are real: one machine, 2-3 runs per cell, synthetic composables, different renderers (so this is time to a verdict, not
fidelity), and the runs predate odiff becoming the default. Roborazzi, Google's tool, Shot and Dropshots were **not** benchmarked, so I
make no speed claim against them; the device-based ones also pay for an emulator, which this test does not measure. Full tables and
method: [docs/benchmarks.md](docs/benchmarks.md). Run `python3 benchmark/bench.py run --n 50` to check it on your machine.

## 🔁 Coming from Paparazzi

| Paparazzi | Medusa |
|---|---|
| `recordPaparazziDebug` | `medusaRecord` |
| `verifyPaparazziDebug` | `medusaVerify` |
| `cleanRecordPaparazziDebug` | `medusaCleanRecord` |
| `deletePaparazziSnapshots` | `medusaDelete` |

- The pixels will not match Paparazzi's (a different renderer), so record fresh goldens.
- Want Paparazzi's exact verdicts? `medusa { differ = "offbytwo" }` uses its code; the 0.01% tolerance is already the default.
- Goldens are PNGs next to your sources, so if you use Git LFS: `echo '*.png filter=lfs diff=lfs merge=lfs -text' >> .gitattributes`.

## 📋 Requirements and status

- JDK 17+, Gradle 9.1+, Android Gradle Plugin 9.x, and the Android CLI (`medusaSetup` installs it).
- **Version 0.1.0.** Compose `@Preview`s only, debug variant only, no animations.
- Verified on macOS arm64, and in CI (unit and Gradle TestKit tests) on Ubuntu and macOS. Rendering on Linux was checked in an amd64
  container under emulation. **Windows is untested**, and one Linux question (`android-37`) is still open. Full list: [docs/limitations.md](docs/limitations.md).

## 🧪 In CI

Not yet verified on GitHub-hosted runners, so treat this as a starting point:

```yaml
- uses: actions/cache@v4
  with: { path: ~/.medusa, key: medusa-${{ runner.os }} }
- run: ./gradlew medusaSetup medusaVerify
```

## ❓ FAQ

**Do I write tests?** No. That's the point.

**Why does a golden end in `_0`?** The preview expands to several images (`@PreviewParameter`, `@PreviewLightDark`, ...), numbered in declaration order.

**Why did verify fail on a size change?** Images are cropped to their content, so a layout change is a size change, and a size change always fails.

**Where did odiff come from?** `medusaSetup` (or the first verify) downloads a pinned release and checks its SHA-256. Offline, Medusa warns and uses the built-in `yiq` differ.

**Is Paparazzi dead?** No, and "which one wins" is the wrong question: they can live in the same project. See [docs/comparison.md](docs/comparison.md).

## 🤝 Contributing

Issues and PRs welcome, see [CONTRIBUTING.md](CONTRIBUTING.md). Found something in the comparison that is out of date or unfair? Please say so.

## 🙏 Credits

- [Paparazzi](https://github.com/cashapp/paparazzi) by Cash App: its six image differs are vendored unchanged (Apache-2.0, see [NOTICE](NOTICE)).
- [odiff](https://github.com/dmtrKovalenko/odiff): the default comparer.
- The [Android CLI](https://developer.android.com/tools/agents/android-cli/overview), which does the rendering.

## 📜 License

Apache-2.0 © 2026 Jordan L. Araujo Jr. Go build something. Verify responsibly.

[![Star History Chart](https://api.star-history.com/svg?repos=AraujoJordan/medusa&type=Date)](https://star-history.com/#AraujoJordan/medusa&Date)
