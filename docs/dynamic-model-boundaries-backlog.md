# Dynamic model boundaries

Tickable implementation backlog for the coordinated SDK and runtime refactor.

- SDK branch: `feature/dynamic-model-boundaries`
- SDK base: `964fdcdac4b5beeffdf155558c9af6a6b944e1ab`
- Runtime branch: `feature/dynamic-model-boundaries`
- Runtime base: `a02bd72c2b3a8981fb2ace3a5dbb63cafa7e77ab`
- Started: 2026-07-25

## Outcome

Replace the fixed aggregate consistency boundary for new models with an commit-scoped boundary. Independently stored
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
- `@Model.eventPublication` defaults to `IF_MODIFIED`: an unchanged result produces no model-stream or global event.
  Use an explicit model- or apply-level `ALWAYS` for intentional no-op domain notifications. Legacy aggregate defaults
  remain unchanged.
- A searchable model is synchronously indexed/deleted as part of commit completion. A successful commit therefore
  makes the directly changed model immediately searchable, matching current searchable aggregate behavior.
- Related current model documents can be queried and stitched as a virtual graph at read time when their
  `@ParentId` declarations provide composition paths. A persisted complete tree/graph document remains a separate,
  durably asynchronous and rebuildable CQRS optimization. Projection execution never joins the model database
  transaction; configurable completion controls whether a request result waits for the affected root documents.
- The authoritative document for `eventSourced = false` remains the ordinary `DocumentStore` record. No versioned
  document history or second `ModelStateStore` is introduced.
- Storage identity is exactly `Id.toString()` (or the equivalent untyped ID string). `@Model` has no name that is
  concatenated into the key. Existing ID prefix/type conventions remain responsible for uniqueness.
- Stored model type is optional reconstruction metadata, never part of model identity. A typed load uses its supplied
  Java type. An untyped `Object` request may infer its runtime model type from the first stored payload's `@Apply`
  factory; otherwise reconstruction may use the stored model type. Stored fully-qualified type names are resolved
  through the serializer's existing chained type-caster/upcasting registry before class loading, exactly as for
  aggregate relationship types.
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
- `@AssertLegal`, `@InterceptApply`, and `@Apply` may inject every commit-scoped model as either its value or
  `Entity<T>`.
- Assertions and applies may read multiple loaded models.
- Applies produced by one domain event all observe the same substep begin-state. Their results are not implicitly
  ordered.
- `@InterceptApply` expansions are ordered substeps. Later substeps observe committed-in-memory results from earlier
  substeps, while the complete handler commit still commits or rolls back as one unit.
- Only a model returned by an `@Apply` is a target of that event in a model stream.
- An `@Apply` may return an ordered typed model collection or a runtime-validated heterogeneous
  `Collection<Object>`. Every returned identity is one target in the same atomic substep; duplicates, null elements,
  non-model runtime values, and creation collisions fail the complete operation.
- A non-null return upserts the target model and stores the event according to its publication strategy.
- A `null` return logically deletes the target model and still stores/publishes that original event according to its
  publication strategy.
- `void` applies are rejected for `@Model`. Legacy mutable `@Aggregate` behavior is unchanged.
- One original domain event is published at most once in the global event log, even when it is stored in multiple model
  streams.
- One model-commit request carries every precomputed consequence of the commit: original events, target memberships,
  desired relationships, optional direct-document mutations, and optional snapshots. The runtime owns their ordering
  and completion; the SDK does not independently race direct document writes after an accepted commit.
- `ACCEPT` means that a stale business event is not rejected. It never means that stale derived documents,
  relationships, snapshots, or cache entries may be installed. On a stale read, the original event is retained while
  its already-produced post-interception events are reapplied against one newly pinned merged model boundary.
  Assertions, command handling, and `@InterceptApply` expansion are not rerun.
- Conflict policy supports application, model, and apply scopes. `DEFAULT` inherits, `ACCEPT` retains the stale event,
  `FAIL` rolls back without automatic retry, and `RETRY` rolls back and permits a bounded complete reevaluation even
  when relationships changed. Resolve each participant's override first and then combine one atomic commit using
  `FAIL > RETRY > ACCEPT`; one participant can never silently weaken another participant's stricter policy.
- Automatic command-to-model handling is enabled by default but can be disabled application-wide, per `@Model`, or per
  `@Apply`. This controls only whether the automatic command registry claims a message; explicit
  `Fluxzero.assertAndApply`, event-sourced reconstruction, and ordinary handler invocation remain available.
- Direct model document upserts and deletes carry their target `stateIndex` as a monotone write fence. A late older
  mutation is a no-op; deletes retain the minimum fence tombstone needed to prevent resurrection.
- `@Model.cachingDepth` defaults to `1`, retaining the latest and one previous revision so event handlers can compare
  changes through `Entity.previous()`. `0` remains an explicit latest-only choice and `-1` remains explicit unbounded
  history.
- No special websocket capability handshake is added. An old runtime rejects the new model-commit request through the
  existing unsupported-request behavior.

## Vocabulary and ordering

These terms must remain distinct in APIs, documentation, and tests.

- **event-log replay**: historical global events delivered to `@HandleEvent`.
- **model reconstruction / event sourcing**: rebuilding one model by applying its model stream through `@Apply`.
- **sequenceNumber**: position within one model stream.
- **eventIndex**: `SerializedMessage#index`, the existing global event-log position. It only exists for published
  messages.
- **stateIndex**: a new, namespace-wide, time-derived monotone position assigned to every model state transition,
  including `STORE_ONLY`, `PUBLISH_ONLY`, and `EventPublication.NEVER` transitions that have no event-log position. It
  uses the same millisecond-plus-offset encoding as `IndexUtils`, but remains a separate namespace from `eventIndex`.
- **commitId**: durable idempotency key grouping all state transitions in one handler commit.
- **readStateIndex**: the single state boundary pinned when an commit begins.

No separate per-model version vector is the primary consistency mechanism. The commit contains the IDs it read and one
`readStateIndex`. The runtime can detect a stale read by checking whether any listed model head advanced beyond that
boundary. A model-head record stores only current coordination metadata such as `lastStateIndex`; it is not document
version history.

An intercepted commit may yield several original events. Each event/substep receives its own ordered `stateIndex`; all
share the same `commitId`. If an event is published, its independently assigned `eventIndex` is recorded alongside the
state transition. Every target stream entry for that original event shares those identities. Both index kinds can be
mapped to an approximate millisecond timestamp, but their numeric values must never be compared as one total order.

## Historical dependency invariant

When an event at `stateIndex = N` is used to reconstruct target model `A`, injected model `B` must be loaded from the
same logical state that its original substep observed, never from its current head.

The intended mechanism is:

1. Pin one persisted `readStateIndex = S` when the commit starts.
2. Evaluate ordered interceptor substeps against state as-of `S`, overlaid with successful earlier substeps from the
   same commit.
3. Store `S`, `commitId`, and the ordered substep identity with every target stream entry.
4. During reconstruction, load injected dependencies as-of `S` and overlay earlier substeps of the same commit. Do not
   substitute `stateIndex - 1`: with the default stale-read acceptance policy that could include unrelated changes
   committed after `S` which the original commit never observed.
5. Batch and cache both base dependency loads and commit-prefix overlays in the reconstruction context.
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

- `CommitModels` is one runtime request carrying `commitId`, `readStateIndex`, read IDs, original events, target
  model IDs, desired relationships, optional direct current-document mutations, and optional snapshot candidates.
- The JDBC runtime fast path stores model-stream entries, model heads, state indices, event-log publications, and
  relationship intervals in one transaction where those facilities share the same database.
- In-memory stores provide the same observable all-or-nothing contract.
- The protocol and storage interfaces do not assume that search shares that transaction. The first JDBC implementation
  writes the exact direct-document/snapshot materialization intent in the core commit transaction and completes it
  through a runtime-owned outbox. Direct current documents use monotone fences and immutable snapshots are idempotent;
  this remains correct for co-located and split stores without claiming XA. Folding co-located search into the core
  transaction remains a later optimization, not a different protocol.
- Direct model search indexing/deletion is completed by the runtime before model-commit success, exactly as it is for
  current aggregates. A split-store failure may still occur after authoritative event/model storage has succeeded, but
  a retry is resolved from the retained commit package and must never use a fresh SDK reevaluation.
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
- Composite graph projections execute asynchronously, idempotently, and rebuildably. `DEFAULT`, `ASYNC`, and `AWAIT`
  completion policies affect result publication only: `AWAIT` waits for the affected projection tasks and fences
  without pretending that model and search stores share one transaction. The active consumer's existing
  `awaitAsyncResults` setting independently determines whether its batch progress also waits.
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
- [x] Prove one commit can allocate an ordered range for interceptor substeps.
- [x] Prove an commit-pinned `readStateIndex` can detect relevant intervening model writes using only read IDs plus the
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
- [x] Exercise hot, uniformly distributed, and one-million-ID short streams with 1, 2, 10, and 100 targets per commit.
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
- [x] Prove one commit uses set-based/batched statements and bounded round trips rather than one query, write, or
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
- [x] Prove both adjacency projections change atomically together in JDBC and specify their inclusion in the model-commit
  transaction and rebuild from authoritative commit/relation history.
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
- [x] Pass the Phase 0 decision gate for a thin vertical implementation. Do not freeze broad `CommitModels`
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
  runtime-backed repository is wired when the model-commit protocol lands; there is deliberately no temporary fallback
  through `AggregateRepository`.
- [x] Keep repository keys exactly equal to `ID.toString()` and cover typed and untyped delegation.

## Phase 2 — Commit-scoped loading and apply engine

### Slice 2.1 — Target resolution

- [x] Resolve target IDs from a payload property matching the model `@EntityId` name, or one unique typed `Id<T>`.
- [x] Reuse parameter-level `@Association("propertyName")` only when automatic matching is ambiguous or overridden.
- [x] Require only direct target IDs; never inspect or require parent/grandparent IDs for routing.
- [x] Deduplicate exact ID-string identities and reject one identity requested as incompatible model types before loading.
- [x] Define one deduplicated batch-load input and expose its single `readStateIndex` through `ModelCommitContext`;
  runtime-backed batch I/O remains tracked in Phase 3.
- [x] Reject unrelated loaded state when constructing an commit context; parent, sibling, child, and graph nodes stay
  unloaded unless they are direct targets of another selected handler.

### Slice 2.2 — Injection

- [x] Inject any commit-scoped model into `@AssertLegal`.
- [x] Inject any commit-scoped model into `@InterceptApply`.
- [x] Inject any commit-scoped model into `@Apply`.
- [x] Support both value parameters and `Entity<T>`, including empty wrappers for creation/missing-state decisions.
- [x] Cache direct identity/property resolution within one commit context.
- [x] Keep target resolution and the bounded commit context independent of live repository state so reconstruction can
  reuse them; historical repository integration remains tracked in Slice 5.1.

### Slice 2.3 — Deterministic execution

- [x] Evaluate all applies for one original event against the same substep begin-state.
- [x] Reject ambiguous duplicate writes to the same target unless their semantics are explicitly combined.
- [x] Execute interceptor expansions as ordered substeps.
- [x] Let later substeps resolve new targets and observe earlier substep results.
- [x] Roll back the complete in-memory commit on assertion/apply/interceptor failure.
- [x] Keep evaluation side-effect free so a failed commit produces no commit input; runtime rollback and no-store
  integration remain tracked in Slice 3.3.

### Slice 2.4 — Return and lifecycle behavior

- [x] Non-null return upserts only the returned target.
- [x] Null return creates a logical-delete target transition and retains the original event.
- [x] Void return fails startup validation for models.
- [x] Models merely read or injected receive no transition.
- [x] Represent one event targeting several models once in the commit result with all target transitions; physical
  global-log and membership storage remains tracked in Slice 3.2.

Phase 2 is complete at the side-effect-free SDK engine boundary. Runtime-backed batch loading, atomic commit, stream
membership, and reconstruction are deliberately not simulated here; their production contracts remain explicit Phase
3 and Phase 5 work.

## Phase 3 — Wire protocol and runtime commit commit

### Slice 3.1 — Common protocol

- [x] Add `CommitModels` and result types to `common`.
- [x] Carry `commitId`, `readStateIndex`, exact read IDs, ordered original events, resolved storage/publication effects,
  target IDs, and complete desired outgoing relationships.
- [x] Let the runtime reconcile desired relationships against current stored edges so a stale-but-accepted commit never
  reopens an edge from its older read view.
- [x] Keep event, state, and per-model sequence indices semantically distinct.
- [x] Add JSON/CBOR compatibility tests, including nullable non-published events, native binary event payloads, and
  forward-compatible unknown fields. Runtime unknown-request behavior remains covered by its generic request handling.

### Slice 3.2 — Runtime storage contract

- [x] Add commit-aware model storage interfaces without changing legacy `AppendEvents`.
- [x] Allocate ordered state indices and batch-update current model heads without per-model head queries.
- [x] Append an independently sequenced membership entry to every target stream while storing the serialized payload
  exactly according to the Phase 0 layout; never default to one full payload copy per target.
- [x] Append each publishable original event exactly once to the global event log.
- [x] Make `commitId` idempotent and return the prior result for a duplicate request.
- [x] Store current and historical relationship transitions at the same state boundary.

### Slice 3.3 — JDBC and in-memory implementations

- [x] Add lazy schema provisioning, tables, and indexes for model heads, commit idempotency, payloads, streams, and
  temporal relationship intervals. No released predecessor schema exists to migrate.
- [x] Implement the selected stable hash-bucket layout for model streams and heads with verified partition pruning.
- [x] Use one JDBC transaction for model streams, state indices, event log, heads, and relationships when co-located.
- [x] Avoid splitting one commit across segment backlogs before the atomic write.
- [x] Use set-based/batched writes with explicit limits and backpressure for large target sets.
- [x] Add in-memory parity.
- [x] Test partial failure, rollback, retry after lost response, duplicate commit, restart, and concurrent commits.
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
the originally evaluated state for an exact same-commit retry; process-loss reconciliation remains explicit future
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
  against the Phase 0 load budgets. Applying events, historical dependency injection, commit-prefix overlays,
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
- [x] Add `FAIL`: runtime rejects and rolls back the complete commit if a read/written model head advanced.
- [x] Add `RETRY_IF_RELATIONS_UNCHANGED`: retry only if relevant relationship state still matches the read boundary.
- [x] Return conflicting IDs and current state/relation indices without requiring a client-supplied version per model.

### Slice 4.2 — SDK resolution

- [x] Add a client-side conflict resolver SPI that runs only after runtime rollback.
- [x] Support bounded silent retry through a fresh pinned-evaluation supplier; Slice 5.1 supplies the real model reload.
- [x] Support mapping the conflict to an application error.
- [x] Prevent rejected commits from mutating direct documents and expose the reload seam without introducing a
  provisional second cache abstraction.
- [x] Test single-writer default behavior remains low-overhead.

The retained protocol, atomicity boundary, temporal relation check, SDK retry rules, and measurements are recorded in
[`dynamic-model-boundaries-phase-4-conflicts.md`](dynamic-model-boundaries-phase-4-conflicts.md). Actual model cache
invalidation/refresh belongs to the pinned loader and cache owner introduced by Slice 5.1.

