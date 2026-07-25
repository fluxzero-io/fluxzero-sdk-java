# Phase 0 storage foundation for dynamic model boundaries

- Status: accepted for vertical implementation
- Date: 2026-07-25
- Scope: SDK and runtime dynamic model boundaries
- Decision frame: Fluxzero behavior and production workloads, not Axon/DCB conventions

## Decision

Proceed with dynamic model boundaries using:

1. one ordered namespace `stateIndex` for committed model transitions;
2. a model stream and model head co-partitioned by Fluxzero's stable ID segment;
3. adaptive inline/shared event payload storage;
4. set-based, byte-bounded commit batches;
5. two temporal relationship adjacency projections, partitioned independently by parent and child;
6. synchronous direct-model search completion and asynchronous composed-graph projection.

The phase is a **GO** for a thin production vertical slice. It is not a claim that a development laptop certifies the
100 GB/min production envelope. Integrated and production-hardware capacity tests remain release gates.

No production schema, migration, wire type, or model-store implementation was created during this phase. The
disposable layout/relationship spike code and schemas were removed after recording the decisions. Only the enhanced
benchmark for the real legacy aggregate path remains as a regression control.

## Required behavior

### State and event identities

- `eventIndex` remains the existing global publication-log position.
- `stateIndex` is a separate namespace-wide visibility order for every committed model state transition.
- One model-affecting original event/substep receives one `stateIndex`, one logical model-payload identity, and zero or
  one `eventIndex`.
- All target stream entries for that event share its `stateIndex`, `readStateIndex`, `actionId`, and logical payload
  identity.
- Each target stream assigns its own `sequenceNumber`.
- The global event log receives a publishable original event once, regardless of target count.
- Bounded inline duplication is allowed below the adaptive sharing crossover. Above it, the model store contains one
  full payload plus target memberships. Publication may require a second payload copy in the independently operated
  global event log; target count must never cause unbounded full-payload amplification.

`stateIndex` ordering is owned by one namespace commit coordinator in the first implementation. It assigns contiguous
ranges to accepted action substeps and advances the durable visible state head in the same JDBC transaction as model
streams, heads, idempotency, relationships, and event-log publication when co-located. Serialization/compression may
happen in parallel, but visibility and commit order do not race.

The coordinator batches by action count **and bytes**. The initial diagnostic batch size was 128 actions, but this is
not a wire or storage constant. One-action transactions are rejected as the normal path: in the local spike, batching
128 actions improved throughput from 1,895 to 43,783 actions/s.

Batch failure rolls the physical transaction back. The runtime then isolates invalid/conflicting actions before
retrying an accepted batch; independent actions must not acquire partial state or be reported committed merely because
they shared a storage batch.

### Model heads and sequence allocation

A model head is current coordination metadata, not versioned document state:

- exact model ID string;
- stable Fluxzero segment;
- last `sequenceNumber`;
- `lastStateIndex`;
- history-completeness marker;
- lifecycle/deletion state where required.

The normal commit path stages targets, groups writes by model ID, reserves sequence ranges, and updates heads with
set-based statements. It must not execute `max(sequenceNumber)`, a head lookup, a transaction, or a network round trip
per target.

`eventSourced = false` continues to load current state directly from `DocumentStore`. It does not introduce document
revisions. Model-head metadata is still used for coordination and historical-dependency safety.

### Historical dependencies

Every stream entry stores the event/substep begin `readStateIndex`. Reconstructing model `A` loads an injected model `B`
as-of that boundary:

`modelEvent.stateIndex <= readStateIndex`

Loads for multiple dependencies are grouped by segment and fetched in batches. Reconstruction snapshots record their
own `stateIndex`, so the latest snapshot not newer than the requested boundary can be used.

Any state transition without a stored target event marks that model's event history incomplete. This includes a
state-changing `PUBLISH_ONLY` or `EventPublication.NEVER` path. The first implementation rejects an event-sourced
cross-model apply that would depend on incomplete history. It never substitutes current document state or a later
model version.

A model with incomplete history remains ineligible as a historical dependency until a future, explicitly designed
checkpoint mechanism establishes a new complete boundary. Such a checkpoint is not part of the foundational contract.

The spike verified:

- ordered range allocation;
- rollback invisibility of a reserved range;
- `readStateIndex = stateIndex - 1` for the one-event diagnostic actions;
- batched as-of reconstruction;
- explicit incomplete-history detection.

### Adaptive payload layout

The logical model stream has one entry per targeted model. Physically, an entry contains either:

- an inline, optionally compressed payload; or
- a reference to one separately stored payload shared by all targets of the original event.

The initial selection rule uses the **stored** payload length, not the Java object or uncompressed size:

- one target is always inline;
- otherwise share when avoided duplicate payload bytes exceed an internal configurable overhead budget;
- the initial measured crossover budget is approximately 512 bytes and must be recalibrated on production hardware.

This means a small event with two targets may remain inline, while a large event with two targets is shared. The choice
is internal storage metadata and may evolve without changing the public action protocol.

