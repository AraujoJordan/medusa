# Changelog

## 0.3.0 — reliability

- Bound CLI and comparer execution, cancellation and pool acquisition with configurable deadlines.
- Validate final CLI manifests and known variant coverage; recover odiff transport failures without leaking pool capacity.
- Stage golden updates and journal replacements/deletions for rollback and recovery, with cross-process locking.
- Isolate attempt artifacts, replace stale reports on infrastructure failures, and record redacted diagnostics and effective tool identities.
- Add opt-in visual retries and explicitly allowed recovered passes, preserving original failure evidence.
- Add `medusaDiagnose`: five warm and five fresh-renderer repetitions, without changing goldens.
- Reject YIQ fallback when configured odiff ignore regions cannot be preserved.

Failure artifact paths now include run and attempt IDs; use recursive collection globs. Strict verification, daemon reuse and automatic aggregation defaults remain unchanged.

## 0.2.0 — 2026-11-04

- Enable the shared Android CLI renderer daemon by default, retaining `useDaemon=false`.
- Automatically aggregate compatible uniquely-owned modules for root Medusa commands and `check`.
  Shared, incompatible, opted-out, manual-aggregation and unsupported modules stay standalone.
- Keep explicitly qualified module tasks standalone. Preserve per-module golden paths, failure artifacts,
  HTML reports and JUnit XML while rendering a group in one batch.
- Add `autoAggregate=false` and `-Pmedusa.autoAggregate=false` to preserve standalone behavior.
- Reuse source reads, discover once during clean-record, narrow render polling, and defer comparer startup.
- Retry daemon class-not-found validation errors once in a fresh renderer; preserve other failures.
- Add stage timings separating rendering, queued work and image processing, plus reproducible probes.

### Migration

App-host rendering uses app resources and dependency resolution. A library's output may change even when
its preview annotation is unchanged. Review failures before re-recording, or opt out of automatic grouping.
Daemon reuse does not remove module build overhead; aggregation reduces repeated CLI invocations.

### Performance

Previously measured 50-preview medians: record 5.1 s versus Paparazzi 7.7 s, verify 4.8 s versus 6.9 s.
These synthetic fixtures use different renderer settings; they do not establish universal or fidelity-equivalent
speedups. See `docs/benchmarks.md` for raw measurements, methodology and release validation.

The 22-preview multi-module release fixture reduced record from 8.34 s to 6.67 s (20%) and verify
from 9.43 s to 6.83 s (28%) with default automatic aggregation versus standalone mode.
Both used parallel execution, warm caches and the daemon; medians of three rounds.