The checked items above record the initial contained conflict implementation. The post-certification API review in
Phase 10 replaces relationship-gated retry with a simpler scoped `DEFAULT`/`ACCEPT`/`FAIL`/`RETRY` contract before the
feature is released.

## Phase 5 — Reconstruction, cache, and snapshots

### Slice 5.1 — Model reconstruction

- [x] Wire `ModelCommitEngine` and `ModelCommitter` into normal command handling through the pinned model loader;
  await direct search mutation before reporting successful completion.
- [x] Connect the Phase 4 conflict reload seam to that loader and evict/refresh its commit-scoped cache entries after
  rejected commits and accepted stale evaluations.
- [x] Reconstruct only the requested model's hash-pruned stream using batched/sequential payload resolution from the
  selected Phase 0 layout.
- [x] Resolve cross-model dependencies as-of the stored commit `readStateIndex`.
- [x] Reconstruct a later substep from its commit `readStateIndex` plus earlier ordered substeps of the same `commitId`;
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

### Slice 5.5 — Coherent commit materialization

- [x] Extend the commit package with pre-serialized optional direct-document mutations and snapshot candidates while
  retaining one event payload regardless of target count.
- [x] Move direct-document completion from the SDK committer into the runtime-owned model-commit workflow.
- [x] Keep the no-conflict fast path to one request and no reconstruction round trip.
- [x] Make default `ACCEPT` return a rebase boundary instead of committing stale derived state; preserve the original
  post-interception events and rerun only their `@Apply` handlers against all commit-scoped models at that boundary.
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
- [x] Let untyped loads use `Object` and payload-side `@Apply` factories when no explicit model class is supplied;
  require stored type metadata only when reconstruction actually needs model-side handlers.
- [x] Change the new-model cache default to `cachingDepth = 1`; retain deterministic latest-only (`0`) and explicit
  unbounded (`-1`) behavior.
- [x] Measure the retained-heap impact of depth `0` versus `1` for one million hot model keys before closing Slice 5.6.
  The retained diagnostic measured 329.03 MiB at depth `0` and 505.66 MiB at depth `1` for deliberately minimal
  immutable models: +176.63 MiB, or about 185 bytes per actually hot key. This is a 54% increase for the deliberately
  wrapper-heavy minimum, not an unbounded-history multiplier. The shared cache remains count- and
  memory-pressure-bounded.
- [x] Parameterize applicable aggregate repository, playback, publication, search, snapshot, cache, fixture, and
  runtime integration contracts so every semantically shared behavior runs for both `@Aggregate` and `@Model`.
- [x] Keep model-specific tests only for intentionally different identity, stream, relationship, commit, and lifecycle
  behavior; record every aggregate-only contract that is deliberately inapplicable.

The completed contract matrix and the deliberately non-shared cases are recorded in
[`dynamic-model-boundaries-phase-5-parity.md`](dynamic-model-boundaries-phase-5-parity.md). The shared executable
fixture covers event-sourced lifecycle, direct search visibility, previous revisions, logical delete/recreate,
document-based loading with stored events, and publication-free current documents. A real runtime integration test
covers model commit/load/search, ancestor injection, and exactly-once global EVENT delivery over websocket.

### Slice 5.7 — Assert-and-apply convenience

- [x] Add `Fluxzero.assertAndApply(update)` for model commits, preserving the same commit-scoped loading, assertion,
  interceptor, apply, commit, conflict, and result semantics as normal dispatch.
- [x] Enter the model-commit engine directly instead of redispatching the update as a command, so an explicit
  `@HandleCommand` may assert-and-apply that same payload without recursion or a second command-handler invocation.
- [x] Wait for durable commit before returning and propagate the original apply/commit failure; include an explicit
  metadata overload and make direct model documents searchable when the call returns.
- [x] Evaluate reuse of the same API for aggregates. Global aggregate inference was deliberately not added: an
  arbitrary typed child ID or one of several IDs cannot identify the aggregate root without changing legacy load,
  ordering, publication, or commit behavior. Existing explicit aggregate apply paths remain the safe API.
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
- [x] Reject cycles at commit with the entire commit rolled back.

### Slice 6.2 — Current and historical lookups

- [x] Query parents, children, roots, ancestors, and descendants at current state.
- [x] Query the same graph as-of a `stateIndex`.
- [x] Route parent-to-children and child-to-parents lookups to their respective bounded hash partitions.
- [x] Use half-open validity intervals and deterministic boundary tests.
- [x] Batch breadth/depth graph fetches and enforce protocol safety limits with one partition-pruned query per breadth
  level, never one query per node; retain a recursive single-query variant as a benchmark-driven optimization because
  it requires preserving child-hash pruning across recursion.
- [x] Benchmark deep, wide, and highly shared DAGs; retain partition-pruned breadth batching as the default and record
  the measured extreme-depth round-trip cost plus the storage trade-off required by an adaptive recursive variant.

Integrity, temporal-boundary, and hot-path details:
[`dynamic-model-boundaries-phase-6-temporal-graph.md`](dynamic-model-boundaries-phase-6-temporal-graph.md).

### Slice 6.3 — Deleted-parent lineage

- [x] Relation-cascade incoming edges to detached tombstones when a parent is logically deleted.
- [x] Keep child model state and stream unchanged.
- [x] Resolve detached descendants for later lifecycle/GDPR operations.
- [x] Prevent accidental edge resurrection from an unchanged stale `@ParentId`.
- [x] Keep the exact deleted parent ID only in temporal lifecycle metadata after logical delete; exclude it from current
  graph/search results and cover the lifecycle-only visibility boundary with privacy-focused tests.
- [x] Protect retained lineage IDs with a persisted HMAC key and define final `NONE`/`DESCENDANTS` erasure semantics,
  including inclusive shared-DAG descendants, exact-ID reuse rejection, and lifecycle-only tombstones.

### Slice 6.4 — Ancestor injection

- [x] Inject direct parents into `@AssertLegal`, `@InterceptApply`, and `@Apply` without requiring the command to carry
  redundant parent IDs.
- [x] Resolve grandparents and arbitrary ancestors through one pinned relationship boundary and batch-load their
  independent models.
- [x] Use the parameter type for an unambiguous ancestor and parameter-level `@Association` when multiple reachable
  ancestors of that type exist.
- [x] Detect ambiguous paths, missing required ancestors, cycles, depth/fan-out limits, and typed/untyped IDs with
  actionable errors.
- [x] Cache ancestor traversal and model loads inside the commit context; use one runtime request and one batched
  child-partition query per breadth level, not one SDK request or store query per ancestor.
- [x] Overlay `@ParentId` values staged by earlier interceptor substeps, so a later substep observes a move made earlier
  in the same atomic commit; use the persisted pre-substep graph boundary during later model reconstruction.

Implementation and measurement details:
[`dynamic-model-boundaries-phase-6-ancestor-injection.md`](dynamic-model-boundaries-phase-6-ancestor-injection.md).

## Phase 7 — Search and CQRS graph projections

### Slice 7.1 — Independent collections

- [x] Make every `searchable = true` model independently searchable without a custom event handler.
- [x] Keep direct search read-after-commit consistency.
- [x] Support mixed bulk model indexing/deletion through the commit materialization package, with an independent
  monotone fence and lifecycle for every `(collection, modelId)` rather than one aggregate-sized document operation.

### Slice 7.2 — Virtual graph search

- [x] Add current-state graph search that can return child models constrained by parent/ancestor documents and roots
  constrained by child/descendant documents.
- [x] Add a bounded ancestor/descendant constraint that composes an ordinary document constraint with relationship
  direction, related collection/type and optional path qualification, and explicit minimum/maximum depth; support
  parent, grandparent, and arbitrary ancestor matching without exposing recursive SQL in the SDK API.
- [x] Evaluate co-located JDBC query compilation against the retained staged plan. Keep the bounded, set-based staged
  plan for the first release: it works for both split and co-located stores, broad profiles are dominated by ordinary
  document loading, and a second execution engine would add divergent pagination semantics without measured benefit.
- [x] Stitch requested current graph documents at read time using only explicitly configured paths and nodes with
  available current documents.
- [x] Define path collisions, default collection cardinality, deterministic child ordering, and DAG/shared-child
  placement before exposing stitched documents as a stable contract.
- [x] Add bounded staged execution for split relation/search stores, with fan-out/depth/result limits and an actionable
  refusal when materialized CQRS is required.
- [x] Retain a repeatable selective/broad JDBC graph-search benchmark that reports state-boundary, related-query,
  relationship-traversal, and target-query latency separately.
- [x] Extend local graph-search certification across deep/wide/shared DAGs, ordinary target paging, relationship moves,
  stitching, and partition fan-out. Preserve absolute cold-cache and production-distribution capacity as deployment
  gates rather than presenting Docker Desktop as production certification.
- [x] Defer historical full-text graph search; do not introduce versioned direct documents implicitly.

Implementation and initial measurement details:
[`dynamic-model-boundaries-phase-7-graph-search.md`](dynamic-model-boundaries-phase-7-graph-search.md).

### Slice 7.3 — Materialized graph search document

- [x] Add an opt-in asynchronous projection for a complete model graph.
- [x] Consume idempotent model-commit/result records.
- [x] Rebuild affected roots using temporal relations and batched model loads.
- [x] Let a registered root projection override child-owned paths without changing relationship truth.
- [x] Handle multi-parent fan-out, moves, deletes, late delivery, duplicate delivery, and rebuild.
- [x] Expose projection lag/high-watermark where callers need freshness awareness.

Implementation, consistency, performance, and rollout details:
[`dynamic-model-boundaries-phase-7-graph-search.md`](dynamic-model-boundaries-phase-7-graph-search.md).

## Phase 8 — Explicit hard delete and GDPR

### Slice 8.1 — Delete API

- [x] Add `deleteModel(id, NONE)` and planned `deleteModel(..., DESCENDANTS)`.
- [x] Make cascade mode mandatory at the wire boundary; `NONE` may execute directly, while `DESCENDANTS` requires a
  matching dry-run fingerprint.
- [x] Add a bounded dry-run plan with counts, published-event disclosure, externally shared-descendant count, and a
  deterministic sample.
- [x] Define DAG shared-descendant behavior: an explicit descendant cascade is inclusive and deletes every reachable
  model exactly once, including models with surviving parents outside the selected set.

### Slice 8.2 — Physical cleanup

- [x] Delete the selected streams, snapshots, cache entries, and direct documents.
- [x] Traverse current plus parent-deletion-detached lineage for descendant cascade.
- [x] Coordinate relationship tombstone retention and final purge.
- [x] Define what historical reconstruction and graph projections report after intentional erasure.
- [x] Test retries/idempotency, partial cross-store failure, huge cascades, and resumable cleanup.

Detailed erasure, safety, lineage-protection, and resumability contract:
[`dynamic-model-boundaries-phase-8-erasure.md`](dynamic-model-boundaries-phase-8-erasure.md).

## Phase 9 — Scale certification, compatibility, and rollout

### Slice 9.1 — Scale certification

- [x] Repeat the Phase 0 production workload suite against the complete implementation; Phase 9 is certification, not
  the first performance discovery point.
- [x] Revalidate the one-million-ID cardinality dataset, partition/routing invariants, and linear billion-row sizing;
  retain absolute multi-billion deployment capacity as an infrastructure gate.
- [x] Certify commit latency and byte throughput for 1, 2, 10, and 100 targets under concurrent model reconstruction.
- [x] Certify local reconstruction, graph loads, and projection rebuilds for deep/wide/shared DAGs; retain controlled
  OS-cold-cache and customer-distribution profiles as deployment inputs.
- [x] Certify local storage/WAL amplification, cache memory, restart/recovery, and overload/backpressure budgets;
  preserve production-duration vacuum/bloat, replication, and backup/restore as operational gates.
- [x] Certify the non-JDBC publish-first event/commit visibility race with the intended tracker retry policy; boundary
  loads must fail instead of admitting wrong state until the durable commit result is visible.
- [x] Compare every result with the Phase 0/5 baselines and explain every material difference before release.

### Slice 9.2 — Compatibility

- [x] Keep all aggregate APIs, formats, tests, and downstream projects green.
- [x] Verify new SDK against old runtime gives the existing clear unsupported request-type error only when a distinct
  model commit is used; legacy request types remain unchanged.
- [x] Verify old SDK against new runtime remains unchanged through the unchanged legacy handlers and full aggregate
  contract suites.
- [x] Add migration/rollback notes; no automatic aggregate-to-model data migration in the first release.

### Slice 9.3 — Final verification

- [x] Run focused SDK and runtime suites for every slice.
- [x] Run both final full `./mvnw -B install` builds.
- [x] Run final Java/Kotlin downstream and protocol compatibility checks.
- [x] Perform a separate regression-only diff review.
- [x] Resolve every checked item's evidence link/commit in the log below.

Phase 9 was a successful implementation checkpoint. The post-certification review deliberately reopened pre-merge
readiness for the API corrections and paired benchmark below. Phases 10 and 11 are now complete and renew the decision:
the implementation is GO for merge and controlled rollout. Absolute 100 GB/min capacity, production-duration
operations, and workload-specific physical sizing remain infrastructure deployment gates.

## Phase 10 — Pre-merge semantics and completion controls

### Slice 10.1 — Shared-payload lifecycle proof

- [x] Add an explicit JDBC contract in which models A and B share one stored event payload, hard deletion of A removes
  only A's membership, current and historical reconstruction of B still succeeds, and deletion of B finally removes
  the now-unreferenced model payload.
- [x] Verify logical deletion of A retains both model histories, hard deletion never removes the independently owned
  global event-log entry, and an inclusive cascade removes shared memberships only when the selected lifecycle set
  actually contains their models.
- [x] Exercise retry, restart, and concurrent erasure/commit ordering so payload reference cleanup cannot race a
  surviving membership into data loss.
- [x] Keep the single-target path always inline and retain the measured adaptive sharing threshold; confirm the new
  proof adds no ordinary commit/load query.

### Slice 10.2 — Time-derived state indices

- [x] Allocate the first state index of each accepted commit batch as
  `max(IndexUtils.indexForCurrentTime(), previousStateIndex + 1)` and assign its ordered substeps from that range in
  both JDBC and in-memory stores.
- [x] Treat state indices as opaque ordered positions everywhere. Remove tests, diagnostics, and production logic that
  infer a historical boundary through arithmetic such as `stateIndex - eventCount`; obtain an observed boundary
  explicitly instead.
- [x] Preserve the separate `stateIndex` and `eventIndex` namespaces while documenting and testing
  `IndexUtils.timestampFromIndex(stateIndex)`.
- [x] Cover multiple substeps in one millisecond, more than one commit in one millisecond, clock rollback, restart,
  non-published transitions, temporal relation boundaries, graph high-watermarks, caches, snapshots, and write fences.
- [x] Repeat the complete model-commit store/load matrix and confirm that one clock read per commit batch causes no
  material throughput, p99, storage, or WAL regression.

### Slice 10.3 — Scoped conflict policy and retry

- [x] Replace unreleased `RETRY_IF_RELATIONS_UNCHANGED` with `RETRY`; a rejected commit may always perform a bounded
  complete reload/assert/intercept/apply evaluation against the new model and relationship boundary.
