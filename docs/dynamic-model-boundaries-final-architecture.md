# S60 — final Model architecture

Status: canonical active end design

Date: 2026-08-19

## Authority and baseline

This document is the architectural source of truth for the remaining S60 work. The CP1–CP14 code-budget ledger and
performance logs remain evidence for retained changes, rejected candidates and known-good behavior, but their former
“complete” labels do not close Macro 2–5. A macro closes only when the end state in this document exists and its
superseded owners are physically absent.

The accepted CP14 source is the safe functional baseline:

| Repository | CP14 production Java | Hard ceiling | Exact remaining removal |
| --- | ---: | ---: | ---: |
| SDK (`common`, `sdk`, `test-server`, `proxy`) | 160,194 | 136,000 | **24,194** |
| Runtime | 36,919 | 32,000 | **4,919** |
| **Combined** | **197,113** | **168,000** | **29,113** |

Macro 1 is complete at CP8: the Runtime has one canonical commit plan and block storage representation. CP9–CP14
contain useful reductions and qualified building blocks for Macro 2–5, but are intermediate architecture. Earlier
wire, storage or diagnostic reductions may satisfy prerequisites of a later macro; their lines are never credited a
second time.

The objective is to pay the exact remaining debt with fewer representations, engines and lifecycle owners. It is not
to make the same architecture visually shorter. Formatting compression, deleted documentation or tests, generated
equivalents, moved source, compatibility loss, wrapper layers around old engines and temporary bridges do not count.

## One final architecture

The final SDK has one neutral update and reconstruction foundation. `@Aggregate` and `@Model` remain different public
programming and persistence contracts; `@Stateful` remains a third, independent handler category. Those forms select
policies and persistence adapters. They do not select different reflection, invocation, transition, replay, cache or
fixture engines.

The fixed live path is:

```text
message
  -> immutable UpdatePlan
  -> UnitOfWork with one pinned StateBoundary
  -> ReplayCursor resolves the plan's state slots
  -> TransitionEngine evaluates ordered invocations
  -> PersistenceAdapter commits one CommitPlan
  -> authoritative result advances revisions and completion once
```

The fixed read path is:

```text
typed read + boundary + projection options
  -> ReplayCursor
  -> immutable revision state or GraphState
  -> typed Entity/Graph view
```

The fixed Runtime-derived-state path is:

```text
canonical model block/update feed
  -> one durable UpdateCursor
  -> locator, direct-document and graph-projection actions
  -> centrally owned acknowledgements, retry, recovery and shutdown
```

### Neutral primitives

The following responsibilities exist exactly once. Names may reuse a suitable current type, but their ownership may
not be split again.

| Primitive | Sole responsibility |
| --- | --- |
| `EntityTypePlan` | Class-scoped identity, aliases, parents, root policies and structural handler metadata owned by `ReflectionUtils.TypeMetadata`. It captures no application or instance state. |
| `UpdatePlan<P>` | Immutable application-scoped plan per payload type and registry generation: ordered invokers, receiver/argument/dependency accessors, target slots, result decoders, transition effects and commit/conflict policies. |
| `StateBoundary` | Current, exact, before-state, commit and event boundaries plus the state index pinned by the first read. Every downstream read receives this value unchanged. |
| `StateFrame` | Slot-indexed immutable begin state for one plan evaluation. It contains resolved identities, revisions, relationships and staged replacements without parallel maps per subsystem. |
| `Transition` | Neutral before/after revision and its identity, relationship, alias, snapshot, document, publication and deletion effects. Aggregate and Model adapters serialize the same transition contract differently where their persisted protocols genuinely differ. |
| `UnitOfWork` | The only mutable live execution scope. It owns ordered operations, model tails, exact dependencies, staged values, transport readiness and completion. |
| `ReplayCursor` | The only seed, page, validation and apply loop for current and historical reconstruction. Storage adapters normalize stored input; they do not replay it. |
| `GraphState` / `GraphView<T>` | One immutable adjacency/index representation, one state resolver and one typed view with immutable options. |
| `PersistenceAdapter` | Thin Aggregate- or Model-specific mapping between neutral transitions/replay pages and the existing persisted/wire contract. It owns no handler discovery, transition evaluation, dependency graph, retry or cache lifecycle. |
| `UpdateCursor` | The only durable update-source cursor and scheduler. Domain actions own their transformation, never their own worker, cursor, retry loop or shutdown lifecycle. |

