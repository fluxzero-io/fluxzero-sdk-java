# Phase 14 — Production hardening and universal model injection

Date: 2026-07-27

## Decision summary

Phase 14 is the merge-readiness pass after the model-first rollout. It closes review findings that could otherwise
produce a wrong historical model, a stale direct document, an incomplete hard deletion, or disproportionate work on a
100-GB/min runtime.

The retained design is:

- exactly one operational model store owns hard-deletion recovery; query-only stores cannot complete erasures;
- all selected message-handler kinds can inject direct models and arbitrary ancestors;
- event/notification parameters use their exact model-action boundary, while other messages use one current handler
  load context;
- ordinary direct search keeps its existing wire/document shape; graph composition alone obtains decoded summaries;
- graph composition bounds source bytes, path expansion, placements and output before unbounded materialization;
- store-only model actions bypass the global event log completely; published actions reserve indices without holding a
  JVM-wide monitor while JDBC work completes;
- a split search store repairs from the exact serialized materialization committed with the action, never by
  re-evaluating application code;
- direct-document fences reject both older and equal `stateIndex` writes, including writes racing a delete tombstone;
- an event-sourced model cannot create, update or logically delete without storing its reconstructing event;
- publication-only no-op events and document-loaded state transitions remain valid.

## Universal handler parameters

`ModelEntityParameterResolver` now applies to command, query, event, notification, schedule, result, error, metrics,
document, custom-message and web handlers.

Resolution remains side-effect-free during candidate selection. Once a handler is selected, one compiled plan:

1. resolves canonical, uniquely typed or `@Association`-qualified IDs from payload and metadata;
2. adds typed payload IDs as read-only graph anchors when the handler requests only an ancestor;
3. batches direct loads at one boundary;
4. follows temporal relations for parents, grandparents and further ancestors;
5. reuses one message-scoped context for repeated `T` and `Entity<T>` parameters.

Events and notifications without model-action metadata do not receive an arbitrary current model. This prevents a
future cache entry from leaking into historical replay.

## Split-store materialization

`CommitModelAction` already carries the original events, optional direct documents, optional snapshots and relation
changes as one logical package. When one runtime owns separate model and search databases, the core JDBC transaction
retains the exact serialized document/snapshot projection and that same runtime applies it to the search database
before returning success. On store activation after restart, a bounded background worker resumes pending projections
with exponential backoff. An SDK-owned custom document store instead applies the retained projection and acknowledges
the action boundary through the existing protocol.

If either runtime-owned database operation or the SDK-owned route fails in between:

- a duplicate commit returns the original durable action result;
- the runtime-owned worker, or the SDK for a custom store, retrieves the original projection by `actionId`;
- the target store applies it through monotone per-model fences;
- the materialization boundary closes only after all writes succeed;
- later duplicate or operator-triggered delivery is idempotent.

The action's compact result is not a bulky retry artifact. It contains the permanent action/substep `stateIndex`,
published event index and target stream positions required by exact handler loads, cache-update tracking and durable
idempotency. Duplicating those values into normalized per-substep and per-target tables would add rows and indexes to
every action and materially increase WAL at the production reference envelope. The compact result therefore remains
the permanent representation. Only the potentially large document/snapshot projection is retry-window state; it is
cleared immediately after a successful acknowledgement and is never discarded merely because a timer expired.

## Event-history invariant

For event-sourced models, every state-changing transition must have a model-stream membership. Fluxzero rejects
`PUBLISH_ONLY` and `eventPublication = NEVER` before committing a create, update or logical delete. The failure cannot
leave a head that breaks on its next reconstruction.

The distinction remains intentional:

- a `PUBLISH_ONLY` no-op can publish a domain fact without changing model state;
- a document-loaded model may change without storing that event because its direct document is the load source;
- normal `STORE_ONLY` and `STORE_AND_PUBLISH` transitions provide complete event-sourced history.

## Operational recovery

Runtime-owned search materialization is repaired automatically after temporary failure and store activation following a
restart. For a pending SDK-owned custom-store action:

1. redeliver the original command/action with the same durable `actionId`, or retrieve
   `GetModelActionMaterialization(actionId)` through the low-level event-store client;
2. apply the returned `MaterializeModelAction` to the intended document store;
3. acknowledge `CompleteModelActionMaterialization(actionId, lastStateIndex)`;
4. repeat safely if the result is uncertain.

Never rebuild a committed projection by rerunning assertions, interceptors or applies. A missing action ID is an
operator/configuration error; a retained action with no recoverable projection must fail explicitly rather than
silently close its readiness fence.

## Retention state

There is no implicit time-based model retention policy in this release:

- the compact action result is retained indefinitely because exact handler boundaries, update tracking and durable
  idempotency still depend on it;
