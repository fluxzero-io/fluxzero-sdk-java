# Phase 21 runtime redesign

Status: accepted implementation decision on `feature/dynamic-model-boundaries`

Date: 2026-07-28

Baseline runtime: `7fc0fa3f`

Comparison base: `origin/main` at `b489b3fc`

## Decision

Model persistence remains one namespace-scoped service, but its durable change feed is now one compact
`model_update` log. Cache tracking, direct-document recovery, hard-erasure tracking and graph projection consume that
same committed fact. The previous receipt-target, graph-signal and graph-task stores are removed.

The model service assigns a monotone, time-derived `stateIndex` with `IndexUtils`. One commit receives a contiguous
state range, one state per substep. `sequenceNumber` remains local to one model stream; `SerializedMessage#index`
remains the global event-log index. These values are deliberately not conflated.

The service is created lazily and shared by the event-sourcing and search endpoints of one normalized namespace.
Closing an individual websocket endpoint does not close it. An availability loss closes all namespace services; a new
active runtime resumes namespaces that already contain model state and drains durable pending work.

## Why this is a compact log rather than another `MessageStore`

The design reuses Fluxzero's log principles:

- `IndexUtils` supplies the same time-sortable index shape;
- rows are range-partitioned by time and complete expired partitions are dropped;
- consumers read ordered bounded pages and long-poll locally;
- local commits wake waiters without periodic database polling;
- a durable consumer position, not an in-memory notification, is authoritative after restart.

It does not instantiate a generic `JdbcMessageStore`. A model update is one commit containing several substeps and
target memberships, not another globally published message. It must be inserted on the caller's existing JDBC
connection in the exact transaction that mutates heads, streams and relationships. The generic store would add message
staging, partition metadata, message serialization and its own transaction/commit lifecycle. It would therefore add
tables and copying while making the required transaction composition harder.

This is intentionally a small model-specific table using existing indexing, partition and long-poll patterns, not a
second queue platform.

## Atomic commit and split search storage

For a JDBC-backed event log in the model database, the following become visible in one transaction:

1. the locked namespace `model_state` and assigned state range;
2. the durable idempotency/commit boundary;
3. model heads, stream memberships and shared or inline event payloads;
4. temporal relation closes and opens;
5. one `model_update` row;
6. the original global event, once, through the existing `JdbcMessageStore` callback.

Any failure rolls the complete package back. An external event-log implementation retains the explicitly documented
publish-first recovery boundary; it cannot be made transactionally atomic without owning that external transaction.

Search may use another database owned by the same runtime. No XA claim is made. The core transaction temporarily keeps
the exact direct document/snapshot projection in `model_commit`. The runtime writes it idempotently to search behind a
monotone fence. Command completion waits for direct materialization, so direct model search is visible immediately.
After success, the projection blob is removed. A restart scans the durable pending marker and remaining blobs and
continues automatically; client redelivery is not required.

Graph projection is asynchronous by default. `AWAIT` delays result completion until each affected projection cursor has
passed the commit boundary. Replay of `model_update` is the durable work queue. There is no graph signal table, root
task table or periodic remote-runtime observer.

## Durable data inventory

Core first use creates 14 logical tables and 48 physical relations. Four high-cardinality keyspaces have eight physical
hash-range children. The current update hour and current shared-payload day each add one lazy time child.

| Data | Cardinality and retention | Access and justification | Physical relations initially |
| --- | --- | --- | ---: |
| `model_state` | one row per namespace, durable | state head, pending floors, erasure key and capability flags | 1 |
| `model_commit` | one row per commit, durable | idempotency and exact commit/substep boundary; transient projection cleared after materialization; sparse raw target IDs only while an unstreamed target can still require hard-erasure repair | 9 |
| `model_update` | one row per commit, one hour by default | cache feed and recoverable projection input; time partitions dropped as complete units | 2 |
| `model_type` | one row per serialized model type, durable | untyped graph/model loading and serializer aliases | 1 |
| `model_document_collection` | one row per direct model collection, durable | batched direct document lookup | 1 |
| `model_head` | one row per model, durable | current stream/document/type head | 9 |
| `model_stream` | one row per stored model-event membership, lifecycle retention | model-keyed reconstruction; sparse shared-payload ownership index | 9 |
| `model_payload` | one row per shared payload, lifecycle retention | payload stored once for larger multi-target events; daily time partitions are lazy | 2 |
| `model_relation_descriptor` | one row per `(parentType,path)`, durable | deduplicates stable edge metadata | 1 |
| `model_relation_by_child` | one row per temporal edge version, lifecycle retention | child-keyed PK plus one parent traversal index; the duplicate parent table is removed | 9 |
| `model_deletion` | one row per hard-delete request, durable | deletion idempotency and final counts | 1 |
| `model_deletion_target` | bounded rows only while a deletion is pending | restartable erasure batches; rows are removed on completion | 1 |
| `model_erasure_fence` | one row per erased model token, durable | prevents stale resurrection without retaining the raw erased ID | 1 |
| `model_protected_lineage` | one row per detached protected edge, lifecycle retention | later GDPR deletion of descendants after parent erasure | 1 |

