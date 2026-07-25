# Dynamic model boundaries

Tickable implementation backlog for the coordinated SDK and runtime refactor.

- SDK branch: `feature/dynamic-model-boundaries`
- SDK base: `964fdcdac4b5beeffdf155558c9af6a6b944e1ab`
- Runtime branch: `feature/dynamic-model-boundaries`
- Runtime base: `a02bd72c2b3a8981fb2ace3a5dbb63cafa7e77ab`
- Started: 2026-07-25

## Outcome

Replace the fixed aggregate consistency boundary for new models with an action-scoped boundary. Independently stored
models can be loaded, asserted, applied, moved, linked, unlinked, searched, cached, snapshotted, reconstructed, and
deleted without loading their parent, siblings, children, or an artificial aggregate root.

This is a Fluxzero-native refactor. Formal DCB/Axon designs are not the design frame. Conflict handling is a contained
phase, not the central abstraction.

## Fixed design decisions

- `@Aggregate` and existing aggregate behavior remain compatible. This project introduces `@Model` alongside it.
- `@Model` marks an independently identified, stored, cached, searchable, snapshotted, and lifecycle-managed model.
- `@Model(eventSourced = true)` is the default. `eventSourced` controls the normal **load** path:
  - `true`: reconstruct from the model stream, optionally starting at a snapshot;
  - `false`: load the current model directly from its own document collection.
- `eventSourced = false` does not suppress event storage or publication. As with aggregates, `EventPublication` and
  `EventPublicationStrategy` determine whether an applied event is stored and/or published.
- A searchable model is synchronously indexed/deleted as part of commit completion. A successful commit therefore
  makes the directly changed model immediately searchable, matching current searchable aggregate behavior.
- A composed tree/graph search document is a separate, asynchronous and rebuildable CQRS projection.
- The authoritative document for `eventSourced = false` remains the ordinary `DocumentStore` record. No versioned
  document history or second `ModelStateStore` is introduced.
- Storage identity is exactly `Id.toString()` (or the equivalent untyped ID string). `@Model` has no name that is
  concatenated into the key. Existing ID prefix/type conventions remain responsible for uniqueness.
- Existing `@Member` semantics remain embedded: members share their root's stream, cache, search document, and
  lifecycle. Independently stored nodes use `@Model`.
- A relationship is declared from a child model using one or more `@ParentId` properties. Multiple parents form a DAG;
  cycles are rejected.
- Target IDs are resolved from command payload properties by matching the target model's `@EntityId`. If names are
  ambiguous or deliberately different, parameter-level `@Association("propertyName")` qualifies the ID source.
- `@AssertLegal`, `@InterceptApply`, and `@Apply` may inject every action-scoped model as either its value or
  `Entity<T>`.
- Assertions and applies may read multiple loaded models.
- Applies produced by one domain event all observe the same substep begin-state. Their results are not implicitly
  ordered.
- `@InterceptApply` expansions are ordered substeps. Later substeps observe committed-in-memory results from earlier
  substeps, while the complete handler action still commits or rolls back as one unit.
- Only a model returned by an `@Apply` is a target of that event in a model stream.
- A non-null return upserts the target model and stores the event according to its publication strategy.
- A `null` return logically deletes the target model and still stores/publishes that original event according to its
  publication strategy.
- `void` applies are rejected for `@Model`. Legacy mutable `@Aggregate` behavior is unchanged.
- One original domain event is published at most once in the global event log, even when it is stored in multiple model
  streams.
- No special websocket capability handshake is added. An old runtime rejects the new model-action request through the
  existing unsupported-request behavior.

## Vocabulary and ordering

These terms must remain distinct in APIs, documentation, and tests.

- **event-log replay**: historical global events delivered to `@HandleEvent`.
- **model reconstruction / event sourcing**: rebuilding one model by applying its model stream through `@Apply`.
- **sequenceNumber**: position within one model stream.
- **eventIndex**: `SerializedMessage#index`, the existing global event-log position. It only exists for published
  messages.
