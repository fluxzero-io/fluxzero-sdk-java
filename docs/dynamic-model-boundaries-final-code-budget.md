# Dynamic model boundaries — final code-budget architecture

Status: active S60 release blocker

Date: 2026-08-18

## Living scoreboard

| Checkpoint | SDK production Java | Runtime production Java | Combined removal | Performance decision |
| --- | ---: | ---: | ---: | --- |
| S60 start (`f19e3db42c8` / `610a7060`) | 168,771 | 42,038 | — | accepted control |
| CP1 canonical binary (`7c62c608352` / `0661533f`) | **168,082** | **42,037** | **690** | accepted; no-model neutral, full model +0.27% |
| CP2 shared binary primitives (`ddc1b73d718` / `0661533f`) | **167,723** | **42,037** | **1,049** | accepted; no-model +11.42%, full model +2.21% |
| CP3 unified lazy message state (`e669a148e36` / `0661533f`) | **167,411** | **42,037** | **1,361** | accepted; no-model +3.98%, full model +0.48% |
| CP4 shared graph views (`79ae96a3961` / `0661533f`) | **167,195** | **42,037** | **1,577** | accepted; full model +10.27% |
| CP5 final stream previews (`19ae14899ed` / `9f74e3d0`) | **166,033** | **41,696** | **3,080** | accepted; full model -0.51%, sustained reconstruction +14.65% |
| CP6 stable batch metrics (`32ccf7b0c575` / `ecdd6d5a9fff`) | **165,457** | **41,471** | **3,881** | accepted; full model -0.26% amid bidirectional host drift |
| CP7 unified batch model state (`8180b01df0e4` / `ecdd6d5a9fff`) | **165,141** | **41,471** | **4,197** | accepted; full model +2.08%, fewer scheduler allocations |
| CP8 canonical Runtime blocks (`8180b01df0e4` / `d7c54eb086d`) | **165,141** | **36,809** | **8,859** | accepted; matched full model +4.65%, no-model +0.56% |
| Required final ceiling | **136,000** | **32,000** | **33,950 still to remove** | pending |

## Objective

Retain the complete independent-model and graph capability, its correctness contracts and its accepted performance,
while returning hand-maintained production Java to at most:

- SDK repository: **136,000 physical `src/main/java` lines**;
- Runtime repository: **32,000 physical `src/main/java` lines**.

Formatting compression, moving Java into resources, generated equivalents, deleted Javadocs, weaker tests and hidden
implementation do not count. The reduction must come from one owner per responsibility, one representation per state,
one execution pipeline and reuse of existing Fluxzero foundations.

## Macro-replacement rule

CP7 is the final small preparatory checkpoint. CP8 is the first accepted macro replacement: it removes the complete
general/initial-packed/update-packed Runtime storage multiplication and 4,809 production Java lines from its direct
parent. Runtime is now 5,229 lines below the S60 start, so the first Runtime structural budget is paid even though the
direct-parent delta rounds to 4.8k rather than 5k.

No later reduction checkpoint is accepted merely because it makes several local classes smaller. A candidate must
instead satisfy at least one of these structural gates:

- remove one complete production pipeline, durable representation or lifecycle owner; or
- remove at least 3,000 production Java lines in one coherent replacement, with an expected range of 3,000-5,000 or
  more.

Correctness and performance are still release contracts, but they are verified at the boundary of a macro replacement.
Diagnostic commits and losing experiments may exist temporarily and are recorded in the run registry; they do not
become named production checkpoints and later work does not build on them.

The remaining campaign therefore has four checkpoints at most after CP8:

| Macro replacement | Owner after replacement | Owners and pipelines that must disappear | Structural target |
| --- | --- | --- | ---: |
| Runtime commit/storage — **CP8 complete** | one `ModelCommitPlan` and one block executor | general rows, initial-packed, update-packed, paired head/locator and paired replay routes | 4,809 direct-parent / 5,229 S60 Runtime lines |
| SDK execution | one compiled payload plan and one batch scope | registry/engine/committer lifecycle overlap, tickets/gates/waves/coordinators and alternate manual/automatic execution | 7,000-8,000 SDK lines |
| Graph/repository | one indexed graph state and one replay cursor | graph wrapper traversals, repository replay variants, graph/batch loaders and overlay-specific state machines | 7,000-8,000 SDK lines |
| Aggregate/Model mechanics | neutral transition, identity, apply and replay mechanics | duplicated aggregate/model reflection, transition, repository and fixture implementations | 6,000-7,000 SDK lines |
| Wire/integration residue | one envelope/codecpad plus thin integrations | remaining handwritten protocol variants, preview schema/codecs and branch-only adapters | 5,000-7,000 combined lines |

