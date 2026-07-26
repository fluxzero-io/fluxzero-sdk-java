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
- Related current model documents can be queried and stitched as a virtual graph at read time when their
  `@ParentId` declarations provide composition paths. A persisted complete tree/graph document remains a separate,
  asynchronous and rebuildable CQRS optimization.
- The authoritative document for `eventSourced = false` remains the ordinary `DocumentStore` record. No versioned
  document history or second `ModelStateStore` is introduced.
- Storage identity is exactly `Id.toString()` (or the equivalent untyped ID string). `@Model` has no name that is
  concatenated into the key. Existing ID prefix/type conventions remain responsible for uniqueness.
- Stored model type is optional reconstruction metadata, never part of model identity. A typed load uses its supplied
  Java type. An untyped load may reconstruct as `Object` when the first stored payload exposes all required
  `@Apply` factories; otherwise graph reconstruction may use the stored model type. Stored fully-qualified type names
  are resolved through the serializer's existing chained type-caster/upcasting registry before class loading, exactly
  as for aggregate relationship types.
- Existing `@Member` semantics remain embedded: members share their root's stream, cache, search document, and
  lifecycle. Independently stored nodes use `@Model`.
- A relationship is declared from a child model using one or more `@ParentId` properties. A typed `Id<T>` determines
  the parent model type; an untyped ID may declare it explicitly. The optional `path` enables automatic graph-document
  composition. Multiple parents form a DAG; cycles are rejected.
- Target IDs are resolved from command payload properties by matching the target model's `@EntityId`. If names are
  ambiguous or deliberately different, parameter-level `@Association("propertyName")` qualifies the ID source.
- A model parameter without a matching direct ID is a read-only ancestor dependency. Parameter-level
  `@Association("qualifier")` still selects a same-named payload property when present; otherwise it selects an
  ancestor edge with the same explicit `@ParentId(path = "qualifier")`. No extra relationship role is persisted.
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
- One model-action request carries every precomputed consequence of the action: original events, target memberships,
  desired relationships, optional direct-document mutations, and optional snapshots. The runtime owns their ordering
  and completion; the SDK does not independently race direct document writes after an accepted commit.
- `ACCEPT` means that a stale business event is not rejected. It never means that stale derived documents,
  relationships, snapshots, or cache entries may be installed. On a stale read, the original event is retained while
  its already-produced post-interception events are reapplied against one newly pinned merged model boundary.
  Assertions, command handling, and `@InterceptApply` expansion are not rerun.
- Direct model document upserts and deletes carry their target `stateIndex` as a monotone write fence. A late older
  mutation is a no-op; deletes retain the minimum fence tombstone needed to prevent resurrection.
- `@Model.cachingDepth` defaults to `1`, retaining the latest and one previous revision so event handlers can compare
  changes through `Entity.previous()`. `0` remains an explicit latest-only choice and `-1` remains explicit unbounded
  history.
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

When an event at `stateIndex = N` is used to reconstruct target model `A`, injected model `B` must be loaded from the
same logical state that its original substep observed, never from its current head.

The intended mechanism is:

1. Pin one persisted `readStateIndex = S` when the action starts.
2. Evaluate ordered interceptor substeps against state as-of `S`, overlaid with successful earlier substeps from the
   same action.
3. Store `S`, `actionId`, and the ordered substep identity with every target stream entry.
4. During reconstruction, load injected dependencies as-of `S` and overlay earlier substeps of the same action. Do not
   substitute `stateIndex - 1`: with the default stale-read acceptance policy that could include unrelated changes
   committed after `S` which the original action never observed.
5. Batch and cache both base dependency loads and action-prefix overlays in the reconstruction context.
6. For `eventSourced = false`, continue to use `DocumentStore` for normal current loads, but use its stored model events
   for an exceptional historical dependency load.
7. Do not store per-event dependency version vectors or target-state outcomes on the normal path.

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

- `CommitModelAction` is one runtime request carrying `actionId`, `readStateIndex`, read IDs, original events, target
  model IDs, desired relationships, optional direct current-document mutations, and optional snapshot candidates.
- The JDBC runtime fast path stores model-stream entries, model heads, state indices, event-log publications, and
  relationship intervals in one transaction where those facilities share the same database.
- In-memory stores provide the same observable all-or-nothing contract.
- The protocol and storage interfaces do not assume that search shares that transaction. The first JDBC implementation
  writes the exact direct-document/snapshot materialization intent in the core action transaction and completes it
  through a runtime-owned outbox. Direct current documents use monotone fences and immutable snapshots are idempotent;
  this remains correct for co-located and split stores without claiming XA. Folding co-located search into the core
  transaction remains a later optimization, not a different protocol.
- Direct model search indexing/deletion is completed by the runtime before model-action success, exactly as it is for
  current aggregates. A split-store failure may still occur after authoritative event/model storage has succeeded, but
  a retry is resolved from the retained action package and must never use a fresh SDK reevaluation.
- Current graph search may join current direct documents through current relationships without first materializing a
  composite document. Co-located JDBC stores should push filtering, traversal, sorting, and pagination into one
  relational query plan; split stores may use a bounded staged plan.
- The first graph-search contract uses ordinary related-document queries plus a target-relative relationship
  direction, minimum/maximum depth, and optional explicit `@ParentId(path)` filters. The related search collection is
  the model-type qualifier; no duplicate relationship role/type qualifier is required in the normal typed case.
- Graph search has a distinct wire request so an older runtime rejects it instead of accepting an ordinary search while
  silently dropping relationship constraints.
- Automatic runtime stitching includes only nodes with available current documents. Otherwise the runtime returns a
  graph/event bundle and the SDK reconstructs the independent models.
- Composite graph projections are asynchronous, idempotent, and rebuildable performance optimizations.
- Historical full-text graph search is deferred. Historical graph membership and model reconstruction remain supported
  through temporal relationships and model events.
- Distributed transactions, a general participant coordinator, and multi-runtime consensus are explicitly deferred.

## Relationship and deletion semantics

Relationship history uses half-open state-index intervals:

`validFrom <= requestedStateIndex < validUntil`

Each edge is identified by child ID, parent ID, and, when present, its explicit composition path. Parent-property
metadata may be retained for diagnostics but is not a required public relationship name. A current-edge projection
makes ordinary routing and graph loading cheap; interval history supports as-of reconstruction.

- Parent type is inferred from `Id<T>`. An explicit `@ParentId(ModelType.class)` is available for `String` and other
  untyped IDs and must agree with an inferred type when both are present.
- `@ParentId.path` is optional. Without it, attach/detach/move, relationship navigation, lineage, and graph bundles
  remain available, but automatic virtual-document stitching and CQRS graph placement are not enabled for that edge.
- No class-name-derived path becomes a silent durable document contract. A configured graph projection may override a
  child-owned canonical path for that projection.

- Changing a `@ParentId` closes the old interval and opens the new interval. Descendants are untouched.
- A logical delete (`@Apply` returns `null`) removes the target's current direct search document, closes its outgoing
  parent edges, and relation-cascades over incoming edges by marking them detached because their parent was deleted.
- That relation cascade does not mutate or delete child models and emits no child model events.
- Detached edges remain discoverable as tombstones/history, including deleted parent ID, child ID, compact relation
  descriptor, interval, and detach reason. This is required so a later GDPR/lifecycle operation can still find
  detached descendants.
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

## Phase 0 — Baseline and contract capture — complete

Storage decisions, measurements, rejected alternatives, and production gates are recorded in the
[Phase 0 storage ADR](dynamic-model-boundaries-phase-0-storage-adr.md).

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
- [x] Lock down current logical and hard-delete effects on streams, snapshots, documents, cache, and relationships.
- [x] Correct misleading `Aggregate.eventSourced` Javadoc without changing behavior.

### Slice 0.4 — State-index feasibility spike

- [x] Prototype allocation of a monotone namespace-wide `stateIndex` in a disposable JDBC store and specify equivalent
  in-memory coordinator semantics without retaining production spike code.
- [x] Prove one published event can be represented once globally with one membership per targeted model.
- [x] Prove one action can allocate an ordered range for interceptor substeps.
- [x] Prove an action-pinned `readStateIndex` can detect relevant intervening model writes using only read IDs plus the
  shared boundary.
- [x] Prove model reconstruction can load an event-sourced dependency as-of a substep begin boundary.
- [x] Prove a normally document-loaded dependency can use the same stored history without adding document
  history.
- [x] Demonstrate and choose fail-fast for the incomplete-history case caused by `PUBLISH_ONLY`/`NEVER`.
- [x] Benchmark batched as-of dependency loads and set the integrated implementation budget.
- [x] Write an ADR from the evidence before freezing the wire protocol.

### Slice 0.5 — Production-scale workload and baseline harness

- [x] Record the available 100 GB/min production envelope and define explicit proxy workloads for payload size, stream
  cardinality, target count, hot/cold shape, read/write mix, and graph degree. No anonymized customer distribution was
  available; production-captured distributions remain a release-certification input.
- [x] Extend the JDBC event-store benchmark to report logical/physical byte throughput, request/event throughput, WAL,
  table/index growth, compression, connection/commit behavior, and latency percentiles. CPU/allocation/GC/IOPS profiles
  remain required when the integrated model path exists.
- [x] Add load benchmarks for complete streams, batched model IDs, and as-of dependency reconstruction. Snapshot-tail
  and controlled OS-cold-cache measurement remain Phase 5/9 integrated gates.
- [x] Exercise hot, uniformly distributed, and one-million-ID short streams with 1, 2, 10, and 100 targets per action.
  Zipf/customer distributions remain in scale certification.
- [x] Exercise 64-byte, 1 KiB, and 16 KiB payloads with random and partially compressible content. Production extreme
  payloads remain in scale certification.
- [x] Run concurrent store/load diagnostics against the 100 GB/min reference and record a calibrated local scale model;
  do not present Docker Desktop as production certification.
- [x] Run the enhanced harness against the untouched legacy aggregate path and store its reproducible baseline results.

### Slice 0.6 — Model-event physical layout bake-off

- [x] Prototype per-target payloads, shared-row and shared-block references, and hybrid variants; derive the adaptive
  sharing crossover without changing the public protocol.
- [x] Prove that every original event has one logical payload identity and that multi-target stream membership remains
  independently ordered and idempotent. Bounded physical inline duplication below the selected crossover is allowed.
- [x] Compare raw, individual/opportunistic, and shared-block compression for short model streams; include the
  current 128-entry aggregate chunk behavior as a control.
- [x] Prototype a first-class model-head table for batched sequence reservation and current `lastStateIndex` reads.
- [x] Prove one action uses set-based/batched statements and bounded round trips rather than one query, write, or
  transaction per target.
- [x] Measure read amplification for inline payloads, shared rows, shared blocks, batched IDs, and historical
  dependencies; retain snapshot/cache variants as integrated Phase 5 gates.
- [x] Select the physical layout only after documenting byte amplification, WAL amplification, store throughput, load
  throughput, latency, operational complexity, recovery, and migration trade-offs in an ADR.

### Slice 0.7 — Hash partitioning and graph adjacency spike

- [x] Prototype stable-segment partitioning of model streams and model heads by the exact model ID string.
- [x] Compare native database hash partitions with Fluxzero's stable 128-segment space and document the segment as a
  future runtime/shard routing key.
- [x] Benchmark 32 and 128 physical partitions and prefilled one-million-ID datasets; do not select a count from
  microbenchmarks on empty tables.
- [x] Use query plans and runtime metrics to prove partition pruning for direct and batched model operations; repeat
  integrated delete plans with the production schema.
- [x] Prototype parent-keyed and child-keyed current adjacency projections so parent-to-children and child-to-parents
  lookups each hit bounded partitions.
- [x] Decide to duplicate temporal edges in both projections after measuring write/read amplification and partition
  pruning; cover moves, shared children, tombstones, as-of traversal, and GDPR lineage.
