# Dynamic model boundaries — final code-budget architecture

Status: active S60 release blocker

Date: 2026-08-17

## Living scoreboard

| Checkpoint | SDK production Java | Runtime production Java | Combined removal | Performance decision |
| --- | ---: | ---: | ---: | --- |
| S60 start (`f19e3db42c8` / `610a7060`) | 168,771 | 42,038 | — | accepted control |
| CP1 canonical binary (`7c62c608352` / `0661533f`) | **168,082** | **42,037** | **690** | accepted; no-model neutral, full model +0.27% |
| Required final ceiling | **136,000** | **32,000** | **42,119 still to remove** | pending |

## Objective

Retain the complete independent-model and graph capability, its correctness contracts and its accepted performance,
while returning hand-maintained production Java to at most:

- SDK repository: **136,000 physical `src/main/java` lines**;
- Runtime repository: **32,000 physical `src/main/java` lines**.

Formatting compression, moving Java into resources, generated equivalents, deleted Javadocs, weaker tests and hidden
implementation do not count. The reduction must come from one owner per responsibility, one representation per state,
one execution pipeline and reuse of existing Fluxzero foundations.

## Causal baseline

The primary comparison is the last accepted ownership-reduction checkpoint, not `main`. Almost all broad Model
functionality already existed there. `main` remains useful only as an architectural reference for compact ownership.

| Repository | Reduction checkpoint | P5 performance pin | Current | Required removal |
| --- | ---: | ---: | ---: | ---: |
| SDK | `dd2fb5f3a14`: 135,566 | `bb8e1db2231`: 153,288 | `f19e3db42c8`: 168,771 | **32,771** |
| Runtime | `4512f59e`: 31,944 | `0c23c91f`: 40,410 | `610a7060`: 42,038 | **10,038** |

The SDK grew by 17,722 lines through P5 and by another 15,483 afterwards. The Runtime grew by 8,466 lines through P5
and by 1,628 afterwards. Combined growth after the reduction checkpoint is 43,299 production lines.

The fixed non-core roots make the budget exact:

- current SDK `common` plus `sdk` contain 160,954 lines; the unchanged supporting roots use 7,817, so the core budget
  is 128,183 and requires removing 32,771 lines;
- current Runtime core contains 39,566 lines; the unchanged usage benchmark application uses 2,472, so the core budget
  is 29,528 and requires removing 10,038 lines.

The requested ceilings therefore permit virtually no net core growth after the reduction checkpoint. Every retained
post-checkpoint capability must replace at least as much older implementation as it adds.

## Accepted checkpoint CP1 — one canonical binary envelope

The first checkpoint removes the unreleased `BINARY_V2` generation and the legacy compact message encodings behind
`BINARY`. `BINARY` now always carries the final reusable `SerializedMessage` envelope. JSON and CBOR are unchanged.
Tracking and model commits no longer maintain paired legacy/native serializers, descriptors and readers. The removed
formats existed only on this development branch and are not released compatibility contracts.

The first implementation changed the hot type-dispatch order in `TrackingWireCodec`. A pinned three-pair no-model
series measured 721,826/s for the control and 683,061/s for that intermediate candidate (**-5.37%**). Matched JFRs
showed smaller result transactions and slower command serialization. Inspection of the exact parent bytecode found
that the previous native route checked `ReadResult` before the generic `Read` path. Restoring that order removed the
regression; this is why the rejected intermediate was not checkpointed despite having identical wire bytes.

| Qualifying route | Control | Final candidate | Difference | Correctness |
| --- | ---: | ---: | ---: | --- |
| no-model command -> durable result, geometric mean | 746,241/s | 735,636/s | -1.42% | exact completions |
| command -> `@Apply` -> model commit -> event + result, ABBA geometric mean | 179,536/s | 180,022/s | +0.27% | exact 1,048,576 results, model events and global events; 8,192 exact states |

The no-model result is inside the observed adjacent host movement: the final individual candidate runs were 782,741/s
and 691,365/s while their surrounding controls were 797,024/s and 698,694/s. The full-model ABBA alternated in the
same way: controls 186,874/s and 172,486/s; candidates 173,653/s and 186,625/s. Both complete Maven reactors and the
SDK downstream Java/Kotlin projects passed after the final hot-path ordering change.

Six earlier no-model runs are explicitly non-canonical because the required 1,048,576-entry command cache pin was
omitted. They exercised the known 65,536-entry cache cliff and ranged from 542,018/s to 784,956/s; they are retained in
the run registry as diagnostic-only evidence and were not used for either the performance decision or checkpoint.

## What caused the growth

The growth is not proportional to added product behavior.

### SDK

Two performance commits account for 14,370 net production lines:

- `c5be57825a26` adds compact model transport, bounded reconstruction and cache-aware loading: **+6,150**;
- `0c8866009654` adds native envelopes, binary protocols, grouped preparation and commit policies: **+8,220**.

The largest concrete expansions since the reduction checkpoint are:

