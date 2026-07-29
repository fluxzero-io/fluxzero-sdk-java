# Phase 6 temporal graph integrity

This checkpoint completes the current/as-of relationship contract and makes model relationships a directed acyclic
graph at every persisted model-commit substep. It does not turn conflict resolution into the design frame and adds no
graph work to an ordinary model write without a newly attached parent.

## Graph and time contract

Every relationship is owned by its child and points from child to parent. The runtime stores the same temporal edge in
two hash-routed adjacencies:

- child-to-parent for parent and ancestor traversal;
- parent-to-child for child and descendant traversal.

`GetModelGraph` follows descendants from one supplied root. `GetModelAncestors` follows parents for one or more supplied
roots. Both pin all returned roots, nodes, edges, heads, memberships, and payloads to one namespace `stateIndex`. A
current request chooses that boundary once; an explicit historical request treats its `stateIndex` as inclusive.

`ModelRepository.loadGraph(...)` reconstructs the current bounded, composable descendant graph.
`ModelRepository.loadGraphAt(...)` reconstructs the same graph at an explicit historical boundary. Each selected model
still owns and replays its independent stream; the graph only selects the nodes and supplies their explicit child-owned
composition paths.

Relationship intervals are half-open:

`validFrom <= requestedStateIndex < validUntil`

An edge closed and replaced at state 12 therefore belongs to the graph at state 11, while its replacement belongs to
state 12. The JDBC and in-memory implementations use the same boundary rule.

## Commit-time cycle rejection

Only a relationship change that introduces a new parent adjacency needs a cycle search. Detaches, path/type changes for
an existing parent ID, unchanged stale relationships, and model writes without relationship changes do not add a
validation traversal.

All relationship changes within one commit substep are overlaid before validation. This permits an atomic edge
reversal, independent of target order. Substeps remain separate historical boundaries: an commit that creates a cycle
in one substep and removes it in a later substep is rejected because the intermediate state would be addressable.

The validator follows only parent paths reachable from newly attached parents. JDBC loads every frontier as one
child-hash-pruned batch and caches stored ancestry across the whole coordinator batch; it never performs one query per
node. The prefetched graph is only an optimization. Authoritative depth/model limits are applied to the effective graph
after the commit overlays, so a deep stored path cut in the same atomic substep cannot cause a false rejection.

The initial limits are 1,024 parent levels and 100,000 effective nodes. Exceeding either limit fails closed because the
runtime can no longer prove that the proposed state is acyclic within its resource bound.

Validation runs after conflict/relation reconciliation but before durable publication:

- the in-memory store validates before appending the global event;
- the co-located JDBC event log, commit, streams, heads, relationship rows, and state head remain in one transaction;
- the split event-log fallback performs validation before invoking its external publish callback, while retaining its
  existing publish-then-local-commit recovery boundary.

A rejected cycle therefore publishes no event and advances no commit, state index, model head, stream membership, or
relationship interval. If one commit in an optimistic JDBC coordinator batch is cyclic, the batch is retried as
isolated commits; the rejected commit fails alone and successfully stored neighbours still complete.

## Hot-path measurement

The retained implementation replaced an initial per-commit validation-query variant. Both retained and disabled runs
used the exact same local PostgreSQL profile: 5,000 commits, 2,000 warm-up commits, 10 one-KiB targets and one
relationship per target, 50,000 model keys, 256 concurrent submissions.

| Variant | Commits/s | Memberships/s | p99 |
| --- | ---: | ---: | ---: |
| Initial per-commit query, discarded | 425 | 4,254 | 790.411 ms |
| Batched relationship validation, retained | 493 | 4,933 | 694.115 ms |
| Exact temporary A/B with validation disabled | 513 | 5,133 | 671.464 ms |

The retained validator cost 3.9% throughput and 3.4% p99 in this local relation-heavy A/B. An ordinary write without a
new parent adjacency performs no cycle-loader query. A no-relationship control in the same environment measured 1,012
commits/s, 10,124 memberships/s, and 499.187 ms p99.

These are comparative local diagnostics, not production certification. Absolute figures are not compared with older
Phase 3 runs because the surrounding code, database state, and container changed. Deep, wide, and highly shared DAG
release certification remains a Phase 9 concern.

### Deep, wide, and shared DAG decision run

A disposable extension of the same benchmark driver generated three actual model graphs and was removed after the
measurements. The retained implementation was not changed for the run. It used local arm64 OpenJDK 25 and PostgreSQL
18.0 with 128 MiB shared buffers, 256 concurrent submissions, publication disabled, zero-byte event payloads, no
documents or snapshots, and five repeated graph loads after the first call. The first-call column is not a cold-disk
claim: the graph had just been written and PostgreSQL buffers were warm.