- [x] Prove both adjacency projections change atomically together in JDBC and specify their inclusion in the model-action
  transaction and rebuild from authoritative action/relation history.
- [x] Document stable-segment partition creation, regrouping/migration, rollback, observability, and the remaining
  backup/restore/vacuum certification work before adopting the layout.

### Slice 0.8 — Architecture performance gate

- [x] Define integrated latency/throughput and amplification budgets from the production envelope; retain absolute
  customer-hardware, replication, and steady-state maintenance certification as a release gate.
- [x] Require no statistically meaningful regression (initial guardrail: more than 2%) in throughput or p99 latency on
  the unchanged legacy aggregate fast path.
- [x] Require the common single-target model path to remain within an agreed small margin of equivalent aggregate
  logical-byte throughput and p99 latency.
- [x] Require multi-target storage amplification to grow primarily with lightweight stream references and relationship
  deltas, not with target count times full payload size.
- [x] Require bounded request round trips and transactions independent of target count, with measured batch-size and
  backpressure limits.
- [x] Require demonstrated partition pruning and bounded-partition access for both directions of relationship traversal.
- [x] Run diagnostic store-only, load-only, and existing aggregate mixed store/load tests. Integrated model
  restart/recovery, overload, and long soak tests remain release gates.
- [x] Record results, datasets, hardware, database settings, query plans, and accepted limits in a committed report and
  ADR; retain CPU/allocation/IO profiles as integrated-path release gates.
- [x] Pass the Phase 0 decision gate for a thin vertical implementation. Do not freeze broad `CommitModelAction`
  semantics or enable multi-model production use until the integrated path satisfies the recorded gates.

## Phase 1 — Model metadata and public vocabulary

### Slice 1.1 — `@Model`

- [x] Add documented `@Model` with aggregate-equivalent storage/search/cache settings and no `name`.
- [x] Define `eventSourced` as load behavior in Javadoc and examples.
- [x] Reuse `@Member` for embedded members inside either `@Aggregate` or `@Model`.
- [x] Add startup validation rejecting `void @Apply` for model targets.
- [x] Preserve mutable/void legacy aggregate behavior.
- [x] Add Java and Kotlin downstream compilation coverage.

### Slice 1.2 — Reflection metadata

- [x] Extend `ReflectionUtils.TypeMetadata`; do not add a parallel class-keyed cache.
- [x] Cache model annotation, `@EntityId`, `@ParentId` type/path metadata, apply return targets, and handler dependency
  descriptors.
- [x] Validate missing/duplicate model IDs, invalid parent types/paths, ambiguous same-type handler dependencies, and
  statically knowable model cycles with actionable errors.
- [x] Validate missing/ambiguous command payload target IDs when compiling target plans in Slice 2.1.
- [x] Measure startup/reflection impact and record the rejected legacy matcher hooks in the
  [Phase 1 metadata report](dynamic-model-boundaries-phase-1-metadata.md).

### Slice 1.3 — Entity/model abstraction

- [x] Generalize persisted-root vocabulary with `ModelRoot` and aggregate-neutral root configuration metadata so model
  code does not pretend every root is an aggregate.
- [x] Preserve supported `Entity<T>`, `AggregateRoot<T>`, and legacy aggregate call paths without adding model
  discovery to the aggregate hot path.
- [x] Add `Fluxzero.loadModel(...)`, `ModelRepository`, and `TestFixture` model-event vocabulary. The standard
  runtime-backed repository is wired when the model-action protocol lands; there is deliberately no temporary fallback
  through `AggregateRepository`.
- [x] Keep repository keys exactly equal to `ID.toString()` and cover typed and untyped delegation.

## Phase 2 — Action-scoped loading and apply engine

### Slice 2.1 — Target resolution

- [x] Resolve target IDs from a payload property matching the model `@EntityId` name, or one unique typed `Id<T>`.
- [x] Reuse parameter-level `@Association("propertyName")` only when automatic matching is ambiguous or overridden.
- [x] Require only direct target IDs; never inspect or require parent/grandparent IDs for routing.
- [x] Deduplicate exact ID-string identities and reject one identity requested as incompatible model types before loading.
- [x] Define one deduplicated batch-load input and expose its single `readStateIndex` through `ModelActionContext`;
  runtime-backed batch I/O remains tracked in Phase 3.
- [x] Reject unrelated loaded state when constructing an action context; parent, sibling, child, and graph nodes stay
  unloaded unless they are direct targets of another selected handler.

### Slice 2.2 — Injection

- [x] Inject any action-scoped model into `@AssertLegal`.
- [x] Inject any action-scoped model into `@InterceptApply`.
- [x] Inject any action-scoped model into `@Apply`.
- [x] Support both value parameters and `Entity<T>`, including empty wrappers for creation/missing-state decisions.
- [x] Cache direct identity/property resolution within one action context.
- [x] Keep target resolution and the bounded action context independent of live repository state so reconstruction can
  reuse them; historical repository integration remains tracked in Slice 5.1.

### Slice 2.3 — Deterministic execution

- [x] Evaluate all applies for one original event against the same substep begin-state.
- [x] Reject ambiguous duplicate writes to the same target unless their semantics are explicitly combined.
- [x] Execute interceptor expansions as ordered substeps.
- [x] Let later substeps resolve new targets and observe earlier substep results.
- [x] Roll back the complete in-memory action on assertion/apply/interceptor failure.
- [x] Keep evaluation side-effect free so a failed action produces no commit input; runtime rollback and no-store
  integration remain tracked in Slice 3.3.

### Slice 2.4 — Return and lifecycle behavior

- [x] Non-null return upserts only the returned target.
- [x] Null return creates a logical-delete target transition and retains the original event.
- [x] Void return fails startup validation for models.
- [x] Models merely read or injected receive no transition.
- [x] Represent one event targeting several models once in the action result with all target transitions; physical
  global-log and membership storage remains tracked in Slice 3.2.

Phase 2 is complete at the side-effect-free SDK engine boundary. Runtime-backed batch loading, atomic commit, stream
membership, and reconstruction are deliberately not simulated here; their production contracts remain explicit Phase
3 and Phase 5 work.