- **stateIndex**: a new, namespace-wide monotone position assigned to every model state transition, including
  `STORE_ONLY`, `PUBLISH_ONLY`, and `EventPublication.NEVER` transitions that have no event-log position.
- **actionId**: durable idempotency key grouping all state transitions in one handler action.
- **readStateIndex**: the single state boundary pinned when an action begins.

No separate per-model version vector is the primary consistency mechanism. The action contains the IDs it read and one
`readStateIndex`. The runtime can detect a stale read by checking whether any listed model head advanced beyond that
boundary. A model-head record stores only current coordination metadata such as `lastStateIndex`; it is not document
version history.

An intercepted action may yield several original events. Each event/substep receives its own ordered `stateIndex`; all
share the same `actionId`. If an event is published, its independently assigned `eventIndex` is recorded alongside the
state transition. Every target stream entry for that original event shares those identities.

## Historical dependency invariant

When an event at `stateIndex = N` is used to reconstruct target model `A`, injected model `B` must be loaded as it was
at that event's recorded substep begin boundary, never as its current head.

The intended mechanism is:

1. Pin one begin `stateIndex` per event/substep.
2. Store that boundary with each target stream entry.
3. Reconstruct all injected dependencies as-of that boundary, batching and caching repeated loads in the reconstruction
   context.
4. For `eventSourced = false`, continue to use `DocumentStore` for normal current loads, but use its stored model events
   for an exceptional historical dependency load.
5. Do not store per-event dependency version vectors or target-state outcomes on the normal path.

An explicitly non-stored state change (`PUBLISH_ONLY` or `EventPublication.NEVER`) creates a gap that cannot be
historically reconstructed from events. Before cross-model applies ship, a characterization/prototype must prove and
document one safe behavior:

- reject a commit that would make an event-sourced target depend on incomplete dependency history; or
- introduce an explicit opt-in checkpoint/fallback for that dependency.

Silent reconstruction against the wrong dependency state is never allowed. A generic target-state checkpoint is not a
foundational storage contract.

## Commit and search boundary

The first implementation deliberately does not solve cross-database atomicity or horizontal runtime coordination.
Future request/result-log based horizontal scaling is out of scope.

- `CommitModelAction` is one new runtime request carrying `actionId`, `readStateIndex`, read IDs, original events,
  target model IDs, and relationship transitions.
- The JDBC runtime fast path stores model-stream entries, model heads, state indices, event-log publications, and
  relationship intervals in one transaction where those facilities share the same database.
- In-memory stores provide the same observable all-or-nothing contract.
- The protocol and storage interfaces must not assume that search shares that transaction.
- Direct model search indexing/deletion is awaited by SDK commit, exactly as it is for current aggregates. A search
  failure fails commit completion but can occur after authoritative event/model storage has succeeded; this existing
  cross-store limitation is documented rather than hidden.
- Composite graph projections are asynchronous, idempotent, and rebuildable.
- Distributed transactions, a general participant coordinator, and multi-runtime consensus are explicitly deferred.

## Relationship and deletion semantics

Relationship history uses half-open state-index intervals:

`validFrom <= requestedStateIndex < validUntil`

Each edge is identified by child ID, parent ID, and the stable `@ParentId` property/role. A current-edge projection makes
ordinary routing and graph loading cheap; interval history supports as-of reconstruction.

- Changing a `@ParentId` closes the old interval and opens the new interval. Descendants are untouched.
- A logical delete (`@Apply` returns `null`) removes the target's current direct search document, closes its outgoing
  parent edges, and relation-cascades over incoming edges by marking them detached because their parent was deleted.
- That relation cascade does not mutate or delete child models and emits no child model events.
- Detached edges remain discoverable as tombstones/history, including deleted parent ID, child ID, role, interval, and
  detach reason. This is required so a later GDPR/lifecycle operation can still find detached descendants.