| Shape | Persisted graph | Writes/s | Physical delta | WAL delta | Descendants first / warm p50 | Ancestors first / warm p50 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Deep chain | 1,024 nodes / 1,023 edges | 608 | 0.28 MiB | 2.14 MiB | 761 / 779 ms | 784 / 823 ms |
| Wide root | 10,000 nodes / 9,999 edges | 4,242 | 13.45 MiB | 16.88 MiB | 164 / 116 ms | 6.3 / 3.9 ms |
| Shared DAG, 32 levels, fan-in 4 | 8,193 nodes / 32,000 edges | 968 | 22.75 MiB | 31.72 MiB | 321 / 280 ms | 1,521 nodes / 5,798 edges: 128 / 144 ms |

The wide result exercises one parent-hash partition for the root breadth. The shared result spreads successive
breadths over the endpoint hash space and returns every shared edge while de-duplicating model streams. Together they
support keeping one partition-pruned query per breadth as the normal plan: it has no per-node round trips and remains
fast for both large fan-out and a relation-dense DAG.

The 1,024-level chain exposes the deliberate weakness of breadth batching: one database round trip per level dominates
even though only 1,023 edges are returned. A recursive PostgreSQL plan cannot preserve the current bounded partition
lookups from the endpoint ID alone, because Fluxzero's stable segment uses the Java Murmur3 implementation. Making that
plan safe and fast would require persisting the opposite endpoint's segment on both adjacency rows (increasing every
edge write, index, WAL, and vacuum footprint) or installing an equivalent database hash function.

That permanent write amplification is not justified by the wide/shared results. The retained decision is therefore:

- keep bounded breadth batching as the default and treat 1,024 as a safety ceiling, not a latency promise;
- do not add opposite-segment columns or database-specific hashing to every relationship yet;
- re-evaluate an adaptive recursive path against representative production depth distributions in Phase 9, and only
  retain it if its deep-graph gain pays for measured write, WAL, index, and vacuum cost without regressing the ordinary
  model path.

## Deleted-parent lineage

`ModelCommitTarget.updateRelationships` separates an intentional complete `@ParentId` replacement from an ordinary
model transition. The SDK compares the returned target's parent references with its own begin-state. It sends the
complete resulting edge set only for attach, detach, move, or delete; unchanged targets carry neither relationship
intent nor relationship payload. This both removes unnecessary relationship reconciliation from the ordinary write
path and prevents an unchanged parent ID retained inside a child document from reopening an edge closed by the runtime.

A logical parent delete closes all current incoming edges at that delete substep's `stateIndex`. Both hash-routed
adjacencies receive the same half-open interval end, `PARENT_DELETED` reason, and exact `deletedParentId`. The operation
does not update child heads, streams, snapshots, documents, caches, or event memberships. Historical graph reads before
the delete still see the edge; current graph and search traversal do not.

Lifecycle traversal pins one current state boundary and combines:

- current parent-to-child edges;
- lifecycle-only `PARENT_DELETED` tombstones indexed by the deleted parent ID.

It follows these in bounded breadth-first batches, deduplicates shared descendants, includes the requested roots, and
fails instead of returning a partial deletion set when `maxDepth` or `maxModels` is exceeded. The JDBC tombstone lookup
uses the parent-keyed hash projection and its `(segment, parent_id, ...)` primary-key prefix.

For logical delete, the exact deleted parent ID is retained as lifecycle metadata because deterministic later removal
is otherwise impossible and the unchanged child state may still contain the same ID. Closed lineage is not returned by
ordinary current graph/search APIs. Whether explicit hard delete rewrites this lookup to a protected token or purges it
depends on `NONE` versus `DESCENDANTS`, shared-DAG ownership, and resumable cleanup; that final privacy contract remains
an explicit Phase 8 gate rather than being guessed here.

## Verification

- SDK repository tests cover a move followed by current loads of both roots and an as-of reconstruction of the old
  root, child, and grandchild.
- Pure validator tests cover same-step cycles, target-order-independent edge reversal, temporary substep cycles, deep
  stored ancestry, and a deep stored path cut by the same atomic step.
- In-memory tests cover pre-publication rejection and complete state/event rollback.
- JDBC tests cover durable rollback, half-open SQL intervals, current/historical graph traversal, edge reversal, and
  isolation of one cyclic commit from valid neighbours in the coordinator queue.
- SDK packaging tests distinguish unchanged parent references from explicit moves; the in-memory SDK store proves that
  a normal child write after parent deletion cannot reactivate the edge.
- Runtime in-memory and JDBC tests prove that parent deletion leaves the child head and stream untouched, keeps the
  edge visible only before its half-open delete boundary, exposes exact lineage only through the lifecycle lookup, and
  resolves a tombstoned child plus its still-current grandchild under bounded traversal.
- The focused SDK repository suite and runtime model-store suite passed.
- The complete SDK reactor, site/Javadoc reactor, and complete runtime reactor passed.

## Deliberately open

- production-scale deep, wide, and highly shared DAG measurements;
- protected-ID and final tombstone purge semantics for explicit hard delete;
- historical full-text graph search;
- cross-database atomicity and horizontal runtime coordination.