## Phase 3 — Wire protocol and runtime action commit

### Slice 3.1 — Common protocol

- [x] Add `CommitModelAction` and result types to `common`.
- [x] Carry `actionId`, `readStateIndex`, exact read IDs, ordered original events, resolved storage/publication effects,
  target IDs, and complete desired outgoing relationships.
- [x] Let the runtime reconcile desired relationships against current stored edges so a stale-but-accepted action never
  reopens an edge from its older read view.
- [x] Keep event, state, and per-model sequence indices semantically distinct.
- [x] Add JSON/CBOR compatibility tests, including nullable non-published events, native binary event payloads, and
  forward-compatible unknown fields. Runtime unknown-request behavior remains covered by its generic request handling.

### Slice 3.2 — Runtime storage contract

- [x] Add action-aware model storage interfaces without changing legacy `AppendEvents`.
- [x] Allocate ordered state indices and batch-update current model heads without per-model head queries.
- [x] Append an independently sequenced membership entry to every target stream while storing the serialized payload
  exactly according to the Phase 0 layout; never default to one full payload copy per target.
- [x] Append each publishable original event exactly once to the global event log.
- [x] Make `actionId` idempotent and return the prior result for a duplicate request.
- [x] Store current and historical relationship transitions at the same state boundary.

### Slice 3.3 — JDBC and in-memory implementations

- [x] Add lazy schema provisioning, tables, and indexes for model heads, action idempotency, payloads, streams, and
  temporal relationship intervals. No released predecessor schema exists to migrate.
- [x] Implement the selected stable hash-bucket layout for model streams and heads with verified partition pruning.
- [x] Use one JDBC transaction for model streams, state indices, event log, heads, and relationships when co-located.
- [x] Avoid splitting one action across segment backlogs before the atomic write.
- [x] Use set-based/batched writes with explicit limits and backpressure for large target sets.
- [x] Add in-memory parity.
- [x] Test partial failure, rollback, retry after lost response, duplicate action, restart, and concurrent commits.
- [x] Keep legacy aggregate throughput on its current fast path.

### Slice 3.4 — Direct search completion

- [x] Index/delete each directly changed searchable model before SDK commit completes.
- [x] Load `eventSourced = false` models from that same direct collection.
- [x] Preserve custom collection, timestamp/end path, serialization revision, metadata, and publication behavior.
- [x] Batch direct document mutations per collection where possible.
- [x] Test and document current cross-store partial-failure semantics.

The direct mutation component pre-serializes each final searchable model with the configured document serializer
before the authoritative model commit, then awaits its batched direct mutation. That preserves model type/revision,
message metadata, configured collection and time paths, and avoids discovering a serialization failure only after
authoritative state changed. Slice 5.1 now routes normal handler execution through this component using the pinned
model loader. If the authoritative commit succeeds but the direct mutation fails, an in-process repair entry preserves
the originally evaluated state for an exact same-action retry; process-loss reconciliation remains explicit future
work rather than a claimed cross-store transaction.

### Slice 3.5 — Pinned model-stream batch reads

- [x] Add a public SDK/runtime request that pins current `stateIndex`, model heads, and returned memberships in one
  database snapshot.
- [x] Batch model IDs and per-stream sequence bounds without one request or query per model.
- [x] Return one serialized payload per selected `stateIndex` plus lightweight per-model memberships.
- [x] Ensure the JDBC query fetches and deserializes a shared multi-target payload once, rather than once per target.
- [x] Provide websocket, SDK in-memory, runtime in-memory, and JDBC implementations with structural validation.
- [x] Support exact historical heads in the in-memory reference implementation.
- [x] Resolve exact JDBC historical heads for models that changed after the requested boundary without adding a
  per-transition head-history write to the hot path.
- [x] Add bounded/chunked SDK stream delivery for reconstruction over this protocol and verify its query overhead
  against the Phase 0 load budgets. Applying events, historical dependency injection, action-prefix overlays,
  snapshots, and reconstruction caches remain Slice 5.1.

JDBC current heads, memberships, and unique payloads are loaded in one partition-prunable query. For a model changed
after an explicit boundary, the runtime finds the exact historical sequence through logarithmic exact probes on the
existing `(segment, modelId, sequenceNumber)` primary key. A nullable first-incomplete state index keeps boundaries
before a non-stored transition usable and rejects boundaries at or after the gap. Stored delete state makes historical
heads exact across delete/recreate. This adds no per-transition head row or secondary stream index; same-container A/B
storage measurements showed no physical or WAL regression. The retained load benchmark points to 1,024 models as the
initial SDK reconstruction chunk. The SDK additionally caps a page at 8,192 requested memberships, 128 memberships
per stream, and 8 MiB of unique event payloads. The runtime selects a global `stateIndex` prefix after deduplication,
so every returned stream remains a valid prefix and a shared event counts once. One oversized oldest payload is
allowed through to guarantee progress. JDBC derives its uncompressed size from the existing Fluxzero compression
header, adding no column or hot-path write amplification.

## Phase 4 — Conflict handling (contained side quest)

### Slice 4.1 — Policies

- [x] Add `ACCEPT` as the default policy: do not reject a stale `readStateIndex`.
- [x] Add `FAIL`: runtime rejects and rolls back the complete action if a read/written model head advanced.
- [x] Add `RETRY_IF_RELATIONS_UNCHANGED`: retry only if relevant relationship state still matches the read boundary.
- [x] Return conflicting IDs and current state/relation indices without requiring a client-supplied version per model.

### Slice 4.2 — SDK resolution

- [x] Add a client-side conflict resolver SPI that runs only after runtime rollback.
- [x] Support bounded silent retry through a fresh pinned-evaluation supplier; Slice 5.1 supplies the real model reload.
- [x] Support mapping the conflict to an application error.
- [x] Prevent rejected actions from mutating direct documents and expose the reload seam without introducing a
  provisional second cache abstraction.
- [x] Test single-writer default behavior remains low-overhead.

