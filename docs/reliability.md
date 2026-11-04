# Flakiness, recovery and diagnostics

Medusa distinguishes infrastructure failures from screenshot differences. Ordinary verification renders
once and keeps its existing comparison tolerances. It does not retry visual failures by default.

## Deadlines

All values are positive seconds and are available as Gradle extension properties and `-Pmedusa.<name>`:

| Setting | Default | Covers |
|---|---:|---|
| `discoveryTimeoutSeconds` | 600 | CLI discovery, including its nested build |
| `renderTimeoutSeconds` | 1800 | Each CLI rendering invocation, including its nested build |
| `comparisonTimeoutSeconds` | 60 | External comparer reads, startup and pool waiting |

Cancellation or a deadline terminates the invocation-owned process and descendants. The shared Android
preview renderer is preserved when daemon mode is enabled. CLI stderr is drained concurrently; bounded,
redacted diagnostics are included in failures. The CLI's supported daemon class-not-found recovery is
still attempted once with a fresh renderer. It is separate from visual retries and appears in run metadata.

## Optional visual retries

```kotlin
medusa {
  visualRetryCount = 1       // 0..3; default 0
  allowRecoveredPass = true // default false; requires visualRetryCount > 0
}
```

These also accept `-Pmedusa.visualRetryCount=1 -Pmedusa.allowRecoveredPass=true`.
Only screenshots with a rendered image and a failed visual/accessibility comparison are candidates.
Missing goldens and infrastructure/comparer errors are not visually retried. Medusa rerenders the failed
functions with a fresh renderer, reconciles all variants, and updates only the originally failing verdicts.
A screenshot stops retrying after its first passing attempt. Other failures continue to fail the task.

With `allowRecoveredPass=false`, diagnostic retries retain the original failing verdict, even after a
passing attempt. With it enabled, a recovered screenshot passes and is visibly marked **FLAKY** in the
console, HTML and JSON, with an explanation in JUnit `system-out`. This deliberately allows CI to pass
an unstable screenshot. Original failure images, deltas and attempt outcomes remain available.
Recording never uses visual retries or updates a baseline from a verification attempt.

## Repeated-render diagnosis

```sh
./gradlew medusaDiagnose --preview='*Button*'
./gradlew :feature:home:medusaDiagnose --preview=PrimaryPreview
```

Diagnosis runs five repetitions with renderer daemon reuse and five with fresh renderers. Alternate
repetitions reverse preview batching order. Root tasks use the same automatic grouping rules as other
Medusa tasks; qualified module tasks preserve standalone rendering context. Goldens are never modified.

Results are classified as `stable-match`, `stable-difference`, `unstable`, or `infrastructure-failure`.
Instability is evaluated using the configured comparer/tolerance and normalized accessibility snapshots,
including changes in the observed set of variants. Diagnosis fails for differences, instability, missing
goldens or infrastructure errors. It records attempt images, timings, requested renderer mode and verdicts
in `build/medusa/diagnostics.json`; images are under `build/medusa/diagnostics/<run-id>/`.

Diagnosis cannot freeze application clocks, randomness, network data or Compose animations. Provide fixed
preview data and explicit locale/device/API/font settings. Pin the Android CLI and comparer in CI when
reproducibility matters; run metadata records tool identities and the effective comparer, including fallback.

## Golden safety and current-run reports

Record and clean-record stage and decode outputs before replacing any golden. A failed batch commits
nothing. Successful commits journal backups and deletions, then replace files atomically within their
destination directories. Clean-record forces fresh golden contents without deleting the originals first.
Overlapping source/output directories are locked across threads and processes in a deterministic order.
Interrupted commits are recovered before the next Medusa operation touching those directories.

Transaction journals are temporarily discoverable through `.medusa-journal` in destination directories.
Do not delete an interrupted journal or its backup directory before recovery. Exclude `.medusa-journal`,
`.medusa-transaction-*/` and `.medusa-*.tmp` from version control. A failed rollback retains
its journal for recovery; permission or disk errors must be corrected before retrying.

Each invocation gets isolated rendered/comparison workspaces. Failures and retry evidence live under
`build/medusa/failures/<run-id>/<attempt>/`; HTML links to them. Old invocation artifacts are retained
until the build directory is cleaned, so another invocation cannot remove their evidence. Existing fixed HTML and JUnit report paths
remain available. `build/medusa/results.json` adds run ID, status, attempts, recovery information and tool
configuration. Infrastructure failures replace old passing reports and retain completed results where
available. A running-status report explicitly invalidates previous results before execution begins.

The final CLI manifest is authoritative: streamed images can be compared early, but omitted/conflicting
final results cannot establish success. Known multipreview variants and index gaps are checked. A missing
trailing parameter-provider value cannot be detected if the CLI supplies no authoritative cardinality;
repeated diagnosis can identify cardinality changes across repetitions.

If odiff is unavailable, YIQ fallback remains available unless ignore regions were requested. In that case
Medusa fails with an installation hint because silently dropping masks would change the comparison policy.
