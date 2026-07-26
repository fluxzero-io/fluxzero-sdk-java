# Phase 6 temporal graph integrity

This checkpoint completes the current/as-of relationship contract and makes model relationships a directed acyclic
graph at every persisted model-action substep. It does not turn conflict resolution into the design frame and adds no
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

All relationship changes within one action substep are overlaid before validation. This permits an atomic edge
reversal, independent of target order. Substeps remain separate historical boundaries: an action that creates a cycle
in one substep and removes it in a later substep is rejected because the intermediate state would be addressable.

The validator follows only parent paths reachable from newly attached parents. JDBC loads every frontier as one
child-hash-pruned batch and caches stored ancestry across the whole coordinator batch; it never performs one query per
node. The prefetched graph is only an optimization. Authoritative depth/model limits are applied to the effective graph
after the action overlays, so a deep stored path cut in the same atomic substep cannot cause a false rejection.

The initial limits are 1,024 parent levels and 100,000 effective nodes. Exceeding either limit fails closed because the
runtime can no longer prove that the proposed state is acyclic within its resource bound.

Validation runs after conflict/relation reconciliation but before durable publication:

- the in-memory store validates before appending the global event;
- the co-located JDBC event log, action, streams, heads, relationship rows, and state head remain in one transaction;
- the split event-log fallback performs validation before invoking its external publish callback, while retaining its
  existing publish-then-local-commit recovery boundary.

A rejected cycle therefore publishes no event and advances no action, state index, model head, stream membership, or
relationship interval. If one action in an optimistic JDBC coordinator batch is cyclic, the batch is retried as
isolated actions; the rejected action fails alone and successfully stored neighbours still complete.

## Hot-path measurement

The retained implementation replaced an initial per-action validation-query variant. Both retained and disabled runs
used the exact same local PostgreSQL profile: 5,000 actions, 2,000 warm-up actions, 10 one-KiB targets and one
relationship per target, 50,000 model keys, 256 concurrent submissions.

| Variant | Actions/s | Memberships/s | p99 |
| --- | ---: | ---: | ---: |
| Initial per-action query, discarded | 425 | 4,254 | 790.411 ms |
| Batched relationship validation, retained | 493 | 4,933 | 694.115 ms |
| Exact temporary A/B with validation disabled | 513 | 5,133 | 671.464 ms |

The retained validator cost 3.9% throughput and 3.4% p99 in this local relation-heavy A/B. An ordinary write without a
new parent adjacency performs no cycle-loader query. A no-relationship control in the same environment measured 1,012
actions/s, 10,124 memberships/s, and 499.187 ms p99.

These are comparative local diagnostics, not production certification. Absolute figures are not compared with older
Phase 3 runs because the surrounding code, database state, and container changed. Deep, wide, and highly shared DAG
certification remains deliberately open.

## Verification

- SDK repository tests cover a move followed by current loads of both roots and an as-of reconstruction of the old
  root, child, and grandchild.
- Pure validator tests cover same-step cycles, target-order-independent edge reversal, temporary substep cycles, deep
  stored ancestry, and a deep stored path cut by the same atomic step.
- In-memory tests cover pre-publication rejection and complete state/event rollback.
- JDBC tests cover durable rollback, half-open SQL intervals, current/historical graph traversal, edge reversal, and
  isolation of one cyclic action from valid neighbours in the coordinator queue.
- The focused SDK repository suite and runtime model-store suite passed.
- The complete SDK reactor, site/Javadoc reactor, and complete runtime reactor passed.

## Deliberately open

- production-scale deep, wide, and highly shared DAG measurements;
- incoming-edge tombstones and deleted-parent lineage for lifecycle/GDPR removal;
- historical full-text graph search;
- cross-database atomicity and horizontal runtime coordination.