| Responsibility | Reduction shape | Current shape | Net observation |
| --- | ---: | ---: | ---: |
| handler/commit orchestration | action registry + engine + committer: 2,484 | commit registry + engine + committer: 7,100 | **+4,616** |
| graph value/view | `ModelGraph`: 85 | `Graph` + `Graphs`: 2,722 | **+2,637** |
| model repository | `DefaultModelRepository`: 2,341 | 4,428 | **+2,087** |
| native message state | `SerializedMessage` + `Metadata`: 722 | 3,489 | **+2,767** |
| model/tracking wire codecs | none | three handwritten codecs: 2,835 | **+2,835** |

The handler registry now also owns a complete batch scheduler, keyed dependency graph, speculative model view,
prefetcher, commit-policy state machine and transport producer lifecycle. `MessageBatchModelView` separately owns a
second staged batch view. The repository separately implements ordinary replay, compact replay, direct replay,
historical graph boundaries and graph overlays. These are fast, but their control flow is multiplied rather than
compiled into one path.

### Runtime

`1c3e8a9b2f0b` added the high-throughput independent-model storage route in one step: **+5,759** production lines.
`JdbcModelCommitStore` grew from the 5,046-line `JdbcModelActionStore` to 10,887 lines. It retains the prior general
commit, stream and replay representation while adding packed initial streams, packed updates, derived locators,
locator recovery, block caches and specialized reads. Four new block/update codecs add another 1,134 lines.

This is the principal Runtime issue: the fast representation is an additional storage system instead of the only
model storage representation.

### Branch-internal compatibility and diagnostics

The new binary websocket formats do not exist on the reduction checkpoint or current `main`; both support JSON and
CBOR. `BINARY` and `BINARY_V2` are successive unreleased branch formats. Likewise, the model storage layout is
unreleased and its packed codec currently reads five branch-internal versions while schema setup migrates earlier
preview tables.

S60 targets a release relative to `main`. JSON/CBOR and supported aggregate data remain compatibility contracts, but
successive branch-internal binary and model-table previews are not permanent product contracts. Only one final native
wire format and one final model storage format should survive. Detailed campaign-only JFR stages should become a small
stable set of boundary metrics rather than permanent branches throughout every hot path.

## Target architecture

### 1. One final native message protocol

Keep JSON and CBOR compatibility. Replace the two unreleased binary generations with one negotiated `BINARY` format
using the current envelope semantics. One allocation-bounded binary reader/writer owns primitive validation, slices,
strings, nullable values and lists. Model commit, model event and tracking codecs become thin type dispatchers instead
of carrying separate reader/writer implementations and legacy/native control flows.

`SerializedMessage` remains the public facade and retains lazy component materialization. Its envelope state and the
compact immutable metadata map use the same byte-view primitives. There is no second native message subclass and no
intermediate map or CBOR representation on the native route.

### 2. One compiled SDK model pipeline

Compile one immutable plan per payload type. The plan contains target accessors, handler invokers, dependency access,
apply result shape and concrete commit policy. Manual `assertLegal`, `assertAndApply`, registered automatic handling,
collection applies, graph changes and retries all submit the same evaluation request.

The direct single-target case remains a compiled strategy inside this pipeline, not a second lifecycle. The generic
case uses the same load, evaluate, stage and commit stages. `ModelCommitHandlerRegistry` returns to registration and
dispatch ownership; it does not own transport batching or a second async scheduler.

### 3. One message-batch model scope

`MessageBatchModelView` becomes the only batch-local read-your-writes and dependency owner. A compact keyed scheduler
records producers, values and completion futures. Commit policy is expressed by two lifecycle choices—start at handler
or batch completion, await at handler or batch completion—rather than separate ticket, gate, wave and producer state
machines.

The same scope serves automatic handlers and explicit nested model operations. Exact per-model ordering remains; an
unrelated model does not acquire a dependency. Context propagation and result publication retain their existing
boundaries.

### 4. Graph is one indexed view

Represent a graph as one immutable adjacency/index state plus lazy node resolver. `Graph<T>` is a typed view onto that
state. Root, parent, ancestor, child and descendant navigation use the same indexes. Previous-state, selection,
filtering, mapped context and change views are options on the view, not forwarding wrapper implementations that repeat
the complete public interface.

Repository graph loading returns that state directly. Serialization, content filtering, materialized search results,
handler injection and graph-change delivery all consume the same view. The repository no longer constructs an
intermediate graph tree and then wraps it in a second graph implementation.

### 5. One repository replay cursor

Model reconstruction uses one cursor over model stream blocks. Current, historical, direct and batch loads differ by
boundary and projection options, not by replay algorithm. Compact pages and ordinary pages adapt into the same cursor
without separate apply loops. Alias resolution, document heads, snapshots and batch-local overlays are inputs to that
cursor.

The event request batcher, batch loader and websocket client share one bounded request/result pipeline. In-memory and
TestFixture stores use the same transition planner and cursor, with storage represented by a narrow adapter rather
than a parallel commit/replay implementation.