Repository ceilings remain the final authority. A macro that falls short of its structural range is redesigned before
the next subsystem is started; missing budget is not paid through another sequence of local cleanups.

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

## Accepted checkpoint CP2 — shared compact binary primitives

Tracking, model commits and model-event reads previously carried three private implementations of byte growth,
primitive encoding, UTF-8, nullability and bounds validation. CP2 replaces them with one allocation-bounded primitive.
The final model-event wire format also rejects its four unreleased preview versions and writes ordinary event payloads
as the same lazy reusable envelope used elsewhere. Compact payload and membership blocks retain their existing
zero-copy views.

| Qualifying route | CP1 control | CP2 candidate | Difference | Correctness |
| --- | ---: | ---: | ---: | --- |
| no-model command -> durable result, ABBA geometric mean | 697,145/s | 776,738/s | **+11.42%** | exact 10,485,760 results per run; zero events |
| command -> `@Apply` -> model commit -> event + result, ABBA geometric mean | 178,732/s | 182,688/s | **+2.21%** | exact 1,048,576 results, model events and global events; 8,192 exact states |

The complete SDK and Runtime reactors passed against the final source. Focused tests additionally cover primitive
growth, exact-size validation, malformed booleans, truncation, Unicode strings, nullable values, arrays, zero-copy
envelope views and rejection of the removed model-event preview versions.

## Accepted checkpoint CP3 — one lazy message and metadata state

`Metadata` previously presented one public value while internally delegating to a map-shaped second state machine with
separate decoded-map, compact-array and opaque-data ownership. `SerializedMessage` separately retained its own UTF-8
length, comparison and primitive access code. CP3 gives `Metadata` one tagged representation owner, materializes only
the requested component and shares the validated cursor and UTF-8 primitives with the envelope. Direct metadata,
target and type lookups remain allocation-free; opaque `Data<byte[]>` identity, chunk flags and wire bytes remain
stable. Public `Metadata` bytecode signatures are unchanged.

The first reduced implementation encoded a map of metadata changes into a second temporary wire value before merging
it. Its full-model ABBA was consistently **-0.84%** (184,402/s control versus 182,862/s candidate), so it was rejected.
The final implementation merges string maps directly into the original byte view, retaining the simpler ownership
without the second wire allocation.

| Qualifying route | CP2 control | CP3 candidate | Difference | Correctness |
| --- | ---: | ---: | ---: | --- |
| no-model command -> durable result, ABBA geometric mean | 606,259/s | 630,398/s | **+3.98%** | exact 10,485,760 results per run; zero events |
| command -> `@Apply` -> model commit -> event + result, ABBA geometric mean | 174,044/s | 174,879/s | **+0.48%** | exact 1,048,576 results, model events and global events; 8,192 exact states |

Spotlight and MediaAnalysis changed host capacity materially during this series. The ABBA endpoints deliberately
bracket that movement: full-model controls were 158,297/s and 191,357/s around candidates of 174,631/s and 175,127/s;
no-model controls were 671,858/s and 547,065/s around candidates of 652,779/s and 608,785/s. An earlier no-model
candidate phase fell from a 707,860/s warmup to 564,099/s measured throughput while the following control recovered to
788,554/s; it is recorded as diagnostic-only and excluded from the final implementation comparison. Both complete
Maven reactors passed after the direct-merge correction.

## Accepted checkpoint CP4 — one forwarding contract for graph views

Four internal `Graph<T>` views independently forwarded nearly the complete public graph contract. CP4 moves that
unchanged delegation, history traversal and operation-result handling into one private `ForwardingGraph<T>`. Identity,
mapped, change and selected views retain only their actual differences: lazy identity resolution, mapped context,
current/previous graph pairing and path selection. The identity-only view deliberately keeps its empty context and
optimized ancestor/descendant behavior; mapped and selected operation results retain their transformed view.

The first ordinary-length ABBA was excluded from the decision because host capacity changed faster than its measured
phases: controls were 139,875/s and 168,230/s while candidates were 159,833/s and 130,588/s. A second ABBA increased
the measured command count to 4,194,304 per run so each sample averaged the same host variation over a longer phase.