- a pending document/snapshot projection is retained until successful materialization, then cleared immediately;
- processed deletion-target worksets are deleted when their deletion completes;
- completed deletion records and erasure fences remain durable for retry identity and model-ID reuse rejection;
- protected lineage remains until the corresponding detached descendants are erased.

Purging or archiving compact actions, completed deletion metadata or fences requires a separately proven replacement
for those correctness contracts; ordinary message-log retention does not apply to these model tables.

## Verification

- SDK/common focused:
  `./mvnw -pl sdk -Dtest='ModelActionCommitterTest,ModelEntityParameterResolverTest,InMemorySearchStoreModelMaterializationTest,ModelCacheTrackerTest' test`
  and
  `./mvnw -pl common -Dtest='ModelGraphDocumentStitcherTest,WebSocketTransportCodecsTest' test`
  — 74 tests passed.
- SDK `./mvnw -B install` — complete reactor passed, including 1,939 SDK tests, 310 common tests, test-server, proxy,
  annotation processing and Java/Kotlin downstream projects.
- SDK `./mvnw -B site -Pjavadoc` — complete site/Javadoc reactor passed. The pre-existing unresolved-link diagnostics
  and downstream Maven Doxia compatibility warning remain warnings.
- The first complete SDK rerun exposed an ordering race in the local-commit cache fast path: a newer unrelated global
  tracker cursor could trigger an unnecessary suffix load after an authoritative local commit. The tracker now trusts
  an existing action-participant entry while retaining the conservative global fence for a newly created entry; the
  deterministic regression test and the repeated complete reactor pass.

Runtime verification:

- focused JDBC/websocket hardening suite:
  `./mvnw -pl runtime -Dtest='JdbcSearchStoreTest,JdbcMessageStoreTest,JdbcModelActionStoreTest,SearchEndpointTest' test`
  — 162 tests passed;
- complete runtime reactor: `./mvnw -B install` — all four modules and 641 runtime tests passed;
- the adversarial review found and corrected one compatibility regression before the final run: the generic
  `SummaryColumn#get` contract remains decoded as before, while ordinary document building still omits summaries and
  graph-only loading opts into the summary read explicitly;
- `git diff --check` passed in both repositories.

After the full reactor, the final repair-read simplification was compiled and exercised by all 76
`JdbcModelActionStoreTest` contracts.

The hard-deletion suite starts a query-only/search store before the operational store and proves that only the latter
can resume a prepared deletion. The shared-payload lifecycle test commits one event for A and B, erases A, restarts and
reconstructs B, then erases B and observes payload reclamation only after the final membership disappears. The global
audit event remains independent.

## Retained benchmark evidence

These local PostgreSQL numbers are comparisons, not a production capacity claim.

> **Correction (2026-07-27):** the paired table below used a deterministic `ZIPF` workload in which every concurrency
> batch contained repeated writes to the same model. It is a contention diagnostic, not the conflict-free mutation
> baseline. The claim that model mutations were intrinsically about 2.5 times slower is superseded by the
> [Phase 15 contention report](dynamic-model-boundaries-phase-15-contention-performance.md).

### Equivalent aggregate/model tree

The paired shape has four roots; three primary children per root; four leaves and two detail children per primary; and
three secondary plus three tertiary children per root. Both implementations mutate and load the same logical data.

| profile | aggregate | independent models | model/aggregate observation |
| --- | ---: | ---: | --- |
| event-only mutations | 1,436.1 actions/s | 583.6 actions/s | 0.406× |
| cold leaf load | 17.035 ms | 10.069 ms | model 41% lower latency |
| hot leaf load | 0.064 ms | 0.013 ms | model cache is faster |
| whole-root load | 15.080 ms | 22.807 ms | model stitching is 1.51× slower |
| WAL / allocation | 4.46 MB / 236.6 MB | 3.59 MB / 183.7 MB | model uses 0.81× WAL and 0.78× allocation |
| searchable mutations | 1,173.1 actions/s | 459.3 actions/s | 0.392× |
| searchable cold leaf | 16.346 ms | 9.310 ms | model 43% lower latency |
| searchable whole root | 16.038 ms | 26.470 ms | model stitching is 1.65× slower |
| direct-search p50 | 0.756 ms | 0.543 ms | model direct search is faster |
| composed/current root-search p50 | 0.491 ms | 4.115 ms | relational graph composition costs more |
| materialized root-search p50 | n/a | 0.513 ms | async CQRS restores the fast root path |
| searchable WAL / allocation | 5.13 MB / 260.5 MB | 6.61 MB / 458.0 MB | explicit sizing gate |

This original table exposed an avoidable all-or-nothing runtime batch fallback and repeated SDK rebase round trips for
same-model writes. It must not be generalized to independent model mutations. Phase 15 removes those multipliers and
reports conflict-free and skewed workloads separately. Searchable tree materialization remains an opt-in workload
decision, not a free default.

### Mixed global event log