Graph projection adds one unpartitioned configuration/cursor table, for 49 core relations. Search-side model use is
lazy and adds at most three relations:

| Search data | Retention | Purpose |
| --- | --- | --- |
| `model_write_fence` | durable latest fence/tombstone | independent direct and graph fences distinguished by `kind` |
| `model_erasure_state` | one durable row | search-database copy of the namespace HMAC key |
| `model_erasure_fence` | durable | blocks late search resurrection after hard deletion |

Core plus search is 51 initial relations; core plus graph plus search is 52. Ordinary user collection partitions and
indexes are excluded because they already belong to search itself. Generic `JdbcSearchStore` construction creates none
of these three model relations.

Here, “physical relations” follows the backlog's table budget and means table parents plus table partitions, not every
PostgreSQL catalog relation. The measured 48-table core additionally has three identity sequences and 89 physical index
objects (27 partitioned roots or standalone indexes plus their inherited children). Those indexes implement primary
keys and the named commit-boundary, sparse erasure, payload-ownership, parent-traversal, lineage and pending-work access
paths; they are recorded by the executable schema inventory but are not disguised as extra tables.

The following unreleased preview objects are deterministically removed:

- `model_commit_receipt` and `model_commit_receipt_target`;
- `model_commit_target`, after any pending unstreamed target references have been migrated into the owning commit row;
- `model_update_partition` and the JSON target GIN index;
- `model_graph_projection_signal` and `model_graph_projection_task`;
- duplicate `model_relation_by_parent`;
- separate direct and graph document-fence tables.

Preview graph-signal and graph-task tables are dropped only when both are empty. Pending work in either store causes a
clear startup failure so it cannot be silently lost.

## Indexing and write amplification

Stable hashing uses 128 logical segments grouped into eight physical partitions. This keeps partition pruning and future
redistribution stable without multiplying every large table by 32. Control, descriptor, deletion and fence tables are
not partitioned.

Temporal relationships are stored once. The child PK serves child and historical reads; one
`(parent_id, valid_from, valid_until)` index serves parent traversal. Cycle validation prefetches all independent
changed roots in one query and only follows deeper ancestors on demand. A regression test fixes the one-batch contract
for 128 independent steps.

Small single-target and small multi-target payloads remain inline. A larger multi-target event has one payload plus
light stream memberships. Hard deletion removes memberships first and removes the shared payload only when no surviving
membership references it. The update log records explicit target IDs only for targets without a stream membership;
stream targets are already located through `model_stream`. This keeps common multi-target rows small without weakening
hard erasure. The same exceptional targets are retained as a sparse JSON array on their existing commit row, protected
by a partial GIN index that contains no entries for ordinary streamed commits. This permits a later hard delete to find
and scrub pending split-store materialization even after the one-hour update feed has expired, without restoring a
row-per-target table or scanning an hour of updates. The delete query repeats the partial-index predicate; its
regression test requires bitmap index scans over the eight commit partitions and rejects sequential scans.

## Workers, waiters and cursors

An aggregate/search-only namespace has no model schema, coordinator, executor, polling loop or HMAC state. The lazy
wrapper performs model initialization only when a model operation is used, or when startup detects existing model state
that may contain pending work.

An initialized model namespace can use:

- one existing ordered `Backlog` coordinator for commits;
- one short-lived virtual materialization repair worker while direct work is pending;
- one short-lived virtual graph worker while a configured projection is behind;
- one bounded virtual thread per active long-poll tracker or `AWAIT` request;
- one short-lived erasure worker per explicit deletion.

There is no 100-ms observer. Commits wake local waiters immediately. After failover, the newly active runtime reads the
durable state, update log and projection cursor before serving new tracking progress.

The graph cursor is `model_graph_projection.processed_state_index`. Cache tracking supplies its cursor in
`TrackModelUpdates`; a one-hour lag is deliberately outside the supported live-cache window and returns a reset
boundary. Pending direct materialization and erasure expose their minimum state in `model_state`, preventing a tracker
from advertising unsafe visibility.

## Retention

- `model_update`: one hour by default (`fluxzero.modelUpdateRetention`, ISO-8601 duration). A graph cursor prevents
  deletion of a partition it has not consumed.
- `model_commit`: compact commit identity and exact substep result are retained for idempotency and event-commit
  boundaries. Direct document/snapshot projection bytes are cleared immediately after successful materialization.
- completed deletion targets: removed immediately; the compact deletion result remains.
- temporal streams, shared payloads, erasure fences and protected lineage: governed by explicit model lifecycle, not
  update-feed retention.
- graph documents and direct documents: latest fenced state in search.

## Paired performance evidence