Reconstruction first reads ordered stream entries. Inline-only streams complete with that query. Shared references are
resolved with a batched payload lookup or measured join; no query is issued per referenced event.

Compression is opportunistic. LZ4 output is stored only when its size plus framing is smaller than the original.
Incompressible payloads remain raw.

Shared multi-event compressed blocks are rejected for the normal model load path. They reduce some WAL/storage but
force unrelated data to be fetched and decompressed for a single model event.

### Partitioning

Model ID partitioning uses the existing stable 128 Fluxzero segments:

`segment = ConsistentHashing.computeSegment(Id.toString())`

The stored segment is independent of the number of physical database partitions or runtimes. PostgreSQL initially uses
32 range partitions, four stable segments per partition. The count remains configurable and migration tooling may
split or regroup segment ranges without changing IDs or rehashing models.

Model streams and heads use the same segment boundary. Direct load, append, head lookup, and delete therefore prune to
one physical partition. A batched multi-ID load touches only the distinct segment partitions represented in the batch.

The separately stored shared-payload table is ordered/range-partitioned by `stateIndex`; it is not hash-partitioned by
an arbitrary target. Action-idempotency lookup receives its own appropriate index/partition strategy.

PostgreSQL-native hash partitioning is not selected even though it was slightly cheaper locally. Its internal hash is
not a stable Fluxzero routing or future shard key.

At 250,000 one-event models, 128 physical partitions were 8% slower to write and used 6% more relation space than 32,
without a meaningful load improvement. Therefore 32 is the initial physical default, not 128 tables by default.

### Temporal relationships

One child-keyed partitioned relationship table is rejected: parent lookup fans out to every child partition. The
runtime stores two transactionally updated temporal adjacency projections:

- `model_relation_by_parent`, segmented by parent ID;
- `model_relation_by_child`, segmented by child ID.

Both contain child ID, parent ID, stable role, `validFrom`, `validUntil`, detach reason, and the deleted parent ID needed
for later lineage/GDPR operations. Intervals are half-open:

`validFrom <= requestedStateIndex < validUntil`

Attach, detach, move, parent delete, and tombstone changes update both projections in the model-action transaction.
Relation action/history records make them verifiable and rebuildable. A rollback leaves neither direction partially
updated.

The spike verified exact move behavior at the boundary, absence after `validUntil`, rollback of a partial write, and
discoverability through `deletedParentId`.

### Lifecycle consequence of shared payloads

Hard-deleting one model removes its stream memberships, head, snapshots, cache, and direct document. It cannot erase a
shared event payload while another model stream still references it. An unreferenced model payload may be garbage
collected according to retention and hard-delete policy.

Sensitive fields that must be independently erasable cannot rely solely on deleting one shared event row. They require
Fluxzero data protection/crypto-shredding or an explicitly isolated event/value lifecycle. A published event may also
exist in the global log, so this requirement already extends beyond the model event store.

## Evidence

### Environment

- macOS 15.7.4, arm64
- Java 25
- PostgreSQL 18.4 in Docker Desktop
- PostgreSQL `shared_buffers = 128MB`
- PostgreSQL `wal_compression = off`
- JVM reported 14 available processors
- one local PostgreSQL instance; no replica

Results are diagnostic comparisons, not production capacity claims. Payloads marked “mixed” contained 25% deterministic
random bytes and 75% repeated bytes. LZ4 was pre-warmed. Layout comparisons used WAL LSN differences after an explicit
checkpoint. The recorded legacy aggregate control used a `pg_stat_wal` delta after its checkpoint; the retained
benchmark now uses LSN differences for subsequent regression runs.

### Event layout

All rows below used 32 physical partitions over 128 stable Fluxzero segments.

| Workload | Layout | Actions/s | Relation/logical | WAL/logical | Load requests/s |
|---|---:|---:|---:|---:|---:|
| 1 target, 1 KiB mixed | inline | 46,013 | 0.63× | 1.21× | 6,308 |
| 1 target, 1 KiB mixed | shared row | 34,612 | 0.75× | 1.73× | 5,272 |
| 2 targets, 1 KiB mixed | inline | 28,574 | 1.15× | 2.29× | 6,316 |
| 2 targets, 1 KiB mixed | shared row | 23,975 | 1.02× | 2.47× | 5,250 |
| 10 targets, 1 KiB mixed | inline | 7,180 | 5.90× | 9.38× | 6,068 |
| 10 targets, 1 KiB mixed | shared row | 7,219 | 3.60× | 7.99× | 5,269 |

With 10 targets and 16 KiB mixed payloads:

- inline: 1,441 actions/s, 22.5 MiB/s logical writes, 3.60× relation/logical;
- shared row: 4,778 actions/s, 74.7 MiB/s logical writes, 0.58× relation/logical;
- shared block: 4,432 actions/s and 0.49× relation/logical, but only 426 single-event reconstructions/s versus
  4,915/s for shared rows.

The block result is the reason shared block compression was rejected despite its smaller footprint.

### Compression