The retained protocol, atomicity boundary, temporal relation check, SDK retry rules, and measurements are recorded in
[`dynamic-model-boundaries-phase-4-conflicts.md`](dynamic-model-boundaries-phase-4-conflicts.md). Actual model cache
invalidation/refresh belongs to the pinned loader and cache owner introduced by Slice 5.1.

## Phase 5 — Reconstruction, cache, and snapshots

### Slice 5.1 — Model reconstruction

- [x] Wire `ModelActionEngine` and `ModelActionCommitter` into normal command handling through the pinned model loader;
  await direct search mutation before reporting successful completion.
- [x] Connect the Phase 4 conflict reload seam to that loader and evict/refresh its action-scoped cache entries after
  rejected actions and accepted stale evaluations.
- [x] Reconstruct only the requested model's hash-pruned stream using batched/sequential payload resolution from the
  selected Phase 0 layout.
- [x] Resolve cross-model dependencies as-of the stored action `readStateIndex`.
- [x] Reconstruct a later substep from its action `readStateIndex` plus earlier ordered substeps of the same `actionId`;
  never admit unrelated intervening global state.
- [x] Batch and context-cache historical dependency loads.
- [x] Preserve normal self-only replay without dependency I/O.
- [x] Keep snapshots as the primary long-stream optimization.
- [x] Verify cold and warm reconstruction throughput continuously against the Phase 0 load budgets.

### Slice 5.2 — Graph reconstruction bundle

- [x] Pin one `stateIndex`, resolve graph membership as-of that boundary, and batch-load every selected independent
  model stream up to the same boundary.
- [x] Return streams grouped by model plus temporal edges; do not invent a flattened aggregate stream.
- [x] Reconstruct each model independently in the SDK and place it only on explicitly configured graph paths.
- [x] Preserve existing global event-log correlation and VictoriaLogs audit behavior; the graph bundle is
  reconstruction transport, not a second audit log.

### Slice 5.3 — Document-loaded dependency history

- [x] Use stored model events for historical reconstruction even when normal load uses `DocumentStore`.
- [x] Track whether model history is complete without retaining document revisions.
- [x] Enforce the Phase 0 decision for non-stored history gaps.
- [x] Cover logical delete/recreate, unknown events, upcasting, and snapshots. Explicit hard-delete history loss stays
  with the hard-delete API and erasure contract in Phase 8.

### Slice 5.4 — Cache synchronization

- [x] Generalize the useful `CachingAggregateRepository` revision-chain and event-handler boundary behavior for
  independent models; retain one head/suffix path instead of adding a second full global-event tracker.
- [x] Seed accepted local changes exactly and synchronize both published and non-published external changes through
  state/model-head suffix awareness.
- [x] Pin event/notification handler loads to the correct historical state.
- [x] Never use a timeless relationship cache for as-of model reconstruction.
- [x] Benchmark cache hit, miss, catch-up, invalidation, and billion-key pressure assumptions.

### Slice 5.5 — Coherent action materialization

- [x] Extend the action package with pre-serialized optional direct-document mutations and snapshot candidates while
  retaining one event payload regardless of target count.
- [x] Move direct-document completion from the SDK committer into the runtime-owned model-action workflow.
- [x] Keep the no-conflict fast path to one request and no reconstruction round trip.
- [x] Make default `ACCEPT` return a rebase boundary instead of committing stale derived state; preserve the original
  post-interception events and rerun only their `@Apply` handlers against all action-scoped models at that boundary.
- [x] Commit the rebased event package only after a final boundary comparison succeeds; repeat within a configured
  bound if another relevant write wins the race.
- [x] Distinguish a newly committed result from an idempotent duplicate. A fresh-process duplicate must use the
  retained package/completion state and never SDK-reevaluated documents, relationships, cache values, or snapshots.
- [x] Fence every direct model document upsert/delete by the runtime-assigned target `stateIndex`, including a minimal
  delete tombstone, so inverse completion order cannot regress current search state.
- [x] Fence local cache and snapshot installation by `stateIndex`; never label an evaluation from an older receiver
  state with a newer committed sequence number.
- [x] Store the core commit and exact materialization intent in one physical JDBC transaction; complete direct
  documents and snapshots through the same recoverable runtime outbox for both co-located and split search stores,
  without introducing XA.
- [x] Cover stale accepted counters, multi-model applies, inverse document completion, delete resurrection, snapshot
  boundaries, same-process repair, fresh-process duplicate retry, restart, and concurrent runtimes.
- [x] Re-run the integrated 1/2/10/100-target store/load benchmarks and account for document/snapshot bytes,
  transient projection-intent bytes, WAL, and latency separately.

### Slice 5.6 — Type evolution, cache defaults, and parity

- [x] Resolve stored model FQNs through `Serializer.upcastType` before class loading, matching aggregate behavior.
- [ ] Let untyped loads use `Object` and payload-side `@Apply` factories when no explicit model class is supplied;
  require stored type metadata only when reconstruction actually needs model-side handlers.
- [x] Change the new-model cache default to `cachingDepth = 1`; retain deterministic latest-only (`0`) and explicit
  unbounded (`-1`) behavior.
- [x] Measure the retained-heap impact of depth `0` versus `1` for one million hot model keys before closing Slice 5.6.
  The retained diagnostic measured 329.03 MiB at depth `0` and 505.66 MiB at depth `1` for deliberately minimal
  immutable models: +176.63 MiB, or about 185 bytes per actually hot key. This is a 54% increase for the deliberately
  wrapper-heavy minimum, not an unbounded-history multiplier. The shared cache remains count- and
  memory-pressure-bounded.
- [ ] Parameterize applicable aggregate repository, playback, publication, search, snapshot, cache, fixture, and
  runtime integration contracts so every semantically shared behavior runs for both `@Aggregate` and `@Model`.
- [ ] Keep model-specific tests only for intentionally different identity, stream, relationship, action, and lifecycle
  behavior; record every aggregate-only contract that is deliberately inapplicable.

### Slice 5.7 — Assert-and-apply convenience