An application-scoped plan cache is invalidated as one generation when handler registration changes. Structural
reflection stays in `ReflectionUtils.TypeMetadata`; runtime state stays in the application, unit of work or cursor.

## Macro 2 — one compiled SDK update pipeline

### End state

Every automatic handler, manual `assertLegal`, manual `assertAndApply`, Graph update, collection apply, retry and graph
change creates the same operation record in one `UnitOfWork`. Execution mode changes plan options only:

| Mode | Plan differences |
| --- | --- |
| automatic | normal assertions, interception, transitions and commit |
| assert-only | assertions enabled; transition emission and commit disabled |
| explicit apply | normal plan with an explicit target constraint |
| stored replay | assertions/interception disabled; transition invocation unchanged |
| retry/rebase | same operation and plan with a replacement boundary and freshly resolved frame |
| direct single target | precompiled one-slot invocation strategy inside the same evaluator and commit continuation |

`UpdatePlan` contains concrete accessors and invocation functions. Runtime evaluation may read values through those
accessors, but it performs no handler, parameter, target, dependency, result-shape or policy discovery. Collection and
dynamic results are compiled result decoders, not switches in the engine.

`UnitOfWork` stores operations by index. Model ID to last-writer indexes establish exact predecessors. One completion
array and one transport tail represent lifecycle state; no operation object extends `CompletableFuture`, and there is
no ticket, gate, wave, producer wrapper or second dependency graph. Commit policy compiles to two moments: when the
operation may start transport and what completion the original request must await.

The commit protocol receives evaluated transitions and produces one wire request. It maps the authoritative result
back to revisions, but owns no discovery, evaluation, retry, ordering, publication barrier or projection wait.

### Current owners to absorb or remove

| Current owner | Final disposition |
| --- | --- |
| `ModelCommitHandlerRegistry` | Retain only supported registration, plan-cache invalidation and dispatch facade. |
| `ModelExecutionPlan.Compiler` | Replace with plan compilation; move runtime evaluation to the neutral transition engine. |
| `ModelTargetResolver` | Remove after target and dependency accessors are compiled into `UpdatePlan`. |
| `ModelEntityParameterResolver` | Remove as a model execution planner; generic parameter support supplies compiled accessors. |
| `ModelCommitContext` | Replace its repeated lookup maps with the plan's slot-indexed `StateFrame`. |
| `ModelBatchScope.Entry` | Remove. `UnitOfWork` owns dependency and completion arrays without a ticket-shaped future. |
| `ModelPipeline` | Replace its automatic/manual/replay/direct/retry branches with one operation continuation. |
| `ModelCommitProtocol` | Reduce to transition-to-wire and result-to-revision mapping. |

The eight named owners currently contain 6,977 production lines. Macro 2 is not complete merely because those lines
move to new neutral files: target resolution, invocation, dependencies, result interpretation, retry and completion
must each have one representation.

### Acceptance

- One plan compilation test proves that every supported entry form uses the same accessors, invokers and result shape.
- One operation representation covers automatic, explicit, Graph, collection, replay and retry behavior.
- The old resolvers, route-specific evaluators and ticket lifecycle are absent.
- Direct single-target performance remains a plan strategy with the shared commit/completion tail.
- The post-Macro-2 planning ceiling is **154,500 SDK production lines**.

## Macro 3 — one Graph state and one replay cursor

### End state

`GraphState` contains immutable placement arrays and indexes: concrete identity/type, parent ordinal, relationship path,
ordered child ranges, identity/type lookup and reverse ancestry. All nodes share one resolver bound to the state's
pinned boundary. Lazy resolution mutates only bounded memoized value/revision cells; structural adjacency and indexes
never change.

`GraphView<T>` is exactly `(state, ordinal, options)`. `ViewOptions` contains path selection, branch/value filtering,
path mapping, response context and explicit history/change context as immutable data. Options compose by value; they do
not chain forwarding objects, decorators or closures that recursively rebuild the public `Graph` contract. Root,
parent, ancestor, child and descendant traversal all use the state indexes.

`ReplayRequest` contains targets, one `StateBoundary`, a seed policy, an optional staged overlay and a projection.
Replay has one loop:

```text
normalize identities -> choose seeds -> pin boundary -> fetch validated page
  -> apply every included membership through TransitionEngine -> advance cursor
  -> project Entity, StateFrame or GraphState
```

Cache entries, snapshots, direct documents and empty values are ordered seed candidates. Aliases normalize request
identity before reconstruction. A batch overlay replaces seed values and relationships before projection. Commit/event
prefixes are boundary predicates in the same membership loop. None has a private reconstruction session or apply loop.

Current compatible reads may share one bounded request coalescer. Historical and local reads can execute directly, but
both consume the same request, page validation and cursor logic.

### Current owners to absorb or remove

| Current owner | Final disposition |
| --- | --- |
| `Graphs` | Keep only state construction, indexes, resolver and the sole `GraphView`; replace chained `ViewContext` decorators with value options. |
| `MaterializedGraphFactory` | Reduce to manifest-to-`GraphState` input conversion; no graph object model. |
| `DefaultModelRepository` | Thin public facade and persistence adapter; no boundary conversion, reconstruction, Graph composition or overlay engine. |
| `ModelReplayCursor.Session` | Replace current view-, prefix-, direct- and generic-apply branches with one cursor state machine. |
| `ModelReadBoundary` | Become the neutral `StateBoundary`; no handler-, Graph- or repository-specific variants. |
| `ModelAncestorResolver` | Remove once reverse adjacency and cursor projection own ancestry. |
| `ModelCacheTracker` replay/refresh paths | Consume the same cursor and seed contract; no parallel cache reconstruction. |

The six direct CP12 owners were 5,982 lines before later mechanical changes. Graph/cache/search adapters enlarge the
real replacement surface. Macro 3 closes only when the cursor is an algorithm rather than a 2,000-line union of the
old algorithms.

### Acceptance

- `GraphView<T>` is the only SDK `Graph<T>` implementation and every view retains one state identity.
- Current, historical, before-state, commit/event, batch, direct-document, ancestor, cache-refresh and Graph reads use
  one replay request and membership loop.
- No intermediate durable graph is overlaid and rewrapped.
- Alias, snapshot, cache, direct-document and batch behavior are inputs or projections, never lifecycle owners.
- Paging, coalescing, lazy values and view caches remain bounded.
- The post-Macro-3 planning ceiling is **148,500 SDK production lines**.

## Macro 4 — shared Aggregate and Model mechanics

### End state

Aggregate and Model use the neutral plans, state frames, transition engine, replay cursor, revision cache and unit of
work. Their public annotations, repositories and persisted data remain compatible facades over two thin
`PersistenceAdapter`s:

- the Aggregate adapter maps one aggregate stream, existing event publication semantics, relationship protocol and
  snapshot representation;
- the Model adapter maps independent model streams, namespace state boundaries, atomic multi-model commits and model
  snapshot/document metadata.

An adapter may normalize a stored page or serialize a commit. It may not inspect handlers, invoke an apply, build a
revision chain, run replay, decide caching, schedule completion or implement retries.

One internal revision value owns identity, sequence/event/state positions, timestamp, previous revision and value.
Aggregate- and Model-specific public root types add only fields that are genuinely part of their different contracts.
Entity update methods delegate to the same transition engine; `ModifiableAggregateRoot` is not a second engine.

Snapshot and cache policies are application-scoped values referenced by the plan and cursor; only their structural
eligibility belongs to `EntityTypePlan`. A shared snapshot lifecycle selects, validates, retains and writes snapshots
through a format adapter. A shared revision cache applies the same fencing, depth and invalidation rules. Persisted
snapshot bytes may remain different where compatibility requires it.

`TestFixture` uses the same in-memory persistence adapter and transition/replay engine as production-facing
repositories. Aggregate and Model convenience methods construct different descriptors, not different stores or apply
loops. The existing large `InMemoryEventStore` is reduced to generic message/event storage plus thin modeling
adapters; it does not reimplement model commit/replay behavior.

`@Stateful` handlers remain neither Aggregate nor Model. They continue through generic handler and stateful-persistence
contracts and use only genuinely neutral reflection/identity primitives.

### Current owners to absorb or remove

- separate Aggregate and Model handler invocation and apply loops;
- `DefaultAggregateRepository.AnnotatedAggregateRepository` as a complete load/replay/commit engine;
- Model-specific reconstruction outside the shared cursor;
- duplicate snapshot selection, revision retention and cache lifecycle;
- orchestration in `ModifiableAggregateRoot` and `LazyAggregateRoot` that duplicates the transition/replay engine;
- Model-versus-Aggregate persistence branches in `TestFixture`;
- parallel in-memory modeling persistence and production replay semantics.

