# Phase 5 model reconstruction, graph bundles, snapshots, and cache

This report records the retained SDK/runtime implementation for independently reconstructing `@Model` state. It
complements the Phase 0 storage ADR and the Phase 3 model-action store report. Measurements are local diagnostics, not
the Phase 9 production-hardware certification against Fluxzero's 100 GB/min reference envelope.

## Retained reconstruction contract

- A model is reconstructed from only its exact-ID stream. Model type metadata lives once in the runtime type catalog
  and is referenced by the current head; it is not concatenated into the persisted ID.
- All IDs in one load are sent in bounded batches. Membership count, per-stream page size, stream count, and total
  deduplicated payload bytes are bounded. A single oversized oldest payload is allowed through to guarantee progress.
- Every load pins one namespace `stateIndex`. Later pages and chunks use that exact boundary.
- A self-only apply performs no dependency load.
- A cross-model apply reconstructs dependencies at the membership's stored `readStateIndex`.
- A later substep in one action additionally overlays only earlier substeps with the same `actionId`. Unrelated state
  committed between the read boundary and this substep is never admitted.
- Historical dependency bases, action-prefix views, deserialized shared payloads, handler selection, and periodic
  in-session checkpoints are bounded and reused inside one reconstruction session.
- Normal serializer upcasting, including split upcasts, runs before replay. Only `@Apply` participates in model
  reconstruction; assertions and interceptors are not rerun.
- Unknown events fail unless the model explicitly enables `ignoreUnknownEvents`.
- `@Apply` returning `null` is a stored logical-delete revision. A later create with the same exact ID is a distinct
  stored revision and reconstructs normally.

No target-state checkpoint is stored for cross-model applies. The stored event, `readStateIndex`, `actionId`, substep,
and independent dependency histories are sufficient.

## Event-handler boundary

Published model events already carry durable `actionId` and substep metadata. On the first model load in an event or
notification handler, `GetModelEvents` or `GetModelGraph` resolves that pair through the existing hash-partitioned
`model_action` row inside the same runtime request. The returned `stateIndex` is retained in the message context and
all later model loads use it directly.

An experimental event-index-to-state table and separate websocket request were measured and discarded. They duplicated
one row per published event and made the first handler load pay another network round trip. The retained design adds no
mapping table and no extra request.

This makes action-result retention a correctness invariant: a `model_action` result may not be purged while a
corresponding published/model event can still be replayed into handlers that require exact historical model state.
Retention and archival certification remains a Phase 9 operational gate.

When the global event log and model store share the JDBC transaction, publication cannot outrun the action row. The
explicitly retained non-JDBC publish-first fallback has the existing cross-store race: a consumer may briefly observe
the event before its action result. Boundary lookup then fails rather than silently loading current or otherwise wrong
model state. Such deployments must treat that failure as retryable; removing the window belongs with the deferred
cross-store request/result-log or outbox design, not with an implicit in-memory approximation.

## Document-loaded model history

`@Model(eventSourced = false)` still means only that its normal current load comes from its synchronously maintained
direct document. Its events are stored normally unless publication policy says otherwise.

When reconstruction needs that model historically, the SDK uses its model stream. No document revisions or second
state store are introduced. Before an event-sourced write may depend on a current document-loaded model, one head-only
batched request verifies that the dependency's stored history is complete. A `PUBLISH_ONLY` or `NEVER` transition
therefore rejects the dependent action before commit. Current direct document reads that create no event-sourced
dependency remain valid.

The direct document update is awaited before command success. A bounded in-process repair registry retains the original
pre-serialized documents and evaluated target state when the authoritative action succeeded but direct search or
snapshot completion failed. Retrying the same `actionId` in that process cannot accidentally index a reevaluation
against newer model state. Recovery after process loss remains the existing explicit cross-store limitation and is a
future reconciliation concern; no distributed transaction is claimed.

## Snapshots and cache

- Independent snapshots store model ID, local sequence, `stateIndex`, timestamp, and serialized value in
  `$modelSnapshots`.
- A snapshot is used only when it is visible at the requested historical boundary. Stream loading starts strictly
  after its local sequence.
- Snapshot creation and configured retention are awaited after an accepted local action.
- Accepted local actions seed exact committed revisions directly into the cache. Multi-substep actions retain every
  revision in order.
- `@Model.cachingDepth` defaults to `0`: only the latest revision is retained. Positive values keep a bounded previous
  chain; a negative value is an explicit request for an unbounded chain.
- The shared Fluxzero cache remains count- and memory-pressure-bounded (one million entries by default), so billions of
  durable model IDs do not imply billions of heap entries.
- Warm loads still perform a hash-pruned head/suffix read. This catches both published and non-published external
  changes without adding target-ID metadata to every global event or running another full global-log tracker.
- The first unresolved event-handler load bypasses current cache and snapshots, then pins the action boundary. This
  prevents future current state from leaking into handler replay.
- Temporal graph reconstruction never consults the existing timeless relationship cache.

