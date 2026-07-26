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

The direct document update is awaited before command success. The SDK sends the exact pre-serialized document mutation
with the action; the JDBC runtime retains it in a compressed materialization outbox in the core action transaction.
Successful search completion clears that payload. A retry after either SDK or runtime process loss reloads the
original package by `actionId`; it never indexes the retrying SDK's reevaluation. No distributed transaction is
claimed.

## Coherent action materialization

- One `CommitModelAction` carries original events, target memberships, complete desired relationships, optional direct
  current-document mutations, and due snapshot candidates. The original event payload still occurs once regardless of
  target count.
- Core JDBC state — global publication when co-located, streams, heads, temporal relationships, action result, and the
  exact compressed materialization intent — commits in one transaction.
- Search completion uses a second recoverable transaction. Direct documents advance a 128-segment monotone
  `stateIndex` fence, physically grouped into 32 partitions; deletes leave a minimal fence tombstone. Direct documents
  and snapshots from one runtime completion batch commit together.
- Snapshot candidates carry their configured period. The runtime compares it with the actually assigned model
  `sequenceNumber`; an SDK prediction outside the real boundary rolls back the core commit. Candidates with incomplete
  event history are not installed.
- Accepted action results distinguish new commits from duplicates and whether documents/snapshots are complete. A
  durable duplicate with pending work replays the retained outbox. The SDK refuses to repair such a fresh-process
  duplicate from newly evaluated state.
- Default `ACCEPT` does not install consequences evaluated from a stale receiver. The runtime returns a new pinned
  boundary, after which the SDK reapplies only the already-produced post-interception events. Assertions, command
  handling, and interceptor expansion are not rerun; the original serialized events are retained. A final runtime
  boundary comparison must succeed, with at most ten apply-only rebases.
- Cache insertion uses the committed `stateIndex` as a monotone fence. A late completion cannot replace a newer cached
  model or cause an older snapshot fallback to be stored.
- Search completion runs on a bounded 4–32-thread executor rather than creating an unbounded platform thread per
  action batch. Actions without document or snapshot work complete independently of a search-store failure.

## Snapshots and cache

- Independent snapshots store model ID, local sequence, `stateIndex`, timestamp, and serialized value in
  `$modelSnapshots`.
- A snapshot is used only when it is visible at the requested historical boundary. Stream loading starts strictly
  after its local sequence.
- Only due snapshot candidates cross the wire. Runtime insertion and configured per-model retention are awaited before
  command success and are recoverable from the action outbox.
- Accepted local actions seed exact committed revisions directly into the cache. Multi-substep actions retain every
  revision in order.
- `@Model.cachingDepth` defaults to `1`: the current and one previous revision are retained for
  `Entity.previous()` comparisons. `0` is explicit latest-only; a negative value is explicit unbounded history.
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

### Coherent-materialization regression matrix

The completed slice was compared immediately against clean commit `e0ea56e1` in a temporary worktree on the same
PostgreSQL container and host state. Absolute Docker Desktop throughput was lower than the older Phase 5 run, so the
paired comparison is the relevant regression signal.

| Targets | Actions | Clean store/s | Slice store/s | Clean current load/s | Slice current load/s | Physical ratio clean/slice | WAL ratio clean/slice |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 20,000 | 4,464 | 4,407 | 28,049 | 25,488 | 1.89× / 1.89× | 2.18× / 2.19× |
| 2 | 10,000 | 2,630 | 3,099 | 27,976 | 30,024 | 2.44× / 2.44× | 3.28× / 3.09× |
| 10 | 3,000 | 929 | 894 | 25,668 | 25,343 | 6.42× / 6.42× | 9.54× / 9.06× |
| 100 | 500 | 67 | 68 | 18,214 | 20,150 | 42.78× / 42.96× | 71.43× / 71.49× |

No consistent write, load, physical-byte, or WAL regression appears across the matrix. The 1-target load difference
and opposite 2/100-target differences are retained as local-run variance; production certification still requires
repeated controlled runs.

The benchmark now reports event, direct-document, snapshot, and pending compressed outbox bytes independently. With
5,000 single-target 1-KiB events:

