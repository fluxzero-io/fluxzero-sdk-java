# S60 — final domain-driven Model architecture

Status: canonical active end design

Date: 2026-08-19

## Authority and baseline

This document is the architectural source of truth for the remaining S60 work. The CP1–CP14 code-budget ledger and
performance logs remain evidence for retained changes, rejected candidates and known-good behavior, but their former
“complete” labels do not close the open SDK and integration targets. S60 closes only when the domain end state in this
document exists and its superseded owners are physically absent.

The accepted CP14 source is the safe functional baseline:

| Repository | CP14 production Java | Hard ceiling | Exact remaining removal |
| --- | ---: | ---: | ---: |
| SDK (`common`, `sdk`, `test-server`, `proxy`) | 160,194 | 136,000 | **24,194** |
| Runtime | 36,919 | 32,000 | **4,919** |
| **Combined** | **197,113** | **168,000** | **29,113** |

CP8 established the Runtime's canonical commit and block-storage path. CP9–CP14 contain qualified building blocks for
the remaining work, but their execution plans, scopes, replay cursors and shared-mechanics layers are intermediate
architecture. Earlier wire, storage or diagnostic reductions remain retained evidence; their lines are never credited
a second time.

The objective is not the same enormous architecture with shorter methods or more neutral names. It is a release-ready
Model architecture whose ownership and compactness feel like the rest of Fluxzero: one clear domain foundation from
which the complete capability set and the required performance follow naturally.

Formatting compression, deleted documentation or tests, generated equivalents, moved source, compatibility loss,
wrapper layers around old engines and temporary bridges do not pay the code debt.

## Domain foundation

The architecture follows the customer-visible Model domain:

```text
Mutation
  -> determine the affected Model or cohort
  -> reconstruct the Model(s)
  -> apply the Mutation
  -> produce Change(s)
  -> commit the current Changes to the Runtime
  -> complete the existing result once
```

Fluxzero already commits automatically at the end of the current handling boundary. That established behavior is a
contract to preserve, not an S60 feature to build. A possible explicit `Fluxzero.commit()` flush is separate product
work and is outside this architecture replacement.

### Domain ownership

The foundation has four domain meanings and two supporting owners:

| Concept | Meaning and ownership |
| --- | --- |
| Model | The state and behavior affected by a mutation, including a cohort of related Models where the domain requires it. |
| Mutation | What the customer asks to change. It is the existing payload and invocation, not a mandatory wrapper object. |
| Change | The observable state, relationship, document, snapshot, publication or deletion change produced by applying a mutation. It reuses existing contracts where they already express that change. |
| Commit | The act of writing all current changes atomically to the Runtime. Commit is the one live lifecycle and a verb, not an execution object passed to itself. |
| `ModelDefinition` | The registered knowledge of a Model type: identity, relationships, handlers, assertions, accessors and immutable compiled mutation metadata per reachable payload type and registry generation. |
| `ModelRepository` | The existing boundary that reconstructs Models and commits their changes while preserving the public, persisted and wire contracts. |

These concepts do not require six new Java classes. A type earns architectural ownership only when it represents one
of these stable domain responsibilities or an existing supported extension contract. Compact records, arrays, indexes,
pages and protocol values may exist as local implementation details, but they do not get independent discovery,
execution, dependency, retry, completion or shutdown lifecycles.

### Compiled knowledge is not an execution domain

Each reachable mutation payload has immutable compiled metadata. `ModelDefinition` owns that metadata and replaces its
application-scoped generation when registration or relevant graph knowledge changes. Structural reflection remains in
`ReflectionUtils.TypeMetadata`; compiled values that capture application registration remain with that application.
Runtime processing performs no handler, parameter, target, dependency, result-shape or policy discovery.

The compiled value is deliberately not a peer called `ModelMutationPlan` that drives execution. It is never supplied by
a customer, passed into `commit`, or allowed to collect runtime state. Whether it is represented by a named private
record or fields inside `ModelDefinition` is an implementation choice governed by clarity and performance, not a new
domain boundary.