- [x] Add `DEFAULT` inheritance and support explicit policy at application, `@Model`, and `@Apply` scope. Resolve an
  apply override before its returned target's model setting; use the model setting for read-only dependencies; resolve
  remaining defaults through the application builder/property and finally `ACCEPT`.
- [x] Combine participant policies for one atomic commit using `FAIL > RETRY > ACCEPT`. If one participant requires
  `FAIL` and another requests `RETRY`, roll back and fail; if one requires `RETRY` and another accepts stale state, roll
  back and retry.
- [x] Retain relationship transition indices in conflict diagnostics and custom resolver context, but do not make an
  intervening relationship change a runtime retry veto. A fresh `@AssertLegal` decides whether the new graph is legal.
- [x] Reuse `FluxzeroBuilder.configureModelConflictHandling(...)` for the application resolver and retry bound; add
  documented property fallbacks with explicit builder configuration taking precedence.
- [x] Keep custom behavior as the existing `ModelConflictResolver` SPI. Do not add a `CUSTOM` enum until a concrete
  per-model named-resolver registry is required, but keep the scoped policy representation evolvable toward it.
- [x] Cover same-type multi-target applies, read-only ancestors, mixed model/apply overrides, retry assertion failure,
  retry after a move, retry exhaustion, custom application exceptions, and JSON/CBOR compatibility.

### Slice 10.4 — Automatic model-commit handling controls

- [x] Add `DEFAULT`, `ENABLED`, and `DISABLED` automatic model handling at application, `@Model`, and `@Apply` scope,
  with explicit apply override, then returned-target model setting, then builder/property, then `ENABLED` precedence.
- [x] When any applicable model-producing apply is explicitly disabled for automatic handling, make the automatic
  registry decline the complete command rather than partially applying the enabled targets.
- [x] Ensure an explicit non-passive `@HandleCommand` and the automatic model registry can never both apply the same
  command. Preserve the supported custom-handler pattern that invokes `Fluxzero.assertAndApply(update)` exactly once.
- [x] Make the switch affect only automatic command-handler selection. Explicit `assertAndApply`, event sourcing,
  event-log replay, fixtures, and direct repository operations must continue to use the same apply methods.
- [x] Cover application-wide migration opt-out, per-model and per-apply overrides, mixed multi-model commands, creation
  factories, model-side handlers, nested dispatch, local and websocket consumers, and Java/Kotlin downstream usage.

### Slice 10.5 — Graph-projection result completion and observability

- [x] Add projection completion `DEFAULT`, `ASYNC`, and `AWAIT` to the application, active
  `Consumer`/`ConsumerConfiguration`, `@GraphProjection`, and `@Apply` scopes. Resolve an explicit apply override first,
  then an explicit active-consumer setting, each affected root's graph definition, application builder/property, and
  finally `ASYNC`.
- [x] Keep projection execution on the existing durable signal/task worker. `AWAIT` delays request-result publication
  until every affected root requiring it has crossed the committed commit boundary; it never moves graph traversal or
  search writes into the authoritative model transaction.
- [x] If several transitions affect the same root, let `AWAIT` dominate `ASYNC` for that root. Mixed roots may complete
  independently; the command result waits only for roots whose effective policy is `AWAIT`.
- [x] Use the existing result-publication barrier so a command result cannot be observed before required root documents.
  Preserve `ConsumerConfiguration.awaitAsyncResults` as the independent choice of whether tracker/batch progress also
  waits. Direct `Fluxzero.assertAndApply` waits for required projection completion as part of its returned completion.
- [x] Define duplicate, timeout, disconnect, restart, worker-failure, split-search-store, move, delete, and rebuild
  semantics. The model commit remains durably committed after projection failure; a duplicate request resumes waiting
  for the same fenced projection work rather than reevaluating the commit.
- [x] Emit bounded runtime projection-batch metrics with collection/root type, configuration and state boundaries,
  root/upsert/delete counts, bytes, stage durations, retry status, and remaining backlog. Evaluate an opt-in exact-root
  update metric separately for ID cardinality and privacy; metrics are never the correctness source.
- [x] Measure `ASYNC` commit latency, `AWAIT` command-result latency, time-to-root-search-visible, metric volume, and
  graph worker throughput for selective, broad, moved, deleted, and shared-DAG roots.

## Phase 11 — Paired aggregate/model end-to-end benchmark

### Slice 11.1 — Equivalent domain shapes

- [x] Build one benchmark domain twice: an aggregate root with three direct `@Member` collections, including one branch
  type with two descendant member collections, and an equivalent graph of independently stored `@Model` nodes linked
  through `@ParentId`.
- [x] Use identical IDs, immutable values, update payloads, serialized event sizes, publication strategies, snapshot
  periods, logical-delete behavior, and graph cardinalities. Record any unavoidable mechanism-specific difference
  rather than hiding it in the result.
- [x] Parameterize narrow/deep, broad, and mixed trees plus hot-root, uniform-random, and Zipf-distributed target access.

### Slice 11.2 — Equivalent mutations and loads

- [x] Measure root, direct-child, grandchild, cross-branch two-target, move, create, logical-delete, and recreate commits
  through the real SDK-to-websocket-to-runtime path.
- [x] Compare cold/warm direct-target loads and cold/warm whole-root loads. For models report both independent target
  load and `loadGraph`; for aggregates report the one root load and the amount of unrelated history/materialization it
  necessarily reads.
- [x] Run sustained leaf-heavy histories through at least 100,000 and 1,000,000 updates and report stream growth,
  replayed events, applied events, physical reads, cache behavior, heap, GC, and time to reconstruct the requested
  direct target and complete root.
- [x] Retain a matching JDBC-core profile to attribute end-to-end differences to storage, transport, reconstruction,
  cache, search, or projection rather than presenting one unexplained total.

### Slice 11.3 — Searchable and composed-root comparison

- [x] Repeat the mutation/load matrix with `searchable = false` and `true`.
- [x] Compare synchronous aggregate root-document mutation/search with synchronous direct-model document mutation/search.
- [x] Compare current whole-root read/search using aggregate document loading, model `loadGraph`, relation-backed live
  graph composition (now `searchGraph(..., true)`), and materialized graph documents.
- [x] For asynchronous model graph projection report commit commit latency and time-to-root-search-visible separately.
  For `AWAIT`, report command-result latency and prove that an immediate root query after receiving the result observes
  the committed tree.
- [x] Measure document bytes rewritten per leaf update, search/query latency, graph lag, rebuild throughput, storage,
  WAL, and the crossover points at which independent models outperform or underperform the aggregate representation.

### Slice 11.4 — Re-certification and honest terminology

- [x] Report stream requests/s, memberships/s, unique payload MiB/s, document loads/s, reconstructed models/s, and
  applied events/s separately. Do not label a one-event serialized stream fetch as full model reconstruction.
- [x] Publish paired p50/p95/p99, throughput, physical/WAL, allocation, cache, and result-visibility tables with exact
  environment, configuration, warm-up, run count, variance, and confidence limitations. Latency, throughput,
  physical/WAL, cache mode, GC, and visibility are retained; direct allocation uses process/thread MXBean counters.
- [x] Re-run all focused suites, both full Maven reactors, site/Javadoc, protocol compatibility, Java/Kotlin downstream
  builds, and a final regression-only review.
- [x] Update the Phase 9 rollout decision using the paired evidence and resolve every new checkbox to a report and
  implementation commit before merge.

## Phase 12 — Long-polled model-cache coherence

### Slice 12.1 — Durable update cursor and protocol

- [x] Add a bounded `TrackModelUpdates` long-poll contract ordered by namespace-wide `stateIndex`. One returned update
  represents one committed model-commit substep and carries its commit ID, substep, nullable global `eventIndex`, and
  resulting target heads without duplicating event or document payloads.
- [x] Make commit results efficiently trackable in state order without tailing `model_stream`: stored memberships alone
  omit non-stored state transitions and duplicate one shared event across targets. Include explicit hard deletions in
  the same logical update cursor.
- [x] Keep the request open while no newer update exists, bound waiters, response item/byte sizes, and wait duration,
  and resume from a client-controlled cursor. A timeout is a heartbeat, not a cache invalidation.
- [x] Wake local waiters only after the model transaction commits. Cross-runtime and reconnect catch-up must query the
  durable cursor so a missed in-memory wake-up can delay but never lose an update.
- [x] Record and benchmark the selected physical layout, ordered lookup, index/WAL amplification, retention/erasure
  interaction, and 1/2/10/100-target response density before accepting it for the write hot path.

### Slice 12.2 — Model cache state machine

- [x] Add one lazily started model-update tracker per namespace/cache lifecycle. Do not duplicate the global domain-event
  tracker or depend on `eventPublication`.
- [x] Fence cached current models as valid, stale or refreshing. A remote newer head marks an entry
  stale immediately but retains the old entity as a replay base; logical deletes retain their empty state and hard
  deletion clears the namespace cache without retaining erased IDs.
- [x] Coalesce repeated updates per model and batch refreshes by hash segment. Event-sourced complete histories load
  only their missing suffix; document-loaded models use their authoritative direct document and its state-index write
  fence. Incomplete event histories evict the replay base and retain the existing explicit fail-fast contract.
- [x] Update own accepted commits directly, ignore duplicate/older completions, and prevent every cache, snapshot, or
  document refresh from overwriting a higher `stateIndex`.
- [x] Let a load of a stale entry join one in-flight refresh by default. Before a remote update is observed, ordinary
  command/query loads intentionally retain the latest-known contract; event handlers use their persisted boundary.
- [x] Advance the tracking cursor after affected entries are synchronously fenced and refreshes are safely scheduled;
  one slow document store must not block observation of unrelated model updates.
- [x] Keep runtime-owned and SDK-owned document stores honest: expose a separate safe materialization boundary and let
  the SDK close a retained commit intent only after its direct documents and snapshots have completed. A split-store
  crash before acknowledgement fails closed as visible materialization lag rather than blessing an old document.

### Slice 12.3 — Exact event-handler boundary

- [x] Keep event-handler correctness independent from current-cache tracker lag: correlate the persisted
  `commitId`/substep directly to its atomically committed model-state boundary instead of waiting for a cache refresh.
- [x] Continue to reconstruct every injected target, parent, grandparent, and relation at the event commit's exact
  `stateIndex`. A current cache entry newer than that boundary must never leak into historical handling or replay.
- [x] Prove that preceding `STORE_ONLY` transitions are included, later transitions are excluded, and a tracker already
  ahead of the handler remains exact. Co-located JDBC publication/model visibility remains one transaction; the
  existing split-store partial-failure boundary is unchanged.
- [x] Preserve exact replay for `@Model(eventSourced = false)` because storage/publication remains independent from its
  normal document-based load strategy. Explicitly incomplete history must fail or use an authoritative persisted
  document/checkpoint; it may never silently fabricate history.

### Slice 12.4 — Failure, lifecycle, and scale proof

- [x] Cover reconnect, duplicate batches, timeout heartbeats, runtime restart, tracker shutdown, cache eviction,
  namespace isolation, old-runtime unsupported responses, hard delete/recreate, cascaded delete, privacy-safe
  commit-result sanitization, and the explicitly retained commit-result retention gate.
- [x] Add transport metrics for tracker lag, observed `stateIndex`, update/target/publication counts and cursor
  progression; retain cache-eviction metrics and explicit failure/revalidation warnings without adding per-load
  metric allocation to the hot path.
- [x] Replace the misleading single-sample warm-load number with local-cache-hit and validated/refresh load
  distributions. Compare aggregate and model leaf loads with identical functional histories and cache states.
- [x] Benchmark update tracking and cache maintenance at representative target counts, cache hit ratios, read/write
  ratios, concurrent handlers, and retained 100,000/1,000,000-commit histories. Reject any design that adds material
  unbounded write amplification or makes every SDK consume document/event payloads for uncached models.
- [x] Re-run focused SDK/runtime suites, both full reactors, site/Javadoc and downstream compatibility, then perform a
  separate regression-only review of storage ordering, event replay, cache lifecycle, and hot-path allocation.

## Phase 13 — Model-first completion and public rollout

Phase 13 removes the remaining reasons for application developers to choose the legacy aggregate API. A `@Model` may
still own embedded `@Member` values when one stream, document, cache and lifecycle are genuinely desired; independent
models and `@ParentId` remain the default for movable or independently searchable children. Human and agent-facing
documentation therefore teaches only the model vocabulary. `@Aggregate` is retained for binary/source compatibility
through 1.x, documented only as a legacy migration source, and scheduled for Java deprecation in Fluxzero 2.0.

### Slice 13.1 — Executable aggregate/model contract parity

- [x] Turn the parity inventory into shared executable contracts for every externally identical persistence behavior;
  do not infer parity from separate green test classes.
- [x] Cover creation, update, logical delete, recreation, event storage/publication variants, direct search visibility,
  event-sourced and document-based loading, snapshots, `Entity.previous()`, namespace isolation, synchronous and
  asynchronous fixture completion, assertion/interceptor failures, metadata propagation and unknown-event behavior.
- [x] Extend real websocket/runtime integration coverage for document-loaded models, conflicts, moves/detaches,
  hard/cascading deletion, remote cache tracking and exact event-handler model injection.
- [x] Keep embedded-member traversal and old aggregate routing explicitly legacy-only. Add equivalent model-plus-member
  coverage where the observable contract is intentionally retained.

### Slice 13.2 — Model parameters in event and notification handlers

- [x] Add a dedicated parameter resolver for `@Model` values and `Entity<Model>` wrappers in `@HandleEvent` and
  `@HandleNotification` methods. Keep it separate from the legacy aggregate resolver.
- [x] Resolve direct targets using the same canonical `@EntityId`, unique typed `Id<Model>` and parameter-level
  `@Association("payloadProperty")` rules as model applies.
- [x] Load from the handler consumer namespace at the exact persisted commit/substep boundary. Never leak a newer
  current-cache value into event handling or replay.
- [x] Deduplicate loads within one handled message; inject an empty `Entity<T>` for a missing/deleted target and match a
  bare `T` only when a value is present.
- [x] Add direct tests for unrelated models in `@AssertLegal`, multiple same-type event targets, document-loaded models,
  logical deletion, namespace isolation, notifications, and tracker-ahead/cache-ahead histories.

### Slice 13.3 — Package-scoped unconfigured consumers

- [x] Add `perPackage` beside `perHandler` and `defaultAppConsumer`. Generate one stable consumer per exact handler
  package and message type while preserving explicit class/package `@Consumer` and custom configurations as more
  specific.
- [x] Select `perPackage` only behind a new `fluxzero.defaults.version`; existing applications retain their configured
  or historical mode.
- [x] Apply the command payload package to automatic model handlers so loose command classes need neither a marker
  interface nor individual consumers.
- [x] Keep generated-name behavior, package-renaming consequences, ordering, failure isolation, `TestFixture` parity and
  Java/Kotlin downstream compatibility explicit and tested.

### Slice 13.4 — Model-only human and agent manuals

- [x] Rewrite the modeling, loading, updating, nesting, persistence and search guides around `@Model`,
  `Fluxzero.loadModel`, automatic handling, `Fluxzero.assertAndApply`, independent `@ParentId` relations and optional
  embedded `@Member` values.