- [x] Add `Fluxzero.assertAndApply(update)` for model actions, preserving the same action-scoped loading, assertion,
  interceptor, apply, commit, conflict, and result semantics as normal dispatch.
- [x] Enter the model-action engine directly instead of redispatching the update as a command, so an explicit
  `@HandleCommand` may assert-and-apply that same payload without recursion or a second command-handler invocation.
- [x] Wait for durable commit before returning and propagate the original apply/commit failure; include an explicit
  metadata overload and make direct model documents searchable when the call returns.
- [ ] Reuse the same API for aggregates when it can delegate to the existing aggregate execution path without changing
  legacy ordering, publication, or commit behavior. Do not infer an aggregate root merely from an arbitrary typed
  child ID or from one of several IDs; those cases are not equivalent to the existing explicit aggregate load.
- [x] Cover synchronous/asynchronous handling, nested dispatch, failure mapping, unchanged enclosing-handler return
  values, metadata, direct-search visibility, same-payload recursion avoidance, outside-handler execution, and
  `TestFixture`.

## Phase 6 — Temporal DAG relationships and graph loading

### Slice 6.1 — `@ParentId`

- [x] Add multi-property parent metadata; infer the parent model from `Id<T>` and allow an explicit model type for
  untyped IDs.
- [x] Add an optional explicit composition path without deriving a durable path from the Java class name.
- [x] Compute relation deltas only for returned targets.
- [x] Support attach, detach, move, and multiple parents by changing only the child model.
- [ ] Reject cycles at commit with the entire action rolled back.

### Slice 6.2 — Current and historical lookups

- [ ] Query parents, children, roots, ancestors, and descendants at current state.
- [ ] Query the same graph as-of a `stateIndex`.
- [x] Route parent-to-children and child-to-parents lookups to their respective bounded hash partitions.
- [ ] Use half-open validity intervals and deterministic boundary tests.
- [x] Batch breadth/depth graph fetches and enforce protocol safety limits with one partition-pruned query per breadth
  level, never one query per node; retain a recursive single-query variant as a benchmark-driven optimization because
  it requires preserving child-hash pruning across recursion.
- [ ] Benchmark deep, wide, and highly shared DAGs.

### Slice 6.3 — Deleted-parent lineage

- [ ] Relation-cascade incoming edges to detached tombstones when a parent is logically deleted.
- [ ] Keep child model state and stream unchanged.
- [ ] Resolve detached descendants for later lifecycle/GDPR operations.
- [ ] Prevent accidental edge resurrection from an unchanged stale `@ParentId`.
- [ ] Decide protected-ID/retention and purge semantics with privacy tests.

### Slice 6.4 — Ancestor injection

- [x] Inject direct parents into `@AssertLegal`, `@InterceptApply`, and `@Apply` without requiring the command to carry
  redundant parent IDs.
- [x] Resolve grandparents and arbitrary ancestors through one pinned relationship boundary and batch-load their
  independent models.
- [x] Use the parameter type for an unambiguous ancestor and parameter-level `@Association` when multiple reachable
  ancestors of that type exist.
- [x] Detect ambiguous paths, missing required ancestors, cycles, depth/fan-out limits, and typed/untyped IDs with
  actionable errors.
- [x] Cache ancestor traversal and model loads inside the action context; use one runtime request and one batched
  child-partition query per breadth level, not one SDK request or store query per ancestor.
- [x] Overlay `@ParentId` values staged by earlier interceptor substeps, so a later substep observes a move made earlier
  in the same atomic action; use the persisted pre-substep graph boundary during later model reconstruction.

Implementation and measurement details:
[`dynamic-model-boundaries-phase-6-ancestor-injection.md`](dynamic-model-boundaries-phase-6-ancestor-injection.md).

## Phase 7 — Search and CQRS graph projections

### Slice 7.1 — Independent collections

- [x] Make every `searchable = true` model independently searchable without a custom event handler.
- [x] Keep direct search read-after-commit consistency.
- [ ] Support bulk model indexing/deletion and per-model lifecycle.

### Slice 7.2 — Virtual graph search

- [x] Add current-state graph search that can return child models constrained by parent/ancestor documents and roots
  constrained by child/descendant documents.
- [x] Add a bounded ancestor/descendant constraint that composes an ordinary document constraint with relationship
  direction, related collection/type and optional path qualification, and explicit minimum/maximum depth; support
  parent, grandparent, and arbitrary ancestor matching without exposing recursive SQL in the SDK API.
- [ ] Compile co-located JDBC graph predicates, traversal, sorting, and pagination into relational query plans without
  materializing unbounded ID lists in the SDK.
- [ ] Stitch requested current graph documents at read time using only explicitly configured paths and nodes with
  available current documents.
- [ ] Define path collisions, default collection cardinality, deterministic child ordering, and DAG/shared-child
  placement before exposing stitched documents as a stable contract.
- [x] Add bounded staged execution for split relation/search stores, with fan-out/depth/result limits and an actionable
  refusal when materialized CQRS is required.
- [x] Retain a repeatable selective/broad JDBC graph-search benchmark that reports state-boundary, related-query,
  relationship-traversal, and target-query latency separately.
- [ ] Extend graph-search certification to recursive depth, paging, concurrent relationship moves, cold cache, and
  production-scale partition fan-out.
- [x] Defer historical full-text graph search; do not introduce versioned direct documents implicitly.

Implementation and initial measurement details:
[`dynamic-model-boundaries-phase-7-graph-search.md`](dynamic-model-boundaries-phase-7-graph-search.md).

### Slice 7.3 — Materialized graph search document

- [ ] Add an opt-in asynchronous projection for a complete model graph.
- [ ] Consume idempotent model-action/result records.
- [ ] Rebuild affected roots using temporal relations and batched model loads.
- [ ] Let a registered root projection override child-owned paths without changing relationship truth.
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
- [ ] Certify the non-JDBC publish-first event/action visibility race with the intended tracker retry policy; boundary
  loads must fail instead of admitting wrong state until the durable action result is visible.
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
  adjacency, and performance gates; no storage choice had yet been made.