Likewise, there is no architectural `ModelCommit`, `UnitOfWork`, execution context or batch ticket. Applying a mutation
may use one compact private carrier with slot arrays, last-writer indexes, pending changes and completions. That carrier
exists only inside the automatic commit boundary, has no public vocabulary or parallel lifecycle, and disappears when
the commit completes.

### Capabilities are variations of the same domain path

| Capability | What it changes in the foundation |
| --- | --- |
| automatic / explicit mutation | how the same mutation behavior is invoked |
| direct / Graph / collections / cohorts | how the affected Models are determined |
| assertions / interceptors | how a mutation may be applied to a Model |
| explicit apply | which already determined Model receives the mutation |
| stored replay | how a Model is reconstructed; it does not commit |
| retry / rebase | the same mutation is applied again after reconstructing current Models |
| relationship and Graph changes | part of the Changes produced by a mutation |
| snapshots and direct documents | optimized representations of a reconstructed or changed Model |
| completion policy | which part of the one commit the existing result awaits |
| `TestFixture` | the same Model, mutation and commit semantics with an in-memory repository boundary |

A capability may add immutable information to `ModelDefinition`, an option to Model selection or reconstruction, or a
Change to the current commit. It may not introduce another pipeline, scope, resolver hierarchy, dependency graph,
completion mechanism or retry lifecycle.

## Final behavior

### Mutation and automatic commit

All automatic handlers, explicit assertions/applies, direct updates, Graph/cohort updates, collection results, retries
and graph changes follow the same domain algorithm:

1. Resolve the registered `ModelDefinition` from the existing payload and application context.
2. Use its compiled accessors to determine the affected Model identities and exact dependencies.
3. Ask `ModelRepository` to reconstruct the required current, historical or staged Models.
4. Invoke the compiled assertions, interceptors and mutation handlers in their defined order.
5. Collect the resulting Changes once, maintaining batch-local read-your-writes and exact last-writer ordering.
6. Commit automatically at the existing handling boundary.
7. Map the authoritative Runtime response to revisions and complete each existing result exactly once.

The common one-target case is a precompiled direct strategy inside these same steps. Empty optional arrays make it
cheap; it does not enter a different engine. Collection and dynamic results are compiled result interpretations, not
runtime discovery switches. Retry reconstructs affected Models and repeats the same mutation application; it does not
enter a retry pipeline.

The repository commit boundary receives only the domain changes and the existing commit policy required by the wire
contract. It serializes one request and applies the authoritative result. It owns no handler discovery, target
resolution, mutation invocation, dependency scheduling or result policy discovery.

### Reconstruction and Graphs

`ModelRepository` has one reconstruction behavior for current, historical, before-state, exact commit/event, batch,
cache, snapshot and direct-document reads. Storage pages, seeds, aliases and boundaries are inputs to that behavior,
not separate reconstruction owners. One internal page-and-apply loop may be optimized independently, but it is not a
second Model lifecycle or an architecture-facing replay domain.

Graph is the domain view of a Model cohort and its relationships. One immutable internal graph representation supports
root, parent, ancestor, child and descendant traversal plus typed public views. View options are immutable values;
wrappers and forwarding chains do not rebuild the Graph contract. Graph selection still reconstructs Models through
the repository and graph mutations still produce ordinary Changes for the same commit.

Aliases normalize Model identity before reconstruction. Cache entries, snapshots, direct documents and empty values
are ordered reconstruction sources. A batch overlay is simply the current uncommitted Changes applied before the
public value or Graph view is returned. None owns a private cursor, session, apply loop or completion platform.

### Aggregate compatibility

`@Aggregate` and `@Model` retain their distinct public annotations, programming contracts and persisted/wire formats.
Where their domain behavior is the same, they use the same registered definition, handler invocation, reconstruction,
revision, snapshot, cache and fixture mechanics. Their repository boundaries map Changes to their existing persistence
contracts; they do not select different engines.

