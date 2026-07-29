# Phase 6 ancestor injection

This checkpoint implements read-only parent, grandparent, and arbitrary ancestor injection for independent models. It
keeps the existing direct-ID load path unchanged and adds graph work only when a selected model parameter has no direct
ID in the handled payload.

## Resolution contract

- A typed payload property or the target model's `@EntityId` property remains a direct model target.
- Parameter-level `@Association("name")` selects that payload property when it exists.
- If the property is absent, a read-only parameter becomes an ancestor dependency. The association value then matches
  an explicit `@ParentId(path = "name")`; an unqualified parameter must have exactly one reachable compatible type.
- Ancestors can be injected as either their value or `Entity<T>` into `@AssertLegal`, `@InterceptApply`, and `@Apply`.
  They are never writable merely because they were reached through the graph.
- Missing and ambiguous ancestors, incompatible stored types, cycles, maximum depth, and maximum node count fail before
  an commit is committed.
- Stored FQNs remain graph/deserialization metadata, not model identity. They are passed through the serializer's
  existing type-caster chain. When stored type metadata is absent but the handler supplies an unambiguous model class,
  that requested class is sufficient.

## Pinned loading and replay

`GetModelAncestors` is a separate protocol request, so an old runtime rejects it through the existing unsupported
request behavior instead of silently returning a descendant graph. One request contains all direct roots and pins
heads and temporal edges to one `stateIndex`.

The runtime traverses the child-keyed relationship table breadth-first. Every level is one batched, child-hash-pruned
query for the whole frontier; there is no request or query per node. Selected model streams are then batch-loaded
through the existing partitioned stream protocol. The SDK caches the expanded target plan and loaded entities within
the commit.

An earlier interceptor substep may change a `@ParentId`. The commit loader overlays those staged outgoing edges and
keys its expansion cache by the staged relationship set, so later substeps observe the new parent without an
intermediate commit. During later model reconstruction, an apply resolves its graph at the state immediately before
the stored membership and reconstructs ancestor values at the membership's original read/commit-prefix boundary. This
keeps current execution and event sourcing equivalent without storing target-state checkpoints.

## JDBC diagnostic

The existing `JdbcModelCommitStoreBenchmark` now includes ancestor-graph loads when relationships are enabled. A local
PostgreSQL run used 5,000 one-target commits, one relationship per target, 1-KiB stored events, 128-root graph batches,
five load iterations, and head-only stream results:

| Measurement | Result |
| --- | ---: |
| Model-commit writes | 3,606 commits/s |
| Ordinary current head loads | 64,723 models/s |
| Ancestor graph roots | 25,479 roots/s |
| Ancestor graph nodes | 50,958 nodes/s |
| Ancestor graph edges | 25,479 edges/s |
| Ancestor batch latency p50 / p95 / p99 / max | 4.797 / 5.363 / 7.002 / 10.791 ms |

This is a comparative local diagnostic, not production certification. Deep, wide, and highly shared DAG benchmarks
remain open. A recursive single-SQL-query alternative should only replace breadth batching if those measurements show
that depth latency matters enough to justify carrying child-hash routing information through SQL recursion.

## Verification

- Direct parents, grandparents, qualified same-type parents, missing/ambiguous ancestors, persisted moves, and
  same-commit staged moves are covered end-to-end.
- Cold reconstruction covers applies that inject ancestors, including a relationship changed in an earlier substep of
  the same commit.
- JSON and CBOR protocol round trips cover multi-root commit-boundary requests.
- In-memory and JDBC runtime tests cover historical multi-parent DAG traversal, hash-partition routing, deterministic
  ordering, and depth refusal.
- The complete SDK reactor passed, including test-server, proxy, annotation processing, and Java/Kotlin downstream
  projects.
- The complete runtime reactor passed with 540 tests.