- [x] Document event-sourced versus document-based load semantics, direct synchronous search, stitched graph search and
  projections, conflict policies, exact event-handler injection, model cache tracking and logical/hard/cascading
  deletion.
- [x] Rewrite Java and Kotlin agent rules to recommend `@Model` exclusively. Mention `@Aggregate` only in a concise
  legacy migration section and never generate it for new code.
- [x] Mark `@Aggregate`, aggregate loading APIs and aggregate-specific examples as legacy throughout 1.x documentation;
  add the actual Java `@Deprecated` marker and migration link when the repository starts the 2.0 line.
- [x] Build the documentation site, Javadoc and all downstream examples and reject remaining prescriptive `@Aggregate`
  references outside explicit legacy/compatibility material.

## Phase 14 — Production hardening and universal model injection

This phase closes the remaining review findings before the branch is considered merge-ready again. It deliberately
combines handler completeness with storage/recovery hardening: both change which model state application code can
observe, so they need one adversarial correctness and performance review.

### Slice 14.1 — Single-owner erasure recovery

- [x] Prevent query-only/search endpoint stores from independently resuming and completing prepared hard-deletion
  batches.
- [x] Make deletion recovery ownership explicit while preserving standalone deployments without a search store.
- [x] Prove that a pending deletion cannot be marked complete before direct and graph search documents have actually
  been erased, including a restart in which the search endpoint initializes first.
- [x] Retain the shared-payload lifecycle proof: deleting A removes only A's membership; B remains reconstructable; the
  shared payload is reclaimed only after its final surviving membership is erased.

### Slice 14.2 — Universal model and ancestor parameter injection

- [x] Support model parameters for every selected message handler kind, not only event and notification handlers,
  whenever a payload or metadata identifier resolves the model.
- [x] Keep event/notification injection pinned to the exact model-commit boundary; use one current handler load context
  for command, query, web, schedule, result, error, metrics, and other non-event messages. Event-sourced targets share
  its pinned repository boundary; document-loaded targets remain current-only direct-document reads.
- [x] Support both `T` and `Entity<T>` and resolve a directly addressed non-family model from typed `Id<T>` values.
- [x] Let `@Association` select an alternative payload or metadata property, including ambiguous same-model IDs.
- [x] Resolve and inject parents, grandparents, and arbitrary ancestors after matching the addressed descendant,
  without requiring the payload to repeat ancestor IDs.
- [x] Compile one handler-level resolution plan and batch/collapse loads only after that handler has been selected; do
  not introduce speculative I/O while candidate handlers are being matched.
- [x] Cover sync/async handlers, interceptors, absent/deleted models, ambiguous IDs, metadata associations, non-family
  models, moves, exact historical ancestors, and mixed direct/ancestor parameters.

### Slice 14.3 — Search and graph-composition hot paths

- [x] Keep ordinary direct search/get summaries byte-for-byte compatible; decode vectorized summaries only inside graph
  composition/projection.
- [x] Enforce graph byte/node/depth limits before large intermediate maps and composed documents can exceed the
  configured allocation budget.
- [x] Make per-namespace JDBC search update executors release idle platform threads without reducing bounded write
  concurrency or shutdown guarantees.
- [x] Add focused compatibility, adversarial-bound, and executor-lifecycle tests plus a retained direct-search and
  graph-composition benchmark.

### Slice 14.4 — Non-blocking model commit/event-log path

- [x] Avoid conditional event-log work entirely for model commits that publish no global events.
- [x] Measure mixed aggregate/model traffic and concurrent conditional model commits against the legacy append path.
- [x] Remove the global monitor-plus-`join` bottleneck from published model commits while preserving event ordering,
  idempotency, rollback, and one-copy global publication; explicitly prove the accepted event-index gap contract if
  rejected reservations can leave gaps.
- [x] Retain throughput, p50/p95/p99 latency, physical amplification, allocation, and JDBC/WAL evidence for store-only,
  published, single-target, and multi-target commits.

### Slice 14.5 — Split-store repair and commit-metadata retention

- [x] Add a durable repair path for process loss between an atomic runtime commit and SDK-owned
  document/cache/snapshot materialization, using the originally committed materialization rather than re-evaluating
  user code.
- [x] Make duplicate delivery and operator-triggered repair converge idempotently and close the materialization fence
  only after all external writes succeed.
- [x] Keep the existing compact commit result as the single permanent exact-boundary/update-tracking/idempotency
  record. Do not duplicate it into per-substep/per-target rows; at the 100-GB/min envelope that normalization costs
  more WAL and storage than the MessagePack value it would replace.
- [x] Treat only the potentially bulky document/snapshot projection as retry-window state: clear it on acknowledged
  success, never expire unfinished work by time, and add repair metrics plus an operational runbook without weakening
  exact event-handler loads, cache tracking, deletion recovery, or durable idempotency.

### Slice 14.6 — Complete event-sourced history

- [x] Reject or safely transform publication policies that would mutate an event-sourced model without storing the
  reconstructing event; never commit a head that can only fail on its next load.
- [x] Preserve publication-only events that do not mutate model state and document the distinction between event
  publication and event-sourced loading.
- [x] Cover create, update, logical delete, no-op, retry, duplicate, and mixed event/document-loaded targets.

### Slice 14.7 — Re-certification

- [x] Run focused SDK/common/runtime tests while each slice lands, then both complete Maven reactors,
  site/Javadoc, annotation processing, proxy/test-server, and Java/Kotlin downstream compatibility.
- [x] Re-run paired aggregate/model and 1/2/10/100-target storage/load benchmarks plus the new mixed event-log,
  direct-search, graph-bound, and recovery profiles.
- [x] Perform a separate adversarial review across public APIs, persisted/wire formats, concurrency, failure recovery,
  deletion/GDPR, shutdown, allocations, and 100-GB/min operational assumptions.
- [x] Publish a Phase 14 report with corrected rollout status, remaining deployment gates, and rollback/runbook updates.

## Phase 15 — Contention-aware model-commit performance

This corrective phase rejects the Phase 14 short `ZIPF` result as a generic model-mutation baseline. Conflict-free
throughput and same-model contention are separate contracts and must remain separately measurable.

### Slice 15.1 — Honest paired workload taxonomy

- [x] Make the retained benchmark print distinct targets, hottest leaf/root shares, repeated same-leaf batches,
  duplicate target slots and maximum same-leaf fan-in.
- [x] Retain `UNIFORM` as the conflict-free aggregate/model baseline and label `ZIPF`/`HOT` explicitly as contention
  profiles.
- [x] Compare event-only and searchable mutations separately so synchronous direct-document visibility is not hidden
  inside a generic write number.

### Slice 15.2 — Preserve independent runtime batching around hot keys

- [x] Replace the all-or-nothing optimistic model-commit batch decision with ordered conflict-free `ACCEPT` waves.
- [x] Keep independent commits batched when another model repeats, while preserving per-target write order and strict
  `FAIL`/`RETRY` barriers.
- [x] Cover independent commits around a hot model and write-only target ordering with deterministic planner tests.

### Slice 15.3 — Local single-writer fast path

- [x] Coordinate overlapping default-`ACCEPT` model read sets within one SDK without serializing disjoint models or
  changing strict `FAIL`/`RETRY` conflict behavior.
- [x] Re-evaluate a waiter against the predecessor's committed cache state before sending it to the runtime.
- [x] Acquire a complete multi-model read set atomically, release it after failures, and retain authoritative runtime
  conflict checks for remote writers.
- [x] Offload only contended re-evaluation from websocket result callbacks while restoring the complete request
  context.

### Slice 15.4 — Re-certification and correction

- [x] Run focused coordinator, registry, committer, JDBC store and benchmark-compilation suites.
- [x] Run both complete Maven reactors and `git diff --check`.
- [x] Retain 10,000-commit conflict-free and skewed event-only measurements plus searchable comparison.
- [x] Correct the Phase 14 report and publish the
  [Phase 15 report](dynamic-model-boundaries-phase-15-contention-performance.md).
- [x] Repair the retained PostgreSQL 18 benchmark compose mount without deleting or rewriting an existing benchmark
  data directory.

## Phase 16 — Single-active-runtime recovery and operational truth

This final rollout correction aligns direct-materialization recovery and cache tracking with Fluxzero's actual
`AvailabilityCheck` topology: one active runtime owns the namespace, even when model and search persistence use
separate databases.

### Slice 16.1 — Runtime-owned restart recovery

- [x] Scan the existing partial pending-materialization index in count- and byte-bounded batches when an existing
  model namespace is activated after restart.
- [x] Apply the exact retained document/snapshot bytes through existing state-index fences, clear retry payloads only
  after success, and unblock graph projection/cache materialization boundaries.
- [x] Retry temporary search failures with bounded exponential backoff without polling an idle namespace.
- [x] Preserve lazy zero-schema initialization for aggregate-only namespaces.

### Slice 16.2 — True long polling

- [x] Remove the 100-ms cross-runtime generation observer and its configuration property.
- [x] Keep direct in-memory wake-up for commits, materialization completion and deletion on the single memoized
  namespace store.
- [x] Retain durable cursor bootstrap across runtime restart; defer multi-active notification to the future
  request/result-log architecture.

### Slice 16.3 — Key, retention and observability contract

- [x] Document that erasure-key configuration is optional: each runtime-owned database generates and persists its own
  key, while external configuration is an operational ownership/recovery choice.
- [x] Record current retention precisely: compact commit results, completed deletion identity and erasure fences have
  no TTL; pending projection bytes and completed deletion-target worksets are the eagerly removed data.
- [x] Distinguish built-in metrics/logs from deployment-owned dashboards and alert thresholds.

### Slice 16.4 — Verification and review

- [x] Cover automatic restart recovery, exact original bytes and repeated temporary search failure.
- [x] Retain local long-poll wake-up, materialization fencing, duplicate idempotency, graph projection and hard-delete
  recovery contracts.
- [x] Run both complete Maven reactors, `git diff --check`, and a final adversarial review.
- [x] Publish the [Phase 16 report](dynamic-model-boundaries-phase-16-recovery.md) with final test counts and remaining
  operational gates.

## Phase 17 — Bounded model-commit receipts

The durable commit boundary and the short-lived cache/update receipt are different data. This phase separates them
without allowing the runtime to inspect or mutate event metadata.

### Slice 17.1 — Durable core versus transient receipt

- [x] Keep a compact hash-partitioned commit core containing the commit-id fence, substep state/event boundaries and
  target positions, but no persisted raw target IDs for new commits.
- [x] Store the full target-bearing receipt once in an append-only, state-time-partitioned update table in the same
  JDBC transaction as the commit.
- [x] Reconstruct an exact duplicate response by combining the durable target positions with the target IDs already
  present in the retried commit, including after the transient receipt was purged.
- [x] Preserve old full commit rows as a readable rolling-upgrade format and initialize the update-retention floor at
  the existing durable head instead of replaying absent historical receipts.

### Slice 17.2 — Bounded tracking and partition retention

- [x] Default transient receipt/update retention to one hour, configurable through an ISO-8601 duration property.
- [x] Drop complete expired time partitions and atomically advance a durable update-retention floor; never issue
  per-row expiry deletes or rewrites.
- [x] Return a backwards-compatible cache-reset update when a tracker cursor predates that floor, then resume at the
  current durable/materialized cursors.
- [x] Keep idle tracking as a true long poll and run no periodic database retention poll; purge at namespace activation
  and partition rollover.

### Slice 17.3 — Privacy, failure and performance hardening

- [x] Sanitize any retained pre-Phase-17 commit rows and recent receipts during hard deletion while keeping the compact
  new commit core free of raw model IDs.
- [x] Cover restart, rollback, duplicate-after-expiry, cursor reset, materialization, historical boundary and
  deletion-versus-retention races.
- [x] Measure commit throughput, WAL, physical amplification, tracking latency and partition-drop behavior against the
  Phase 15 baseline.

### Slice 17.4 — Certification

- [x] Run focused SDK/common/runtime suites and both complete Maven reactors.
- [x] Perform an adversarial compatibility, concurrency, shutdown and capacity review.
- [x] Publish the Phase 17 report with retained measurements and any remaining deployment gate.

## Phase 18 — Local graph parity and model-cache controls

This corrective phase closes the remaining difference between a runtime-backed application and the SDK-only
`LocalClient`/test server while separating independent searchability from graph composition.

### Slice 18.1 — Graph-component documents

- [x] Treat `@Model(searchable = false)` as suppression of only the model's independent search collection.
- [x] Store an internal current document when an explicit `@ParentId(path = "...")` opts the model into graph
  composition, including document-loaded models and deletes.
- [x] Keep non-searchable models without an explicit graph path on the zero-document-write fast path.

### Slice 18.2 — Local projection worker

- [x] Materialize registered graph projections in `LocalClient`, the test server and synchronous fixtures using the
  shared bounded graph stitcher and exact current-document collection locators.
- [x] Honor `DEFAULT` to application-level `AWAIT`, report honest projection positions, and delay waiters until direct
  documents are visible.
- [x] Update both old and new roots on a move and preserve projection path overrides, rebuild fences and duplicate-ID
  collection safety.

### Slice 18.3 — Dedicated model-cache configuration

- [x] Add `FluxzeroBuilder.withModelCache(Cache)` without changing aggregate or relationship cache selection.
- [x] Add `disableAutomaticModelCaching()` and include it in `DefaultFluxzero.Builder.disableAutomaticTracking()`.
- [x] Close and instrument a distinct model cache exactly once while retaining compatibility for custom
  `FluxzeroBuilder` implementations.

### Slice 18.4 — Verification and documentation

- [x] Cover the local materialized-root, move, non-searchable graph child, zero-document fast path and cache-selection
  contracts.
- [x] Update public Javadocs, developer/agent manuals, the Phase 7 limitation and this evidence log.
- [x] Run focused suites, both complete Maven reactors, Javadocs/site checks, `git diff --check`, and an adversarial
  regression/performance review.

## Phase 19 — Explicit graph-search contract and write-impact remeasurement

The graph view is not the root Java model and its query semantics must not depend on whether a materialized projection
is configured. This corrective phase replaces the provisional ordinary-search modifier with one explicit JSON graph
search.

### Slice 19.1 — Public graph-result API

- [x] Replace `Search.includeModelGraph()` with `Fluxzero.searchGraph(Root.class)`.
- [x] Return graph-shaped `ObjectNode` values by default while retaining explicit `SerializedDocument`, `Document` and
  typed `SearchHit` terminals.
- [x] Use the configured materialized graph collection in default mode, stitch live when no projection exists, and
  support `searchGraph(Root.class, true)` to force live composition.

### Slice 19.2 — Equal live and materialized query semantics

- [x] Apply graph constraints, sorting, field selection and pagination to the complete stitched view rather than the
  uncomposed root.
- [x] Bound live candidate-root discovery before composition and fail with guidance to narrow the query or use a
  materialized projection.
- [x] Apply projection-local path overrides to forced-live results so their public shape matches the materialized view.

### Slice 19.3 — Deterministic placement and compatibility

- [x] Preserve one deterministic ID-ordered list when different model types write to the same parent path.
- [x] Preserve the distinct graph-search wire commit and old-runtime unsupported-commit behavior.
- [x] Cover no-projection live fallback, materialized default, forced live, child-path constraints, path overrides,
  path filtering and candidate bounds across common, SDK/local and runtime tests.