- Relationship resolution must not silently reactivate a detached edge merely because an unchanged child document still
  contains the deleted parent ID.
- Explicit hard delete has an explicit cascade mode:
  - `NONE`: hard-delete only the selected model while retaining the minimum relationship tombstones needed to find its
    detached descendants;
  - `DESCENDANTS`: resolve the selected model's current and deletion-detached descendant lineage, then hard-delete that
    set as one requested lifecycle operation.
- DAG/shared-child behavior, retention of raw versus protected relationship IDs, dry-run reporting, and final tombstone
  purge rules must be fixed and tested before `DESCENDANTS` is enabled in production.

## Non-negotiable storage and load envelope

Fluxzero runtimes exist that sustain roughly 100 GB/min (about 1.67 GB/s) on the event write and read paths. Store
**and** load performance are therefore architecture contracts, not optimizations deferred until the end. Splitting a
few large aggregate streams into potentially billions of short model streams changes cardinality, compression,
indexing, cache locality, WAL, vacuum, and partition-pruning behavior and must be proven before the wire/storage
contract is frozen.

- Keep the global event log as the one ordered publication log. One original event is appended there at most once.
- Do not blindly copy a full serialized event payload once per target model. Phase 0 must compare:
  - a chunked payload in every target stream;
  - one shared stored payload plus lightweight per-model stream references;
  - a hybrid that keeps the single-target path inline and deduplicates multi-target payloads.
- A chosen layout must optimize both writes and reconstruction. Payload deduplication is not acceptable if it replaces
  sequential reads with an unbounded number of joins or random reads.
- Treat short streams as the normal high-cardinality case. The current aggregate store only compresses completed
  multi-event groups; a workload with billions of one- or two-event streams must not silently lose the storage and I/O
  benefits on which aggregate throughput relies.
- Model-head reads and sequence allocation must be batched and co-partitioned with stream writes. A cache miss may not
  introduce one `max(sequenceNumber)` query or one round trip per target model.
- The model event store and model-head store are candidates for stable hash-bucket partitioning by model ID. Compare
  PostgreSQL native hash partitioning with an explicit Fluxzero-computed bucket that can also become a future routing
  and sharding key.
- The relationship store must prune efficiently in both directions. Partitioning one table only by child or only by
  parent makes the opposite traversal fan out over every partition, so Phase 0 must evaluate dual parent-keyed and
  child-keyed adjacency projections, including temporal intervals and detached tombstones.
- Measure logical event bytes, physical table/index bytes, WAL and replica bytes separately. Also measure CPU, heap/GC,
  allocations, connections, IOPS, compression, cache behavior, vacuum/bloat, backpressure, recovery, and p50/p95/p99
  latency.
- Preserve the existing aggregate storage path unless measured evidence supports a change. New model machinery must add
  no statistically meaningful overhead to legacy aggregate traffic.

## Phase 0 — Baseline and contract capture

### Slice 0.1 — Branches and executable backlog

- [x] Create `feature/dynamic-model-boundaries` in SDK.
- [x] Create `feature/dynamic-model-boundaries` in runtime.
- [x] Record both base SHAs and the cross-repository backlog.
- [x] Commit this backlog before implementation commits.

### Slice 0.2 — Baseline verification

- [x] Run SDK `./mvnw -B install`.
- [x] Run runtime `./mvnw -B install`.
- [x] Record failures that already exist on the base commits; do not normalize them into feature work.

### Slice 0.3 — Existing aggregate characterization

- [x] Lock down that searchable `eventSourced = false` aggregates load current state from `DocumentStore`.
- [x] Lock down that their events are still stored/published unless the publication strategy excludes that.
- [x] Lock down direct search visibility before aggregate commit completion.
- [x] Lock down `PUBLISH_ONLY`, `STORE_ONLY`, and `EventPublication.NEVER` state/index behavior.
- [x] Lock down `CachingAggregateRepository` catch-up and playback behavior using `eventIndex`.
- [ ] Lock down current logical and hard-delete effects on streams, snapshots, documents, cache, and relationships.
- [x] Correct misleading `Aggregate.eventSourced` Javadoc without changing behavior.