`EntityMetadata`, `PersistedRoot` and `ImmutableRoot` are useful CP13 building blocks, not proof of completion. They stay
only if they become the neutral owners above and allow the duplicate engines to disappear.

### Acceptance

- Aggregate and Model handler invocation is produced by the same plan compiler and transition engine.
- Current/historical reconstruction uses the same cursor with different persistence adapters.
- Snapshot, cache, revision traversal and fixture behavior each have one lifecycle owner.
- Aggregate compatibility is proven against existing API, snapshots, events, relationships and downstream use.
- Stateful handlers remain a separate category without Aggregate/Model branching.
- The post-Macro-4 planning ceiling is **140,000 SDK production lines**.

## Macro 5 — one final wire, update and integration path

### End state

The retained transport formats are JSON, CBOR and exactly one negotiated `BINARY`. `SerializedMessage` is the sole
native envelope and `BinaryWire` the sole primitive reader/writer. Tracking, model commit and model event dispatchers
select one protocol schema and otherwise fall back to the normal JSON/CBOR object model. They do not own private byte
growth, UTF-8, nullability, bounds, envelope or version machinery.

`ModelBlockCodec` remains the only Runtime model block codec established by Macro 1. Macro 5 may simplify its consumers
but receives no credit for CP8's storage reduction. Supported released wire and persisted forms retain compatible
readers; unreleased branch previews and campaign-only diagnostics do not.

Runtime has one `UpdateCursor` over the canonical `model_update_feed`. It reads each bounded page once and offers it to
registered domain actions:

| Action | Domain responsibility only |
| --- | --- |
| locator | derive block hashes/head lookup information |
| direct materialization | apply document and snapshot mutations |
| graph projection | derive affected roots and write graph documents/tombstones |
| cache tracking | expose the same ordered update cursor contract to SDK subscribers |

The cursor owner persists action acknowledgements centrally. Independent action progress is represented as lanes in
that one cursor state, not as private cursor columns, workers or retry loops in each store. A slow or disabled lane does
not block unrelated foreground commits; its durable acknowledgement lets the common cursor replay it after restart.
The cursor owns wake-up generation, waiter bounds, coalescing, serialization per lane, retry/backoff, recovery,
backpressure and shutdown.

Each action is idempotent and shaped as `apply(page, fromBoundary, toBoundary) -> acknowledgement`. It owns no executor,
running/recovering flag, delayed scheduling, polling loop, completion monitor or shutdown join. Projection-specific
configuration and AWAIT waiters remain domain data, but their scheduling and durable progress belong to the cursor.

The SDK cache tracker consumes `TrackModelUpdates` through one generic bounded update subscription. It owns cache
projection logic, not a second transport retry/recovery lifecycle. Document and Graph consumers use the same update
page and boundaries rather than separately decoding or querying an update feed.

The oversized Runtime store and projection classes are reduced around the canonical plan/block/cursor primitives.
Repeated block location, decode, boundary, receipt and update-feed SQL becomes one internal block access path; splitting
the same rules into more files is not a reduction.

### Current owners to absorb or remove

- private protocol primitive/envelope/version logic in tracking/model codecs;
- remaining branch-only preview readers, storage adapters and campaign diagnostics;
- `ModelUpdateLifecycle` as a scheduler above still-independent durable cursors;
- locator `located_state_index`, materialization `pending_materialization_state_index` and per-projection worker-owned
  `processed_state_index` as separate lifecycle mechanisms; central cursor lanes replace them while preserving
  externally visible progress;
- action-specific running/recovering flags, retry timers, polling workers, executors and shutdown joins;
- duplicated update-feed queries and block decode/location logic across commit, tracking and Graph projection stores;
- temporary SDK/Runtime integration adapters that only bridge feature-branch generations.

### Acceptance

- JSON, CBOR and final binary compatibility pass in mixed-version and malformed-input tests.
- Exactly one native envelope, primitive codec, final model block codec and update-feed representation remain.
- Locator, materialization and graph projection are pure cursor actions with centrally owned durable progress and
  lifecycle.
