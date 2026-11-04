# Multi-module builds

Apply `io.github.araujojordan.medusa` in every module that has previews (a convention plugin works). From the root,
`./gradlew medusaVerify` runs in all of them and each golden sits next to its own source, also in modules whose directory differs from
their project path. Checked on [`fixtures/multimodule`](../fixtures/multimodule) (`:app`, `:feature:home`, `:core:designsystem`, a remapped
`:legacy`, a Compose module without previews, a module without Compose).

- Medusa gives the CLI the module's **Gradle path** (`:feature:home`). The CLI resolves directories only for top-level projects, which
  is why an early version failed on nested modules.
- Modules without previews or without Compose report `0 preview(s)`; they do not fail.
- Each task starts a CLI process with its own nested Gradle build (about 800 MB each). `medusa.maxConcurrentRenders` (default: chosen from
  the machine's cores, memory and the project's Gradle heap, see [`benchmark/CONCURRENCY.md`](../benchmark/CONCURRENCY.md); 3 on a 10 core
  Mac, 1 on a 2 or 4 CPU runner) caps how many run at once under `--parallel`. Measured on 16 modules: 88 s with 1, 41 s with 3 or 4,
  56 s with 10 and 9 GB. `medusaSetup` prints the value. `useDaemon = true` and the aggregator mode below help more.
- **Aggregator mode:** `medusa { includeDependentModules = true }` on `:app` makes **one** CLI call (`list --all`) for the module
  and its project dependencies: 6.8 s (3.8 s with the daemon) for 21 previews, against 27 s for verifying the six modules one by one
  (22 previews: `:legacy` is not a dependency of `:app`), and goldens still go beside the sources of whichever module declares the
  preview. Use it on one module and not on the libraries it covers.
- `./gradlew medusaVerify --preview=X` from the root skips modules without a match (and says so); `:feature:home:medusaVerify
  --preview=X` fails if nothing matches. A filter matching nothing in the whole build passes silently, with one "Skipped" line per module.
- Directories named `external` or `third_party`, and `.gradle`, are excluded by the CLI's `--all`.