### Slice 0.4 — State-index feasibility spike

- [ ] Prototype allocation of a monotone namespace-wide `stateIndex` in JDBC and in-memory stores.
- [ ] Prove one published event can be logged once and stored in multiple model streams with one identity.
- [ ] Prove one action can allocate an ordered range for interceptor substeps.
- [ ] Prove an action-pinned `readStateIndex` can detect relevant intervening model writes using only read IDs plus the
  shared boundary.
- [ ] Prove model reconstruction can load an event-sourced dependency as-of a substep begin boundary.
- [ ] Prove a normally document-loaded dependency can be reconstructed from stored events without adding document
  history.
- [ ] Demonstrate and fix/fail-fast the incomplete-history case caused by `PUBLISH_ONLY`/`NEVER`.
- [ ] Benchmark batched as-of dependency loads against an explicit performance budget.
- [ ] Write an ADR from the evidence before freezing the wire protocol.

### Slice 0.5 — Production-scale workload and baseline harness

- [ ] Capture anonymized production workload distributions for logical bytes, serialized payload sizes, stream
  cardinality, events per stream, target count, hot/cold skew, read/write mix, publication strategy, and graph degree.
- [ ] Extend the JDBC event-store benchmark to report logical and physical byte throughput, request/event throughput,
  WAL bytes, table/index growth, compression, CPU, allocation/GC, connections, IOPS, and latency percentiles.
- [ ] Add load benchmarks for current head, one complete stream, snapshot-plus-tail, batched model IDs, and as-of
  dependency reconstruction under both warm and cold caches.
- [ ] Exercise hot streams, uniform IDs, Zipfian IDs, and millions-to-billions-equivalent short streams with 1, 2, 10,
  and 100 targets per action.
- [ ] Exercise representative small, medium, large, and extreme payloads, including production-captured distributions
  instead of relying only on a fixed 512-byte synthetic event.
- [ ] Benchmark sustained mixed store/load traffic against the 100 GB/min production reference on representative
  hardware, or record and validate a calibrated scale model when that hardware is unavailable.
- [ ] Run the same harness against the untouched legacy aggregate path and store its reproducible baseline results.

### Slice 0.6 — Model-event physical layout bake-off

- [ ] Prototype per-target chunked payloads, a shared-payload/reference layout, and a hybrid single-target/multi-target
  layout without changing the public protocol.
- [ ] Prove that every original event has one durable payload identity and that multi-target stream membership remains
  independently ordered and idempotent.
- [ ] Compare compression and compaction strategies for predominantly one- and two-entry model streams; include the
  current 128-entry aggregate chunk behavior as a control.
- [ ] Prototype a first-class model-head table for batched sequence reservation and current `lastStateIndex` reads.
- [ ] Prove one action uses set-based/batched statements and bounded round trips rather than one query, write, or
  transaction per target.
- [ ] Measure read amplification for reconstruction, including payload references, snapshot tails, batching, cache
  misses, and historical dependencies.
- [ ] Select the physical layout only after documenting byte amplification, WAL amplification, store throughput, load
  throughput, latency, operational complexity, recovery, and migration trade-offs in an ADR.

### Slice 0.7 — Hash partitioning and graph adjacency spike

- [ ] Prototype stable hash-bucket partitioning of model streams and model heads by the exact model ID string.
- [ ] Compare native database hash partitions with explicit power-of-two buckets produced by a stable Fluxzero hash;
  document compatibility requirements if the bucket later becomes a runtime/shard routing key.
- [ ] Benchmark representative partition counts and prefilled high-cardinality datasets; do not select a count from
  microbenchmarks on empty tables.
