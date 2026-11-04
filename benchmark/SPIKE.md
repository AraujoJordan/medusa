# Phase 0 spike results

Machine: Apple Silicon Mac, Android CLI 1.1.16717390, Gradle 9.1.0, AGP 9.0.1, JDK 21. Module: `:sample` (6 previews at the time of timing).
All times are wall-clock seconds for one invocation, measured with `python3 time.time()` around the process.

## 1. Where does the time go?
| Call | Seconds |
|---|---|
| `preview list` | 1.8 |
| `preview render` x1 preview, no daemon | 4.9 |
| `preview render` x5 previews, no daemon | 5.5 |
| `preview render` x5, `--daemon`, first run (starts the daemon) | 5.8 |
| `preview render` x5, `--daemon`, warm | 2.4 |
| `preview render` x1 / x5 / x3, `--daemon`, warm | about 2.1 |

- The cost is almost all **fixed per invocation**: about 4.8s without the daemon, about 2s with it. Each extra preview costs roughly 45-150 ms.
- Stdout is **not** streamed: the list of result paths arrives in one burst at the end of the process.
- PNGs **are** written progressively (6 images spread over 0.27 s). A complete file can be detected while the process is still running, and the path `<pkg>/<FileKt>/<Name>_<index>.png` gives the FQN, so the stream does not need the JSON.

## 2. Concurrent render processes (3 previews, same module)
| Setup | Wall time |
|---|---|
| 3 processes at once, no daemon | 10.5 (all succeed; two wait on the first) |
| daemon warm, 3 processes at once | 3.2 |
| daemon warm, 3 processes one after another | 6.3 |
| daemon warm, **one process with 3 `--fqn`** | **2.1** |

Concurrent processes are safe (no failures, no lock timeouts) but never beat one batched call, because the fixed cost is paid per process.

**Decision:** one `--daemon` batch per run. Results are streamed by watching the output directory and are compared in parallel on `Dispatchers.Default`. `renderParallelism` stays an option (default 1); the benchmark with N=200 will tell whether chunking ever pays.

## 3. Variants
`android preview ... <ref>`: the part after `?` is resolved as a **sub-project** (`sample?debug` -> "Gradle project ':sample:debug' not found"). The CLI runs a `generateDebugComposePreviewRunfiles` task that it injects itself, and `android preview list/render -h`, `android describe` and `android build` expose no variant option.

**Decision:** variant selection is a CLI limitation. Medusa documents it and does not generate per-variant tasks.

## 4. @Preview parameter matrix (checked by eye with a probe composable, `PreviewParams.kt`)
| Parameter | Result |
|---|---|
| `device` (`PIXEL_5`, `PIXEL_TABLET`) | works (392x850dp @2.75, 1280x800dp @2.0); default device is `medium_phone` 411x914dp @2.625 |
| `widthDp`, `heightDp` | works |
| `fontScale` | works |
| `locale` | works (`de`, `ar` reach `Configuration.locales`); RTL layout direction not verified |
| `apiLevel` | works (`Build.VERSION.SDK_INT` = 30; default is 33) |
| `uiMode` night / `@PreviewLightDark` | works |
| `backgroundColor`, `showBackground` | works |
| `showSystemUi` | no visible effect |
| Multipreview annotations (`@PreviewLightDark`, `@PreviewFontScale`) | work: one render per entry |

**Bug found in v1:** a multipreview yields several list entries with the **same** `methodFqn` (told apart only by `name`), and render returns `<Name>_0..N.png` in **declaration order**, while the list is sorted alphabetically by name. v1 only added the index to golden names for `@PreviewParameter` previews, so every variant of a multipreview would have been written to and compared against the same file.

**Decision:** golden files get the `_<index>` suffix when the preview has a parameter provider **or** `preview list` has more than one entry for the FQN. If render still returns an extra image for a preview that was listed once, that is reported as an error rather than overwriting the golden. Names cannot be used in file names because they cannot be mapped to a render index.

## 5. Paparazzi compatibility
`app.cash.paparazzi` 2.0.0-alpha05.1 applies and records on AGP 9.0.1 / Gradle 9.1 / compileSdk 37 (only an "AGP tested up to 36.1" warning). It needs a **JDK 21 toolchain**: with `jvmToolchain(17)` the test fails with `UnsupportedClassVersionError`.

**Decision:** the benchmark library module `:ui` uses JDK 21.

---

# v3 spike: multi-module, custom annotations, other differs

Fixture: `fixtures/multimodule/` (`:app`, `:feature:home`, `:core:designsystem`, `:legacy` remapped to `old/legacy`, `:empty`, `:nocompose`).

## Module references
| Reference | `preview list` |
|---|---|
| `:feature:home`, `:core:designsystem`, `:legacy`, `:app` (Gradle path) | works |
| `legacy` (project name) | works |
| `feature/home`, `old/legacy` (directory) | **fails**: "Runfiles command failed" |