### Slice 19.4 — Performance and release evidence

- [x] Re-run the existing paired aggregate/model end-to-end benchmark so explicit-path non-searchable graph-component
  writes are included in the before/after evidence.
- [x] Record live multi-root stitching and materialized-query results, including the retained 1-root/5-child and
  1,024-root/5,000-child profiles.
- [x] Run focused suites, both complete Maven reactors, site/Javadocs, downstream compatibility, `git diff --check`
  and a final adversarial regression/performance review.

## Phase 20 — Production-code reduction without performance loss

The completed design is deliberately broad, but breadth must not leave accidental duplication, parallel state
machines, or implementation scaffolding in the permanent hot path. This phase simplifies the SDK and runtime after the
contracts have stabilized. Fewer lines are desirable only when the result remains easier to prove correct; hiding
state transitions behind generic abstractions does not count as simplification.

The strict `origin/main...HEAD` source-root baseline at entry is:

- SDK repository: 20,071 added / 86 deleted `src/main` lines and 15,311 added / 11 deleted `src/test` lines;
- runtime repository: 16,249 added / 12 deleted `src/main` lines and 14,452 added / 11 deleted `src/test` lines;
- runtime benchmark tests add another 5,513 / delete 11 lines, while the SDK repository adds 7,227 / deletes 6,041
  documentation lines.

`test-server/src/main` and public `TestFixture` support remain production source roots in the strict count even though
their purpose is testing. The phase report must therefore provide both Maven-layout and functional classifications.

### Slice 20.1 — Complexity and duplication map

- [x] Record per-module and per-source-root added/deleted/net lines, plus the largest production additions.
- [x] Identify repeated state transitions, validation, paging, graph stitching, materialization, cache fencing and
  protocol conversion across SDK local/in-memory and runtime JDBC/in-memory implementations.
- [x] Measure structural complexity and allocations on the principal hot methods before editing; distinguish genuinely
  necessary domain complexity from temporary implementation scaffolding.
- [x] Mark public API, persisted schema, wire format, failure, ordering, lifecycle and performance invariants that each
  candidate simplification must preserve.

### Slice 20.2 — SDK and common simplification

- [x] Reduce duplication and unnecessary intermediate representations around `DefaultModelRepository`,
  `InMemoryEventStore`, automatic model commits, target/ancestor resolution, cache tracking and graph composition.
- [x] Prefer compiled immutable plans and central `ReflectionUtils.TypeMetadata` ownership over repeated reflection or
  runtime branching.
- [x] Keep legacy aggregate traffic, direct single-model loads and commits on their current fast paths.
- [x] Retain fixture/local/test-server parity without copying a second implementation of graph or commit semantics.

### Slice 20.3 — Runtime simplification

- [x] Decompose `JdbcModelCommitStore` and `JdbcModelGraphProjectionStore` by cohesive storage responsibility where that
  reduces reasoning surface, while retaining set-based SQL, prepared-statement reuse, batching and transaction scope.
- [x] Share pure validation, relationship, payload-membership and projection semantics between JDBC and in-memory
  stores where doing so removes duplication without introducing polymorphic dispatch or allocation on measured hot
  paths.
- [x] Remove superseded compatibility scaffolding, dead alternatives and redundant commit/result transformations.
- [x] Keep lazy schema creation, hash pruning, backpressure, restart recovery and zero-overhead aggregate-only behavior
  intact.

### Slice 20.4 — Performance and regression gate

- [x] Compare before/after production lines, files and structural complexity; document important code that remained
  large because reducing it would obscure transactional or temporal invariants.
- [x] Re-run the affected aggregate/model commit and multi-target store/load profiles; retain the Phase 19
  graph-search/stitch, split-store materialization and contention evidence for execution paths unchanged by this
  simplification.
- [x] Accept no statistically meaningful throughput, p95/p99 latency, allocation, WAL, physical amplification or
  legacy aggregate regression. Revert simplifications that fail this gate.
- [x] Run focused suites, both complete Maven reactors, site/Javadocs, downstream compatibility, `git diff --check`
  and a separate adversarial final-diff review.

## Phase 21 — Log-centric runtime redesign and schema containment

The post-Phase 20 architecture review found that the runtime implementation preserves the intended model contracts but
implements them as a parallel coordination platform instead of composing Fluxzero's existing durable logs, tracked
consumers, positions, JDBC schema primitives, and thin endpoint/store boundaries. This phase is a release blocker. It
must remove accidental infrastructure rather than merely split large classes or move lines between files.

The entry baseline against `origin/main` is a net increase of 15,832 physical Java production lines in the runtime
(`22,847` to `38,679`, +69.3%). The new `modeling` package accounts for 13,158 lines. The six largest model additions
account for 13,693 lines, including the 6,998-line `JdbcModelCommitStore`. At first model use, the current core can
create roughly 309 parent/partition relations in one namespace; enabling every graph/search capability can raise the
combined model/search total to roughly 444 before ordinary collection partitions and indexes. In addition,
`JdbcSearchStore` currently initializes model-erasure state and 32 erasure partitions even for aggregate-only
namespaces.

### Non-negotiable Phase 21 contracts

- [x] Preserve the public SDK API, wire commits, model identity, commit ordering, `stateIndex` ordering, one-time global
  event publication, publication strategies, exact historical loads, temporal relationships, conflict semantics,
  direct search visibility, graph search shape, snapshot behavior, cache coherence, retry/idempotency, hard erasure,
  detached lineage, split-store recovery, and `ASYNC`/`AWAIT` completion behavior.
- [x] Preserve all legacy aggregate, search, scheduling, tracking, key-value and consumer behavior. An
  aggregate/search-only namespace must create **zero model-specific tables, partitions, workers, executors, polling
  loops, HMAC state, or hot-path branches**.
- [x] Base model coordination on Fluxzero's existing durable-log and tracked-consumer primitives. A compact internal
  model-update log should be the first design evaluated as the owner of the time-based `stateIndex`, cache change feed,
  durable materialization outbox, and graph-projection input. A new parallel receipt, signal, task, cursor, waiter, or
  retention subsystem is forbidden unless a recorded spike proves that the existing primitives cannot meet a named
  correctness or performance contract.
- [x] Keep the commit package atomic in the model/event database: model memberships, heads, temporal relation deltas,
  the internal model update, and the original globally published event must either all become visible or none do.
  Split search storage remains recoverable and idempotent without XA.
- [x] Keep synchronous direct-model document visibility. Graph projections remain durably asynchronous by default and
  may be awaited per configured result boundary. Neither path may lose, duplicate, reorder, or resurrect mutations
  across restart, retry, stale completion, move, logical delete, or hard erasure.
- [x] Retain the inline/shared payload policy and delete ownership guarantees without duplicating ordinary event
  payloads per target.
- [x] Do not preserve an unreleased internal table layout, serialized commit-result blob, or implementation class when
  doing so obstructs the simpler architecture. Any required branch-local schema transition must nevertheless be
  deterministic, explicitly tested, and fail clearly rather than silently misreading old data.
- [x] Do not accept cosmetic decomposition as completion. Production lines must be removed through shared ownership,
  deleted state machines, reused infrastructure, simpler persistence, or eliminated transformations.

### Hard schema and code budgets

- [x] Inventory every logical table, index, physical partition, worker and durable cursor before editing; record its
  owner, cardinality, retention, read/write path, and proof that it cannot reuse an existing runtime primitive.
- [x] Control/configuration/idempotency tables are not hash-partitioned by default. Only measured high-cardinality data
  may be partitioned, and one logical dataset may not be duplicated solely to support the opposite traversal when an
  indexed or measured alternative meets the load budget.
- [x] Create partitions lazily or from an explicitly justified small default. Never precreate the same fixed
  32-partition fan-out across every model table.
- [x] The default first-use budget is at most **64 new physical model relations** for core commit/load plus synchronous
  direct documents, and at most **96** with graph projection and hard-erasure capabilities enabled. These counts include
  parent tables and child partitions across model and search databases, but exclude ordinary user search collections
  that would exist independently. Exceeding either ceiling requires explicit user acceptance backed by production-scale
  measurements and an explanation of why fewer relations fail.
- [x] No generic `JdbcSearchStore` construction may create model state. Model document fencing and erasure must be a
  lazy model capability or a separate narrow store.
- [x] Finish with a runtime production-source delta of at most **+10,000 net physical Java lines** against
  `origin/main`. A higher result is a failed phase unless the user explicitly accepts a documented exception after the
  behavior, table and performance gates pass.

### Slice 21.1 — Executable baseline and replacement ADR

- [x] Freeze the current branch as the behavioral and performance oracle. Record exact production/test LOC, physical
  schema objects, startup work, background workers, transaction statements, round trips, allocations, WAL and latency
  for aggregate-only and every representative model path.
- [x] Map `model_state`, commits, receipts, targets, heads, streams, payloads, temporal relations, materialization,
  projection signals/tasks, fences, deletion state and protected lineage onto existing `MessageStore`,
  `PositionStore`, `Table`, partition-retention and endpoint/service primitives.
- [x] Spike the compact internal model-update log and atomic `JdbcMessageStore` callback path. Prove time-based monotone
  state indices, STORE_ONLY tracking, exact event-commit boundaries, restart replay, bounded retention, and one global
  event publication. Record decisions and discard the spike before production implementation.
- [x] Publish a replacement ADR with the exact target tables, indexes, partition counts, transaction boundary, retained
  state, workers and failure recovery. Do not start the rewrite until it satisfies the budgets above.

### Slice 21.2 — Shared commit log and narrow core stores

- [x] Implement the internal model-update log using existing message-log indexing, long polling, retention and consumer
  positions; use its assigned index as `stateIndex` unless the Phase 21.1 spike disproves that design.
- [x] Reduce permanent commit state to the minimum required for durable idempotency and exact commit boundaries. Do not
  retain complete document/snapshot materialization blobs indefinitely.
- [x] Decompose persistence into narrow model-stream/head, temporal-relation and commit-idempotency stores, coordinated
  by one commit service. Keep set-based SQL, batching, transaction-local work and hash pruning on measured
  high-cardinality tables.
- [x] Share commit transition semantics between JDBC, local/test and test-server paths. Replace the independent
  handwritten in-memory commit engine with a thin storage adapter or the shared coordinator.
- [x] Keep endpoints as protocol adapters and share one namespace-scoped model query/service graph rather than
  constructing a second full query-only model store.

### Slice 21.3 — Materialization, tracking and projection consumers

- [x] Drive cache coherence, direct document/snapshot materialization and graph projection from the same committed model
  update log. Remove superseded receipt/target and projection-signal infrastructure.
- [x] Reuse ordinary durable consumer positions and long polling. Local commits wake waiters immediately; single-active
  failover resumes from the durable position without permanent database polling.
- [x] Preserve runtime-owned autonomous recovery after restart and temporary split-search-store failure. A committed
  commit may not depend on client redelivery for repair.
- [x] Preserve coalescing and bounded graph composition without a second generic scheduling platform. If a minimal
  durable root-task table remains necessary, prove why replay plus an existing consumer position is insufficient and
  keep it inside the schema budget.
- [x] Preserve the direct-model zero-extra-round-trip cache hot path and exact event-handler commit-boundary loads.

### Slice 21.4 — Lazy search lifecycle and erasure

- [x] Move model document fences, snapshots and erasure out of generic search-store construction into a lazy,
  model-specific capability.
- [x] Express hard deletion as a bounded lifecycle commit using the same update/outbox/recovery mechanism. Retain
  protected detached lineage and irreversible search fences without contaminating ordinary model commits or
  aggregate-only startup.
- [x] Test `NONE` and cascading erasure, shared payload membership, detached descendants, split stores, restart during
  every phase, stale late document/projection writes, deletion retries and key loss/rotation behavior.
- [x] Verify that graph search and ad-hoc stitching use a narrow graph query service and never initialize commit,
  receipt, recovery or deletion ownership merely to read.

### Slice 21.5 — Performance, scale and release gate

- [x] Run paired before/after measurements from separately installed worktrees with identical JVM, PostgreSQL, schema,
  data, warm-up and concurrency. Require no statistically meaningful regression in single-target and 2/10/100-target
  writes, current/historical loads, hot leaf loads, STORE_ONLY tracking, relation moves, direct searchable commits,
  graph search/stitching, projection throughput or erasure.
- [x] Require unchanged or better p50/p95/p99 latency, throughput, allocation, physical amplification and WAL on every
  changed hot path. A simplification that loses speed is reverted or redesigned; fewer lines never compensate for a
  regression.
- [x] Verify that legacy aggregate/search-only startup, schema, throughput, latency, allocation and WAL are byte-for-byte
  or statistically unchanged, apart from test instrumentation.
- [x] Repeat the highest-throughput store/load matrix and demonstrate that no singleton lock, control table, per-model
  round trip, N+1 traversal or consumer backlog prevents the established 100-GB/min reference architecture. Do not
  claim production hardware certification from a local diagnostic.
- [x] Record final production/test LOC, absolute lines touched, class/interface sizes, logical/physical table counts,
  partitions, indexes, workers and cursors. Explain every retained model-specific mechanism.
- [x] Run both complete Maven reactors, site/Javadocs, Java/Kotlin downstream compatibility, binary API checks,
  `git diff --check`, schema upgrade/restart tests and an adversarial final review before renewing release readiness.

Completion evidence and the accepted replacement architecture are recorded in
[Phase 21 runtime redesign](dynamic-model-boundaries-phase-21-runtime-redesign.md). The final implementation replaces
the parallel receipt/signal/task machinery with one compact update log, remains within the +10,000 production
line ceiling, creates 48 core relations (52 with graph and search lifecycle enabled), and passed the paired performance,
restart/upgrade and complete-reactor gates.

## Phase 21b — Production-code compression without performance compromise

Phase 21 removed the accidental parallel runtime platform, but its accepted ceiling was deliberately a first release
gate rather than the desired final size. At the Phase 21 completion commits, runtime production Java has grown by
9,992 physical lines against `origin/main` (`22,848` to `32,840`) and SDK production Java by 19,872
(`116,835` to `136,707`). This follow-up is a second release blocker: retain every accepted model contract while making
both implementations materially smaller and more native to their repositories.

The immutable comparison points are runtime `552ead1e` and SDK `e5d30518003`. They are the correctness, throughput,
latency, allocation, WAL and physical-amplification oracles for this phase.

### Non-negotiable Phase 21b contracts and budgets

- [ ] Finish with a runtime production-source delta of at most **+7,000 net physical lines** against `origin/main`.
  This requires removing at least 2,992 net lines from the Phase 21 result and reduces the feature growth by at least
  29.9%.
- [ ] Finish with an SDK production-source delta of at most **+14,000 net physical lines** against `origin/main`.
  This requires removing at least 5,872 net lines from the Phase 21 result and reduces the feature growth by at least
  29.5%.
- [x] Count all hand-maintained production implementation under `src/main`, independent of language or file extension.
  Moving Java into SQL/resources, generating equivalent checked-in code, minifying statements, collapsing formatting,
  deleting Javadocs or moving implementation into tests does not reduce the budget.