- [ ] Use query plans and runtime metrics to prove partition pruning for head lookup, append, stream load, delete, and
  batched ID load.
- [ ] Prototype parent-keyed and child-keyed current adjacency projections so parent-to-children and child-to-parents
  lookups each hit bounded partitions.
- [ ] Decide whether temporal edges are duplicated in both projections or stored canonically with a measured lookup
  strategy; include moves, shared children, tombstones, as-of traversal, and GDPR lineage.
- [ ] Prove both adjacency projections change atomically with model streams when they share the JDBC transaction and
  remain rebuildable from authoritative action/relation history.
- [ ] Document partition creation, migration, re-bucketing, backup/restore, vacuum, observability, and rollback before
  adopting the layout.

### Slice 0.8 — Architecture performance gate

- [ ] Define absolute latency and sustained-throughput budgets from the captured production envelope, including
  concurrent reads, prefilled tables, WAL/replication, and steady-state maintenance.
- [ ] Require no statistically meaningful regression (initial guardrail: more than 2%) in throughput or p99 latency on
  the unchanged legacy aggregate fast path.
- [ ] Require the common single-target model path to remain within an agreed small margin of equivalent aggregate
  logical-byte throughput and p99 latency.
- [ ] Require multi-target storage amplification to grow primarily with lightweight stream references and relationship
  deltas, not with target count times full payload size.
- [ ] Require bounded request round trips and transactions independent of target count, with measured batch-size and
  backpressure limits.
- [ ] Require demonstrated partition pruning and bounded-partition access for both directions of relationship traversal.
- [ ] Run sustained store-only, load-only, and mixed store/load tests plus restart/recovery and overload tests.
- [ ] Record results, datasets, hardware, database settings, query plans, profiles, and accepted limits in a committed
  report and ADR.
- [ ] Do not freeze `CommitModelAction`, start broad implementation, or check Phase 0 complete until this gate passes.

## Phase 1 — Model metadata and public vocabulary

### Slice 1.1 — `@Model`

- [ ] Add documented `@Model` with aggregate-equivalent storage/search/cache settings and no `name`.
- [ ] Define `eventSourced` as load behavior in Javadoc and examples.
- [ ] Reuse `@Member` for embedded members inside either `@Aggregate` or `@Model`.
- [ ] Add startup validation rejecting `void @Apply` for model targets.
- [ ] Preserve mutable/void legacy aggregate behavior.
- [ ] Add Java and Kotlin downstream compilation coverage.

### Slice 1.2 — Reflection metadata

- [ ] Extend `ReflectionUtils.TypeMetadata`; do not add a parallel class-keyed cache.
- [ ] Cache model annotation, `@EntityId`, `@ParentId` roles, apply return targets, and handler dependency descriptors.
- [ ] Validate duplicate/ambiguous IDs, missing target IDs, invalid parent roles, and model cycles with actionable errors.
- [ ] Measure startup/reflection impact.

### Slice 1.3 — Entity/model abstraction

- [ ] Generalize internal root metadata so model code does not pretend every root is an aggregate.
- [ ] Preserve supported `Entity<T>` behavior and legacy aggregate call paths.
- [ ] Add `Fluxzero.loadModel(...)`, repository APIs, and `TestFixture` vocabulary.
- [ ] Keep persisted keys exactly equal to ID strings.

## Phase 2 — Action-scoped loading and apply engine

### Slice 2.1 — Target resolution

- [ ] Resolve target IDs from payload properties matching each model `@EntityId`.
- [ ] Reuse parameter-level `@Association("propertyName")` only when automatic matching is ambiguous or overridden.
- [ ] Require only direct target IDs; never require parent or grandparent IDs for routing.
- [ ] Batch/deduplicate loads and expose a single `readStateIndex`.
- [ ] Keep unrelated parent, sibling, child, and graph nodes unloaded.

