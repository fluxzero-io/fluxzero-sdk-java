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
- Audit/event search may still find the globally published event as described above.
- Reusing an erased exact model ID is rejected by the durable erasure fence. Explicit administrative fence removal, if
  ever supported, is outside this phase.

## Performance gates

- Planning traverses partition-pruned parent adjacency in bounded batches and never loads event payload bytes.
- Counts of memberships and distinct global event indexes are set-based.
- Execution deletes by the stable 128 model segments and keeps shared-payload reference checks set-based.
- The ordinary commit and model-load paths perform no erasure-table query until hard deletion has been used in the
  namespace. After opt-in, target fence checks are batched once per action.
- Phase 8 benchmarks must cover `NONE`, 100/10,000/100,000-node cascades, deep and wide graphs, shared descendants,
  restart/resume, and simultaneous ordinary model traffic.