Inspection of `CachingAggregateRepository` informed the event-boundary wait and revision-chain behavior, but its
always-on global-event tracker was not copied. Independent model actions can be non-published and one original event can
target several streams. A head/suffix probe is the one uniform correctness path; avoiding it later requires a compact
model-state invalidation feed, not extra metadata and deserialization on the production global event path.

## Graph bundle

`GetModelGraph` resolves temporal child edges breadth-first at one boundary, batch-queries each frontier through the
parent-partitioned adjacency store, and returns:

- streams grouped by exact model ID;
- deduplicated event payloads;
- temporal edges with explicit path and validity interval;
- one shared `stateIndex`.

The SDK reconstructs each model independently and exposes a `ModelGraph` view with children grouped only by explicitly
configured paths. An edge without `@ParentId.path` remains valid relationship truth but is excluded from automatic
composition. No flattened aggregate stream or second audit log is created. Global event correlation and VictoriaLogs
remain unchanged.

## Local performance diagnostics

Environment: macOS/arm64, Java 25, PostgreSQL 18 in Docker Desktop. PostgreSQL data was dropped after each run.

### Integrated JDBC model path

| Profile | Store throughput | p99 | Physical/logical | WAL/logical | Current load | Historical load |
|---|---:|---:|---:|---:|---:|---:|
| 20,000 × one published 1-KiB target | 9,193 actions/s | 35.8 ms | 1.89× | 2.19× | 23,484 models/s | 24,600 models/s |
| 5,000 × ten targets + ten relations, shared 1-KiB event | 1,892 actions/s / 18,925 memberships/s | 164.1 ms | 9.96× | 14.65× | 19,482 models/s | 19,711 models/s |

Loads used 128-model batches. The ten-target physical/WAL ratios use the one-copy 1-KiB event as the logical
denominator while also storing 50,000 heads, memberships, and dual relationship rows; they must not be read as payload
duplication.

The single-target retained layout improves on the discarded event-state mapping run:

| Design | Store throughput | Physical/logical | WAL/logical |
|---|---:|---:|---:|
| Separate event→state mapping row | 8,517 actions/s | 2.05× | 2.36× |
| Existing action-result boundary | 9,193 actions/s | 1.89× | 2.19× |

Individual existing action-result boundary lookups measured 6,639/s, with 0.149 ms p50 and 0.217 ms p99. Actual handler
loads resolve it inline with the first stream/graph request, so this is database work, not an extra websocket round
trip.

### SDK replay and cache

- Ten repeated cold reconstructions of a 10,000-event model: 45.3 ms/load and 220,714 applied events/s.
- Cached 10,000-event model: 34–78 ms full replay after invalidation, 0.436 ms one-event suffix catch-up, and 101.7 µs
  per warm in-memory head-check load across 10,000 loads.

The spread in full replay is JIT/local-run variance. The diagnostic is retained so Phase 9 can repeat the same modes
against remote runtime, cold OS/database caches, snapshots, concurrent reconstruction, and production hardware.

## Verification

Retained implementation commits: SDK/protocol `0249394ba5c`, runtime `e0ea56e1`.

Focused coverage includes:

- normal local and tracked command integration, direct document completion, multi-target prefetch, payload and
  receiver-side applies reached through interceptors, and conflict reload;
- paginated and byte-bounded replay, current and historical heads, malformed pages, and incomplete history;
- self-only replay, stale accepted actions, exact cross-model boundaries, same-action substep overlays, and batched
  dependency loads;
- normally document-loaded historical dependencies and pre-commit rejection after a non-stored gap;
- logical delete/recreate, unknown-event policy, serializer upcasting, snapshots, bounded previous chains, external
  suffix catch-up, and repair after a direct-document failure;
- current and moved root/child/grandchild graph reconstruction using explicit child-owned paths;
- event/notification handler loads pinned through existing action-result metadata;
- runtime in-memory/JDBC action-boundary, graph, type-catalog, partition, rollback, and relationship tests.

Final gates on the retained diff:

- SDK `./mvnw -B install`: complete reactor passed, including common, SDK, test server, proxy, annotation processor,
  Java downstream, and Kotlin downstream modules;
- SDK `./mvnw -B site -Pjavadoc`: complete site/Javadoc reactor passed;
- runtime `./mvnw -B install -Dfluxzero-sdk.version=0-SNAPSHOT`: complete reactor passed with 528 runtime tests and
  benchmark test compilation;
- both repositories passed `git diff --check` after the regression-only review.

## Deliberately later

- Explicit hard delete does not exist yet. Phase 5 covers logical delete/recreate; intentional history erasure,
  tombstone reporting, and the resulting historical reconstruction contract remain Phase 8 work and are not simulated
  as if the API already existed.
- Current relational graph search and runtime document stitching are Phase 7.
- A materialized asynchronous root graph document is Phase 7.3.
- Multi-store/distributed atomicity and request/result-log horizontal runtime scaling remain out of scope.
- The non-JDBC publish-first event/action visibility race must be certified with the chosen tracker retry policy before
  a split-store deployment relies on event-handler model injection.
- Production 100 GB/min certification, cold-cache tests, retention, vacuum/bloat, replicas, backup/restore, and
  millions-to-billions cardinality remain explicit Phase 9 gates.