### Slice 2.2 — Injection

- [ ] Inject any action-scoped model into `@AssertLegal`.
- [ ] Inject any action-scoped model into `@InterceptApply`.
- [ ] Inject any action-scoped model into `@Apply`.
- [ ] Support both value parameters and `Entity<T>`.
- [ ] Cache resolution within the action and reconstruction contexts.

### Slice 2.3 — Deterministic execution

- [ ] Evaluate all applies for one original event against the same substep begin-state.
- [ ] Reject ambiguous duplicate writes to the same target unless their semantics are explicitly combined.
- [ ] Execute interceptor expansions as ordered substeps.
- [ ] Let later substeps resolve new targets and observe earlier substep results.
- [ ] Roll back the complete in-memory action on assertion/apply/interceptor failure.
- [ ] Store/publish no event for a failed action.

### Slice 2.4 — Return and lifecycle behavior

- [ ] Non-null return upserts only the returned target.
- [ ] Null return creates a logical-delete target transition and retains the original event.
- [ ] Void return fails startup validation for models.
- [ ] Models merely read or injected receive no stream entry.
- [ ] One event targeting several models is represented once globally and has one lightweight membership entry in each
  target stream, using the physical payload layout selected in Phase 0.

## Phase 3 — Wire protocol and runtime action commit

### Slice 3.1 — Common protocol

- [ ] Add `CommitModelAction` and result types to `common`.
- [ ] Carry `actionId`, `readStateIndex`, ordered original events, storage/publication policy, target IDs, relation deltas,
  and direct model head expectations.
- [ ] Keep event and state indices semantically distinct.
- [ ] Add JSON/MessagePack compatibility tests and unknown-request coverage.

### Slice 3.2 — Runtime storage contract

- [ ] Add action-aware model storage interfaces without changing legacy `AppendEvents`.
- [ ] Allocate ordered state indices and batch-update current model heads without per-model head queries.
- [ ] Append an independently sequenced membership entry to every target stream while storing the serialized payload
  exactly according to the Phase 0 layout; never default to one full payload copy per target.
- [ ] Append each publishable original event exactly once to the global event log.
- [ ] Make `actionId` idempotent and return the prior result for a duplicate request.
- [ ] Store current and historical relationship transitions at the same state boundary.

### Slice 3.3 — JDBC and in-memory implementations

- [ ] Add migrations/tables/indexes for model heads, action idempotency, and temporal relationship intervals.
- [ ] Implement the selected stable hash-bucket layout for model streams and heads with verified partition pruning.
- [ ] Use one JDBC transaction for model streams, state indices, event log, heads, and relationships when co-located.
- [ ] Avoid splitting one action across segment backlogs before the atomic write.
- [ ] Use set-based/batched writes with explicit limits and backpressure for large target sets.
- [ ] Add in-memory parity.
- [ ] Test partial failure, rollback, retry after lost response, duplicate action, restart, and concurrent commits.
- [ ] Keep legacy aggregate throughput on its current fast path.

### Slice 3.4 — Direct search completion

- [ ] Index/delete each directly changed searchable model before SDK commit completes.
- [ ] Load `eventSourced = false` models from that same direct collection.
- [ ] Preserve custom collection, timestamp/end path, serialization revision, metadata, and publication behavior.
- [ ] Batch direct document mutations per collection where possible.
- [ ] Test and document current cross-store partial-failure semantics.

## Phase 4 — Conflict handling (contained side quest)

### Slice 4.1 — Policies

- [ ] Add `ACCEPT` as the default policy: do not reject a stale `readStateIndex`.
- [ ] Add `FAIL`: runtime rejects and rolls back the complete action if a read/written model head advanced.
- [ ] Add `RETRY_IF_RELATIONS_UNCHANGED`: retry only if relevant relationship state still matches the read boundary.
- [ ] Return conflicting IDs and current state/relation indices without requiring a client-supplied version per model.

