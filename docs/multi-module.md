# Multi-module builds

Starting in **0.2.0**, root Medusa commands and `check` automatically group compatible libraries under their
unique application host. Apply the plugin to the app and its preview-containing modules. One discovery and
render batch handles the group; goldens and reports still belong to their original modules.

The host uses its merged resources and dependency classpath. This can change library pixels compared with
0.1.x. Review verification failures before accepting new goldens. To preserve standalone rendering:

```kotlin
medusa { autoAggregate = false }
```

Or pass `-Pmedusa.autoAggregate=false` for the build. Explicitly qualified module Medusa tasks, such as
`:core:designsystem:medusaVerify`, always run standalone. A qualified module's `check` may verify its whole
group. `verifyOnCheck=false` excludes that module from check-driven groups.

Shared libraries used by multiple apps, incompatible settings, manual `includeDependentModules` aggregators,
independent modules, and unsupported dependency graphs remain standalone. App ownership includes opted-out
apps. Gradle configuration-cache reuse is supported. `--info` explains fallback decisions. Existing root task
name collisions are preserved instead of replacing user tasks.

Automatic grouping requires the CLI's supported `debugRuntimeClasspath` graph in the same Gradle build.
Unresolved or unavailable graphs disable automatic grouping conservatively. Runtime source-attribution
ambiguity falls back before rendering or deleting goldens.

- Medusa gives the CLI the module's **Gradle path** (`:feature:home`). The CLI resolves directories only for top-level projects, which
  is why an early version failed on nested modules.
- Modules without previews or without Compose report `0 preview(s)`; they do not fail.
- Each task starts a CLI process with its own nested Gradle build (about 800 MB each). `medusa.maxConcurrentRenders` (default: chosen from
  the machine's cores, memory and the project's Gradle heap, see [`benchmark/CONCURRENCY.md`](../benchmark/CONCURRENCY.md); 3 on a 10 core
  Mac, 1 on a 2 or 4 CPU runner) caps how many run at once under `--parallel`. Measured on 16 modules: 88 s with 1, 41 s with 3 or 4,
  56 s with 10 and 9 GB. `medusaSetup` prints the value. Renderer daemon reuse is enabled by default; the aggregator mode below reduces additional overhead.
- **Aggregator mode:** `medusa { includeDependentModules = true }` on `:app` makes **one** CLI call (`list --all`) for the module
  and its project dependencies: 6.8 s (3.8 s with the daemon) for 21 previews, against 27 s for verifying the six modules one by one
  (22 previews: `:legacy` is not a dependency of `:app`), and goldens still go beside the sources of whichever module declares the
  preview. This remains an explicit override and is excluded from automatic grouping. Use it on one module and not on the libraries it covers.
- `./gradlew medusaVerify --preview=X` from the root skips modules without a match (and says so); `:feature:home:medusaVerify
  --preview=X` fails if nothing matches. A filter matching nothing in the whole build passes silently, with one "Skipped" line per module.
- Directories named `external` or `third_party`, and `.gradle`, are excluded by the CLI's `--all`.

## The renderer daemon is shared across modules

Verified on 2026-11-04 with Android CLI 1.1.16717390 on macOS arm64: the same persistent renderer
PID handled `:app`, `:feature:home` and `:core:designsystem`, concurrent module requests, and then
`:bench` in a separate project. Every request succeeded (1, 17, 3 and 50 images respectively).
The renderer remained alive after each CLI client exited. With daemon mode disabled, the control
request spawned a separate short-lived renderer while the persistent daemon stayed alive.

Medusa's Gradle plugin defaults `useDaemon` to `true` and passes `--daemon` for each render call;
it does not stop the shared renderer when a module task finishes. The CLI advertises a ten-minute
idle timeout. No extra Medusa daemon manager or keep-alive is needed for consecutive module tasks.
This was verified for one installed CLI version and user environment, not across machines or versions.

Daemon reuse does **not** eliminate each module's CLI invocation, nested Gradle build, or project-specific
render setup. The aggregation measurements already used the shared daemon: their speedup comes from
batching that remaining work. A shared process also does not prove render requests execute concurrently.

Reproduce with `JAVA_HOME` set to JDK 21 and `python3 benchmark/daemon_reuse.py` after generating
the 50-preview benchmark fixture. The probe observes renderer PIDs without reading authentication
credentials. [Raw observations](../benchmark/DAEMON_REUSE_RESULTS.json) include sequential and
concurrent requests plus the no-daemon control; the single-run timings are diagnostic, not benchmark claims.
