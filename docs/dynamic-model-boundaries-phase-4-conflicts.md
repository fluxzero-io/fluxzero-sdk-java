# Phase 4 — Optional model conflict handling

## Scope

Conflict handling is deliberately a contained policy layer, not the design framework for dynamic model boundaries.
Fluxzero keeps its normal single-writer-friendly behavior: stale model actions are accepted unless an application
explicitly selects a rejecting policy.

The action carries one global `readStateIndex`. It does not carry a version vector. Every written model is already
required to occur in `readModelIds`, so that one action scope covers both read-only dependencies and write targets.

## Policies

- `ACCEPT` is the compatibility default, including when an older request omits the field. It performs no model-head or
  relationship conflict query.
- `FAIL` rejects the complete action when any action-scoped model head has a `lastStateIndex` greater than the action's
  `readStateIndex`.
- `RETRY_IF_RELATIONS_UNCHANGED` rejects on the same head condition and additionally determines whether another SDK
  evaluation is safe. Retry is allowed only when no relationship touching an action-scoped identity changed after the
  read boundary.

A conflict response contains each relevant exact model ID, its current head state index, and its most recent
relationship transition index. A relation-only conflict entry may therefore have a model head at or before the read
boundary. Rejected results are not stored under the action idempotency key.

## Temporal relationship check

The runtime derives a relationship transition index from the existing temporal graph; it adds no write-side version
column or per-action relation record.

For every scoped identity the value is the maximum of:

- `valid_from` and non-null `valid_until` in the child-partitioned adjacency table where it is the child;
- `valid_from` and non-null `valid_until` in the parent-partitioned adjacency table where it is the parent.

Both queries include stable hash segments and exact IDs, retaining partition pruning. They run only after a strict
policy has already found a stale model head. `FAIL` loads relation positions only for the head conflicts;
`RETRY_IF_RELATIONS_UNCHANGED` loads them for the complete action scope.

## Atomic runtime behavior

The in-memory stores perform the conflict decision before global event publication or any model mutation.

For co-located JDBC event and model stores, each strict action is isolated:

1. the event store tentatively assigns global event indices;
2. one database transaction locks the namespace model state row;
3. the runtime loads scoped heads and, only when needed, temporal relation positions;
4. a conflict throws an internal expected-rollback marker before any model write;
5. the database transaction, event indices, event-log in-memory head, and event objects are restored together.

The event store holds its head monitor until the conditional transaction finishes. Its commit executor remains ordered,
and monitor callbacks are dispatched asynchronously. This prevents another append from receiving indices between a
rejected assignment and restoration.

Accepted `ACCEPT` actions remain on the original asynchronous, batched path. Strict actions intentionally pay for an
isolated transaction; conflict handling is optional and correctness is more important than strict-policy throughput.
With a non-JDBC event log the runtime holds the model state lock while it performs the external append and model write.
After that append succeeds, a transient retry of the model transaction skips both the conflict decision and another
event append: the already-published action must finish its model write and its global event remains single-copy. The
pre-existing cross-store accepted-write partial-failure boundary remains; distributed atomicity is outside this phase.

## SDK resolution

`ModelConflictResolver` runs only after a runtime conflict result, hence after rollback. It may:

- fail with the default `ModelActionConflictException`;
- throw an application exception to map the conflict to an application error;
- request a silent retry.

A retry occurs only if both the runtime returned `retryAllowed=true` and the configured SDK retry count is not
exhausted. The committer obtains a fresh `ActionEvaluation` from a reload supplier and reuses the rejected action ID,
which is safe because conflicts are not retained. The supplier is the integration seam for the pinned loader in
Slice 5.1.

A rejected action never mutates direct search documents. Only an accepted commit proceeds to the synchronous direct
document update. The actual model cache does not exist before Slice 5.1; invalidation and refresh are therefore wired
there, alongside the loader that owns those entries, rather than introducing a second provisional cache abstraction.

## Verification and performance

Focused coverage includes:

- JSON and CBOR request/result round trips, omitted-policy compatibility, and conflict metrics;
- default stale acceptance, `FAIL`, bounded retry, custom error mapping, and non-retention of rejected action IDs;
- no event, model, relationship, direct-document, state-index, or event-head mutation after rejection;
- relation-safe and relation-unsafe retry through both child and parent temporal adjacency indexes;
- stale unchanged relationships do not overwrite a newer current edge;
- transient event-store behavior and commit-outcome handling remain intact.

Local Docker Desktop PostgreSQL diagnostics on Apple Silicon, 256 concurrent submissions:

| Profile | Throughput | p99 | Physical/logical | WAL/logical |
|---|---:|---:|---:|---:|
| Explicit `ACCEPT`, 5,000 × one 1-KiB target | 5,551 actions/s | 88.8 ms | 2.15× | 2.87× |
| Omitted legacy policy, same profile | 5,542 actions/s | 90.7 ms | 2.15× | 2.90× |
| Strict accepted `FAIL`, 1,000 × one 1-KiB target | 642 actions/s | 430.0 ms | 1.78× | 4.92× |

The explicit and omitted `ACCEPT` runs differ by 0.2%, within local run variance, and execute the same SQL path.
No query, row, physical-storage, or WAL structure was added to accepted default actions. The strict result quantifies
the deliberate cost of per-action isolation and conflict eligibility; it is not a target for the normal Fluxzero path.