### Slice 4.2 — SDK resolution

- [ ] Add a client-side conflict resolver SPI that runs only after runtime rollback.
- [ ] Support bounded silent retry after reloading all action-scoped models.
- [ ] Support mapping the conflict to an application error.
- [ ] Evict/refresh speculative cache entries after accepted stale writes or rejected actions.
- [ ] Test single-writer default behavior remains low-overhead.

## Phase 5 — Reconstruction, cache, and snapshots

### Slice 5.1 — Model reconstruction

- [ ] Reconstruct only the requested model's hash-pruned stream using batched/sequential payload resolution from the
  selected Phase 0 layout.
- [ ] Resolve cross-model dependencies as-of each stored substep begin `stateIndex`.
- [ ] Batch and context-cache historical dependency loads.
- [ ] Preserve normal self-only replay without dependency I/O.
- [ ] Keep snapshots as the primary long-stream optimization.
- [ ] Verify cold and warm reconstruction throughput continuously against the Phase 0 load budgets.

### Slice 5.2 — Document-loaded dependency history

- [ ] Use stored model events for historical reconstruction even when normal load uses `DocumentStore`.
- [ ] Track whether model history is complete without retaining document revisions.
- [ ] Enforce the Phase 0 decision for non-stored history gaps.
- [ ] Cover delete, recreate, unknown event, upcasting, snapshot, and hard-delete history loss.

### Slice 5.3 — Cache synchronization

- [ ] Generalize the useful `CachingAggregateRepository` event-index behavior for independent models.
- [ ] Synchronize published changes through the event log and non-published changes through state/model-head awareness.
- [ ] Pin event/notification handler loads to the correct historical state.
- [ ] Never use a timeless relationship cache for as-of model reconstruction.
- [ ] Benchmark cache hit, miss, catch-up, invalidation, and billion-key pressure assumptions.

## Phase 6 — Temporal DAG relationships and graph loading

### Slice 6.1 — `@ParentId`

- [ ] Add repeatable/multi-property parent metadata with a stable role.
- [ ] Compute relation deltas only for returned targets.
- [ ] Support attach, detach, move, and multiple parents by changing only the child model.
- [ ] Reject cycles at commit with the entire action rolled back.

### Slice 6.2 — Current and historical lookups

- [ ] Query parents, children, roots, ancestors, and descendants at current state.
- [ ] Query the same graph as-of a `stateIndex`.
- [ ] Route parent-to-children and child-to-parents lookups to their respective bounded hash partitions.
- [ ] Use half-open validity intervals and deterministic boundary tests.
- [ ] Batch breadth/depth graph fetches and enforce configurable safety limits.
- [ ] Benchmark deep, wide, and highly shared DAGs.

### Slice 6.3 — Deleted-parent lineage

- [ ] Relation-cascade incoming edges to detached tombstones when a parent is logically deleted.
- [ ] Keep child model state and stream unchanged.
- [ ] Resolve detached descendants for later lifecycle/GDPR operations.
- [ ] Prevent accidental edge resurrection from an unchanged stale `@ParentId`.
- [ ] Decide protected-ID/retention and purge semantics with privacy tests.

## Phase 7 — Search and CQRS graph projections

### Slice 7.1 — Independent collections

- [ ] Make every `searchable = true` model independently searchable without a custom event handler.
- [ ] Keep direct search read-after-commit consistency.
- [ ] Support bulk model indexing/deletion and per-model lifecycle.

### Slice 7.2 — Graph search document

- [ ] Add an opt-in asynchronous projection for a complete model graph.
- [ ] Consume idempotent model-action/result records.
- [ ] Rebuild affected roots using temporal relations and batched model loads.
- [ ] Handle multi-parent fan-out, moves, deletes, late delivery, duplicate delivery, and rebuild.
- [ ] Expose projection lag/high-watermark where callers need freshness awareness.

## Phase 8 — Explicit hard delete and GDPR

