# Phase 8 — Explicit model erasure

This phase adds intentional physical erasure. It is deliberately separate from a logical model delete: an
`@Apply` returning `null` remains an ordinary event-sourced state transition, while hard delete removes retained model
history and materializations.

## Safety contract

- Cascade is mandatory at the wire boundary. There is no boolean or omitted default.
- `NONE` selects only the requested model. It may be executed directly.
- `DESCENDANTS` is inclusive: every model reachable through a current child edge or a child edge detached by logical
  parent deletion is selected exactly once. A model that also has a parent outside the selected set is still selected.
  The plan reports such externally shared descendants explicitly.
- `DESCENDANTS` requires a preceding dry-run plan. Execution presents the plan fingerprint and the runtime recomputes
  the bounded closure. A changed closure makes the plan stale; unrelated writes do not.
- Planning and execution are bounded by explicit `maxDepth` and `maxModels` values and fail without returning or
  deleting a partial result when a bound is exceeded.
- A plan returns counts and a bounded deterministic sample, not an unbounded list of every selected ID.

The inclusive shared-descendant rule is intentionally strong. A caller explicitly asking to erase a lifecycle tree
must not silently retain a descendant that can still contain data derived from the erased root. The dry-run and shared
count make the blast radius visible before confirmation.

## What is erased

For every selected model, execution removes:

- its model head and stream memberships;
- inline event payloads and shared payloads that no remaining stream membership references;
- snapshots and their hidden search fences;
- its direct current search document and hidden search fence;
- current and historical relationship rows involving the selected model;
- materialized graph documents whose root is erased, and schedules surviving affected roots for reconstruction;
- local SDK cache entries returned to the caller.

Action result records may describe multiple models and are therefore not rewritten destructively. Once their pending
direct-document and snapshot materialization is complete, their compact idempotency result may remain with erased
target IDs protected or redacted. An action record must never be allowed to recreate erased search state.

The original globally published event is not owned by a model stream. It may already have been consumed or copied to
other stores, and one event can target both erased and retained models. Hard model delete therefore does not claim to
erase the global event log or downstream projections. The plan reports distinct published events referenced by the
selected streams. Applications that require locally erasable sensitive history should use an appropriate publication
strategy and data-protection scheme.

## Detached lineage without retained raw parent IDs

A logical parent delete currently retains the raw deleted parent ID so a later lifecycle operation can find detached
children. Hard delete must remove that raw ID.

Before raw relationship rows are purged for `NONE`, direct deletion-detached child links are copied to a dedicated
lineage index under a deterministic protected token. The token is an HMAC of the exact model ID using a per-namespace
runtime key. The key is generated once and stored separately from the lineage rows so ordinary data inspection does
not reveal raw deleted IDs. A later `DESCENDANTS` request can derive the same token from the user-supplied root ID and
continue traversal. Erasing the complete descendant closure purges the corresponding protected lineage rows.

This is pseudonymization for lifecycle discovery, not encryption of low-entropy IDs. Deployments with a stronger threat
model must provide an externally managed namespace erasure key; supporting externally managed keys remains an
operational configuration item in Phase 9.

## Resumability and write fencing

Large deletion sets execute as durable jobs in bounded batches. Creating a job atomically stores its exact selected IDs
and an erasure fence before cleanup starts. Model commits check selected target IDs against active/completed erasure
fences and cannot recreate a hard-deleted model accidentally. Retrying a request or restarting a runtime resumes the
same job.

Execution uses batches of at most 1,024 targets by default (`fluxzero.modelErasureBatchSize`). Search cleanup completes
idempotently before the corresponding relational batch is marked processed. A crash at either side therefore repeats
at most one search batch and never skips relational cleanup. Prepared jobs are resumed at runtime startup, including
deployments without a configured search store.

The relational store owns the durable job and stream/relationship cleanup. Search can be a separate database, so the
operation is a resumable saga rather than a fictitious cross-database transaction:

1. persist job targets and erasure fences;
2. remove or neutralize pending action materializations for those targets;
3. delete direct documents, snapshots, graph documents, and their fences idempotently;
4. delete relational stream, head, relationship, and unreferenced payload rows in bounded transactions;
5. mark the job complete and retain only the protected erasure fence/status required for idempotency.

