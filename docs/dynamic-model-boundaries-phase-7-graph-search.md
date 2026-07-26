# Phase 7 checkpoint — bounded current graph search and composition

Date: 2026-07-26

- Graph-search implementation: SDK `7c83d714573`, runtime `619ceeee`
- Graph-composition implementation: SDK `e4de8597314`, runtime `f95cb5c4`

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

`includeModelGraph()` additionally returns each matching current root document with its explicitly placed current child
graph stitched into it. This remains a read-time operation over independent direct documents; it does not create or
silently enable a materialized whole-tree projection.

## Execution contract

`SearchModelDocuments` is a distinct wire request. This is intentional: an older runtime must reject graph search
instead of deserializing an ordinary `SearchDocuments` request and silently ignoring its new relationship semantics.

`SearchModelGraphDocuments` is a second distinct wire request for the same reason. It carries explicit depth, model,
placement, collection, and output-byte bounds. Search pagination still applies to roots; graph bounds apply separately
to each returned root page.

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
- Path filters run after stitching, so callers can select composed branches. Root filtering, sorting, skip, and
  pagination still run against the ordinary root document.

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
- one multi-collection JDBC child-document lookup in both regular and RUM stores;
- compact collection-registry persistence, preservation across state-only writes, clearing on delete, and duplicate-ID
  input handling;
- final path filtering after runtime stitching;
- explicit failure when graph search is not configured.

## Deliberately open

- Compile a co-located search/relation store into one relational query plan.
- Recursive-depth, paging, concurrent-move, cold-cache, and production-scale performance certification.
- The opt-in asynchronous materialized root projection in Slice 7.3.
- Historical full-text graph search.