### Slice 8.1 — Delete API

- [ ] Add `deleteModel(id, NONE)` and `deleteModel(id, DESCENDANTS)`.
- [ ] Make cascade mode mandatory at the wire boundary.
- [ ] Add a dry-run/plan result before destructive descendant cascades.
- [ ] Define DAG shared-descendant behavior explicitly before enabling execution.

### Slice 8.2 — Physical cleanup

- [ ] Delete the selected streams, snapshots, cache entries, and direct documents.
- [ ] Traverse current plus parent-deletion-detached lineage for descendant cascade.
- [ ] Coordinate relationship tombstone retention and final purge.
- [ ] Define what historical reconstruction and graph projections report after intentional erasure.
- [ ] Test retries/idempotency, partial cross-store failure, huge cascades, and resumable cleanup.

## Phase 9 — Scale certification, compatibility, and rollout

### Slice 9.1 — Scale certification

- [ ] Repeat the Phase 0 production workload suite against the complete implementation; Phase 9 is certification, not
  the first performance discovery point.
- [ ] Certify millions-to-billions of model IDs, model-head rows, streams, direct documents, and temporal/current edges.
- [ ] Certify action latency and byte throughput for 1, 2, 10, and 100 targets under concurrent model reconstruction.
- [ ] Certify cold/warm reconstruction, graph loads, and projection rebuilds for deep/wide/shared DAGs.
- [ ] Certify storage/WAL amplification, cache memory, vacuum/bloat, recovery, and overload/backpressure budgets.
- [ ] Compare every result with the Phase 0 baseline and explain every material regression before release.

### Slice 9.2 — Compatibility

- [ ] Keep all aggregate APIs, formats, tests, and downstream projects green.
- [ ] Verify new SDK against old runtime gives a clear unsupported-action error only when `@Model` is used.
- [ ] Verify old SDK against new runtime remains unchanged.
- [ ] Add migration/rollback notes; no automatic aggregate-to-model data migration in the first release.

### Slice 9.3 — Final verification

- [ ] Run focused SDK and runtime suites for every slice.
- [ ] Run both full `./mvnw -B install` builds.
- [ ] Run Java/Kotlin downstream and protocol compatibility checks.
- [ ] Perform a separate regression-only diff review.
- [ ] Resolve every checked item's evidence link/commit in the log below.

## Evidence log

Add one line per completed slice with SDK/runtime commit(s), tests, benchmarks, and any remaining limitation.

- 2026-07-25 — Branches created from the SHAs recorded above; backlog initialized.
- 2026-07-25 — Baselines: runtime `./mvnw -B install` passed (477 runtime tests); SDK
  `./mvnw -B install` passed. A proxy health test first returned 404 while both full builds ran concurrently, then passed
  both in isolation and in the repeated full SDK build; no base failure recorded.
- 2026-07-25 — Existing contracts located in `SearchableAggregateTest`, `PublicationStrategyTests`, and
  `AggregatePlaybackTest`; added explicit non-event-sourced default event-storage/publication coverage and corrected
  `Aggregate.eventSourced` Javadoc. Focused verification:
  `./mvnw -pl sdk -Dtest='EventSourcingRepositoryTest,SearchableAggregateTest,AggregatePlaybackTest' test`
  (102 tests passed).
- 2026-07-25 — Storage-path review: current JDBC aggregate events are unpartitioned, use per-stream 128-entry chunks,
  compress only completed chunks, cache 100,000 stream heads, and query `max(sequenceNumber)` on a cache miss. Current
  relationships are one unpartitioned child-keyed table with a secondary parent/aggregate index. The existing append
  benchmark does not cover physical/WAL amplification, reconstruction throughput, graph traversal, billions of short
  streams, or sustained mixed traffic at the production reference envelope. Added Phase 0 layout, hash-partition,
  adjacency, and performance gates; implementation has not started and no storage choice has been made.