Status reports partial progress and the last failure. A successful API result means every configured store completed,
not merely that the relational delete was accepted.

## Historical behavior after erasure

- Loading an erased model, at any state boundary, returns no model history.
- Graph reconstruction omits erased nodes and their edges.
- Surviving materialized graph roots are rebuilt; freshness remains visible through the existing projection status.
- An erasure projection signal is visible to the projection worker only after the durable deletion job reaches
  `COMPLETE`, so a multi-batch delete cannot expose an intermediate graph.
- Audit/event search may still find the globally published event as described above.
- Reusing an erased exact model ID is rejected by the durable erasure fence. Explicit administrative fence removal, if
  ever supported, is outside this phase.

## Performance gates

- Planning traverses partition-pruned parent adjacency in bounded batches and never loads event payload bytes.
- Counts of memberships and distinct global event indexes are set-based.
- Execution deletes by the stable 128 model segments and keeps shared-payload reference checks set-based.
- The ordinary commit and model-load paths perform no erasure-table query until hard deletion has been used in the
  namespace. After opt-in, target fence checks are batched once per action.
- Direct-document and graph-document writes fold the lifecycle lock and HMAC fence lookup into their existing fence
  statement. A concurrent erasure causes a transient retry with a fresh database snapshot; it cannot recreate a
  document after the tombstone commits.
- Phase 8 benchmarks must cover `NONE`, 100/10,000/100,000-node cascades, deep and wide graphs, shared descendants,
  restart/resume, and simultaneous ordinary model traffic.

## Completed implementation and evidence

Phase 8 is implemented by SDK commit `49000dd69f1` and runtime commit `d3a74b60`.

- The SDK exposes planning and execution through the model repository and websocket protocol. `DESCENDANTS` requires
  the exact fingerprint and bounds returned by planning; `NONE` may execute directly. Automatic descendant erasure
  invalidates the complete local model cache because the bounded result intentionally does not return an unbounded raw
  ID list.
- JDBC stores durable deletion jobs, targets, HMAC fences, and protected detached lineage in hash/range-partitioned
  lifecycle tables. Stream memberships, heads, relationships, action materializations, snapshots, direct documents,
  graph documents, and unused shared payloads are cleaned in resumable batches. Global published events remain.
- Functional verification passed 51 focused SDK tests and 173 focused runtime tests. These include duplicate requests,
  changed-plan rejection, shared descendants, logical-parent-detached lineage, restart with and without search,
  failure between search and relational cleanup, 1,025-target multi-batch execution, surviving-root rematerialization,
  document/index races, regular PostgreSQL search, and RUM search.
- The retained PostgreSQL benchmark measured:

  | Selection | Shape | Plan | Erase | Notes |
  | --- | --- | ---: | ---: | --- |
  | 1 of 100 | `NONE` | 0.007 s | 0.097 s | one membership; cold lifecycle path |
  | 100 | wide | 0.040 s | 0.057 s | set-based one-level traversal |
  | 1,025 | depth 1,024 | 1.943 s | 2.065 s | deliberately exercises the configured maximum depth |
  | 10,000 | wide | 0.216 s | 4.652 s | 46,311 planned and 2,150 erased models/s |
  | 100,000 | wide | 2.558 s | 70.804 s | 39,089 planned and 1,412 erased models/s |

  The 100,000-model run removed 100,000 stream memberships while retaining its single published global event.
- An A/B run against the pre-erasure runtime found no material ordinary-write regression. The paired 5,000-action
  direct-document run measured 6,020 actions/s with erasure support versus 6,070 actions/s before it (about -0.8%),
  with the same p50 latency and physical amplification. The 20,000-action event-only run was slightly faster in the
  new build (5,705 versus 5,541 actions/s); this is treated as run variance, not an improvement claim.

The lifecycle tables currently generate and retain their HMAC keys inside their owning database. Supplying externally
managed keys and production hardware/steady-state operational certification remain Phase 9 rollout gates.