With 2,000 measured single-target 1-KiB model actions at concurrency 128 and 2,000 ordinary aggregate appends at
concurrency 64, the ordinary append path sustained 4,082 events/s (p50/p95/p99
15.359/36.111/37.318 ms) while published model actions sustained 3,636 actions/s
(37.330/53.780/77.785 ms). The combined log contained exactly 4,500 warmup-plus-measured events in strictly increasing,
unique index order. Total physical data was 3.91 MiB and measured WAL 11.10 MiB.

Index reservation now holds the JVM monitor only long enough to assign indices and enqueue a JDBC job. A rejected
conditional reservation may leave an index gap, which is valid because indices are monotone time positions rather
than dense sequence numbers. Its optimistic staging estimate can also cause at most a conservative early flush before
the next batch boundary. The estimate is bounded by the configured row/byte threshold and self-corrects on that flush;
adding a reservation ledger to every aggregate append was rejected as the worse trade-off on the 100-GB/min path.

### Target-count matrix

| publication | targets | actions/s | memberships/s | p50 / p95 | current loads | physical / WAL amplification |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| store only | 1 | 4,165 | 4,165 | 12.636 / 23.895 ms | 26,695 models/s | 3.65× / 6.53× |
| store only | 2 | 3,638 | 7,275 | 14.130 / 32.377 ms | 34,962 models/s | 1.86× / 6.18× |
| store only | 10 | 911 | 9,111 | 82.0 / 95.9 ms | 37,340 models/s | 6.80× / 16.17× |
| store only | 100 | 63 | 6,304 | 790 ms / — | 37,006 models/s | 59.04× / 101.51× |
| published | 1 | 1,631 | 1,631 | 18.643 / 177.173 ms | 27,407 models/s | 3.65× / 9.57× |
| published | 2 | 1,539 | 3,078 | 23.383 / 166.884 ms | 34,826 models/s | 1.89× / 7.82× |
| published | 10 | 538 | 5,381 | 98.432 / 236.641 ms | 35,484 models/s | 8.28× / 17.83× |
| published | 100 | 63 | 6,264 | 795 ms / — | 37,076 models/s | 61.44× / 103.65× |

The 100-target action is deliberately pathological and remains an operational warning. The short local published
profile also shows noisy p95 spikes; production-duration qualification must use the retained benchmark with the actual
storage and network topology.

## Adversarial review

- Public and wire compatibility: all protocol additions have JSON and CBOR round-trip tests; legacy aggregate and
  ordinary search requests keep their existing path and shape.
- Persistence: no normalized per-target action-result table was added. The compact MessagePack result remains
  permanent; only unfinished document/snapshot bytes remain in the existing action projection column.
- Concurrency: event indices remain unique and ordered under mixed traffic; equal/older direct-document writes cannot
  cross a newer write or delete tombstone; cache and snapshot updates cannot move backwards.
- Recovery: a crash after runtime commit cannot invoke application code again. Runtime-owned materialization resumes
  automatically; duplicate or SDK-owned delivery retrieves the exact retained bytes, applies them idempotently, and
  only then closes the materialization fence.
- Deletion/GDPR: query stores cannot own erasure; direct/graph documents finish before completion; shared event
  payloads survive while any membership remains; detached lineage stays discoverable through protected tokens.
- Resources and shutdown: graph source bytes, placements, path expansion and output share a fail-fast budget; JDBC
  search workers retain fixed concurrency but release idle core threads and preserve explicit shutdown.
- History: an event-sourced state change without a stored stream event is rejected before commit; publication-only
  no-ops and document-loaded transitions remain supported.

## Rollout, observability and rollback

Existing signals cover the durable/materialized cursor gap in `TrackModelUpdatesResult.Metric`, successful exact-package
repair in `ModelMaterializationRepairMetric`, graph-projection batches/status, generic request latency/failures, cache
evictions and bounded-backlog rejection logs. Runtime-owned recovery and deletion failures also log while their durable
pending boundaries remain visible. Deployment dashboards and alert thresholds are operational configuration, not
automatically installed by the runtime. A repair metric intentionally contains counts/bytes and completion state but no
action or model ID, so it remains safe as a bounded metric dimension; the action ID remains available in request/audit
logs for diagnosis.

Before a runtime or SDK downgrade, drain and acknowledge all pending split-store materializations and graph deletion
work. Existing aggregate streams are untouched and remain the immediate application-level rollback path. New
independent-model streams are not implicitly converted back to aggregates; disabling model command registration is a
traffic rollback, not a persisted-data migration.

## Remaining deployment gate

Local and container benchmarks characterize regressions and physical amplification, but do not certify the
100-GB/min production envelope. Before enabling independent models on such an installation, run the retained mixed
aggregate/model, published/store-only, 1/2/10/100-target, direct-search, graph-bound and recovery profiles on
production-equivalent PostgreSQL storage and network topology. Keep the aggregate path available as the rollout
rollback for existing streams; no persisted aggregate migration is implicit.