| Qualifying route | CP3 control | CP4 candidate | Difference | Correctness |
| --- | ---: | ---: | ---: | --- |
| command -> `@Apply` -> model commit -> event + result, long ABBA geometric mean | 157,285/s | 173,438/s | **+10.27%** | exact 4,194,304 results, model events and global events per run; 8,192 exact states |

The complete SDK reactor passed. Focused graph/repository tests passed, and the standalone Proxy suite passed 83/83;
an earlier Proxy run overlapped another Maven reactor in the same worktree and hit an existing five-second websocket
test timeout, so that contaminated run is not treated as product evidence. CP4 removes 216 production Java lines,
introduces no public API and adds no graph object or allocation.

## Accepted checkpoint CP5 — one final model-stream preview

The persisted model-stream block still carried five unreleased preview readers and an optional embedded-payload form.
No Runtime writer constructed that form: every stored membership block referenced the separately packed global event
payload. The SDK nevertheless retained a second reconstruction API, decoder, page validator and apply loop for it.
CP5 advances the unreleased block to one final version and removes that dormant representation end to end. The active
route remains unchanged: compact membership blocks plus packed event payloads, expanded into the single ordinary replay
cursor.

The first reduction also removed a useful scheduling rule because it happened to be expressed as part of the old
`getCompactModelEvents` API. Four already-large 1,024-stream requests were then coalesced on one batcher thread instead
of leaving in parallel. The cold reconstruction profile exposed the regression immediately: controls sustained
72,517 and 72,024 models/s, while the intermediate candidate sustained only 45,802 and 46,615 models/s. That
intermediate was rejected. The final implementation restores the rule generically: a native request of at least 1,024
streams bypasses fine-grained coalescing, independent of payload representation.

| Qualifying route | CP4 control | CP5 candidate | Difference | Correctness |
| --- | ---: | ---: | ---: | --- |
| command -> `@Apply` -> model commit -> event + result, long ABBA geometric mean | 173,696/s | 172,814/s | -0.51% | exact 4,194,304 results, model events and global events per run; 8,192 exact states |
| sustained disjoint cold reconstruction, 100 measured iterations with JFR | 86,119 models/s | 98,733 models/s | **+14.65%** | 409,600 models and 4,096,000 replayed events; eight active processors |

The short reconstruction series remained host-sensitive after the correction: the first fixed ABBA measured 71,614
models/s for the candidate and 73,146/s for control, while a reverse BAAB contained one 56,215/s candidate outlier
between otherwise 69,990-78,506/s samples. The longer profile amortized that scheduler movement and showed the final
candidate ahead. Its hot stacks no longer contain the removed version-4 decoder; the remaining work is ordinary JDBC
read, envelope decode, model deserialization and replay.

The complete Runtime reactor passed 733 tests. The complete SDK reactor passed all changed and downstream modules; one
full-reactor Proxy attempt hit the existing five-second external-socket timeout under host load, after which the
isolated Proxy suite passed 83/83. CP5 removes 1,162 SDK and 341 Runtime production Java lines. The four pre-measurement
or pathological diagnostics with a missing cache pin or deliberately cache-thrashing L1 shape are excluded from the
registry; every completed comparison is retained there.

## Accepted checkpoint CP6 — stable batch metrics only

The performance campaign had placed a deterministically sampled `RequestStage` JFR event and request-correlation
state throughout generic websocket, tracking, request/result, model-commit and JDBC paths. Those stages were valuable
while decomposing the route, but were branch-internal diagnostics rather than product behavior. They also forced
generic foundations to know command, result and model protocols. CP6 removes that campaign layer while retaining the
stable `FluxzeroJfr.Batch` boundary event, including batch size, bytes, queue depth and phase/resource timings.

The long full-model route was run in control-candidate-candidate-control-candidate-control order because Spotlight
capacity changed during the series. Every run completed exactly 4,194,304 results, stored model events and global
events and verified all 8,192 final model states. The first ABBA favored control by 3.13%; the following reverse pair
favored the candidate by 5.72%. Across all three order-balanced comparisons the mean ratio was -0.18%; geometric means
were 173,257/s for control and 172,799/s for the candidate (**-0.26%**). This is neutral inside the directly observed
host movement, not a hidden throughput claim.