- 2026-07-25 — SDK commit `b0115ace77f` characterizes existing delete behavior in
  `EventSourcingIntegrationTest`: hard delete removes stream, snapshot, direct document, cache entry, and relationships;
  logical delete retains its event history, removes the direct document and relationships, and caches an empty
  aggregate head. Focused test:
  `./mvnw -pl sdk -Dtest=EventSourcingIntegrationTest test` (4 tests passed).
- 2026-07-25 — Phase 0 storage spikes completed and discarded after recording the results in the
  [storage ADR](dynamic-model-boundaries-phase-0-storage-adr.md). Accepted: ordered namespace `stateIndex`, byte-bounded
  commit batching, adaptive inline/shared-row payloads, opportunistic individual LZ4, 32 physical range partitions over
  Fluxzero's stable 128 ID segments, and dual temporal parent/child adjacency. Rejected: per-target unbounded payload
  duplication, shared compressed blocks, native database hash as the durable routing key, and one-directionally
  partitioned relationships.
- 2026-07-25 — Runtime commit `efedc0dd` retains the enhanced real aggregate-path benchmark with logical/physical/WAL,
  compression, latency, warm reconstruction, and mixed-read metrics. Runtime `./mvnw -B install` passed (477 runtime
  tests); SDK `./mvnw -B install` passed, including Java/Kotlin downstream and protocol/fixture suites. Phase 0 is GO
  for the thin vertical implementation only; production hardware, steady-state operations, and the integrated model
  path remain explicit release gates.
- 2026-07-25 — SDK commits `7f4f3fc6a55` and `ff77f4f5e21` add the independent `@Model` contract, child-owned typed
  `@ParentId`/path metadata, centrally cached handler/model metadata, Java/Kotlin downstream coverage, and the
  [Phase 1 measurements](dynamic-model-boundaries-phase-1-metadata.md). Full SDK reactor and Javadoc passed; measured
  legacy aggregate discovery and apply throughput showed no systematic regression.
- 2026-07-25 — SDK commit `bdbd71e4786` adds aggregate-neutral `ModelRoot`/root configuration, exact-ID
  `ModelRepository` and `Fluxzero.loadModel` APIs, and model-specific `TestFixture` vocabulary without routing models
  through the legacy aggregate protocol. Focused contracts, full reactor, Javadoc, and downstream builds passed.
- 2026-07-25 — SDK commit `24ee120adc1` compiles retained direct target plans for receiver, parameter, and apply-return
  models; exact-name, unique typed-ID, and `@Association` resolution; global-ID deduplication; and deferred same-type
  write selection. Focused tests, Javadoc, and the full SDK reactor passed. The retained resolver measured 13.3 ns /
  104 bytes for one target and 73.4 ns / 504 bytes for two cross-model targets; the allocation-heavy predecessor was
  discarded and is documented in the [Phase 2 report](dynamic-model-boundaries-phase-2-action-loading.md).
- 2026-07-25 — SDK commit `ac2922b5f53` adds exact action-scoped model/value injection without touching legacy
  aggregate discovery. Automatic and qualified context lookup retained zero allocation at 2.73 ns and 4.59 ns median.
- 2026-07-25 — Phase 2 completed at the side-effect-free SDK action boundary: deterministic cross-model applies,
  ordered interceptor substeps, logical delete, receiver-side handlers, before/after assertions, action-prefix
  reconstruction semantics, and complete in-memory rollback. Focused tests (39), Javadoc, and the full SDK reactor
  passed. The retained complete one-write action measured 305.7 ns / 2,520 bytes median; details are in the
  [Phase 2 report](dynamic-model-boundaries-phase-2-action-loading.md).
- 2026-07-25 — Runtime commits `41a57adc` and `b17806d2` complete authoritative storage Slices 3.2/3.3: lazy partitioned
  JDBC schema, ordered namespace state indices, set-based heads/streams/actions/relationships, adaptive inline/shared
  payloads, compact durable idempotency results, one-transaction global publication when co-located, in-memory parity,
  and explicit per-action/pending-byte overload protection. Full runtime module passed (501 tests); the focused suite
  additionally found and fixed multi-parent selective detach being misclassified as a move. The retained diagnostic
  benchmark measured 8,839 actions/s for one 1-KiB target, 31,051 memberships/s for ten targets, 39,940 memberships/s
  for one hundred 16-KiB targets, and 18,704 memberships/s with one relationship per target. Details and limitations
  are in the [Phase 3 storage report](dynamic-model-boundaries-phase-3-storage.md). Direct search remains Slice 3.4.
- 2026-07-25 — SDK commit `e4674571875` and runtime commit `2b77b7dd` add the action commit transport, synchronous
  direct-document mutation component, document-based model repository, and pinned batched model-stream reads.
  Multi-target reads carry and deserialize one shared payload while retaining independent stream memberships. The SDK
  `common`/`sdk` reactor passed 1,800 tests; the complete runtime module passed 508 tests. SDK and runtime in-memory
  stores enforce all-or-nothing publication/state application. JDBC current reads pin state, heads, and memberships in
  one repeatable-read transaction. Exact JDBC historical heads after intervening model changes remain deliberately
  fail-fast and open in Slice 3.5 before Slice 5.1 reconstruction.
- 2026-07-25 — Runtime commit `bf106284` completes the exact JDBC as-of-head item in Slice 3.5. A first-gap marker and
  stored delete bit preserve reconstructibility before a non-stored transition and delete/recreate state without a
  head-history row per transition. Changed historical heads use logarithmic exact probes on the existing stream
  primary key; current heads, memberships, and unique payloads share one query. Same-container A/B measurements found
  identical 10.50-MiB physical storage for 5,000 one-target actions and equivalent throughput/WAL. The retained public
  load measured 64,223 current models/s with 1,024-model batches; a 100,000-event head at the midpoint resolved in
  6.2 ms warm. The complete runtime module passed 511 tests. Byte-bounded SDK reconstruction remained open at this
  checkpoint.