- Schema migration folds existing locator, materialization and projection progress into cursor lanes without losing
  stored progress or retaining a standing dual lifecycle.
- SDK cache tracking uses the same bounded cursor protocol and generic subscription lifecycle.
- Restart, recovery, partial failure, long polling, AWAIT and shutdown prove no lost or duplicated updates.
- Final source is at most **136,000 SDK** and **32,000 Runtime** production lines.

## Code-budget runway

The final ceilings are hard. The intermediate ceilings below are planning allocations that make the current exact debt
close without double counting:

| Checkpoint | SDK ceiling | SDK removal from previous | Runtime ceiling | Runtime removal from CP14 |
| --- | ---: | ---: | ---: | ---: |
| CP14 baseline | 160,194 | — | 36,919 | — |
| Macro 2 | 154,500 | 5,694 | 36,919 | — |
| Macro 3 | 148,500 | 6,000 | 36,919 | — |
| Macro 4 | 140,000 | 8,500 | 36,919 | — |
| Macro 5 / final | **136,000** | **4,000** | **32,000** | **4,919** |
| **Exact remaining removal** | | **24,194** | | **4,919** |

A macro may remove somewhat more or less when its full redesign genuinely requires that. Before accepting such a
checkpoint, the remaining allocations must be reforecast from concrete owner footprints and still close exactly at
136,000/32,000. A budget is never relaxed retroactively merely because a candidate is correct, fast or has a cleaner
class name. LOC reduction alone likewise never compensates for a surviving duplicate engine.

Physical counts include every hand-maintained Java file under production `src/main/java` roots. Moving code, deleting
documentation, generating equivalent source, compressing formatting or hiding implementation in resources receives no
credit.

## Macro qualification

Before implementation of each macro, its checkpoint note records:

1. the current owner/file footprint;
2. the final owner map and maximum successor footprint;
3. every class, representation, loop and lifecycle that will disappear;
4. the public, persisted, wire and extension contracts at risk;
5. focused correctness and performance qualification;
6. rollback to the previous accepted source checkpoint.

Correctness and performance qualify the boundary of a complete replacement, not each edited file. Losing candidates
are reverted and recorded in the technical log. Later work never builds on a regression because its LOC result is
attractive.

### Non-negotiable correctness contracts

Every macro preserves:

- Aggregate compatibility and existing persisted/wire data;
- JSON, CBOR and final-binary compatibility;
- exact identity and alias semantics;
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
- TestFixture, Java, Kotlin and downstream compatibility.

### Performance gate

The qualifying route remains:

```text
command
  -> model applies
  -> atomic model/event commit
  -> durable result
  -> original request future complete
```

Current references are the 425,606 commands/s quiet-host model pin, the 358,973 commands/s active-host floor, the
978,950 commands/s current no-model run and the 1,011,511 commands/s historically stable no-model pin.

Every checkpoint uses matched control/candidate runs in the same host state and verifies exact result, model-event,
global-event and final-state counts. It rejects meaningful throughput, latency or allocation regression and any
worsening of batching, backpressure or memory ownership. An absolute run detects route failure. Common, wire,
tracking or completion changes rerun no-model. Runtime store changes separately characterize initial create, updates,
long streams, relationships, deletion, Graphs and reconstruction.

## Definition of done

S60 completes only when:

- SDK production Java is at most 136,000 lines;
- Runtime production Java is at most 32,000 lines;
- superseded owners and pipelines are physically absent;
- every responsibility has one final representation and lifecycle;
- both complete Maven reactors pass;
- downstream Java/Kotlin compatibility passes;
- schema, restart, recovery and reconstruction tests pass;
- site, Javadocs and public documentation are correct;
- complete feature characterization is retained;
- matched and absolute performance gates pass;
- no functional capability was sacrificed to reach the code budget.

The desired end state is not the same enormous architecture with shorter methods. It is a release-ready Model
architecture whose ownership and compactness match the rest of Fluxzero: one clear foundation from which the breadth
of behavior follows naturally.

## Evidence and history

- [CP1–CP14 code-budget ledger](dynamic-model-boundaries-final-code-budget.md)
- [Model capacity log](performance-runs/sdk-model-commit-capacity-log.md)
- [Feature characterization log](performance-runs/sdk-model-feature-characterization-log.md)
- [JDBC staging cleanup log](performance-runs/jdbc-staging-cleanup-log.md)
