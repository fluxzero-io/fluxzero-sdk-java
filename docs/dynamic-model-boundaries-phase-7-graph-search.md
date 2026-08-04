# Phase 7 checkpoint — bounded current graph search and composition

Date: 2026-07-26

- Graph-search implementation: SDK `7c83d714573`, runtime `619ceeee`
- Graph-composition implementation: SDK `e4de8597314`, runtime `f95cb5c4`
- Materialized-graph implementation: SDK `304756a3f70`, runtime `a236277c`

## Outcome

Independent model documents can now be searched through their current model relationships without first enabling a
materialized whole-tree CQRS projection.

The SDK exposes target-relative constraints:

- `whereParent(...)` and `whereAncestor(...)` return target models whose related parent/ancestor document matches;
- `whereChild(...)` and `whereDescendant(...)` return target models whose related child/descendant document matches;
- minimum and maximum depth select direct, grandparent/grandchild, or arbitrary bounded lineage;
- the related document collection qualifies the model type and optional explicit `@ParentId(path)` values qualify
  relationship edges.

No class-derived path, duplicate relationship role, recursive SQL, or relation-store implementation detail appears in
the public search API.

The provisional `includeModelGraph()` modifier was replaced in Phase 19 by `searchGraph(Root.class)`. A graph result is
explicitly graph-shaped JSON rather than the root Java model. The default route reads a configured materialized graph
projection and otherwise stitches the current direct documents live; `searchGraph(Root.class, true)` forces the live
route.

An independent root can now also opt into an asynchronously maintained graph collection with
`@Model(graphProjection = @GraphProjection(collection = "..."))`. This never replaces or weakens the direct model
collection: direct model search remains part of command success, while the separately named graph collection has an
explicit observable lag.

## Execution contract

`SearchModelDocuments` is a distinct wire request. This is intentional: an older runtime must reject graph search
instead of deserializing an ordinary `SearchDocuments` request and silently ignoring its new relationship semantics.

`SearchModelGraphDocuments` is a second distinct wire request for the same reason. It carries explicit depth, model,
placement, collection, and output-byte bounds. Live candidate-root discovery is bounded before composition. Graph
constraints, sorting, pagination and path filtering are applied after stitching, matching an ordinary query against
the materialized graph collection.

The first runtime implementation is a bounded staged plan that also works when search and model relationships live in
different databases:

1. execute each ordinary related-document query with `maxRelatedModels + 1`;
2. pin one durable current model `stateIndex`;
3. traverse the dual hash-partitioned temporal adjacency projection at that boundary, one set-based query per breadth
   level;
4. intersect multiple relationship constraints and an optional pre-existing target-ID restriction;
5. execute the unchanged target-document query, including target sorting, pagination, time and path filters, with the
   bounded candidate IDs as an internal predicate.

Relationship constraints are ANDed. Traversal tracks `(modelId, depth)` states, remains cycle-safe, and refuses requests
that exceed explicit related-model or traversed-model limits with guidance to narrow the query or use a materialized
graph projection.

The runtime never sends candidate ID lists back to the SDK. A future co-located recursive/join plan can replace the
staged internals without changing this wire or fluent API.

Historical full-text graph search remains deliberately deferred. Relationship traversal itself still uses temporal
half-open intervals, but this route combines it only with current direct documents and does not invent versioned
document history.

## Stable composition contract

- Only a relationship with an explicit `@ParentId(path = "...")` participates.
- Every configured path is list-valued. Numeric path segments and `$metadata` are reserved and rejected while model
  metadata is constructed.
- Children sharing one parent/path are ordered by their globally unique model ID. Their list ordinal is appended to the
  path.
- A child without an available current direct document is omitted without removing its relationship.
- A shared DAG child is placed at every reachable parent/path. Distinct model and placement bounds separately limit
  traversal and output expansion.
- Cycles fail explicitly. A composition path that overlaps another path or a direct parent-document path also fails
  explicitly instead of overwriting data.
- Root metadata remains root metadata. Child metadata is not copied; child entries, facets, sortables, and summaries are
  placed below their graph path.
- Constraints, sorting, path filters, skip and pagination run after stitching, so live and materialized graph searches
  have the same complete-document semantics.

## Consistency boundary

The directly changed model document remains synchronously searchable when model commit succeeds. Graph search pins
relationship traversal to one current `stateIndex`.

The first implementation does not claim a distributed snapshot across a separate search database and relationship
database. That would require the cross-store atomicity/horizontal-runtime work explicitly deferred by the main backlog.
Monotone direct-document fences still prevent older materialization from overwriting newer model search state.

## Storage and performance

No new relationship index or event duplication was introduced. Traversal uses the already selected child-keyed and
parent-keyed 128-segment adjacency projections. Batch queries join requested `(segment, id)` pairs so both directions
retain partition pruning.