| Materialization | Store/s | p50 / p99 | Physical/logical | WAL/logical | Pending outbox |
|---|---:|---:|---:|---:|---:|
| 1-KiB direct document, completed | 4,206 | 53.8 / 131.4 ms | 2.38× | 2.97× | 0 |
| 1-KiB snapshot every event, completed | 3,297 | 72.8 / 124.6 ms | 2.48× | 3.12× | 0 |
| 1-KiB document + 1-KiB snapshot, completion deliberately disabled | 4,571 | 49.3 / 96.7 ms | 0.96× | 1.21× | 5.56 MiB |

The last row isolates the retained compressed recovery intent: 4.88 MiB each of logical document and snapshot bytes
plus 4.88 MiB of events produced 5.56 MiB of live outbox payload. Its intentionally absent final search documents are
not comparable to the completed rows. Snapshot-every-event is a deliberate upper-bound workload; normal traffic sends
a snapshot only at its configured period.

### SDK replay and cache

- Ten repeated cold reconstructions of a 10,000-event model: 45.3 ms/load and 220,714 applied events/s.
- Cached 10,000-event model: 34–78 ms full replay after invalidation, 0.436 ms one-event suffix catch-up, and 101.7 µs
  per warm in-memory head-check load across 10,000 loads.
- A forked retained-heap diagnostic with one million minimal immutable model keys measured 329.03 MiB at depth `0` and
  505.66 MiB at depth `1`: one retained predecessor added 176.63 MiB, or about 185 bytes per hot key. Four 250,000-key
  forks gave the same direction and a stable 203–204 bytes/key delta. The deliberately tiny value makes this a
  wrapper-heavy baseline; real value size remains workload-dependent.

The spread in full replay is JIT/local-run variance. The diagnostic is retained so Phase 9 can repeat the same modes
against remote runtime, cold OS/database caches, snapshots, concurrent reconstruction, and production hardware.

## Verification

Retained implementation commits: initial SDK/protocol `0249394ba5c` and runtime `e0ea56e1`; coherent-materialization
hardening SDK/protocol `0b9b74b0764` and runtime `39cb88e1`.

Focused coverage includes:

- normal local and tracked command integration, direct document completion, multi-target prefetch, payload and
  receiver-side applies reached through interceptors, and conflict reload;
- paginated and byte-bounded replay, current and historical heads, malformed pages, and incomplete history;
- self-only replay, stale accepted actions, exact cross-model boundaries, same-action substep overlays, and batched
  dependency loads;
- normally document-loaded historical dependencies and pre-commit rejection after a non-stored gap;
- logical delete/recreate, unknown-event policy, serializer upcasting, snapshots, bounded previous chains, external
  suffix catch-up, legacy model-snapshot readability, and repair after a direct-document failure;
- stale default-accept rebase without assertion/interceptor reruns, exact original-event retention, duplicate versus
  new completion, cross-runtime visible-boundary refresh, inverse document completion, delete resurrection, and cache
  fences;
- runtime snapshot-boundary verification, compressed snapshot/document outbox restart repair, transactional search
  completion, immutable snapshot retention, and direct-document fence partitioning;
- current and moved root/child/grandchild graph reconstruction using explicit child-owned paths;
- event/notification handler loads pinned through existing action-result metadata;
- runtime in-memory/JDBC action-boundary, graph, type-catalog, partition, rollback, and relationship tests.

Final gates on the retained diff:

- SDK `./mvnw -B install`: complete reactor passed, including common, SDK, test server, proxy, annotation processor,
  Java downstream, and Kotlin downstream modules;
- SDK `./mvnw -B site -Pjavadoc`: complete site/Javadoc reactor passed;
- runtime `./mvnw -B install`: complete reactor passed with 538 runtime tests and
  benchmark test compilation;
- both repositories passed `git diff --check` after the regression-only review.

## Deliberately later

- Explicit hard delete does not exist yet. Phase 5 covers logical delete/recreate; intentional history erasure,
  tombstone reporting, and the resulting historical reconstruction contract remain Phase 8 work and are not simulated
  as if the API already existed.
- Current relational graph search and runtime document stitching are Phase 7.
- A materialized asynchronous root graph document is Phase 7.3.
- XA, multi-store atomicity, and request/result-log horizontal runtime scaling remain out of scope. The retained
  runtime outbox makes current split-store completion recoverable without pretending it is one distributed
  transaction.
- The non-JDBC publish-first event/action visibility race must be certified with the chosen tracker retry policy before
  a split-store deployment relies on event-handler model injection.
- Production 100 GB/min certification, cold-cache tests, retention, vacuum/bloat, replicas, backup/restore, and
  millions-to-billions cardinality remain explicit Phase 9 gates.