### 6. One packed Runtime model representation

Promote the measured packed representation to the canonical representation for both initial models and later updates.
One block format contains model membership, state/sequence boundaries and payload references. The general row path and
the initial-only packed path cease to be separate storage engines. Rare relationships, conflicts, aliases, deletions,
documents and snapshots are optional mutations in the same prepared commit plan, not alternate commit pipelines.

One locator/index mechanism serves every block. Initial and updated streams use one loader and one cache. Hard erasure
and historical reads operate on the same blocks. The schema and codecs retain only the final unreleased format.

The existing global event log remains the single publication owner. Any reuse of `JdbcMessageStore` is through its
transaction preparation, binary copy, batching and ordered publication primitives; model commits do not introduce a
second message-store transaction or duplicate publication.

### 7. One durable update-consumer mechanism

Cache tracking, direct materialization recovery, graph projection and derived locator work retain distinct domain
actions but share one cursor/wakeup/retry lifecycle over the existing model update log. No capability owns a private
polling, retention or completion platform. Direct materialization remains awaited, graph projection retains
`ASYNC`/`AWAIT`, and restart recovery remains autonomous.

### 8. Aggregate compatibility through shared mechanics

`@Aggregate`, its public types and its persisted/event wire contracts remain supported. Shared entity application,
reflection plans, snapshots, revision traversal, handler invocation and cache mechanics move behind aggregate-neutral
primitives used by both Aggregate and Model. Aggregate becomes a compatibility/configuration shape over those
mechanics rather than justification for keeping a second SDK replay and apply engine.

## Removal budget

These are design budgets, not credit for moving code. A workstream that cannot remove its budget without regression
must be redesigned before another feature is sacrificed.

| SDK workstream | Required structural removal |
| --- | ---: |
| final binary envelope, metadata and wire protocol | 6,000–7,000 |
| single commit plan, handler pipeline and batch scope | 7,000–8,000 |
| one graph state and one repository/replay cursor | 7,000–8,000 |
| shared Aggregate/Model and in-memory transition mechanics | 6,000–7,000 |
| preview migration, campaign diagnostics and thin integration cleanup | 3,500–4,500 |
| **Required SDK total** | **32,771** |

| Runtime workstream | Required structural removal |
| --- | ---: |
| canonical packed stream and one commit/store path | 5,000–6,000 |
| shared update, recovery and locator lifecycle | 1,500–2,000 |
| reuse message/JDBC preparation, copy, batching and schema primitives | 1,200–1,800 |
| final-only codecs/schema plus campaign diagnostics | 1,000–1,400 |
| **Required Runtime total** | **10,038** |

The ranges deliberately overlap. Acceptance is based on the repository totals, not on assigning a deleted line to a
preferred workstream.

## Execution order

1. Freeze the current exact functional matrices and matched performance pins as immutable controls.
2. Remove branch-internal binary/storage compatibility and collapse primitive binary I/O; prove byte, allocation and
   no-model/model E2E behavior before proceeding.
3. Replace Graph wrappers and repository graph composition with one indexed graph view.
4. Replace the SDK handler/ticket/gate and duplicate batch-view machinery with one compiled model pipeline and scope.
5. Spike the canonical packed Runtime representation together with the single SDK replay cursor. Keep the spike only
   when current, historical, long-stream, relationship, deletion, restart and full E2E profiles all pass.
6. Move shared Aggregate/Model transition mechanics behind neutral primitives and delete superseded implementations.
7. Remove campaign-only diagnostics, run the final ownership/LOC audit and close any remaining budget through the
   largest surviving duplicate owner—not through formatting or weakened contracts.

Each accepted step is a separate checkpoint commit. Losing candidates are reverted and recorded; no later work builds
on a regression merely because it removes many lines.

## Non-negotiable gates

Every checkpoint must preserve:

- JSON/CBOR compatibility and the one selected final binary format;
- aggregate storage, replay, snapshots, search, handler and cache behavior;
- model identity, aliases, exact current/historical reconstruction and batch-local read-your-writes;
- atomic multi-model commits, conflicts, retries, idempotency and completion policies;
- temporal relationships, moves, cycles, cascades, logical deletion, hard erasure and detached lineage;
- direct documents, materialized graphs, graph search, `ASYNC`/`AWAIT`, recovery and tombstones;
- event and result counts, ordering, one-time publication and durable request completion;
- bounded memory, backpressure, shutdown and mixed JSON/CBOR clients.

Performance truth remains the full qualifying E2E route. Common/transport changes also rerun the no-model route;
model-store changes rerun initial, updated, long-stream, relationship, deletion, graph and reconstruction profiles.
Matched control/candidate pairs are required for throughput, latency and allocation. The accepted approximately
one-million-command no-model pin and approximately 400k/s full model/event/result pin may not regress.

The task is complete only when both absolute repository ceilings, both complete Maven reactors, downstream
compatibility, site/Javadocs, schema/restart tests, functional characterization and matched performance gates pass.
