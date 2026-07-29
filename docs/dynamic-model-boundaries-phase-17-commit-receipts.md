# Phase 17 — Bounded model-commit receipts

Date: 2026-07-27

## Contract

Event metadata remains opaque to the runtime. The SDK-authored model commit ID and substep ordinal continue to resolve
historical model boundaries, but the complete target-bearing commit receipt is no longer permanent storage.

The durable commit core retains:

- the commit ID as a durable idempotency fence;
- every substep's `stateIndex` and optional global `eventIndex`;
- target sequence/history positions in request order;
- pending direct document/snapshot projection bytes only until successful materialization.

For new commits, the core does not retain raw target IDs. A duplicate request already carries those IDs; the runtime
combines them with the durable positions to reproduce the exact accepted result. Existing full commit rows remain
readable and are sanitized by the established erasure path.

The full receipt used by cache tracking is written atomically to an append-only table partitioned by the time-derived
last `stateIndex`. Complete expired partitions are dropped rather than row-deleted. A tracker older than the durable
retention floor receives the existing empty-target `HARD_DELETE` cache-reset update and resumes at the current
namespace boundary. That encoding deliberately works with SDKs from before this phase: they already clear the
namespace cache for a privacy-safe hard-delete fence.

There is no new event field and the runtime never adds to or changes event metadata. Event-handler exactness still uses
the SDK-authored commit ID plus substep ordinal and resolves their `stateIndex` from the durable commit core.

## Defaults

The default transient receipt retention is one hour. It can be overridden with the positive ISO-8601 duration system
property `fluxzero.modelCommitReceiptRetention`. Because expiry occurs at complete hourly partition boundaries,
physical retention is between one and two hours with the default. The runtime performs retention work only at namespace
activation and partition rollover; an idle namespace is not polled.

The durable floor never advances past the namespace's committed state head. This matters after a long idle shutdown:
old partitions may be removed at restart, but a tracker already at the last committed commit must not receive a reset
whose update position is newer than the namespace itself.

## Storage and hot-path shape

- The durable commit core remains hash-partitioned by the stable Fluxzero commit segment. Existing point lookup,
  idempotency and historical-boundary locality are unchanged.
- Temporary receipts use one current time partition and one ordered primary index. Tracking therefore scans a
  contiguous `stateIndex` range instead of merging the former 32 commit partitions.
- Targets with a stored model event reuse their durable stream membership for erasure lookup. The uncommon
  publication-suppressed target gets one sparse reverse-lookup row in a matching temporary time partition; it is
  dropped with the receipt rather than accumulating in the legacy permanent commit-target index.
- Creating and purging partitions is outside the commit transaction. The commit transaction then writes core,
  receipt, streams, heads, relationships and publication as one package.
- Retention is never checked per commit. It runs once at namespace activation and only when an commit crosses into a
  receipt partition that this runtime has not created yet.
- Hard delete sanitizes a still-retained receipt in the deletion transaction. New durable cores contain no raw model
  IDs; old target-bearing cores remain readable and use the existing sanitization route.

## Verification

The complete `JdbcModelCommitStoreTest` passed 80 tests after the storage split. Focused contracts additionally prove:

- core and receipt commit or roll back together;
- the new core does not contain its raw target ID while the temporary receipt does;
- expiry drops the old receipt partition and returns a backwards-compatible cache reset;
- the sparse reverse lookup for a target without a model-stream membership expires with that same partition;
- a runtime restart and duplicate retry after expiry reproduce the original state/event/sequence boundary without
  appending another event or stream membership;
- hard delete still returns erased target identities for an old commit retry and sanitizes any live receipt;
- application-owned event metadata is byte-for-byte semantically unchanged by model commit;
- a long-idle restart never advances the retention floor beyond the committed namespace head;
- current-state receipt reads prune to one hourly partition.

Paired local PostgreSQL 18 / Apple-silicon diagnostics compared runtime commit `426793ec` with this phase. These are
comparative development-machine results, not a production hardware capacity claim.

| profile | before | Phase 17 | observation |
| --- | ---: | ---: | --- |
| 20,000 published one-target commits, run 1 | 4,397 commits/s | 4,975 commits/s | run variance, no observed regression |
| same profile, run 2 | 4,917 commits/s | 4,852 commits/s | -1.3% |
| physical storage | 35.63 MiB | 37.72 MiB | +5.9% while receipts are retained |
| WAL | 45.51 MiB | 47.41 MiB | +4.2% |
| update tracking | 64.5k updates/s | 273–294k updates/s | 4.2–4.6x faster |
| 5,000 published ten-target commits | 1,674 commits/s | 1,655 commits/s | -1.1% |
| ten-target physical / WAL | 25.80 / 48.15 MiB | 27.07 / 48.94 MiB | +4.9% / +1.6% |
| ten-target update tracking | 91.6k updates/s | 177.9k updates/s | 1.94x faster |

One instrumented 20,000-commit run attributed 0.44 MiB to durable compact results and 0.81 MiB to temporary receipts.
The latter disappears by complete partition drop after its bounded retention window. No row delete, vacuum churn,
event-payload copy or foreground retention query was introduced for the common stored-event path.

The adversarial review also exposed an SDK-side race that predated this storage split: a tracker could observe its own
in-flight commit just before the accepted command result seeded the cache and start an unnecessary suffix load. Local
commits now install a target-scoped fence before commit. The accepted result clears the fence after updating the cache;
failure releases it and immediately makes any deferred remote update refreshable. This adds no database polling and
does not delay unrelated or remote model updates.

The final complete SDK reactor passed all nine modules, including 1,943 SDK tests, common, test-server, proxy,
annotation processing and Java/Kotlin downstream compatibility. The final complete runtime reactor passed all four
modules and 645 runtime tests. `git diff --check` passed in both repositories.

The implementation review found no remaining correctness blocker. Absolute 100 GB/min qualification, a
production-duration retention/partition-rollover soak and workload-specific sizing remain deployment gates rather than
claims made from a development laptop.