- [x] Preserve every Phase 21 user-facing persistence, lifecycle, ordering, replay, search, graph, caching,
  materialization, conflict, deletion, recovery and compatibility contract. The unreleased SDK-owned materialization
  commands were removed because they encoded a second production owner rather than a product capability; successful
  commit now directly guarantees runtime-owned materialization. Tests may not be deleted, weakened or rewritten around
  a narrower implementation merely to enable code removal.
- [x] Preserve the 48-relation core and 52-relation fully enabled schema ceilings. New tables, indexes, workers,
  durable cursors or polling loops are forbidden unless they replace more state than they add and the user explicitly
  accepts the measured result.
- [x] Require equal or better performance than the immutable Phase 21 baseline for every changed hot path. A stable
  throughput, p50/p95/p99 latency, allocation, WAL or physical-amplification regression is a failed approach, even
  when it meets the source budget. Normal benchmark noise must be resolved with paired repeated runs, never waved
  through.
- [x] Preserve statistically unchanged legacy aggregate/search-only behavior and resource use. Model simplification
  may not put model branches, schema initialization or background work onto legacy paths.
- [x] Reject cosmetic decomposition. Completion requires deleted ownership, states, transformations, reflection,
  copies, round trips or algorithms; splitting a large class into several classes is organizational work and earns
  zero budget credit by itself.

### Slice 21b.1 — Ownership and irreducible-code audit

- [x] Inventory every retained model production class and protocol type by responsibility, caller, hot-path status and
  reason it cannot use an existing aggregate, handler, repository, client, cache, search, JDBC or serialization
  primitive.
- [x] Record separately the irreducible public/wire surface and the executable implementation. Identify repeated
  target resolution, handler planning, reconstruction, materialization, graph traversal, in-memory emulation, JDBC
  binding and migration logic across both repositories.
- [x] Build deletion spikes for the largest candidates before adopting abstractions. Measure the spikes against
  runtime `552ead1e` and SDK `e5d30518003`, retain the decisions, and discard any spike that merely moves code or
  slows a hot path.

The ownership inventory, accepted deletions, rejected single-source tracker and joined-lifecycle spikes, paired
performance measurements and remaining budget gap are recorded in
[Phase 21b production-code compression](dynamic-model-boundaries-phase-21b-compression.md). The checkpoint is
deliberately not a numerical completion claim: after removing the second materialization owner, duplicate snapshot
writes, duplicate cycle validation and repeated commit/parameter planning, runtime is +9,097 and SDK +18,447
production lines. Reaching both requested ceilings now needs a product-scope or budget decision.

### Slice 21b.2 — Runtime compression

- [x] Reduce `JdbcModelCommitStore` by removing responsibilities and repeated persistence mechanics, not by hiding its
  SQL or splitting it mechanically. Prefer the runtime's existing transaction, statement, schema, partition and
  lifecycle primitives where paired measurements prove they preserve or improve speed.
- [x] Consolidate model/search materialization, graph projection, erasure and endpoint adaptation where they currently
  encode the same fencing, batching, retry or lifecycle transition more than once.
- [x] Keep commit/load SQL set-based, partition-prunable and allocation-bounded. Any shared abstraction introduced on
  a measured hot path must compile down to equal or fewer statements, bindings, copies and round trips.
- [ ] Meet the **+7,000** runtime ceiling, rerun the complete schema inventory and explain every remaining
  model-specific production mechanism.

### Slice 21b.3 — SDK compression

- [x] Unify automatic handler registration, target planning, assertion/apply evaluation, conflict retry and commit
  preparation around one commit plan. Do not independently rediscover model targets or handler metadata in registry,
  engine, committer and repository layers.
- [x] Share aggregate-proven reflection, invocation, caching, reconstruction and client primitives where doing so
  removes real model code without adding model conditionals or allocations to legacy aggregate paths.
- [x] Consolidate current, historical, ancestor and graph loading around the minimum batched reconstruction core while
  preserving exact commit-boundary semantics and the direct hot-cache path.
- [x] Keep local, synchronous fixture, asynchronous fixture, test-server and websocket behavior on the same model
  transition semantics without duplicating a complete in-memory server in the SDK.
- [ ] Meet the **+14,000** SDK ceiling while retaining all public Javadocs, Java/Kotlin downstream compatibility and
  the full aggregate/model contract-parity suite.

### Slice 21b.4 — Performance and release gate

- [x] Run the complete Phase 21 paired runtime matrix plus representative SDK command handling, automatic apply,
  event reconstruction, hot-cache, ancestor injection, graph search and fixture paths from separately installed
  worktrees.
- [x] Require equal or better stable model throughput and latency on every changed path, no higher allocation/WAL or
  physical amplification, and statistically unchanged legacy aggregate paths. Revert any abstraction that fails.
- [x] Run both complete Maven reactors, site/Javadocs, binary compatibility, Java/Kotlin downstream projects,
  schema upgrade/restart/split-store recovery, `git diff --check` and a fresh adversarial regression review.
- [x] Record final absolute/net production and test lines, deleted responsibilities, largest remaining classes, schema
  objects and paired performance evidence. Phase 21c starts before external migration because the original absolute
  event-throughput gate was not actually exercised by the retained comparison.

## Phase 21c — Absolute model-commit throughput and terminology correction

This is a release blocker. The previous model benchmark reported one-event operations as `commits/s` and compared
successive model-store implementations, while the established JDBC event-store bulk control was not kept in the final
matrix. A direct rerun stored 1,000,000 aggregate events, including global event-log publication, at 680,735 events/s.
The integrated model store measured 6,424–8,194 one-event commits/s with direct documents, snapshots, relations and
graph projection disabled. The workloads are not identical, but an 83–106x event-rate gap invalidates release
readiness until batching, byte throughput and complete SDK-to-runtime behavior are measured and corrected explicitly.

The feature initially introduced action-oriented terminology for the same persisted commit. That distinction had no
domain value and obscured comparison with aggregate commits. Unreleased public, wire, runtime, schema, benchmark and
documentation names therefore use `model commit`, `commit`, `commitId`, `commits/s` and `events/s` consistently.

### Slice 21c.1 — Safe, absolute benchmark controls

- [x] Make every destructive benchmark require or construct an explicit isolated datasource/schema, print its
  effective target before teardown and refuse the runtime default database. Never let an ignored system property route
  a benchmark `dropSchema` to a developer or customer database.
- [x] Retain the existing `JdbcEventStoreBenchmark` as the bulk reference including aggregate event storage and global
  tracking-log publication. Report commits/requests, events, logical bytes, physical bytes, WAL, p50/p95/p99 and batch
  shape rather than one ambiguous rate.
- [x] Extend the JDBC model benchmark with configurable commit steps/events, targets, model cardinality, payload size,
  compressibility, publication, documents, snapshots and relations. Always report commits/s, globally published
  events/s, model memberships/s and logical MiB/s separately.
- [x] Run paired cold and warm loads for equivalent event counts and byte volumes. Report fetched blocks/rows, applied
  events/s, logical MiB/s, p50/p95/p99, allocations and read amplification.

### Slice 21c.2 — SDK-to-runtime end-to-end matrix

- [x] Retain the functionally identical aggregate/member tree and independent-model graph through the real SDK,
  WebSocket transport, runtime endpoints and PostgreSQL stores.
- [x] Run the matrix with `searchable=false` for every independently stored model and with `searchable=true` for the
  equivalent aggregate/model documents. Direct searchable documents must be synchronously visible in both cases;
  asynchronous graph materialization is measured separately and may not hide commit latency.
- [x] Add a conflict-free, high-cardinality throughput profile in addition to hot/Zipf contention. Report commands/s,
  commits/s, events/s, logical MiB/s, end-to-end p50/p95/p99, allocations, physical bytes, WAL and query visibility.
- [x] Compare aggregate and model paths under identical payloads, command concurrency, durability, publication,
  cache state and search configuration. A small application benchmark may not substitute for the absolute JDBC gate.

### Slice 21c.3 — JFR-guided commit-path redesign

- [x] Capture repeatable JFR profiles for the bulk aggregate control and model commits with search disabled and
  enabled. Retain summaries of CPU samples, allocation pressure, monitor/park time, socket/JDBC waits, compression,
  serialization and garbage collection.
- [x] Attribute every ordinary model commit statement, row, index update, serialization and copy. Remove redundant
  idempotency reads, duplicate result serialization, underfilled count-bounded batches and per-event work that can be
  represented once per byte-bounded commit batch.
- [x] Recover aggregate-grade payload batching/compression without making direct or batched model reconstruction
  perform an N+1 query or repeatedly decompress unrelated data. Evaluate cached immutable payload blocks, offsets and
  adaptive inline/block crossover against real compressible and incompressible payloads.
- [x] Keep model heads, sequence numbers, exact historical boundaries, durable retries, cache tracking, hard deletion
  and shared-payload ownership correct. Performance work may change their physical representation but not weaken
  their observable contract.

### Slice 21c.4 — Commit terminology

- [x] Replace all former action-oriented class, field, store, table, metric and benchmark terminology with
  `CommitModels`, `ModelCommit*`, `commitId`, `model_commit`, `commit_id`, `commits/s` and `events/s`. Preserve
  `action` only where it describes a user/domain action rather than the persistence commit.
- [x] Use one clear request/result vocabulary analogous to existing aggregate commits: a model commit contains ordered
  commit steps, each original event is published globally at most once and each target receives one stream
  membership.
- [x] Update JSON/CBOR type registration, WebSocket endpoints, local/test stores, fixtures, schema upgrade tests,
  Javadocs and manuals together. Because this surface is unreleased, do not retain misleading commit aliases.

### Slice 21c.5 — Release gate

- [x] Require the common one-target, one-event model commit to remain at least as fast end-to-end as the equivalent
  aggregate command.
- [x] Require **at least 600,000 stored and globally published model events/s** on the same local datasource, JVM,
  payload and batch shape as the established JDBC aggregate control. This is a hard acceptance floor, not a target or
  extrapolation. The measured path retains model streams and heads, exact sequence/state boundaries, durable commit
  identity, cache-update tracking, replay, conflict behavior, lifecycle and global event publication; disabling,
  bypassing or weakening accepted functionality does not satisfy the gate.
- [x] Report bulk `commits/s`, `events/s`, `memberships/s` and logical MiB/s independently so packing many events into
  one commit cannot conceal unacceptable one-event command latency or vice versa.
- [x] Require no material regression in hot/cold direct loads, batched reconstruction, direct search, graph search,
  cache tracking, conflict handling, restart recovery, erasure, physical amplification or WAL.
- [x] Repeat the complete JDBC and SDK-to-runtime matrices from clean schemas in alternating order and record variance.
  Local hardware is a regression oracle; representative production hardware remains the absolute 100-GB/min
  certification gate.
- [x] Run both complete Maven reactors, site/Javadocs, downstream Java/Kotlin compatibility, wire round trips, binary
  API review, `git diff --check` and an adversarial review. Only then reconsider release or start Phase 22.

Phase 21c closed on 2026-07-29. The final clean-schema runtime gate committed 10,400,000 model events after a
520,000-event warm-up at **1,018,715 stored memberships/s and 1,018,715 globally published events/s**, with exact
post-run row-count verification, 1.01x physical amplification and 1.04x WAL amplification. The equivalent SDK-to-
runtime tree remained faster than the aggregate representation in both execution orders: 1.371–1.457x without search
and 1.299–1.424x with synchronous direct documents and asynchronous graph projection. Both complete Maven reactors,
the SDK site/Javadocs, downstream Java/Kotlin builds, focused wire/storage matrices and `git diff --check` passed.
JFR retained the real stream/head, commit identity, update tracking, event publication and transaction paths; the gate
does not disable documents or graph work globally, but its explicit search-disabled profile isolates the absolute
event-storage floor while the searchable end-to-end matrix verifies that those features remain intact.

## Phase 21d — 500–600k one-event SDK-to-runtime commit gate

This is a release blocker. Phase 21c proved the runtime store can retain and globally publish more than one million
events/s when 100 events share one commit, but the customer-shaped SDK benchmark produced only 1,558–1,765 commands,
one-event commits and events/s. The benchmark does perform automatic target resolution, model repository loading,
`@Apply`, durable model-stream and global-log storage, cache maintenance and command-result delivery. Calling that
result acceptable because it remained faster than the equivalent aggregate path confuses relative parity with
absolute capacity.

The hard gate is now **at least 500,000 independently committed one-target, one-event model updates per second, with
600,000/s as the desired release result**, through the real Java SDK, WebSocket transport, tracked command consumer,
runtime endpoints and PostgreSQL stores. Every update
must complete with its ordinary command result and retain model loading, exact state boundaries, model cache tracking,
idempotency, replay, global event publication and failure semantics. Packing many events into one commit remains an
important complementary byte-throughput profile, but may not substitute for this gate.

### Slice 21d.1 — Honest decomposition and repeatable profiling

- [~] Extend the E2E driver with a model-only mode, asynchronous bounded in-flight sending and configurable
  events-per-command while preserving the existing blocking `sendAndWait` latency profile.
- [~] Report command publication, command-log visibility, consumer queueing, target resolution, cache/repository load,
  assertions/applies, commit submission, JDBC commit, cache update and result-delivery time separately.
- [~] Capture JFR profiles and allocation counts for the one-event runtime store, SDK sender, WebSocket endpoints,
  tracked consumer and result path. Record CPU, allocation, park/monitor, socket, serialization, compression, JDBC and
  garbage-collection costs rather than inferring the bottleneck from total latency.
- [~] Keep one-event commits, commits/s and events/s visibly identical in the gate output. Add a separate
  multi-event-per-commit profile without using it to satisfy the one-event floor.

### Slice 21d.2 — Runtime one-event commit capacity

- [x] Raise the one-event runtime store beyond the original 100,000/s floor without
  weakening model heads, sequence/state indices, durable commit identity/results, update tracking, replay, lifecycle
  or exactly-once global event publication. The retained one-target, one-event result is **718,439 independently
  committed and globally published events/s**.
- [x] Remove or batch fixed per-commit statements, rows, serialization and transaction work that dominates when a
  commit contains one event. Preserve independent failure/results and bound batches by bytes, latency and memory.
- [ ] Re-run searchable and non-searchable profiles. Synchronous direct-document visibility remains part of the
  searchable commit; asynchronous graph composition is measured and fenced separately.

### Slice 21d.3 — SDK, tracking and transport capacity

- [x] Prove the low-level production WebSocket request/result boundary at the new scale before changing tracked
  command handling. Two repeated 1,048,576-request runs, each containing one independent commit, one target and one
  globally published event, completed at **539,986/s** and **538,773/s** with exact result, membership and event
  counts.
- [~] Support at least 500,000 bounded in-flight automatic model commands/s without one platform thread per request,
  unbounded futures, hidden fire-and-forget behavior or omitted results.
- [~] Batch compatible command publication, tracking delivery, model loads/commits and result frames while preserving
  per-command metadata, correlation, ordering contracts, exception delivery, cancellation and backpressure.
- [~] Keep automatic model caching enabled in the primary profile and prove every command still resolves and loads its
  target through the ordinary `ModelRepository` path. Add cache-disabled and forced-cold controls.
- [x] Prevent adaptive-cache memory-pressure eviction from deadlocking model-prefetch work: select and remove entries
  under the internal cache lock, deliver eviction callbacks only after releasing it, and cover the former lock inversion
  with a deterministic regression test. Completed in SDK commit `806d4dba308`.