Aggregate event publication, one-stream persistence and relationship behavior remain compatible. Model independent
streams, namespace boundaries and atomic multi-model commits remain compatible. `@Stateful` remains a separate handler
category and uses only genuinely shared reflection or handler facilities; it is not forced into the Model domain.

`TestFixture` exercises the same production Model definitions and mutation semantics. Its in-memory repository mimics
the existing persistence result rather than reimplementing handler application, replay, retry or Graph behavior.

### Runtime commit and derived behavior

CP8's canonical Runtime commit/block-storage path remains the only durable Model commit implementation. The SDK sends
the current Changes through that path; no new SDK abstraction mirrors the Runtime commit plan.

Locator maintenance, direct materialization, graph projection and cache tracking consume the same ordered committed
changes. They retain their distinct domain transformations but share durable progress, wake-up, retry, recovery,
backpressure and shutdown mechanics. A capability does not own a private worker platform merely because its output is
different.

JSON, CBOR and exactly one negotiated binary envelope remain supported. One native message envelope, primitive binary
implementation, model block representation and committed-change feed serve the final path. Released persisted and wire
forms keep compatible readers; unreleased preview generations and campaign-only bridges disappear.

## One coherent S60 replacement

The remaining work is one coupled architecture replacement. Mutation, reconstruction, Graph/cohorts, Aggregate
compatibility, fixtures, wire integration and derived Runtime behavior cross the former technical boundaries. They are
therefore work surfaces within one design, not macros, phases or independently acceptable checkpoints.

The surfaces may be investigated and implemented in the safest dependency order. That order does not grant them
separate architecture, acceptance or code ceilings. A retained change must remove ownership in favor of the final
domain foundation; it may not introduce a temporary second engine for a later surface to clean up.

### Mutation, definition and commit ownership

The complete SDK execution subsystem is absorbed by the domain algorithm above. It is not replaced by another
plan/scope/engine vocabulary.

| Current owner | Required disposition |
| --- | --- |
| `ModelCommitHandlerRegistry` | Keep only supported registration and dispatch. Registered mutation knowledge belongs to `ModelDefinition`. |
| `ModelExecutionPlan.Compiler` | Absorb compilation into `ModelDefinition`; runtime evaluation ownership disappears rather than moving to another plan type. |
| `ModelTargetResolver` | Remove after the definition contains concrete identity and dependency accessors. |
| `ModelEntityParameterResolver` | Remove as a Model execution owner; generic handler parameter facilities provide compiled accessors where needed. |
| `ModelCommitContext` | Remove repeated execution maps; required values become compact local commit data. |
| `ModelBatchScope.Entry` | Remove tickets and future-shaped operations; one local change/completion representation serves the automatic commit. |
| `ModelPipeline` | Remove automatic/explicit/direct/replay/retry branches in favor of the single mutation algorithm. |
| `ModelCommitProtocol` | Retain only Change-to-wire and authoritative-result mapping, preferably behind the repository boundary. |

Automatic, explicit, direct, Graph, collection, retry and graph-change mutations use the same compiled definition and
domain algorithm. The existing automatic commit remains the sole commit trigger in S60. Old resolvers, route-specific
evaluators, tickets, gates, waves and duplicate completion/dependency state disappear, and no successor execution plan,
engine, context or scope recreates their combined ownership. The one-target route retains its precompiled
allocation-light strategy.

### Reconstruction, Graph and cohort ownership

Reconstruction becomes a repository responsibility and Graph becomes a view of a reconstructed cohort. The replacement
absorbs or removes `Graphs` wrapper/view chains, `MaterializedGraphFactory` graph object construction, reconstruction
orchestration in `DefaultModelRepository`, `ModelReplayCursor.Session`, Model-specific boundary conversion,
`ModelAncestorResolver` and cache-refresh replay variants.