| Qualifying route | CP5 control | CP6 candidate | Difference | Correctness |
| --- | ---: | ---: | ---: | --- |
| command -> `@Apply` -> model commit -> event + result, three long matched comparisons | 173,257/s | 172,799/s | -0.26% | exact 4,194,304 results, model events and global events per run; 8,192 exact states |

Both complete Maven reactors passed, including SDK downstream projects and 733 Runtime tests. CP6 removes 576 SDK and
225 Runtime production Java lines and deletes the protocol-specific hooks from `AbstractWebsocketClient` and the
Runtime websocket foundation. Archived campaign recordings remain readable by the benchmark-side summary tool; new
recordings expose the supported Batch boundaries only.

## Accepted checkpoint CP7 — one message-batch model state

Automatic model handlers previously maintained a private speculative `BatchModelView` alongside the general
`MessageBatchModelView`. The same registry also owned a second commit-wave scheduler consisting of gates, pending
commit wrappers and separate producer lifecycle state. CP7 uses the message-batch resource as the single owner of
staged model values and reduces commit release to dependency-aware tickets. Exact repository-ID lookups scan the
already present batch slots directly; ordinary alias-aware and graph loads retain the generic lazy alias index.

The first collapsed implementation accidentally forced the automatic exact-ID route to materialize that full generic
index. Its initial long runs ranged from 157,179/s to 170,141/s while nearby controls ranged from 183,776/s to
187,055/s. Two scheduler hypotheses were then rejected: chunk-parallel release measured 164,694/s, and removing the
late dynamic dependency pass measured 171,658/s without restoring a stable advantage. Neither intermediate was
checkpointed. A full sampling profile showed that the collapsed ownership itself already removed substantial future
and heap pressure, after which the direct exact-slot lookup removed the unnecessary alias-index work.

| Qualifying route | CP6 control | CP7 candidate | Difference | Correctness |
| --- | ---: | ---: | ---: | --- |
| command -> `@Apply` -> model commit -> event + result, two long matched comparisons | 156,731/s | 159,985/s | **+2.08%** | exact 4,194,304 results, model events and global events per run; 8,192 exact states |

The two final comparisons were 158,667 -> 164,792/s and 154,818 -> 155,319/s. A final sampling recording ran during a
slower host interval and is therefore not used for throughput, but still measured 716 MiB of `CompletableFuture`
allocation versus 1,228 MiB in the CP6 recording (**-42%**) and a 5,193 MiB maximum heap versus 5,507 MiB (**-5.7%**).
Its total allocation estimate is deliberately not used because that recording alone included 888 MiB of one-time JDK
classfile constant-pool allocation.

The complete SDK reactor passed, including proxy, annotation processor and Java/Kotlin downstream projects. One first
reactor attempt observed a pathless cascade child before deletion completion; the exact public regression passed 20/20
isolated repetitions and the immediately repeated complete reactor passed. CP7 removes 316 production Java lines,
adds no public API and leaves Runtime production code unchanged.

## Accepted checkpoint CP8 — one canonical Runtime model block

Runtime `d7c54eb086d` replaces the general row engine, initial-packed engine and update-packed engine with one
`ModelCommitPlan`, one ordered transition type, one version-2 block codec and one atomic JDBC block insert. A block
contains both its transition sequence and durable receipts; local payloads and materialization recovery data live in
that representation, while globally published payloads remain owned by the event log. The old `model_commit`,
`model_update` and `model_payload` tables and their four independent block/update codecs are physically gone.

One asynchronous `model_lookup` GIN locator indexes candidate blocks by privacy-safe model hashes. Reads combine the
located prefix with the not-yet-indexed tail from the same database snapshot, decode the same canonical transitions
and update one bounded lifecycle-local head cache. Current, historical, long-stream, duplicate-result, update-feed and
hard-erasure reads therefore no longer merge general and packed representations. The durable `model_update_feed` is a
view over canonical blocks and completed deletions; graph projection, tracking and retention use its cursor instead of
owning a separately serialized update log.

Hard erasure rewrites the affected canonical blocks, receipts, locator hashes and materialization bytes in place while
retaining unrelated transitions and globally shared events. Temporal relationships, aliases, deletion progress and
erasure fences remain normalized because they have independent query and lifecycle semantics, but the one executor
writes them from the same plan. Graph rebuild scans use the projection's captured state boundary, preventing models
created after registration from racing into both rebuild and update-feed materialization.