- [~] Measure the runtime-wide opaque-message-envelope backlog item against this exact E2E profile. If the retained JFR
  attribution holds, keep payload and metadata as serialized `Data<byte[]>` through runtime storage and routing and
  deserialize them only in the consuming SDK. Preserve runtime-owned routing headers, upcasting, metadata mutation
  extension points and wire/stored compatibility explicitly; do not count a metadata-free synthetic message as proof.
- [ ] Preserve ordinary aggregate, notification, event-handler and custom consumer behavior; model throughput work may
  not globally change established delivery or batching semantics without focused compatibility evidence.

### Slice 21d.4 — Read capacity must keep up with writes

- [x] Retain a representative full-reconstruction profile rather than treating decoded JDBC rows as model loads:
  8,192 independently stored models, ten published events per model, real WebSocket transport and
  `Fluxzero.loadModels`, payload deserialization, `@Apply` replay and automatic model caching enabled.
- [x] Keep physical stream blocks bounded at 1,024 memberships. The retained dataset uses 100 blocks for 81,920
  memberships (819 average, 1,024 maximum); increasing the block size to make writes look faster was rejected because
  it reduced random-read locality.
- [x] Use bounded immutable runtime block/location caches, generation-fenced invalidation and zero-copy compact wire
  slices. Cover current misses, appends, historical boundaries, restart locator recovery and repeatable-read
  visibility. A possible-duplicate commit that cached a pre-commit location miss exposed and fixed a real stale-empty
  read race.
- [x] Gate sustained cold SDK reconstruction against the measured write floor for both long and shortest useful
  streams. The ten-events-per-model profile remains useful for replay capacity, but originally hid excessive
  per-model overhead. The stricter retained gate therefore uses 32,768 independently stored one-event models, real
  WebSocket transport, four concurrent disjoint `Fluxzero.loadModels` pipelines, payload deserialization, `@Apply`
  replay and automatic model caching. One long-lived `Fluxzero` instance clears its model cache before every measured
  iteration without repeatedly charging tracker/WebSocket startup to sustained service. Final-code legacy
  soft-reference-cache runs completed at **1,015,960** and **1,034,579 models/events/s**; current adaptive-cache runs
  completed at **1,051,170** and **977,714 models/events/s**. Every retained result is above the
  718,439-events/s independent one-event write result.
- [x] Preserve embedded compact stream blocks when concurrent SDK reads are coalesced. Previously, two batched callers
  silently forced expansion back into tens of thousands of membership/payload objects, adding roughly 48 ms of block
  expansion and 60–117 ms of classification in the representative profile. Compact splitting now shares the immutable
  wire blocks, while a caller starved by the combined byte window still retries independently.
- [x] Do not re-coalesce an already large compact `loadModels` request. Combining several native batches made
  hash-mixed physical blocks span callers and caused each split caller to decode unrelated entries again. Fine-grained
  loads still coalesce; requests of at least 1,024 streams retain ordinary client/runtime concurrency. Add bounded
  bulk-merge support to both legacy and adaptive automatic caches so a cold reconstructed batch does not take one
  global cache lock transition per model.
- [x] Keep a raw transport/storage control alongside reconstruction. Single-pipeline one-event compact reads completed
  at **830,615–939,864 events/s** beside the final four-pipeline full reconstruction runs.
  The older ten-events-per-model raw control remains approximately 1.17–2.03 million events/s. Physical blocks stay
  capped at 1,024; no larger-block read-locality trade-off was used to pass the gate.

### Slice 21d.5 — Absolute release gate

- [~] Sustain at least **500,000 one-event model commits/events per second end-to-end**, targeting 600,000/s, after
  warm-up on the Phase 21c
  local regression machine, with zero missing/duplicate events and results and exact post-run stream/global-log counts.
- [ ] Record p50/p95/p99/max command-result latency at the passing throughput, plus unloaded single-command latency.
  Throughput obtained through an unbounded queue or multi-second tails does not pass.
- [ ] Repeat with synchronous direct search documents and default asynchronous graph projection; record their separate
  floors and projection-visibility lag. Fix targeted `AWAIT` completion so it does not wait on unrelated roots.
- [ ] Run both complete reactors, focused compatibility/concurrency/restart tests, Javadocs, downstream builds,
  `git diff --check` and an adversarial regression review before closing Phase 21d.

The exact benchmark definitions, current measurements, JFR attribution and accepted/rejected experiments are kept in
the [Phase 21d performance journal](dynamic-model-boundaries-phase-21d-performance.md). Update that journal before a
new experiment whenever an earlier attempt could otherwise be repeated under another name.

## Phase 22 — Existing-application `@Aggregate` to `@Model` migration

This phase uses a real existing Fluxzero application as an external acceptance test rather than adding another
synthetic SDK fixture. The user will provide the repository coordinates after the Phase 21 runtime release blocker is
closed.

### Slice 22.1 — Baseline and migration map

- [ ] Obtain the repository coordinates and inspect its own agent/build instructions before making changes.
- [ ] Run and record the untouched application test suite and any representative integration/performance checks.
- [ ] Map aggregate roots, embedded members, command/event flows, searches, snapshots, caches, relationships and
  lifecycle expectations to independent models versus intentional same-stream `@Member` values.
- [ ] Identify persisted-data compatibility requirements separately from source migration; do not silently claim that
  changing stream identities migrates existing production history.

### Slice 22.2 — Model-first conversion

- [ ] Replace application aggregate boundaries with `@Model`, typed direct IDs and explicit `@ParentId(path = ...)`
  composition where tree search is required.
- [ ] Use automatic model handling, model/ancestor injection and `Fluxzero.assertAndApply` where they remove real
  application boilerplate without changing command or event meaning.
- [ ] Preserve external payloads, handler results, publication strategies, search visibility and application-level
  consumer configuration unless a deliberate migration change is documented.
- [ ] Avoid application-local workarounds for SDK/runtime shortcomings; first reproduce and fix any general gap in the
  owning Fluxzero repository with a focused regression test.

### Slice 22.3 — External acceptance and evidence

- [ ] Run the complete migrated application suite against local/test-fixture and real runtime-backed paths applicable
  to the project.
- [ ] Compare aggregate and model behavior for commands, event replay, direct and graph search, moves, deletes,
  ancestors, caching and restart/retry.
- [ ] Record the source delta, removed boilerplate, migration friction, discovered framework gaps and any intentional
  semantic differences.
- [ ] Feed proven migration guidance back into the human and agent manuals, then rerun the affected SDK/runtime full
  reactors before renewing release readiness.

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
  discarded and is documented in the [Phase 2 report](dynamic-model-boundaries-phase-2-commit-loading.md).
- 2026-07-25 — SDK commit `ac2922b5f53` adds exact commit-scoped model/value injection without touching legacy
  aggregate discovery. Automatic and qualified context lookup retained zero allocation at 2.73 ns and 4.59 ns median.
- 2026-07-25 — Phase 2 completed at the side-effect-free SDK commit boundary: deterministic cross-model applies,
  ordered interceptor substeps, logical delete, receiver-side handlers, before/after assertions, commit-prefix
  reconstruction semantics, and complete in-memory rollback. Focused tests (39), Javadoc, and the full SDK reactor
  passed. The retained complete one-write commit measured 305.7 ns / 2,520 bytes median; details are in the
  [Phase 2 report](dynamic-model-boundaries-phase-2-commit-loading.md).
- 2026-07-25 — Runtime commits `41a57adc` and `b17806d2` complete authoritative storage Slices 3.2/3.3: lazy partitioned
  JDBC schema, ordered namespace state indices, set-based heads/streams/commits/relationships, adaptive inline/shared
  payloads, compact durable idempotency results, one-transaction global publication when co-located, in-memory parity,
  and explicit per-commit/pending-byte overload protection. Full runtime module passed (501 tests); the focused suite
  additionally found and fixed multi-parent selective detach being misclassified as a move. The retained diagnostic
  benchmark measured 8,839 commits/s for one 1-KiB target, 31,051 memberships/s for ten targets, 39,940 memberships/s
  for one hundred 16-KiB targets, and 18,704 memberships/s with one relationship per target. Details and limitations
  are in the [Phase 3 storage report](dynamic-model-boundaries-phase-3-storage.md). Direct search remains Slice 3.4.
- 2026-07-25 — SDK commit `e4674571875` and runtime commit `2b77b7dd` add the commit commit transport, synchronous
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
  identical 10.50-MiB physical storage for 5,000 one-target commits and equivalent throughput/WAL. The retained public
  load measured 64,223 current models/s with 1,024-model batches; a 100,000-event head at the midpoint resolved in
  6.2 ms warm. The complete runtime module passed 511 tests. Byte-bounded SDK reconstruction remained open at this
  checkpoint.
- 2026-07-25 — Slice 3.5 stream delivery (`SDK b51cd43ba38`, runtime `8218acbb`) is byte- and membership-bounded end to
  end. `GetModelEvents.maxBytes` is a deduplication-aware total response-payload limit; zero preserves the earlier
  unlimited request behavior. The runtime
  applies it before deserialization and extracts uncompressed size from the existing compression header, so no
  storage column or write amplification was added. The SDK pins the first response boundary across 1,024-model chunks
  and validates heads, sequence continuity, commit metadata, payload references, limits, and forward progress page by
  page. Ten repeated reads of 10,000 one-event models measured 78,483 models/s without a byte cap and 76,490 models/s
  with an inactive 8-MiB cap: 2.5% overhead in this local warm-cache run for computing the safe global/byte prefix.
  The complete SDK reactor and all 516 runtime-module tests passed; the benchmark reactor also test-compiled.
- 2026-07-26 — Phase 4 (`SDK 2b59b35d0d5`, runtime `a2a6d6d5`) adds optional global-read-boundary conflict handling
  without making it the model design frame: `ACCEPT` remains the zero-rejection default; strict policies roll back the
  whole runtime commit and may fail or retry after a fresh pinned load, optionally only while relationships remain
  unchanged. Details are in the [Phase 4 report](dynamic-model-boundaries-phase-4-conflicts.md).
- 2026-07-26 — Phase 5 (`SDK 0249394ba5c`, runtime `e0ea56e1`) connects independent model commits to normal local and
  tracked command handling; reconstructs exact self/cross-model state through bounded hash-pruned stream pages,
  snapshots, cache suffixes, and commit-prefix views; makes direct searchable documents visible before command success;
  and returns grouped temporal graph bundles with explicit child-owned paths. Existing compact commit results pin
  event-handler loads without a second event→state table. The complete SDK reactor, site/Javadoc reactor, Java/Kotlin
  downstream projects, and complete runtime reactor passed; runtime reported 528 tests. The retained local integrated
  diagnostics measured 9,193 one-target commits/s, 18,925 ten-target memberships/s, roughly 19–25k model loads/s, and
  220,714 SDK replayed events/s. Full evidence and limitations are in the
  [Phase 5 report](dynamic-model-boundaries-phase-5-reconstruction.md). Production 100-GB/min certification,
  commit-result retention/archival, and the explicit non-JDBC publish-first visibility race remain Phase 9 gates.
- 2026-07-26 — Coherent commit materialization (`SDK 0b9b74b0764`, runtime `39cb88e1`) sends original events, optional
  direct documents, due snapshots, and relationships as one model-commit package. The core JDBC commit durably retains
  exact compressed recovery intent; direct search and snapshots complete synchronously through state-index fences and
  survive restart without SDK reevaluation. Default `ACCEPT` now preserves the original post-interception events while
  reapplying only `@Apply` against a fresh pinned boundary. The retained 1/2/10/100-target comparison found no
  systematic store/load, physical-byte, or WAL regression. One million minimal hot keys measured 329.03 MiB at cache
  depth `0` and 505.66 MiB at depth `1`, supporting the default of one predecessor while keeping the shared cache
  bounded. Full SDK, site/Javadoc, downstream, and runtime reactors passed; runtime reported 538 tests. A separate
  regression review retained public registry constructors, legacy snapshot readability, metric compatibility, and
  document/snapshot-aware runtime backpressure.
- 2026-07-26 — Direct assert-and-apply (`SDK 51dc3fd3b84`) adds synchronous
  `Fluxzero.assertAndApply(update[, metadata])`. It enters the independent-model commit engine without command
  redispatch, so an explicit handler can apply its own payload exactly once; it returns only after durable commit and
  direct-search visibility, while preserving the enclosing handler result and original failures. Synchronous,
  asynchronous-result, nested-dispatch, metadata, failure, and outside-handler fixture paths are covered. The complete
  SDK and site/Javadoc reactors passed, including test-server, proxy, annotation processing, and Java/Kotlin downstream
  projects. Aggregate inference remains deliberately open because a typed child ID or one of several IDs does not
  safely identify the aggregate root.
- 2026-07-26 — Ancestor injection (`SDK f408c5d7e10`, runtime `1424343c`) resolves parents, grandparents, and arbitrary
  read-only ancestors for `@AssertLegal`, `@InterceptApply`, and `@Apply` through one pinned temporal graph request.
  `@Association` remains a direct payload-property qualifier when that property exists and otherwise qualifies an
  explicit `@ParentId(path = ...)` edge. Same-commit moves overlay staged child relations; cold reconstruction resolves
  the pre-event graph and original commit-prefix state. Stored FQNs use the serializer's existing upcasting/type-caster
  chain and remain optional metadata rather than identity. The direct commit path performs no graph lookup. Full SDK,
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
- 2026-07-26 — Current graph composition (`SDK e4de8597314`, runtime `f95cb5c4`) adds
  `Search.includeModelGraph()` as a distinct bounded wire commit. Explicit `@ParentId(path)` edges become deterministic
  list placements; missing child documents are omitted, shared DAG nodes are placed at every path, collisions and
  cycles fail, and root path filters run after stitching. Current document collections are stored once in a small
  registry while partitioned model heads retain only nullable integer locators. Exact `(segment, modelId)` joins and one
  multi-collection document query keep the broad read path set-based: fixing an accidental segment/id cross-product
  reduced the 5,000-node locator from about 799 ms p50 to 3.943 ms p50. The retained 1,024-root/5,000-child benchmark
  measured 49.274 / 65.461 / 66.057 ms p50/p95/p99 end to end. Full SDK/downstream and runtime reactors passed; runtime
  reported 571 tests. Co-located query compilation, deeper concurrency/scale certification, asynchronous materialized
  roots, and historical full-text graph search remain open.
- 2026-07-26 — Model/aggregate parity and untyped loading (`SDK fd6709f1f7c`, runtime `dbaefbea`) add one executable
  lifecycle fixture for the semantically shared event-sourced, document-loaded, direct-search, previous-revision,
  logical-delete/recreate, and publication-free contracts. The separate real-runtime fixture verifies model
  commit/load/search, arbitrary ancestor injection across assert/intercept/apply, and exactly-once global EVENT
  delivery over websocket. Untyped `Object` loads inspect at most one stream membership for a payload-side `@Apply`
  factory and fall back safely to Serializer-upcast stored metadata; typed loads retain their zero-extra-read path.
  The [parity matrix](dynamic-model-boundaries-phase-5-parity.md) records the aggregate-only contracts that deliberately
  remain separate and why global aggregate `Fluxzero.assertAndApply` inference is not safe. Focused SDK coverage passed
  71 tests; complete SDK, site/Javadoc, downstream, and runtime reactors passed. The runtime feature branch now
  resolves SDK `0-SNAPSHOT` so it cannot accidentally test these contracts against a published pre-feature SDK.