The current direct-document collection is retained as compact coordination metadata: each model head stores one
nullable integer referencing a namespace-local collection registry. Collection names are therefore not duplicated
across potentially billions of model heads. Graph reads resolve exact `(segment, modelId)` pairs and then issue one
multi-collection document query. Root documents already returned by search are not loaded again.

The retained local PostgreSQL diagnostic used 5,000 independently searchable models, 1,024 parent documents, 5,000
current edges, 128 concurrent model writes, and 50 measured graph searches after one warm-up:

| Scenario | End-to-end p50 / p95 / p99 | Boundary p95 | Related query p95 | Traversal p95 | Target query p95 |
| --- | --- | --- | --- | --- | --- |
| selective: 1 parent → 5 targets | 1.462 / 1.785 / 1.895 ms | 0.070 ms | 0.366 ms | 1.025 ms | 0.431 ms |
| broad: 1,024 parents → 5,000 targets | 66.260 / 72.755 / 74.959 ms | 0.257 ms | 6.362 ms | 13.312 ms | 53.395 ms |

The broad result shows that materializing and returning 5,000 target documents, not relationship traversal, dominates
this workload. It also validates why broad requests need explicit refusal limits and why a co-located join plan and
materialized graph projection remain valuable optimizations rather than prerequisites for the API.

This is diagnostic evidence, not the Phase 9 production-scale certification. Recursive depth, paging, concurrent moves,
cold-cache behavior, larger fan-out, allocation/GC/IOPS, and the 100-GB/min customer envelope remain explicit gates.

The retained benchmark now also measures current graph composition with 1,024 root documents, 5,000 independently
stored child models, 5,000 explicit edges, 256-byte configured child payloads, and 20 measured queries after warm-up:

| Scenario | End-to-end p50 / p95 / p99 | Root query p95 | Traversal p95 | Collection locator p95 | Child load p95 | Stitch p95 |
| --- | --- | --- | --- | --- | --- | --- |
| selective: 1 root → 5 children | 2.371 / 2.655 / 3.422 ms | 0.392 ms | 1.044 ms | 0.915 ms | 0.270 ms | 0.136 ms |
| broad: 1,024 roots → 5,000 children | 49.274 / 65.461 / 66.057 ms | 6.230 ms | 13.220 ms | 4.362 ms | 19.689 ms | 20.754 ms |

The first locator query used independent segment and model-ID arrays. At broad fan-out PostgreSQL effectively paid for
their cross-product and locator p50 was about 799 ms. Joining aligned `(segment, modelId)` arrays reduced it to 3.943 ms
p50 without adding an index or denormalizing collection names. This failure and correction are retained because the
same query shape matters much more at the intended scale than the happy-path API timings.

## Opt-in materialized graph projection

The durable definition contains the root's stored type descriptor, synchronous direct-document collection, distinct
graph collection, explicit composition bounds, and optional canonical-path replacements. A root must be searchable.
Every stitched child must likewise have an available current document. Phase 18 separated graph participation from
independent searchability: a non-searchable child with an explicit parent path uses the internal
`$modelGraphComponents` collection for composition without gaining its own searchable collection. A non-searchable
child without an explicit path remains omitted and retains the zero-document-write fast path.

The SDK registers configured roots before an action that can affect them commits. It discovers roots from direct
transition types and recursively from statically typed `Id<Parent>` references, including payload-side applies.
Ambiguous or untyped parent links require explicit application registration because their Java root type cannot be
derived without loading application state. A transient registration failure is not cached permanently.

Registration is idempotent. A first registration always performs a bounded resumable scan of current roots, even when
the caller does not explicitly request a rebuild. Changing paths or bounds advances a monotone configuration version
and rebuilds. The target collection cannot later be rebound to another root type or direct collection.

### Durable execution and fences

With at least one projection registered, every accepted model substep writes one compact projection signal in the same
JDBC transaction as its events, stream memberships, heads, temporal relationships, action result, and global event
publication when that log is co-located. The signal refers to the already idempotent action/substep and target IDs; it
does not duplicate event payloads or graph documents.

The asynchronous worker:

1. waits until that action's direct document/snapshot materialization package is durably complete;
2. coalesces consecutive signals;
3. resolves affected roots at both the pre-batch and post-batch temporal boundaries, so moves update the old and new
   roots;
4. upserts one durable task per `(hash segment, graph collection, root ID)`;
5. resolves graph heads and temporal edges at a safe contiguous projection boundary;
6. batch-loads direct root and child documents by collection and stitches each root under its configured bounds;
7. applies one bulk graph-document mutation and then removes only tasks not superseded by newer work.