| Qualifying route | Direct control | CP8 candidate | Difference | Correctness |
| --- | ---: | ---: | ---: | --- |
| command -> `@Apply` -> model/event commit -> result, 4,194,304 commands | 331,383/s | **346,809/s** | **+4.65%** | exact results, model events, global events and 65,536 states |
| no-model command -> result, 10,485,760 commands | 876,053/s | **880,980/s** | **+0.56%** | exact results; zero model and global events |

The absolute active-host model floor remains 358,973/s and the quiet-host P5 pin remains 425,606/s. Both the direct
control and CP8 candidate ran below those absolute identities in the current host state, so they do not establish a
new pin; the adjacent +4.65% comparison rejects a causal storage regression. The no-model route is neutral and confirms
that completion/tracking boundary changes did not move generic command/result capacity.

Relationship, graph, aging/deletion, hard-erasure, cold-restart and 73,984-event long-stream characterizations all
completed with exact final state and durable counts. The focused store/codec suite passed 128 tests, and the complete
four-module Runtime reactor passed from final source. The separate adversarial review found and fixed a historical
graph-rebuild race: rebuild enumeration now respects the boundary captured at registration rather than reading a
later current head.

CP8 removes 4,809 production Java lines from direct parent `8d5ac5cff0d` (41,618 -> 36,809) and 5,229 Runtime lines
from the S60 start. It adds no supported public API. Deleted Java types were package-private branch-preview internals;
the intentionally incompatible storage format has never been deployed. Detailed performance evidence is recorded in
the model-capacity and feature-characterization logs.

## What caused the growth

The growth is not proportional to added product behavior.

### SDK

Two performance commits account for 14,370 net production lines:

- `c5be57825a26` adds compact model transport, bounded reconstruction and cache-aware loading: **+6,150**;
- `0c8866009654` adds native envelopes, binary protocols, grouped preparation and commit policies: **+8,220**.

The largest concrete expansions since the reduction checkpoint are:

| Responsibility | Reduction shape | Current shape | Net observation |
| --- | ---: | ---: | ---: |
| handler/commit orchestration | action registry + engine + committer: 2,484 | commit registry + engine + committer: 6,562 | **+4,078** |
| graph value/view | `ModelGraph`: 85 | `Graph` + `Graphs`: 2,722 | **+2,637** |
| model repository | `DefaultModelRepository`: 2,341 | 4,428 | **+2,087** |
| native message state | `SerializedMessage` + `Metadata`: 722 | 3,489 | **+2,767** |
| model/tracking wire codecs | none | three handwritten codecs: 2,835 | **+2,835** |

The handler registry still owns a dependency scheduler, prefetcher, commit-policy state machine and transport producer
lifecycle. CP7 made `MessageBatchModelView` the single staged-state owner and collapsed the second gate scheduler, but
the remaining handler and repository pipelines are still much larger than their reduction-checkpoint counterparts.
The repository separately implements ordinary replay, compact replay, direct replay, historical graph boundaries and
graph overlays. These are fast, but their control flow is still multiplied rather than compiled into one path.

### Runtime

`1c3e8a9b2f0b` added the high-throughput independent-model storage route in one step: **+5,759** production lines.
Before CP8, `JdbcModelCommitStore` had grown from the 5,046-line `JdbcModelActionStore` to 10,887 lines. It retained the
prior general commit, stream and replay representation while adding packed initial streams, packed updates, derived
locators, locator recovery, block caches and specialized reads. Four extra block/update codecs added another 1,134
lines. CP8 removes that multiplication; remaining Runtime budget now lies in shared update/recovery lifecycle, generic
JDBC reuse and final preview/integration residue rather than a second model storage engine.

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

#### SDK execution replacement blueprint — active Macro 2

The replacement compiles one `ModelExecutionPlan` when a payload type first becomes reachable from a registered model.
That immutable plan contains the ordered assertion/interceptor/apply invokers, direct and collection result shapes,
payload and metadata ID accessors, direct and ancestor target slots, graph-change delivery, automatic-handling choice,
conflict policy and the merged start/await policy. Registration invalidates the payload-plan cache as one unit; runtime
evaluation performs no second handler, target or dependency discovery.

Every entry point creates the same execution request. `AUTOMATIC`, `ASSERT_ONLY`, `APPLY`, `STORED_EVENT`, explicit
Graph operations and conflict retries differ only in plan options and read boundary. The fixed lifecycle is:

```text
request -> compiled plan -> one pinned load -> evaluate ordered substeps
        -> stage in batch scope -> await exact predecessors -> prepare wire commit
        -> authoritative Runtime result -> repository/cache/projection completion
```

The direct single-model route is a plan strategy that supplies its proven cache entry to the same evaluator and commit
continuation. It does not select another engine, scheduler or completion path. A retry reruns the same request and plan
at the conflict boundary; stored events disable assertions/interception through plan options rather than entering a
separate replayer.

One `ModelBatchScope` is attached to the tracking batch and owns all mutable execution state: ordered operations,
pending model/alias values, exact read dependencies, same-model tails, transport slots and completion futures. The four
public commit policies reduce to the declared start and await moments. Explicit nested operations join this same scope.
Outside a tracking batch, the same scope implementation is used as a one-operation scope without batch arrays. The
scope releases unrelated targets independently and flushes a ready transport tail before a dependent operation waits,
preserving bounded batching without ticket, gate, wave or producer-wrapper lifecycles.

The Runtime request builder becomes a narrow protocol step: it turns evaluated transitions into one `CommitModels`
request and maps the authoritative result back to committed repository revisions. It owns neither evaluation, retry,
ordering, batch completion nor projection waiting.

The accepted checkpoint removes the current execution ownership represented by `ModelCommitEngine`,
`ModelCommitter`, `ModelCommitCoordinator`, `MessageBatchModelView`, the registry's nested commit plans/tickets/gates
and its alternate explicit/automatic methods, plus the separate target, model-parameter, conflict, event-replay and
graph-change execution planners. A small registration facade may retain the supported `ModelCommitHandlerRegistry`
surface used by `DefaultFluxzero`; it delegates every operation to the compiled pipeline and owns no execution state.
`DefaultModelRepository` remains the durable/current/historical load owner until Macro 3 and is changed here only at
the batch-overlay call boundary.

The direct pre-rewrite footprint of those execution classes is just over 10,000 production Java lines. The replacement
budget is at most roughly 3,000 lines, producing the required 7,000-8,000-line structural reduction. This budget may
not be met by compressed formatting, deleted contracts or moving Graph/repository replay into this checkpoint.

Qualification preserves automatic registration precedence, validation-only behavior, heterogeneous collections,
Graph updates, conflict retry/acceptance, initial-create proof, batch-local ancestors and moves, all commit policies,
result publication barriers, exact once-only event publication, projection `ASYNC`/`AWAIT`, cache fencing, TestFixture
synchrony and Java/Kotlin downstream compilation. Focused execution and integration suites run before the complete
reactor; the full command/model/event/result route then receives matched control/candidate throughput and allocation
runs. The no-model route is rerun only if generic handler, tracking, result-completion or wire code changes.

Rollback is the CP8 SDK commit `9bcfc2389b0`: the replacement remains uncommitted until the old owners are physically
gone and every gate qualifies. A candidate that needs an adapter back to an old engine or materially regresses the
matched route is discarded as one unit rather than checkpointed partially.

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

#### Runtime replacement blueprint — implemented by CP8

The pre-CP8 10,779-line store made the representation choice before canonical preparation:

```text
Job
├── initial-create candidate -> InitialCommit/PreparedInitialStream -> packed blocks
├── cached simple update     -> InitialCommit/PreparedInitialStream -> packed blocks
└── general commit           -> PreparedCommit/StoredCommit         -> heads + rows + blocks
```

Reads then merge ordinary heads/stream rows with packed pseudo-stream rows, derived locators and two cache families.
Commit receipts likewise exist as ordinary rows, compact commit blocks and stream-embedded receipts. This multiplication
is the first macro replacement.

CP8 implements this intake and transaction shape:

```text
List<Job>
  -> derive canonical target/conflict/materialization intake
  -> ModelCommitPlan.assign(...)
       -> ordered Commit[]
            Transition[]
            Receipt
  -> ModelBlockBatch.partition(...)
       -> canonical blocks containing transitions + receipts
  -> insertBlocks(connection, blocks)
       lock/validate one state boundary
       resolve duplicates and conflicts
       assign contiguous state and per-model sequence ranges
       insert all canonical blocks and advance one boundary atomically
       apply non-empty optional mutations
       append published events through the same JDBC transaction
  -> publish(plan)
       update one bounded head cache
       advance one asynchronous block locator
       publish update cursors
       materialize direct documents
       complete each real durable result
```