- 2026-07-26 — Temporal graph integrity (`SDK 3f62c0bada4`, runtime `af521f6c`) adds explicit
  `ModelRepository.loadGraphAt`, rejects model-relation cycles before event publication with complete commit rollback,
  preserves atomic same-substep edge reversals, and isolates invalid commits from valid coordinator neighbours.
  Current/as-of traversal and half-open interval boundaries are covered in memory and JDBC. The retained batched
  validator measured 493 commits/s versus 513 with validation temporarily disabled (3.9% throughput and 3.4% p99 cost)
  on the exact local relation-heavy A/B; the per-commit-query predecessor was discarded. The complete SDK,
  site/Javadoc, downstream, and runtime reactors passed; runtime reported 560 tests. Details and open production-scale
  DAG certification are in the
  [Phase 6 temporal graph report](dynamic-model-boundaries-phase-6-temporal-graph.md).
- 2026-07-26 — Materialized model graphs (`SDK 304756a3f70`, runtime `a236277c`) add opt-in
  `@Model(graphProjection = @GraphProjection(...))` definitions, automatic typed-root registration, durable
  commit-transaction signals, coalesced hash-partitioned root tasks, resumable rebuilds, projection-local path
  overrides, and configuration/state-fenced graph documents in a collection distinct from direct model search.
  Direct documents remain synchronously visible; graph lag and both durable backlog stages are explicit. No projection
  table, query, signal, or hot-path write exists before opt-in. Full SDK, site/Javadoc, and runtime suites passed
  (1,882 SDK tests and 580 runtime tests), plus an SDK-to-runtime websocket materialization test. On the retained local
  2,000-root diagnostic, conservative default bounds materialized about 1,110 roots/s in 1.802 s; the leaf-model
  profile materialized about 7,491 roots/s in 0.267 s. Storage/WAL grew from 4.45/7.66 MiB without projection to
  7.41/13.15 MiB at the default projection profile. Cross-instance configuration propagation remains a Phase 9 rollout
  gate. Phase 18 subsequently added equivalent in-memory projection materialization to `LocalClient`, synchronous
  fixtures and the test server.
- 2026-07-26 — Phase 8 SDK commit `49000dd69f1` and runtime commit `d3a74b60` add bounded deletion planning and
  explicitly confirmed `NONE`/`DESCENDANTS` hard deletion. JDBC execution is a fenced, hash-partitioned, resumable
  1,024-target saga across streams, relationships, commit materializations, snapshots, direct search, and materialized
  graph search; detached lineage remains lifecycle-discoverable through HMAC tokens and the global event log is
  retained. Focused verification passed 51 SDK and 173 runtime tests. Retained benchmark results range from 0.097 s for
  `NONE` to 70.804 s for a 100,000-model wide cascade; a paired direct-document hot-path A/B measured 6,020 versus 6,070
  commits/s (about -0.8%, with unchanged p50/physical amplification). Full measurements and remaining key-management
  limitation are recorded in [the Phase 8 erasure contract](dynamic-model-boundaries-phase-8-erasure.md).
- 2026-07-26 — Phase 9 runtime commit `62b8b3bd` hardens distributed rollout with cross-instance graph-projection
  registration, externally managed and startup-validated lineage HMAC keys, fail-closed external-store boundary
  visibility, and retained concurrent complete-commit/load benchmarks. The final SDK `./mvnw -B install` passed all
  nine modules, including aggregate, protocol, annotation-processor, test-server, proxy, and Java/Kotlin downstream
  contracts. The final runtime `./mvnw -B install` passed all four modules and 601 runtime tests. The
  [Phase 9 certification](dynamic-model-boundaries-phase-9-certification.md) records complete-commit, graph,
  projection, cache, storage/WAL, failure/recovery, compatibility, migration, rollback, and rollout evidence. The
  implementation is GO for merge and controlled rollout; absolute 100 GB/min capacity and production-duration
  operational qualification remain explicit infrastructure deployment gates.
- 2026-07-26 — The post-certification design review accepted two additional pre-merge phases: time-derived state
  indices, scoped conflict and automatic-handling overrides, configurable graph-result completion, projection metrics,
  an explicit shared-payload erasure proof, and one genuinely paired aggregate/model end-to-end benchmark. No
  implementation or renewed rollout GO is claimed until their open checkboxes are resolved.
- 2026-07-27 — Phases 10 and 11 are complete (SDK: this commit; runtime: `5ff09709`). The final SDK reactor passed all
  nine modules, including protocol, annotation processor, proxy, test server, and Java/Kotlin downstream contracts; the
  site/Javadoc reactor passed. The final runtime reactor passed all four modules and 617 runtime tests. Focused
  post-review verification passed 81 SDK model commit/handler/repository tests and the JDBC scoped-`AWAIT` deletion
  contract. The separate regression review corrected async retry/rebase request-context propagation, prevented an
  unresolved root scope from releasing projection completion early, and removed a misleading unsupported in-memory
  projection no-op. The [Phase 10 semantics](dynamic-model-boundaries-phase-10-semantics.md) and
  [paired Phase 11 report](dynamic-model-boundaries-phase-11-e2e.md) retain the complete contracts and measurements.
  The [Phase 9 decision](dynamic-model-boundaries-phase-9-certification.md) is renewed as GO for merge and controlled
  rollout, with absolute 100 GB/min qualification, production-duration operations, commit-result retention, and
  workload-specific physical sizing retained as deployment gates.
- 2026-07-27 — Phase 12 (`SDK 90665b4b1658`, runtime `6affc207b205`) replaces per-load model-head validation with one
  durable long-polled update cursor per active namespace. The retained hybrid cache fences remote changes immediately,
  refreshes only cached targets in bounded batches, keeps event-sourced replay bases, and reconstructs event-handler
  state at its exact commit boundary. A separate materialization head prevents direct documents or deletes from being
  fenced current before their external writes complete; SDK-owned stores close that fence with an idempotent
  acknowledgement. The regression-only review additionally separated update and document readiness to prevent
  head-of-line blocking, made tracker bootstrap safe during pending writes, and closed the old-document recache race
  during hard deletion. Focused suites passed 101 SDK/common and 73 JDBC runtime tests. Both full reactors passed
  (625 runtime tests), as did SDK site/Javadoc, test-server, proxy, annotation processing, and Java/Kotlin downstream
  compatibility. Measurements and the explicit split-database crash limitation are retained in the
  [Phase 12 report](dynamic-model-boundaries-phase-12-cache-tracking.md).
- 2026-07-27 — Phase 13 (`SDK 2737dd80ab8`, `b13a59dc546`, `b1265fe2d7f`; runtime `f47a62fa`) makes the
  public rollout model-first. Tracked event/notification handlers inject `T` and `Entity<T>` at the exact model-commit
  boundary; unconfigured handlers can share a version-gated consumer per exact package; and one executable parity suite
  runs lifecycle, publication, search, rollback, snapshot and embedded-member contracts against aggregates and models.
  The runtime integration suite now covers direct and document-loaded models, exact event injection, logical/hard
  deletion, ancestors, projections and moves, and hard erasure works even before the first snapshot partition exists.
  Focused SDK verification passed 88 tests and focused runtime/JDBC verification passed 99 tests. Both full reactors
  passed, including Java/Kotlin downstream projects, 1,925 SDK tests and 632 runtime tests; site/Javadoc also passed.
  Human and Java/Kotlin agent manuals now prescribe `@Model`, treat `@Model` plus `@Member` as the intentional
  single-stream option, and confine `@Aggregate` to 1.x migration compatibility. Java deprecation remains reserved for
  Fluxzero 2.0. The [Phase 13 report](dynamic-model-boundaries-phase-13-rollout.md) records the final coverage and
  compatibility contract.
- 2026-07-27 — Phase 14 (SDK: this commit; runtime `0f7615916615`) closes the production-hardening review.
  All message-handler kinds can inject direct independent models and arbitrary ancestors from typed payload IDs or
  `@Association`-selected payload/metadata values, while event and notification loads remain pinned to their exact
  model-commit boundary. Runtime erasure recovery now has one operational owner; shared event payloads survive until
  their final stream membership is erased; store-only commits bypass the global event log; and published commits
  reserve ordered indices without waiting for JDBC under the global head monitor. Split search stores repair from the
  exact committed document/snapshot bytes and close a monotone materialization fence only after success. Complete
  event-sourced history is enforced before commit. Focused verification passed 74 SDK/common and 162 runtime tests;
  the complete SDK reactor passed with 1,939 SDK tests plus protocol, test-server, proxy, annotation-processor and
  Java/Kotlin downstream coverage; site/Javadoc passed; and the complete runtime reactor passed all four modules and
  641 runtime tests. The retained paired-tree, mixed event-log, and 1/2/10/100-target measurements—plus the explicit
  100-GB/min deployment gate and rollback/runbook—are recorded in the
  [Phase 14 report](dynamic-model-boundaries-phase-14-hardening.md).
- 2026-07-27 — Phase 15 (SDK: this commit; runtime `a0fce8ef`) corrects the misleading Phase 14 hot-key conclusion.
  Default-`ACCEPT` commits now coordinate overlapping local read sets before commit, while the runtime retains
  independent optimistic batches as ordered conflict-free waves. Strict conflict policies and cross-process runtime
  authority are unchanged. Focused verification passed 28 SDK and 78 JDBC tests; both full reactors passed, including
  1,941 SDK and 643 runtime tests plus protocol, test-server, proxy, annotation-processing, Java/Kotlin downstream and
  benchmark compilation. The retained conflict-free 10,000-commit comparison measured models at 1,493.9 versus
  aggregates at 1,089.0 commits/s (1.372×), with 59% less WAL and 50% less allocation. The deliberately skewed profile
  improved from 0.265× to 0.773× aggregate throughput; its remaining same-model serialization boundary is explicit in
  the [Phase 15 report](dynamic-model-boundaries-phase-15-contention-performance.md).
- 2026-07-27 — Phase 16 (SDK: this commit; runtime `426793ec`) makes runtime-owned split-database
  document/snapshot materialization self-healing after namespace activation following restart. Exact retained
  projection bytes are drained in 128-commit/8-MiB batches with fenced idempotent writes and bounded retry, while the
  unsupported multi-active 100-ms observer is removed in favor of true single-active long polling. Erasure-key,
  retention and observability documentation now describes implemented behavior rather than deployment assumptions.
  Focused JDBC verification passed 78 tests. The complete runtime reactor passed all four modules and 643 tests; the
  complete SDK reactor passed all nine modules, including 1,941 SDK tests and Java/Kotlin downstream compatibility.
  The final adversarial review additionally made recovery waits interruptible during shutdown. The
  [Phase 16 report](dynamic-model-boundaries-phase-16-recovery.md) retains commit-result archival, multi-active
  notification, customer alert thresholds, production-duration soak and absolute 100-GB/min qualification as explicit
  future or deployment gates.
- 2026-07-27 — Phase 17 (SDK: `7207e9ed`; runtime: `2a500a42`) separates the permanent, ID-free model-commit boundary
  from target-bearing cache receipts. Receipts and the sparse reverse lookup for publication-suppressed targets are
  hourly range-partitioned, atomically written and dropped as whole partitions after a configurable one-hour minimum;
  old trackers receive a backwards-compatible cache reset. Duplicate idempotency, exact historical boundaries, hard
  erasure and opaque event metadata remain intact after expiry and restart. The adversarial review additionally closed
  an SDK race in which a tracked local commit could start an unnecessary suffix refresh before its accepted result
  seeded the cache. The final SDK reactor passed all nine modules and 1,943 SDK tests; the runtime reactor passed all
  four modules and 645 tests. Paired one- and ten-target results show commit throughput within 1.3% of the baseline,
  +4.2%/+1.6% WAL while receipts are retained, and 1.94–4.6x faster update tracking. Absolute 100 GB/min qualification
  and production-duration partition-rollover soak remain deployment gates; full evidence is retained in the
  [Phase 17 report](dynamic-model-boundaries-phase-17-commit-receipts.md).
- 2026-07-28 — Phase 18 (SDK: this change; runtime unchanged) gives `LocalClient`, synchronous fixtures and the
  websocket test server real in-memory graph projection materialization. Explicitly placed non-searchable children now
  use the internal `$modelGraphComponents` collection without gaining an independent search collection; models without
  a path retain the zero-document-write fast path. Dedicated and disabled model-cache selection are public builder
  controls. Focused graph/commit/cache verification passed 55 tests, including explicit default-`ASYNC` and opt-in
  `AWAIT` asynchronous fixtures, and the complete websocket contract passed 12
  tests. The complete nine-module SDK reactor, site/Javadoc reactor and four-module runtime reactor passed; the latter
  retained all 645 tests. The regression review found no production-runtime hot-path change: the only new production
  storage is the intentional current document for a non-searchable model that explicitly opts into graph placement.
  Aggregate migration remains deliberately deferred. Full contracts are recorded in the
  [Phase 18 report](dynamic-model-boundaries-phase-18-local-parity.md).
- 2026-07-28 — Phase 19 (SDK/runtime: this change) replaces provisional `includeModelGraph()` with the explicit
  `searchGraph` JSON-view contract. AUTO uses a configured materialized projection and otherwise stitches live;
  callers can force live composition. Constraints, sorting, field selection, paging and projection path overrides now
  have the same full-view semantics, while bounded candidate-root discovery prevents unbounded accidental fan-out.
  Same-path children form one deterministic ID-ordered list. The benchmark review found and fixed an immutable JDBC
  root-summary split that could otherwise make live full-text graph constraints false-negative. The complete SDK
  reactor passed all nine modules, including 1,970 SDK tests and Java/Kotlin downstream compatibility; site/Javadoc
  passed. The complete runtime reactor passed all four modules and 646 tests before the final JDBC summary correction;
  the affected 102 JDBC/RUM/endpoint tests and subsequent complete runtime reactor passed afterward. Paired
  aggregate/model and 1,024-root/5,120-child measurements, including non-searchable graph-component writes and 1-KiB
  documents, are retained in the [Phase 19 report](dynamic-model-boundaries-phase-19-graph-search.md).
- 2026-07-28 — Phase 20 (SDK: this change; runtime `7fc0fa3f`) consolidates protocol validation, direct
  materialization extraction, all three search pagination variants, temporal boundary resolution and deletion-batch
  conversion. It changes 1,696 production lines in absolute terms while removing 518 net production lines. A proposed
  shared head-transition abstraction was reverted after a measured short-run regression; the final separately
  installed A/B measured single-target writes within 1.3%, reads 5.0% faster, ten-target writes 1.7% faster and a
  ten-iteration multi-target load within 0.2%, with unchanged physical/WAL amplification. Fresh CPD reports,
  `git diff --check`, both complete reactors, downstream projects and site/Javadocs passed. Detailed counts, retained
  complexity and benchmark evidence are in the
  [Phase 20 report](dynamic-model-boundaries-phase-20-simplification.md).