A compact internal page loop and immutable Graph indexes may remain, but neither owns an independent Model lifecycle.
Current, historical, before-state, exact commit/event, batch, direct-document, ancestor, cache-refresh and Graph reads
use the same repository reconstruction behavior. One public Graph view retains one immutable internal graph identity;
paging, coalescing, lazy values and caches remain bounded.

### Aggregate compatibility and fixture ownership

Duplicated Aggregate and Model handler application, reconstruction, revision, snapshot, cache and fixture behavior
disappears wherever their domain semantics are equal. Thin repository-specific mapping preserves genuinely different
public and persisted contracts.

The replacement absorbs or removes `DefaultAggregateRepository.AnnotatedAggregateRepository` as a complete engine,
orchestration in `ModifiableAggregateRoot` and `LazyAggregateRoot` that duplicates mutation/reconstruction,
Model-versus-Aggregate store branches in `TestFixture`, parallel in-memory modeling persistence and duplicated
snapshot/cache lifecycle. Both forms obtain handler knowledge through the same definition mechanism and share mutation
application and reconstruction where semantics match. Aggregate compatibility remains complete and `@Stateful` stays
independent.

### Wire, Runtime and derived behavior ownership

The remaining wire generations, update-worker multiplication and temporary branch integration disappear. The SDK and
Runtime communicate only the existing mutation results and committed Model changes; derived consumers process that
same ordered truth with one durable lifecycle.

The replacement removes private protocol primitives, preview codecs and schema adapters; action-specific cursor
columns, workers, retry timers, executors and shutdown joins; duplicated update-feed queries and block
location/decoding; and temporary SDK/Runtime bridges. Existing stored progress migrates without a standing dual path.
Mixed JSON/CBOR/final-binary compatibility, restart, recovery, partial failure, long polling, projection
`ASYNC`/`AWAIT` and shutdown retain no-lost/no-duplicated-change behavior.

### Single acceptance boundary

S60 has one architecture acceptance boundary. The replacement is accepted only when all ownership surfaces above form
the final domain foundation, all superseded owners are absent, every qualification gate passes, SDK production Java is
at most **136,000 lines** and Runtime production Java is at most **32,000 lines**.

## Code-budget accounting

The former intermediate ceilings are removed because they implied architecture boundaries that do not exist. The
working reductions below are accounting forecasts for finding sufficient deletion surface, not phases or acceptance
targets:

| Deletion surface | Working forecast |
| --- | ---: |
| SDK mutation, definition and commit ownership | 5,694 |
| SDK reconstruction, Graph and cohort ownership | 6,000 |
| SDK Aggregate compatibility and fixture ownership | 8,500 |
| SDK wire and integration residue | 4,000 |
| **Required SDK removal** | **24,194** |
| Runtime wire, committed-change and integration residue | **4,919** |

The forecasts may shift freely when concrete owner and successor footprints expose a more coherent deletion bundle.
Only the repository totals count, and they never relax retroactively. Physical counts include every hand-maintained
Java file under production `src/main/java` roots. Moving code, deleting documentation, generating equivalent source,
compressing formatting or hiding implementation in resources receives no credit.

## Implementation discipline

Before implementing a deletion candidate, record:

1. every current owner, caller, representation, loop and lifecycle in its deletion bundle;
2. the surviving domain owner and a maximum successor footprint;
3. the supported public, persisted, wire and extension contracts at risk;
4. focused correctness and performance qualification;
5. the rollback point at the accepted source baseline.

Start with a direct single-Model walking skeleton on the final `Model → Mutation → Change → Commit` path. Add assertions,
interceptors, cohorts, collections, explicit apply, Graph, stored replay, retry and batching as compiled definition data,
selection/reconstruction options or Changes. A capability migrates only in the candidate that deletes its former owner.
There is never an accepted `NewPipeline` beside `ModelPipeline`, nor a new scope beside the old batch scope.