`ModelCommitPlan` is the only prepared representation. Fast common commits are cheap because their optional arrays are
empty and conflict inputs are already proven, not because they enter another pipeline. Rich multi-model commits,
deletions and relationship changes use the same plan with additional mutations.

One final transition entry can express every target case:

- commit/substep identity and assigned state index;
- model identity/type and resulting sequence/head fields;
- stored membership and its payload/global-event reference when present;
- update-state, history-incomplete and logical-deletion state;
- document collection and the information needed by direct materialization;
- enough target identity to serve `GetModelChange`, hard erasure and exact duplicate results.

Receipts and transitions coexist in the same canonical block: receipts are the sole idempotency/result form and
transitions are the sole current and historical stream form. Pending materialization is receipt data plus a sparse
block marker that is cleared after the external search write, not another commit representation. Temporal
relationships, aliases, erasure fences and deletion progress remain normalized tables because they have independent
query/lifecycle semantics, but they are written only by the same executor from the same plan.

The final locator is one derived candidate index over canonical blocks. It stores model lookup hashes and block
locations; readers decode and identity-filter the candidates, then include the not-yet-located tail from the same
snapshot. Ordinary and freshly created models no longer have separate head/cache/loader families. Local publication
updates the same bounded in-memory head cache immediately. There is no fallback to a general row stream.

The durable update feed references or projects the same committed transition/receipt data instead of serializing an
independent model-update representation. Cache tracking, graph projection and recovery keep their different actions,
but read through one cursor/wakeup/retry owner.

The replacement deletes, rather than wraps:

- `initialCreateCandidate`, `packedPublishedCandidate`, `packedPreviousHead` and all mode selection;
- `InitialCommit`, `PreparedInitialStream` and the paired `PreparedCommit`/`StoredCommit` hierarchy in favor of one
  plan/transition hierarchy;
- ordinary `model_head`/per-model stream writes and the paired packed pseudo-stream read merge;
- regular-head versus initial-stream cache generations and loaders;
- stream-embedded versus row/block commit-result lookup;
- separate `ModelStreamBatchCodec`, `ModelStreamBlockCodec`, `ModelCommitBlockCodec` and `ModelUpdateCodec` ownership in
  favor of one final model-block codec with narrow typed views.

The historical pre-performance store at `1c3e8a9b^` is a useful ownership reference: it implemented the complete
then-current lifecycle in 5,453 lines. The rewrite does not restore its slower row representation. It uses that single
lifecycle shape as the skeleton and makes the later measured block representation its only storage form, then carries
forward aliases, before-state graph boundaries, cascade deletion and other later contracts as plan mutations.

CP8 is accepted because the old modes and representations are physically gone, Runtime is 5,229 lines below the S60
start and the current/historical/long-stream/relationship/deletion/restart/full-E2E gates pass. Its 4,809-line direct
delta is recorded exactly rather than rounded up. A temporary bridge that leaves both stores in place remains an
experiment, not a checkpoint.

### 7. One durable update-consumer mechanism

Cache tracking, direct materialization recovery, graph projection and derived locator work retain distinct domain
actions but share one cursor/wakeup/retry lifecycle over the canonical block-derived update feed. No capability owns a private
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

1. Freeze CP7's exact functional matrices, schemas and matched performance pins as immutable controls.
2. **Complete at CP8:** replace the complete Runtime commit/storage subsystem according to the blueprint above, with
   no adapter layer or coexistence state. The old representations are gone; Runtime is 5,229 lines below S60 start and
   the direct CP8 parent delta is 4,809 lines.
3. Replace the complete SDK execution subsystem with one compiled payload plan and one batch scope. The registration,
   manual invocation, retry and automatic paths must all enter it before the alternate lifecycle owners are deleted.
4. Replace Graph and repository loading together: one indexed graph state, one replay cursor and no repository-specific
   traversal/apply variants.
5. Move Aggregate and Model behind neutral transition/identity/apply/replay mechanics and remove the superseded public-
   implementation duplication while retaining Aggregate compatibility contracts.
6. Collapse the remaining wire/preview/integration residue, then audit absolute LOC. Any missing budget is solved in the
   largest surviving duplicate owner, never through formatting, generated hiding or weaker documentation.

Each accepted macro replacement is a separate checkpoint commit. Functional tests may be run continuously while a
replacement is being built, but full correctness and matched performance qualification happen at the replacement
boundary. Losing candidates are reverted and recorded; no later work builds on a regression merely because it removes
many lines.

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
