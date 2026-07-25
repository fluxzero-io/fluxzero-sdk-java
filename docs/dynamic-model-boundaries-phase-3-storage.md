# Phase 3 model-action storage

This report records the retained runtime implementation and local measurements for the authoritative model-action
store. It complements the Phase 0 storage ADR; it is not production hardware certification.

## Retained contract

- One namespace-wide `stateIndex` orders every model state transition.
- Every model stream has its own `sequenceNumber`.
- A published original event receives one existing global `eventIndex` and is appended to the global event log once.
- Every event-returned target receives an independently sequenced stream membership.
- One `actionId` is the durable idempotency key for all ordered substeps and targets.
- A duplicate `actionId` returns its compact persisted prior result without publishing, advancing state, or adding
  memberships again.
- The complete desired outgoing relationship set is reconciled only for returned targets. A stale action that did not
  change a relationship cannot resurrect its old edge.
- Attach, detach, move, and multiple-parent transitions update both temporal adjacency directions in the same commit.
  Selectively removing one of several parents is `DETACHED`; replacing an edge with the same type/path descriptor is
  `MOVED`.

When the namespace event log unwraps to `JdbcMessageStore`, global publication and all model state are committed in one
JDBC transaction. A deliberately retained fallback for a non-JDBC or separately located event log publishes first and
then commits model state; that cross-store partial-failure boundary is not presented as atomic.

## Physical layout

All routing identities remain exactly the ID string. A stable Fluxzero segment in the range 0–127 is stored explicitly;
32 physical range partitions group four stable segments each. The physical partition count can therefore change later
without changing the durable routing function.

| Table | Purpose | Partitioning |
|---|---|---|
| `model_state` | current namespace `stateIndex` | singleton |
| `model_action` | `actionId`, read boundary, compact MessagePack result | action-ID segment |
| `model_head` | current sequence/state/history/deleted coordination state | model-ID segment |
| `model_stream` | target membership and inline/shared payload reference | model-ID segment |
| `model_payload` | shared serialized event payload | ranges of 10,000,000 state indices |
| `model_relation_descriptor` | compact parent type/path catalog | unpartitioned |
| `model_relation_by_child` | temporal outgoing adjacency | child-ID segment |
| `model_relation_by_parent` | temporal incoming adjacency | parent-ID segment |

The runtime creates these tables lazily on first independent-model use, so legacy aggregate-only namespaces do not pay
schema or worker initialization cost. There is no released predecessor schema to migrate in this feature branch.

Event payloads use the Phase 0 hybrid:

- serialize with MessagePack;
- retain LZ4 only when it is smaller;
- inline the common single-target payload;
- use one shared payload row when avoiding target copies saves more than the measured 512-byte reference overhead.

The action result is one compact blob rather than normalized action/substep/target result rows. Stream membership rows
retain `actionId`, substep, `readStateIndex`, `stateIndex`, optional `eventIndex`, and payload location, which is enough
for model reconstruction and action-prefix dependency overlays without a high-cardinality idempotency write amplifier.

## Write and load path

- Event serialization/compression starts on the CPU executor while requests await the ordered coordinator.
- Coordinator batches are capped at 128 actions and an estimated 8 MiB.
- Actions are never split across segment queues or transactions.
- Action results, heads, shared payloads, stream memberships, relation descriptors, relation opens, and relation closes
  use set-based `UNNEST` statements.
- Heads and current relationships are loaded in sets; sequence allocation never performs `max(sequenceNumber)` per
  model.
- Direct and batched model streams resolve inline/shared payloads in one query shape.
- Both relationship directions are physically routed to one stable-segment partition.
- One action is bounded to 256 MiB estimated write size by default, configurable with
  `fluxzero.maxModelActionBytes`. An action above the ordinary 8-MiB batch bound and below this hard limit progresses
  alone in its coordinator batch.
- Accepted in-flight writes are bounded to 256 MiB per namespace by default, configurable with
  `fluxzero.maxPendingModelActionBytes`. Requests beyond that capacity receive an explicit overload failure and can
  retry after capacity is released.

The in-memory implementation has the same ordered action, idempotency, state/sequence, temporal relation, rollback, and
byte-bounded behavior.

## Integrated diagnostic results

Environment: local Docker Desktop PostgreSQL on Apple Silicon, random/incompressible event payloads, 256 concurrent
submissions. Physical and WAL ratios use the original logical event payload once per action as denominator and include
the global event log, action result, heads, memberships, and any relationships. Loads were warm batched direct-model
loads. Exact commands remain reproducible through `JdbcModelActionStoreBenchmark`.

| Profile | Write throughput | p99 | Physical/logical | WAL/logical | Warm load |
|---|---:|---:|---:|---:|---:|
| 5,000 actions, 1 target, 1 KiB | 8,839 actions/s | 44.5 ms | 1.88× | 2.52× | 56.77 MiB/s |
| 5,000 actions, 10 targets, 1 KiB | 3,105 actions/s / 31,051 memberships/s | 129.2 ms | 5.76× | 9.46× | 58.98 MiB/s |
| 1,000 actions, 100 targets, 16 KiB | 399 actions/s / 39,940 memberships/s | 1,039.8 ms | 3.53× | 5.48× | 262.90 MiB/s |
| 5,000 actions, 10 targets, 1 relation each, 1 KiB | 1,870 actions/s / 18,704 memberships/s | 162.5 ms | 9.97× | 14.66× | 46.23 MiB/s |

The retained design was reached through two measured corrections:

1. replacing normalized action result tables with one compact idempotency result improved the 100-target profile from
   113 to 171 actions/s and reduced physical/WAL amplification from 4.70×/8.18× to 3.55×/5.70×;
2. replacing per-row JDBC batches with set-based writes raised it again to 399 actions/s while reducing amplification
   to 3.53×/5.48×.

The relation profile improved from the earlier 733 actions/s row-oriented design to 1,870 actions/s. The final
set-based relation pass alone raised it from 1,328 to 1,870 actions/s and reduced WAL amplification from 16.23× to
14.66×.

## Verification

- Runtime commits: `41a57adcb80e2b0b86a9ae628b80548eeaef080c`,
  `b17806d25130f4627d7d33140e332197c4663c30`
- Full runtime module: 501 tests passed.
- Focused model suite covers atomic global publication, multi-target membership, adaptive shared payloads, durable
  restart idempotency, concurrent ordering, transaction rollback, temporal moves and stale actions, multi-parent
  detach, logical delete/history gaps, as-of batch loads, payload range creation, partition pruning, overload/retry,
  in-memory parity, and lazy lifecycle.
- The retained benchmark compiles as part of the benchmark module.

## Deliberately open

- Direct model document indexing/loading and its existing cross-store search boundary are Slice 3.4.
- SDK reconstruction, historical dependency injection, snapshots, and cache synchronization are Phase 5.
- Cycle checks, graph traversal APIs, deleted-parent incoming-edge cascade, and GDPR lineage are Phase 6/8.
- Action-result retention, shared-payload hard-delete garbage collection, cold-cache behavior, vacuum/bloat,
  replication, backup/restore, tuning the explicit action/pending byte limits, and production-hardware 100 GB/min
  certification remain explicit Phase 8/9 gates.
- Horizontal multi-runtime coordination and request/result logs remain out of scope for this phase.