**Bug in Medusa v2:** it passed the module *directory* (`sample` worked only because that is also a top-level project name).
Run on this fixture, `:core:designsystem`, `:feature:home` and `:legacy` all failed. **Decision:** pass `project.path`.

## Empty and non-Compose modules
`:empty` (Compose, no `@Preview`) and `:nocompose` both return `success=true` with zero previews. No special error path is needed, only a clean "0 previews" result.

## `--all`
`list --all :app` returns 21 entries from `:app`, `:feature:home` and `:core:designsystem` (its transitive project dependencies) and **not** from `:legacy`, `:empty` or `:nocompose` (not dependencies).
`render --fqn=<preview declared in :feature:home> :app` works without `--all`: the classpath contains the dependency. So an aggregator run is `list --all` followed by one batched `render`.
Rendered paths are `<package>/<FileKt>/<Name>_<index>.png` regardless of the module.

## Custom and nested multipreview annotations (defined in `:core:designsystem`, used in `:feature:home`)
| Annotation | Entries | Render images |
|---|---|---|
| `@DevicePreviews` (3 `@Preview`) | 3 | 3 |
| `@ThemePreviews` (`@PreviewLightDark` + `@PreviewFontScale`) | 9 | 9 (7 distinct pixel sets: font scale 100% equals the plain light render) |
| `@AllPreviews` (`@DevicePreviews` + own `@Preview`) | 4 | 4 |

Two renders are pixel-identical index by index, so index-based golden names are stable. No Medusa change is needed for custom annotations.

## Variants (settles the open question from the first spike)
The CLI's Gradle plugin registers `generate<Variant>ComposePreviewRunfiles` for every variant, but the CLI only requests the **debug** task and passes the manifest path to it
as a task option. Appending `:feature:home:generateReleaseComposePreviewRunfiles` as a build arg does run the release task, which then fails (`composePreviewManifestFile` unset) because the option belongs to the debug task.
**Decision:** variant selection stays unsupported (CLI limitation), now with evidence.

## odiff 4.5.0 (installed with `brew install odiff`)
- Exit codes: 0 match, 22 pixel diff, 21 layout diff (only with `--fail-on-layout`), 1 error (file missing).
- `--parsable-stdout` prints `0`, `count;percent` (percent rounded to 2 decimals: one differing pixel prints `1;0.00`), or `layout`. Without `--fail-on-layout`, a 1px wider image is compared and the extra column is counted as 210 differing pixels.
- `--server`: JSON lines. A request needs `base`, `compare` and **`output`** (mandatory) and the options **nested** under `"options"` (`threshold`, `antialiasing`, `failOnLayoutDiff`, `ignoreRegions: [{x1,y1,x2,y2}]`); options placed at the top level are silently ignored.
  Responses: `{"match":true}`, `{"match":false,"reason":"pixel-diff","diffCount":N,"diffPercentage":P}`, `{"reason":"layout-diff"}`, `{"error":"..."}`.
- Cost per compare: **server 1.1 ms**, one process per compare 5.5 ms, `magick compare` 15.4 ms.

## Real corpus (a sample golden, 339x210, and perturbed copies), differing pixels out of 71,181
| pair | odiff default (t=0.1) | odiff `-t 0` | odiff `-t 0 --aa` | magick `AE -fuzz 2%` (value, fraction) |
|---|---|---|---|---|
| blue +1 / +3 levels, rgb +3 | 0 | 70,260 (98.7%) | 69,223 (97.2%) | 0 |
| rgb ~+5 levels (saturating) | 0 | 70,260 | 69,223 | 901.3 (1.27%) |
| 1px horizontal shift | 1,267 (1.78%) | 1,673 | 40 (0.06%) | 333.7 (0.47%) |
| one black pixel | 1 | 1 | 1 | 0.74 (0.001%) |
| alpha x0.99 | 0 | 70,248 | 69,223 | 0 |
| 1px wider (340x210) | 210 | 210 | 210 | **0 (silent pass)** |
| a different preview (421x210) | 47,095 | 47,294 | 45,818 | 9,069 |

Findings:
- odiff's default threshold hides colour drift completely (98.7% of pixels differ at `-t 0`, none at 0.1): it is a cliff, not a tolerance.
- ImageMagick `AE` is **not an integer pixel count** on RGBA images (alpha weighted: one black pixel gives 0.74); use the fraction in parentheses.
- ImageMagick **silently passes a size mismatch** (`0 (0)`, exit 0) when `-fuzz` is used, so the size must be checked separately. Exit codes are unreliable (PSNR of identical images exits 1).
- A diff image is written by passing a third path to `magick compare` (`-highlight-color`, `-lowlight-color`).

**Correction:** my first corpus used `-evaluate Add 3`, which adds on the 16-bit quantum scale (a no-op in 8 bits). That briefly suggested odiff tolerates +3 levels at `-t 0`; the pixels were unchanged (250,250,250). The corrected figures above were verified at the pixel level (251 / 253 / 253).