- 2026-07-25 — Slice 3.5 stream delivery (`SDK b51cd43ba38`, runtime `8218acbb`) is byte- and membership-bounded end to
  end. `GetModelEvents.maxBytes` is a deduplication-aware total response-payload limit; zero preserves the earlier
  unlimited request behavior. The runtime
  applies it before deserialization and extracts uncompressed size from the existing compression header, so no
  storage column or write amplification was added. The SDK pins the first response boundary across 1,024-model chunks
  and validates heads, sequence continuity, action metadata, payload references, limits, and forward progress page by
  page. Ten repeated reads of 10,000 one-event models measured 78,483 models/s without a byte cap and 76,490 models/s
  with an inactive 8-MiB cap: 2.5% overhead in this local warm-cache run for computing the safe global/byte prefix.
  The complete SDK reactor and all 516 runtime-module tests passed; the benchmark reactor also test-compiled.
- 2026-07-26 — Phase 4 (`SDK 2b59b35d0d5`, runtime `a2a6d6d5`) adds optional global-read-boundary conflict handling
  without making it the model design frame: `ACCEPT` remains the zero-rejection default; strict policies roll back the
  whole runtime action and may fail or retry after a fresh pinned load, optionally only while relationships remain
  unchanged. Details are in the [Phase 4 report](dynamic-model-boundaries-phase-4-conflicts.md).
- 2026-07-26 — Phase 5 (`SDK 0249394ba5c`, runtime `e0ea56e1`) connects independent model actions to normal local and
  tracked command handling; reconstructs exact self/cross-model state through bounded hash-pruned stream pages,
  snapshots, cache suffixes, and action-prefix views; makes direct searchable documents visible before command success;
  and returns grouped temporal graph bundles with explicit child-owned paths. Existing compact action results pin
  event-handler loads without a second event→state table. The complete SDK reactor, site/Javadoc reactor, Java/Kotlin
  downstream projects, and complete runtime reactor passed; runtime reported 528 tests. The retained local integrated
  diagnostics measured 9,193 one-target actions/s, 18,925 ten-target memberships/s, roughly 19–25k model loads/s, and
  220,714 SDK replayed events/s. Full evidence and limitations are in the
  [Phase 5 report](dynamic-model-boundaries-phase-5-reconstruction.md). Production 100-GB/min certification,
  action-result retention/archival, and the explicit non-JDBC publish-first visibility race remain Phase 9 gates.
- 2026-07-26 — Coherent action materialization (`SDK 0b9b74b0764`, runtime `39cb88e1`) sends original events, optional
  direct documents, due snapshots, and relationships as one model-action package. The core JDBC commit durably retains
  exact compressed recovery intent; direct search and snapshots complete synchronously through state-index fences and
  survive restart without SDK reevaluation. Default `ACCEPT` now preserves the original post-interception events while
  reapplying only `@Apply` against a fresh pinned boundary. The retained 1/2/10/100-target comparison found no
  systematic store/load, physical-byte, or WAL regression. One million minimal hot keys measured 329.03 MiB at cache
  depth `0` and 505.66 MiB at depth `1`, supporting the default of one predecessor while keeping the shared cache
  bounded. Full SDK, site/Javadoc, downstream, and runtime reactors passed; runtime reported 538 tests. A separate
  regression review retained public registry constructors, legacy snapshot readability, metric compatibility, and
  document/snapshot-aware runtime backpressure.
- 2026-07-26 — Direct assert-and-apply (`SDK 51dc3fd3b84`) adds synchronous
  `Fluxzero.assertAndApply(update[, metadata])`. It enters the independent-model action engine without command
  redispatch, so an explicit handler can apply its own payload exactly once; it returns only after durable commit and
  direct-search visibility, while preserving the enclosing handler result and original failures. Synchronous,
  asynchronous-result, nested-dispatch, metadata, failure, and outside-handler fixture paths are covered. The complete
  SDK and site/Javadoc reactors passed, including test-server, proxy, annotation processing, and Java/Kotlin downstream
  projects. Aggregate inference remains deliberately open because a typed child ID or one of several IDs does not
  safely identify the aggregate root.
- 2026-07-26 — Ancestor injection (`SDK f408c5d7e10`, runtime `1424343c`) resolves parents, grandparents, and arbitrary
  read-only ancestors for `@AssertLegal`, `@InterceptApply`, and `@Apply` through one pinned temporal graph request.
  `@Association` remains a direct payload-property qualifier when that property exists and otherwise qualifies an
  explicit `@ParentId(path = ...)` edge. Same-action moves overlay staged child relations; cold reconstruction resolves
  the pre-event graph and original action-prefix state. Stored FQNs use the serializer's existing upcasting/type-caster
  chain and remain optional metadata rather than identity. The direct action path performs no graph lookup. Full SDK,
  site/Javadoc, downstream, and runtime reactors passed; runtime reported 540 tests. The retained local JDBC diagnostic
  measured 25,479 ancestor roots/s and 4.797 / 5.363 / 7.002 ms p50/p95/p99 latency for 128-root batches. Deep/wide DAG
  certification and payload-only untyped `Object` reconstruction remain open.
- 2026-07-26 — Current graph search (`SDK 7c83d714573`, runtime `619ceeee`) adds bounded parent/ancestor and
  child/descendant document constraints without requiring a materialized whole-tree projection. A distinct wire
  request preserves old-runtime failure semantics; split stores use related-document search, one durable relationship
  boundary, hash-pruned traversal, and a candidate-constrained ordinary target query. Null and wire-normalized empty
  internal ID lists preserve ordinary search compatibility, while empty graph candidates short-circuit before target
  search. Full SDK/site/downstream and runtime reactors passed; runtime reported 546 tests. The retained 5,000-model
  diagnostic measured 1.462 / 1.785 / 1.895 ms selective and 66.260 / 72.755 / 74.959 ms broad p50/p95/p99, with target
  document retrieval dominating the broad result. Co-located recursive query compilation, stitched current documents,
  recursive/paging/move certification, and historical full-text graph search remain open.