Both worktrees were compiled separately and invoked with an explicit
`benchmarks/target/test-classes:runtime/target/classes:<dependencies>` classpath. This avoids accidentally benchmarking
the same installed runtime jar twice. PostgreSQL, JVM, payload, warm-up and concurrency were identical per pair.

| Local PostgreSQL 18 profile | Before `7fc0fa3f` | Phase 21 | Result |
| --- | ---: | ---: | ---: |
| 1 target, 1 KiB, commits/s | 6,915 | 10,566 | +52.8% |
| 1 target current loads/s | 23,511 | 26,724 | +13.7% |
| 2 targets, 1 KiB, commits/s | 5,186 | 7,868 | +51.7% |
| 10 targets, shared 16 KiB, median commits/s | 998 | 1,139 | +14.1% |
| 10 targets median p50 / p95 | 115.9 / 197.1 ms | 105.0 / 174.3 ms | lower |
| 100 targets, shared 16 KiB, commits/s | 181 | 290 | +60.2% |
| direct searchable commits/s | 3,157 | 3,722 | +17.9% |
| STORE_ONLY commits/s | 6,675 | 9,948 | +49.0% |
| relation commits/s | 2,432 | 3,201 | +31.6% |
| relation current / ancestor / historical loads/s | 22,974 / 17,338 / 22,048 | 27,410 / 23,449 / 28,544 | all higher |
| graph projection catch-up after 10k writes | 10.63 s | 0.016 s | caught up during writes |
| selective graph searches/s | 478 | 783 | +63.8% |
| broad graph compositions/s | 11 | 18 | +63.6% |
| wide hard erasure, 10k models/s | 2,678 | 11,254 | 4.20x |
| legacy aggregate append requests/s | 1,770 | 1,788 | statistically unchanged |
| legacy aggregate event loads/s | 1,676 | 1,633 | statistically unchanged |

Representative physical storage and WAL also decreased: one-target writes used 18.09 versus 20.35 MiB physical and
24.21 versus 26.40 MiB WAL; two-target writes used 10.94 versus 12.62 MiB and 16.96 versus 18.84 MiB; graph projection
used 40.20 versus 70.94 MiB and 58.99 versus 99.10 MiB. The 100-target physical delta differed by 0.14 MiB (0.5%) while
WAL and latency fell substantially; this is not a meaningful amplification regression.

These are local diagnostics, not certification of a particular customer's 100-GB/min hardware. They do demonstrate
that the redesign removes singleton polling, N+1 relationship validation and redundant rows rather than moving the
bottleneck elsewhere.

After adding the sparse erasure field and index, the one-target hot-write confirmation still measured a median 7,716
commits/s versus 5,346 before Phase 21 (+44.3%). Direct searchable commits measured 5,046 versus 3,102 commits/s
(+62.7%). Ordinary commits put no entry in the partial GIN index; its eight initially empty child indexes add fixed
catalog/storage overhead but no per-commit index write.

## Code budget

Against `origin/main`, runtime Java is:

- production: 22,847 to 32,839 lines, net **+9,992**;
- tests and benchmarks: 16,959 to 30,260 lines, net **+13,301**.

Within Phase 21 itself, production changed by +7,304/−13,534 tracked lines plus the 390-line update log: net **−5,840**.
Tests and benchmarks changed by +656/−1,778 lines at the final count. The largest retained classes are
`JdbcModelCommitStore` (5,538 lines), `JdbcSearchStore` (2,646), `JdbcModelGraphProjectionStore` (876) and
`JdbcModelUpdateLog` (390). The commit store remains large because it owns one transaction spanning idempotency,
streams, payload sharing, temporal relations and lifecycle; splitting those statements behind independent state
machines would reintroduce the architecture this phase removed.

## Rejected alternatives

- Keep receipt, target, signal and root-task tables: rejected because the same committed commit was being copied into
  several state machines with separate recovery and retention.
- Duplicate temporal edges by child and parent: rejected after indexed single-storage traversal met the load budget.
- Precreate 32 children for every high-cardinality table: rejected; eight physical partitions retain pruning and
  scaling headroom with far lower catalog and startup cost.
- Put search in the core transaction with XA: rejected for split databases. Fenced autonomous recovery preserves the
  user-visible direct-search contract without pretending two databases commit atomically.
- Make graph composition depend on `searchable=true`: rejected. `searchable` controls a model's own collection;
  explicit graph `path` controls root composition.

## Remaining limits

- Only one runtime is active at a time. Multi-active distribution is intentionally deferred to request/result logs;
  no permanent database poll has been added in anticipation.
- The one-hour update retention is a live cache-coherence contract, not historical model retention. A consumer behind
  that boundary resets rather than silently skipping updates.
- `model_commit` idempotency rows currently have no archival policy. Removing them requires a replacement for exact
  duplicate results and commit-boundary lookup and is not hidden inside this release.