Signals are unique by `stateIndex`; root tasks coalesce by root; search writes use a lexicographic
`(configurationVersion, stateIndex)` fence. Duplicate delivery, reversed completion, restarts, changed definitions, and
late older work therefore cannot overwrite a newer graph. A logical root delete emits a fenced graph-document delete.
An already pending root or delete is atomically carried to a changed configuration version instead of becoming an
unreachable old-version task.

Projection progress reports four distinct facts: current source `stateIndex`, highest contiguous consumed-signal
`processedStateIndex`, pending signals, and pending materialized roots, plus whether a resumable root scan is active.
`processedStateIndex` alone does not promise that every queued graph document has been written; callers requiring
freshness wait for both backlog counts and rebuild state.

No projection storage is created eagerly. When no definition exists, model commits do not query a projection table,
allocate signal objects, or write projection bytes. Once enabled, signal and task tables are separate from model
streams and the task table uses the same stable 128 hash segments over 32 physical PostgreSQL partitions. Direct search
and asynchronous graph search use separate fence tables and may live in separate databases; the graph write is a
recoverable durable projection, not part of direct model commit success.

### Retained materialization diagnostic

The retained local PostgreSQL comparison writes 2,000 independent roots with one 256-byte event and one 256-byte
direct document per action at concurrency 128. Publication, relationships, and model loading are disabled so the
measurement isolates direct action storage plus optional graph materialization.

| Profile | Action write throughput | Projection catch-up | Physical storage | WAL |
| --- | ---: | ---: | ---: | ---: |
| projection disabled | 2,097 actions/s | n/a | 4.45 MiB | 7.66 MiB |
| default bounds (`maxModels=10,000`) | 4,957 actions/s | 1.802 s / ~1,110 roots/s | 7.41 MiB | 13.15 MiB |
| leaf profile (`maxModels=1`) | 5,201 actions/s | 0.267 s / ~7,491 roots/s | 6.98 MiB | 13.18 MiB |

These are noisy single local runs; the apparently higher write throughput with projection enabled is not treated as an
improvement. The retained conclusions are the measured storage/WAL amplification, the zero-storage disabled route,
and the effect of configured per-root bounds on safe set-based materialization batch width. Phase 9 must repeat this
against long runs, deep/wide/shared graphs, cold cache, multiple runtimes, vacuum/bloat, IOPS, and the 100-GB/min
customer envelope.

## Verification

Coverage includes:

- parent/grandparent and child/descendant search through the normal `TestFixture`;
- exact-depth traversal in both directions;
- explicit path filtering;
- traversal refusal limits;
- related-query bounding;
- preservation of target sorting and ID intersection in the runtime endpoint;
- exact candidate-ID predicates in both JDBC search implementations, plus wire-compatible ordinary empty-list
  behavior and graph-level empty-candidate short-circuiting;
- JSON and CBOR wire round trips for the distinct graph-search request;
- JSON and CBOR wire round trips for the distinct composition request and all bounds;
- deterministic child/grandchild placement, shared-DAG placement limits, missing documents, metadata omission, and
  direct/nested path collisions in the shared stitcher;
- direct `TestFixture` model creation and search through a composed multi-path grandchild graph;
- automatic projection registration through a typed ancestor, including retry after a transient registration failure;
- dormant projection storage before opt-in;
- projection-local path replacement without modifying canonical relationship truth;
- old/new root rematerialization after a move, logical root deletion, late registration, full rebuild, changed
  configuration with pending work, failure retry, and process restart;
- configuration-before-state graph-document fences in both JDBC search implementations;
- a real SDK-to-websocket-to-runtime-to-PostgreSQL-to-search materialization round trip;
- one multi-collection JDBC child-document lookup in both regular and RUM stores;
- compact collection-registry persistence, preservation across state-only writes, clearing on delete, and duplicate-ID
  input handling;
- final path filtering after runtime stitching;
- explicit failure when graph search is not configured.

## Deliberately open

- Compile a co-located search/relation store into one relational query plan.
- Recursive-depth, paging, concurrent-move, cold-cache, and production-scale performance certification.
- Historical full-text graph search.
- Propagate newly registered projection definitions to already-running sibling runtime instances. The current
  in-process fast-path flag intentionally avoids a configuration query on every model commit; one websocket/runtime
  instance is coherent, and restart discovers durable definitions, but horizontal rollout needs the request/result-log
  coordination planned for the runtime architecture before this can be certified.
- `LocalClient`, the test server and synchronous `TestFixture` executions now run an in-memory projection worker using
  the same graph stitcher. `AWAIT` therefore materializes affected roots before command completion and reports a real
  caught-up boundary; live `searchGraph(..., true)` and durable projection assertions are both available locally.