Development commits may be narrow, but no intermediate commit becomes a separate architecture milestone. Every
retained deletion bundle has no dual path or temporary bridge. Losing candidates are reverted and recorded. No later
work builds on a regression merely because it removes many lines.

## Verification strategy

Correctness and performance are not qualified after each locally edited file. They are qualified at the boundary of
every complete, coherent replacement that removes its superseded owners and leaves a usable slice of the final domain
foundation. Each such replacement receives its own checkpoint commit after qualification. A checkpoint is an evidence
and rollback boundary, not a predefined macro, separate architecture or intermediate code ceiling.

A candidate that violates any correctness contract, loses functionality, weakens compatibility, meaningfully regresses
throughput, latency or allocation, or worsens resource ownership is rejected regardless of its LOC reduction. Losing
candidates are reverted and recorded in the canonical technical log with their source identity and causal result.
Later work starts from the last fully qualified source and never builds on a regression because its code reduction or
local design looks attractive.

## Qualification

### Non-negotiable correctness contracts

Every complete replacement checkpoint preserves all of the following. Failure of one contract rejects the candidate:

- Aggregate compatibility and existing persisted/wire data;
- JSON, CBOR and final-binary compatibility;
- exact Model identity and alias semantics;
- current and historical reconstruction;
- batch-local read-your-writes;
- atomic multi-model commits;
- conflicts, retries and idempotency;
- commit and result-completion policies;
- event counts, order and once-only global publication;
- temporal relationships, moves and cycle checks;
- logical deletion, cascade deletion, hard erasure and detached lineage;
- direct documents and materialized graph projections;
- Graph search, filtering, tombstones and `ASYNC`/`AWAIT`;
- bounded memory and backpressure;
- restart, recovery, shutdown and partial failures;
- `TestFixture`, Java, Kotlin and downstream compatibility.

### Performance gate

The qualifying route remains:

```text
command
  -> Model mutates
  -> Changes commit atomically with events
  -> durable result
  -> original request future completes
```

Current references are the 425,606 commands/s quiet-host model pin, the 358,973 commands/s active-host floor, the
978,950 commands/s current no-model run and the 1,011,511 commands/s historically stable no-model pin.

Every complete replacement checkpoint uses matched control/candidate runs in the same host state and verifies exact
result, Model-event, global-event and final-state counts. No meaningful throughput, latency or allocation regression
and no worsening of batching, backpressure or memory ownership is accepted. An absolute run detects dramatic route
failure. Common, wire, tracking or completion changes rerun no-model. Runtime storage changes separately characterize
initial create, updates, long streams, relationships, deletion, Graphs and reconstruction.

## Definition of done

S60 completes only when:

- the code and its ownership visibly follow `Model → Mutation → Change → Commit`;
- `ModelDefinition` owns compiled registered knowledge and `ModelRepository` owns reconstruction and persistence;
- SDK production Java is at most 136,000 lines;
- Runtime production Java is at most 32,000 lines;
- superseded owners and pipelines are physically absent;
- every responsibility has one final representation and one lifecycle;
- no helper vocabulary recreates execution, replay, dependency or completion mini-domains;
- all capabilities arise from the one foundation rather than parallel flows;
- both complete Maven reactors pass;
- downstream Java/Kotlin compatibility passes;
- schema, restart, recovery and reconstruction tests pass;
- site, Javadocs and public documentation are correct;
- complete feature characterization is retained;
- matched and absolute performance gates pass;
- no functional capability was sacrificed to reach the code budget.

## Evidence and history

- [CP1–CP14 code-budget ledger](dynamic-model-boundaries-final-code-budget.md)
- [Model capacity log](performance-runs/sdk-model-commit-capacity-log.md)
- [Feature characterization log](performance-runs/sdk-model-feature-characterization-log.md)
- [JDBC staging cleanup log](performance-runs/jdbc-staging-cleanup-log.md)
