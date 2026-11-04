# Medusa and the other screenshot tools

Checked on **2026-11-04** against each tool's own repository and documentation (linked below). These projects move fast, and Medusa's author
is not neutral, so if something here is out of date or unfair, please [open an issue](https://github.com/AraujoJordan/medusa/issues).

## At a glance

| | Renders with | What you write | Needs a device | Goldens | Status |
|---|---|---|---|---|---|
| **Medusa** | the Android CLI's preview renderer (it bundles layoutlib) | nothing: every `@Preview` is a test | no | next to the `.kt` file | 0.1.0 |
| **[Paparazzi](https://github.com/cashapp/paparazzi)** | layoutlib on the JVM | a JUnit test with the `Paparazzi` rule and `snapshot { }` per screen (Compose or Views) | no | `src/test/snapshots` | 2.0.0-alpha05.1 (2026-09-28), Apache-2.0 |
| **[Roborazzi](https://github.com/takahirom/roborazzi)** | Robolectric Native Graphics on the JVM | a Robolectric test; or the *experimental* generator that turns `@Preview`s into tests | no | `build/outputs/roborazzi` by default | 1.77.0 (2026-10-22), Apache-2.0 |
| **[Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing)** (Google) | host-side, layoutlib | `@PreviewTest` on previews placed in a separate `screenshotTest` source set | no | `src/<suite>.../reference/` | the standalone plugin is **no longer maintained**; its replacement (AGP test suites) is **experimental** and needs AGP 9.5.0-alpha03 or newer |
| **[Shot](https://github.com/pedrovgs/Shot)** | on a device or emulator | an instrumented test per screen | **yes** | recorded with `-Precord` | 6.1.0 (2024-02-12), Apache-2.0 |
| **[Dropshots](https://github.com/dropbox/dropshots)** | on a device or emulator, asserted inside the instrumentation test | an instrumented test with the `Dropshots` rule | **yes** | stored in the repository | 0.6.0 (2026-03-25), Apache-2.0 |

## What each one does that Medusa does not

**Paparazzi** renders **XML Views** as well as Compose, lets each test pick its own device configuration (`deviceConfig = PIXEL_5`),
records **animations** as APNG, and has accessibility snapshots. It is the established tool (the most stars of this list by a wide margin) and its
documentation recommends Git LFS for the snapshots. It is still published as an alpha for 2.0.
Sources: [README](https://github.com/cashapp/paparazzi#readme), [docs](https://cashapp.github.io/paparazzi/).

**Roborazzi** runs on Robolectric, so a test can **interact** with the screen (and use Hilt) before taking a screenshot, which a static preview
cannot do. It also produces comparison images and a JSON diff, can capture **GIFs and video** (video is experimental), and has an accessibility
check. Its Compose preview support is the closest thing to Medusa's idea, but it is experimental and generates Robolectric tests that you
still have to set up (dependencies and configuration it tells you about).
Sources: [README](https://github.com/takahirom/roborazzi#readme), [preview support](https://github.com/takahirom/roborazzi#experimental-compose-preview-support).

**Google's Compose Preview Screenshot Testing** is first-party and has **IDE integration** in Android Studio (gutter icons, an in-IDE diff viewer with
match percentage). The previews it tests must be annotated `@PreviewTest` and live in their own `screenshotTest` source set, so a preview you
already have in `main` is copied or moved. The Google page itself says the standalone plugin is no longer maintained and recommends AGP test
suites, which are marked experimental.
Sources: [standalone plugin](https://developer.android.com/studio/preview/compose-screenshot-testing), [AGP test suites](https://developer.android.com/studio/preview/compose-screenshot-testing-with-testsuites).

**Shot and Dropshots** run on a real device or emulator, so they see what the device's graphics stack really draws (including behaviour that
host-side renderers approximate) and they fit existing instrumented tests. The price is an emulator or device in CI. Shot's last release is from
2024-02; Dropshots is at 0.6.0.
Sources: [Shot](https://github.com/pedrovgs/Shot#readme), [Dropshots](https://github.com/dropbox/dropshots#readme).

## What Medusa does differently

- **No test code.** It asks the Android CLI for the `@Preview`s that already exist, so there is no second copy of every screen to keep in sync.
- **Goldens next to the source**, so the image for `Button.kt` is a neighbour of `Button.kt` in code review.
- **Multipreviews and custom annotations come for free** (one golden per expanded image).
- **One command across modules**, with an aggregator mode that renders a module and its dependencies in a single CLI call.
- **A choice of comparers**, including odiff (installed for you) and Paparazzi's own differs, so a project moving from Paparazzi can keep its exact rule.

## Which one should you use?

- **Use Medusa** if you already write Compose `@Preview`s, want screenshot tests without writing tests, and can live with the debug variant, no
  animations and a tool at version 0.1.0 that depends on Google's new Android CLI.
- **Use Paparazzi** if you have XML Views, need per-test device configuration or animation snapshots, or want the most established option.
- **Use Roborazzi** if you need Robolectric (interaction, dependency injection) or GIFs and video, or you want to test behaviour, not only appearance.
- **Use Google's tool** if you want first-party support and the IDE workflow and can accept an experimental setup.
- **Use Shot or Dropshots** if the real device's rendering matters to you more than avoiding an emulator in CI.

They are not mutually exclusive: nothing stops a project from using Medusa for its previews and Paparazzi or Roborazzi for the screens that need
more.

## Speed

Only Paparazzi was benchmarked; there is no measured speed claim against Roborazzi, Google's tool, Shot or Dropshots. Measured against Paparazzi on the same generated composables: [benchmarks](benchmarks.md). In short, Paparazzi is faster for small projects, and Medusa
(with the daemon) was about 13% faster at 50 previews and 30% faster at 200 on the one machine tested.