For one-target 1 KiB mixed payloads, individual LZ4 reduced relation/logical from 1.38× to 0.63× while write throughput
remained approximately 45 MiB/s. For random payloads, compression produced no relation reduction and reduced action
throughput from 46,538 to 38,741 actions/s. The result requires adaptive compression.

### State and historical loads

- One action/transaction: 1,895 actions/s.
- 128 actions/transaction: 43,783 actions/s.
- 100 events/model, as-of reconstruction: 150,487 events/s and 147 MiB/s logical payload.
- 32 models per as-of query: p50 8.84 ms, 180,864 events/s, and 177 MiB/s logical payload.

The batched history run also exercised rollback visibility and incomplete-history rejection.

### Cardinality

One million unique model heads and one-event streams with a 64-byte payload produced:

| Family | Heap | Indexes | Total | Approximate bytes/model |
|---|---:|---:|---:|---:|
| model head | 72 MiB | 65 MiB | 138 MiB | 145 B |
| one-entry model stream | 150 MiB | 80 MiB | 231 MiB | 242 B |
| combined | 222 MiB | 145 MiB | 368 MiB | 386 B |

A linear one-billion-model projection is approximately 145 GB for heads and 242 GB for the first 64-byte stream entry,
before replicas, backups, WAL retention, snapshots, documents, or relationships. This makes head/index lifecycle and
partition-local maintenance first-class operational concerns.

### Relationships

The relationship workload contained 200,000 temporal edges, 20 children per parent, and two parents per child.

| Layout | Writes (edges/s) | Relation bytes/edge | Parent reads/s | Child reads/s |
|---|---:|---:|---:|---:|
| one unpartitioned table, two indexes | 90,043 | 322 B | 6,211 | 7,097 |
| one child-partitioned table | 124,465 | 262 B | 1,511 | 6,290 |
| dual parent/child segment adjacency | 83,241 | 368 B | 5,540 | 6,348 |

The child-partitioned parent query planned 32 index scans. Dual adjacency costs about 14% more relation space and 8%
write throughput than the local unpartitioned control, but keeps both traversal directions bounded to one partition
and remains routable when the graph is sharded.

### Existing aggregate reference

The enhanced existing-path benchmark used one 16 KiB event per request, 100 hot aggregate streams, 16 writers, and
concurrent reconstruction:

- mixed write: 265 events/s and 4.15 MiB/s logical, p99 131 ms;
- concurrent reads: 1.15 GiB/s logical, p99 20.9 ms;
- subsequent warm reads: 1.52 GiB/s logical, p99 28.8 ms;
- write relation/logical: 1.21×;
- write WAL/logical: 1.31×.

This workload exposes the cost of small synchronous requests and repeatedly extending large open aggregate chunks. It
is not the optimized batching profile used to claim production store capacity, but it is now a reproducible regression
control.

## Gates for the production implementation

The vertical implementation may begin. It must not be declared production-ready until:

- the unchanged aggregate path has no statistically meaningful throughput or p99 regression; initial alert threshold
  is 2%;
- the common one-target model path is within 5% throughput and 10% p99 of the equivalent aggregate storage operation
  after matching serialization, batching, publication, and durability settings;
- multi-target physical payload bytes do not grow with target count after the adaptive sharing crossover;
- commit statements and round trips are bounded by batch/parameter limits, never one per target;
- direct ID operations and both graph directions demonstrably prune to the expected segment partitions;
- cold-cache, concurrent store/load, vacuum/bloat, checkpoint, restart, overload, backup/restore, replication, and
  retention tests pass;
- representative production hardware sustains the required 100 GB/min write/read envelope, or a documented horizontal
  segment deployment reaches it with headroom;
- action idempotency and unknown commit outcome are tested under failure;
- hard-delete/shared-payload and history-erasure behavior is accepted in lifecycle/GDPR tests.

## Rejected alternatives

- Full payload copied to every target stream: unbounded target-count amplification.
- Shared payload row for every single-target event: slower and larger than inline.
- Shared compressed payload blocks: unacceptable random reconstruction amplification.
- Always compress: wastes CPU on incompressible data.
- PostgreSQL-native hash as the durable routing key: not stable outside PostgreSQL.
- Physical partition count as the hash modulus: re-partitioning would change routing.
- 128 physical partitions by default: overhead without a measured lookup benefit at tested cardinality.
- One child- or parent-partitioned relationship table: opposite traversal fans out.
- One action per JDBC transaction: state-index/commit overhead dominates.
- Per-model `max(sequenceNumber)` lookup on head-cache miss: cardinality-dependent round trips.
- Versioned document state for `eventSourced = false`: unnecessary duplicate source of truth.
- Current-state fallback during historical dependency reconstruction: nondeterministic model state.

## Follow-up boundary

The first real implementation is a thin end-to-end slice: one `@Model`, one event, one target, state/head commit,
reconstruction, cache, and direct search through SDK and runtime. Multi-target sharing, cross-model dependencies, and
temporal graph loading follow only after that path continuously satisfies the gates above.

Horizontal request/result-log coordination and cross-database atomicity remain separate projects. The stable segment
and payload/membership split deliberately keep those future designs possible without turning them into Phase 0.
