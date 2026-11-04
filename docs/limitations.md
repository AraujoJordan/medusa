# Limitations and what was verified

## Previews supported

Checked against the real CLI with [`sample/.../PreviewParams.kt`](../sample/src/main/kotlin/dev/medusa/sample/ui/components/PreviewParams.kt):

| `@Preview` feature | Result |
|---|---|
| `device` (`PIXEL_5`, `PIXEL_TABLET`, ...), `widthDp`, `heightDp` | works |
| `fontScale`, `locale`, `apiLevel`, night `uiMode`, `backgroundColor` | works |
| `@PreviewLightDark`, `@PreviewFontScale`, repeated `@Preview`, `@PreviewParameter` | works, one golden per image |
| **Custom multipreview annotations**, also nested, also defined in another module | works (the CLI expands them from the compiled classes) |
| a custom multipreview with `@PreviewParameter` | entries x values images, numbered `_0` to `_N` |
| `showSystemUi` | no visible effect in the render |
| RTL layout direction | locale is applied; direction not verified |

## Limits

- **Compose `@Preview`s only.** There is no equivalent of Paparazzi's XML View snapshots.
- **Build variants cannot be chosen:** the CLI always builds the debug variant. Its Gradle plugin registers a task per variant, but
  the CLI requests only the debug one and hands it the manifest path; running a release task by hand fails for lack of that
  option. (`preview` has no variant option; the text after `?` in its reference selects a sub-project.) Medusa reads the debug
  build type's source sets from AGP.
- **No animations:** the CLI renders single frames and has no clock control, so there is no equivalent of Paparazzi's
  animation (APNG) snapshots.
- Accessibility snapshots are Medusa's own normalised text format (`.semantics.txt`, from the CLI's component tree), not
  Paparazzi's rendered accessibility legend.
- The CLI sends usage metrics to Google unless disabled. Medusa always passes `--no-metrics`.
- Medusa depends on a young Google tool (the Android CLI's `preview` commands); its behaviour can change.

## What was verified where

- **macOS arm64:** everything: the sample, the multi-module fixture, the benchmarks.
- **CI (GitHub Actions, Ubuntu and macOS):** the unit and Gradle TestKit tests (with a fake CLI). On macOS the real odiff and ImageMagick are installed, so their tests run too.
- **Linux amd64 (a container under emulation):** `medusaSetup` (Android CLI and odiff with its pinned checksum), rendering and recording
  a Compose module. The speed curve on small machines was not measured, see [`benchmark/CONCURRENCY.md`](../benchmark/CONCURRENCY.md).
- **Not verified:** Windows; Linux on real hardware; GitHub-hosted runners running a full Medusa build of an Android project.
- **Open question:** AGP 9.0.1 could not find `android-37` in an SDK installed by the new Android CLI on Linux (the platform files match
  the Mac's, which works). The Linux checks used `android-36`.
