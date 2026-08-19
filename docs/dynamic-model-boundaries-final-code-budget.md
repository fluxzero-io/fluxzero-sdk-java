# Dynamic model boundaries — CP1–CP14 code-budget ledger

Status: historical checkpoint and performance evidence

> This ledger preserves the decisions, measurements, historical target architecture and rejected candidates through
> CP14. Its former Macro 2–5 completion labels and the plan sections below were based on ownership consolidation and
> are no longer the S60 acceptance source. Do not implement from this ledger. The canonical domain-driven architecture,
> exact 29,113-line debt and single S60 acceptance boundary are defined in
> [`dynamic-model-boundaries-final-architecture.md`](dynamic-model-boundaries-final-architecture.md).

Date: 2026-08-19

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
| CP9 intermediate SDK execution (`6183d8a66a3` / `d7c54eb086d`) | **161,747** | **36,809** | **12,253** | retained intermediate; clean adjacent full model -0.30%, loaded reverse pair +4.69% |
| CP10 intermediate Graph/replay (`34f0038e98b` / `d7c54eb086d`) | **160,076** | **36,809** | **13,924** | retained intermediate; full model +2.41%, graph projection -2.62%, cold replay +56.57% |
| CP11 final compiled SDK pipeline (`bc213d593e9` / `d7c54eb086d`) | **160,217** | **36,809** | **13,783** | accepted; matched full-model geometric mean +1.50%, final adjacent pair +2.69% |
| CP12 final Graph/replay cursor (`71e43a18924` / `d7c54eb086d`) | **160,079** | **36,809** | **13,921** | accepted; cold replay +6.85%, graph command + catch-up +2.04%, profiled full model +0.60% |
| CP13 shared Aggregate/Model mechanics (`3603102f5de` / `d7c54eb086d`) | **160,194** | **36,809** | **13,806** | accepted; full model +1.68%, no-model +10.16%, Aggregate matched geometric mean -1.33% |
| CP14 final wire/update/integration lifecycle (`3603102f5de` / `ddb6bbd8`) | **160,194** | **36,919** | **13,696** | accepted; large full model +4.28%, short matched geometric mean -1.92%, no-model reverse +1.77%, G2 +0.12% |
| Required final ceiling | **136,000** | **32,000** | **29,113 still to remove** | pending |

## Objective

Retain the complete independent-model and graph capability, its correctness contracts and its accepted performance,
while returning hand-maintained production Java to at most:

- SDK repository: **136,000 physical `src/main/java` lines**;
- Runtime repository: **32,000 physical `src/main/java` lines**.

Formatting compression, moving Java into resources, generated equivalents, deleted Javadocs, weaker tests and hidden
implementation do not count. The reduction must come from the domain-driven ownership and deletion of duplicate
lifecycles defined by the canonical architecture, not from preserving CP14's technical pipeline vocabulary.

## Historical macro-replacement rule

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

The macro campaign is tracked by completed ownership boundaries, not by the earlier optimistic checkpoint labels:

| Macro replacement | Owner after replacement | Owners and pipelines that must disappear | Structural target |
| --- | --- | --- | ---: |
| Runtime commit/storage — **CP8 complete** | one `ModelCommitPlan` and one block executor | general rows, initial-packed, update-packed, paired head/locator and paired replay routes | 4,809 direct-parent / 5,229 S60 Runtime lines |
| SDK execution — **CP11 complete** | one executable compiled payload plan, one pipeline and one batch scope | registry/engine/committer lifecycle overlap, tickets/gates/waves/coordinators and alternate manual/automatic execution | 3,397 SDK lines at CP9; CP11 completes ownership without additional LOC credit |
| Graph/repository — **CP12 complete** | one indexed graph state, one node resolver and one replay cursor | graph wrapper traversals, repository replay variants, graph/batch loaders and overlay-specific state machines | 1,668 SDK lines from CP9; CP12 completes ownership with 138 direct-parent lines |
| Aggregate/Model mechanics — **CP13 complete** | neutral metadata, persisted-root, transition, snapshot, revision and replay mechanics | duplicated aggregate/model reflection, root state, transition, replay and fixture apply implementations | ownership completed; 115-line direct-parent increase, no LOC credit |
| Wire/update/integration — **CP14 complete** | one native envelope, final codecs and one update lifecycle | handwritten protocol variants, preview schema/codecs, private update workers and branch-only adapters | ownership completed across CP1-CP8 and CP14; CP14 adds 110 Runtime lines and receives no new LOC credit |

Repository ceilings remain the final authority. A macro that falls short of its estimate must independently remove a
complete pipeline, representation or lifecycle owner, or remove at least 3,000 lines; missing budget is never paid
through another sequence of local cleanups or silently credited.

CP9 paid 3,397 physical lines but was subsequently reclassified as an intermediate checkpoint: execution still lived
inside both the registry and compiler, plans were mainly data, and batch dependency/completion administration remained
exposed as a second operation abstraction. CP11 completes Macro 2 by replacing that ownership rather than by shaving
more rules. Its 141-line increase relative to CP10 receives no reduction credit. The original 7,000-8,000 estimate was
directional; all unpaid budget remains visible in the absolute SDK ceiling.

CP10 paid nearly all of Macro 3's physical reduction but remained intermediate because graph, ancestor, alias,
document-head and cache-refresh reconstruction still had repository-specific owners. CP12 moves those variants behind
the same cursor and binds every graph node to one state resolver. Its 138-line direct-parent reduction closes the
ownership gate, not the estimated LOC budget; Macro 3's total reduction from CP9 is 1,668 lines and the complete
shortfall remains part of the 24,079-line SDK deficit.

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

## Intermediate checkpoint CP9 — initial compiled SDK execution

SDK `6183d8a66a3` began compiling handler order, direct invocation, target access and commit policy once per payload type in
`ModelExecutionPlan`. Automatic handling, explicit assertion/apply, stored-event replay, Graph changes and conflict
retry began producing the same evaluation shape. It also removed the former top-level engine/committer/coordinator
classes and established `ModelCommitProtocol` as the transition-to-wire and authoritative-result boundary.

The intermediate replacement physically deletes `ModelCommitEngine`, `ModelCommitter`, `ModelCommitCoordinator`,
`MessageBatchModelView`, `ModelConflictPolicies`, `ModelEventReplayer` and the graph-change decorator/invocation
lifecycle. A later literal audit nevertheless found that the registry still owned most execution, plans did not invoke
their compiled behavior themselves, and the batch scope exposed a parallel operation/completion abstraction. CP9 is
therefore evidence and rollback history, not the Macro-2 completion boundary.

The first complete candidate lost both normal-case specializations while consolidating the owners. Two canonical runs
then sustained 289,301 and 288,392/s against adjacent controls of 317,896 and 314,686/s (**-8.68%** geometric). Measured
allocation and CPU samples identified generic multi-substep protocol grouping, conflict-set construction and generic
evaluation maps on every one-model update. Restoring those as strategies inside the compiled plan/protocol removed the
regression without restoring any deleted lifecycle owner.

| Qualifying route | CP8 control | CP9 candidate | Difference | Correctness |
| --- | ---: | ---: | ---: | --- |
| clean adjacent command -> `@Apply` -> model/event commit -> result | 334,168/s | 333,183/s | **-0.30%** | exact 4,194,304 results, model events and global events; 65,536 states |
| loaded-host reverse observation of the same route | 304,732/s | 319,037/s | **+4.69%** | same exact counts; Spotlight, MediaAnalysis and VM work active |
| no-model command -> durable result, first order | 782,632/s | 708,625/s | -9.46% | exact 10,485,760 results; zero events |
| no-model command -> durable result, reverse order | 782,632/s | 825,476/s | **+5.47%** | exact 10,485,760 results; zero events |

The bidirectional host movement prevents a new absolute pin, but neither the clean model pair nor the reverse loaded
pair shows a causal regression after specialization. Model transaction batch sizes remained comparable. The no-model
route moved in opposite directions around the same control, confirming that the generic WebSocket completion hook did
not introduce a repeatable route loss.

The focused execution/protocol/conflict suite passed 98 tests. The final complete nine-module reactor passed, including
2,404 SDK tests, Proxy packaging, annotation processing and Java/Kotlin downstream projects. A separate ordinary
StatefulHandler regression found during qualification was fixed by making payload property access lazy: ordinary
command value types no longer open unselected JDK members merely because the automatic Model registry inspected the
payload class.

CP9 removes 3,397 physical production Java lines from its exact CP8 rollback `9bcfc2389b0` (165,144 -> 161,747).
Several deleted types had public Java visibility for cross-package infrastructure, but repository-wide usage found no
documented or downstream consumer; the supported `ModelCommitHandlerRegistry` and public Model/Graph entry points
remain. This is an intentional branch-only source/binary break for unreleased implementation types.

## Accepted checkpoint CP11 — one compiled SDK model pipeline

SDK `bc213d593e9` completes the Macro-2 ownership replacement that CP9 only started. `ModelCommitHandlerRegistry` is a
400-line registration, discovery, plan-cache and dispatch facade. `ModelPipeline` is the single lifecycle owner for
automatic handling, explicit `assertLegal` and `assertAndApply`, Graph updates, collection applies, replay, conflicts,
retries, protocol commit and result completion. `ModelExecutionPlan` is an immutable executable payload plan: it owns
target plans, compiled handler invokers, dependency accessors, apply-result shape, direct/collection strategy and
commit/conflict traits, and it also performs repository replay. `ModelReplayCursor` consequently invokes the plan
instead of a second compiler-owned replay route.

`ModelBatchScope` now owns evaluate -> stage -> exact predecessor wait -> optional re-evaluation -> commit -> completion
as one scope lifecycle. Its private entry is itself the one completion future and dependency record, so the final
candidate does not allocate a producer wrapper plus another completion future. Tickets, gates, waves, public operation
objects and producer wrappers are absent. Direct single-model loading remains a strategy selected by the same request
and plan; explicit batched execution deliberately disables speculative direct prefetch so fixed-id Graph loads and
pending predecessors retain their established read-your-writes semantics.

The qualifying 8-CPU B0 ABBA comparison used the exact CP10 control `ac872d992423` and the same Runtime/database/host
state for every run:

| Run | Full E2E throughput | p50 / p95 / p99 / max latency | Exactness |
| --- | ---: | --- | --- |
| control A1 | 223,175/s | 33.708 / 51.418 / 60.309 / 75.582 ms | 1,048,576 results, model events and global events; 8,192 states |
| candidate B1 | 217,579/s | 34.094 / 56.139 / 85.027 / 118.251 ms | exact |
| candidate B2 | 221,357/s | 34.590 / 51.308 / 62.751 / 77.213 ms | exact |
| control A2 | 209,456/s | 34.550 / 63.126 / 90.501 / 124.592 ms | exact |

The candidate/control geometric means are 219,460/s and 216,207/s respectively (**+1.50%**). After folding completion
into the scope entry, the final adjacent candidate ran at 215,100/s versus control A2's 209,456/s (**+2.69%**) with the
same exact counts and states. Transaction batching remained in the same range. The no-model route was not rerun because
CP11 changes only independent-model planning/scope code, not common wire, tracking or generic result completion.

The final focused execution/scope/protocol suite passes 198 tests. The complete nine-module reactor passes with 2,403
SDK tests, Test Server, Proxy packaging, annotation processing and Java/Kotlin downstream projects. CP11 adds no
supported Model/Graph entry point and preserves the existing persisted/wire contracts; construction changes concern
unreleased implementation types only.

CP11 contains 160,217 physical SDK production-Java lines, 141 more than CP10. That increase is intentionally not called
a reduction and earns no LOC credit. Macro 2 is accepted solely because its named old owners and parallel lifecycles are
now physically absent; the remaining absolute SDK debt is 24,217 lines. Rollback is exact CP10 SDK
`ac872d992423`; the accepted implementation checkpoint is `bc213d593e9`.

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

## Superseded CP8–CP14 target architecture

This section records the architecture used to produce and qualify CP8–CP14. Names such as compiled execution plan,
batch scope, transition engine and replay cursor are historical evidence, not the open implementation plan.

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

`ModelBatchScope` is the only batch-local read-your-writes and dependency owner. A compact keyed scheduler
records producers, values and completion futures. Commit policy is expressed by two lifecycle choices—start at handler
or batch completion, await at handler or batch completion—rather than separate ticket, gate, wave and producer state
machines.

The same scope serves automatic handlers and explicit nested model operations. Exact per-model ordering remains; an
unrelated model does not acquire a dependency. Context propagation and result publication retain their existing
boundaries.

#### SDK execution replacement blueprint — completed by CP11

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

The direct pre-rewrite footprint of those execution classes was just over 10,000 production Java lines. CP9 removed
3,397 lines; CP11 completes the owner replacement but adds 141 lines relative to the intervening CP10 and therefore
receives no extra LOC credit. The superseded owners are physically gone. Every unpaid line remains part of the absolute
SDK deficit rather than being attributed to this checkpoint or manufactured through compressed formatting.

Qualification preserves automatic registration precedence, validation-only behavior, heterogeneous collections,
Graph updates, conflict retry/acceptance, initial-create proof, batch-local ancestors and moves, all commit policies,
result publication barriers, exact once-only event publication, projection `ASYNC`/`AWAIT`, cache fencing, TestFixture
synchrony and Java/Kotlin downstream compilation. Focused execution and integration suites run before the complete
reactor; the full command/model/event/result route then receives matched control/candidate throughput and allocation
runs. The no-model route is rerun only if generic handler, tracking, result-completion or wire code changes.

Rollback for the completed replacement is CP10 SDK commit `ac872d992423`; CP11 is accepted at `bc213d593e9`. No adapter
back to an old engine remains, and the losing generic-only normal route from the CP9 phase was corrected rather than
carried into later macros.

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

#### SDK replacement blueprint — designed from CP9

Macro 3 replaces the Graph and repository replay owners together. Their exact CP9 footprint is 7,685 production Java
lines across `Graphs`, `MaterializedGraphFactory`, `DefaultModelRepository`, `ModelEventBatchLoader`,
`ModelEventRequestBatcher` and `ModelAncestorResolver`. This is not a promise that every line in those files can
disappear: deletion, projection registration, direct-document conversion and accepted-commit cache maintenance remain
repository responsibilities. The acceptance budget is a combined replacement no larger than 4,500 lines in those
owners and their successors, for at least 3,185 net structural lines. A larger reduction is welcome, but the old
7,000-8,000 estimate is not used to justify local compaction or loss of explicit contracts.

The target class design has four owners:

1. `GraphState` owns one immutable placement array and all indexes over it. A placement contains persisted identity,
   concrete type, parent ordinal, relationship path and ordered child ordinals. Identity/type and adjacency indexes
   serve root, parent, ancestor, child, descendant, lookup, selection and cycle checks. A node resolver memoizes only
   the value and durable revision data for that placement. Repository entities, materialized JSON nodes and detached
   identity loads are resolver inputs, not alternate `Graph` implementations.
2. `GraphView<T>` is the only `Graph<T>` implementation. It is a tuple of state, placement and immutable view options.
   Path selection, value filtering, branch filtering, response context, path remapping and explicit previous state
   compose into those options. One per-view cache preserves stable root/parent/child identity without building
   forwarding object graphs. Materialized graph results construct `GraphState` directly from their manifest and use a
   lazy JSON resolver; durable history and updates enter through the same boundary resolver as repository graphs.
3. `ModelReadBoundary` is the one current/state/commit/event boundary representation, including inclusive versus
   before-state projection and the state index pinned by the first response. Graph history, ancestor lookup, handler
   metadata and replay pass this value unchanged; no Graph-, ancestor-, loader- or handler-specific boundary records
   remain.
4. `ModelReplayCursor` owns one bounded request/result pipeline and one reconstruction session. It accepts typed model
   requests, a `ModelReadBoundary`, optional expected heads and one batch overlay. Cache or snapshot revisions are
   starting cursors; compact and ordinary pages become the same validated stream page; every event follows one apply
   loop. Stored cross-model dependencies open a child view on the same session caches instead of starting another
   reconstruction engine. `DefaultModelRepository` selects inputs and converts the result to `Entity`,
   `ModelCommitContext` or `GraphState`; it no longer owns replay state.

The complete flows are therefore:

```text
current model       -> current boundary -> cache/snapshot base -> replay cursor -> Entity
historical model    -> exact boundary   -> snapshot/checkpoint -> replay cursor -> Entity
current graph       -> graph edges/heads pinned once -> same cursor -> GraphState -> GraphView
historical graph    -> exact graph boundary/heads   -> same cursor -> GraphState -> GraphView
message-batch graph -> durable edges + one staged overlay before selection -> same state/view
materialized graph  -> manifest adjacency + lazy JSON resolver -> same state/view
```

Document-based values remain a source option, not a second replay algorithm. A current direct document can satisfy a
read after its head/cache proof; historical reads and dependencies of event-sourced writes use the same stored-event
cursor. Alias resolution changes the canonical request identity once in the cursor result. Snapshots and cache entries
only choose the initial sequence and never bypass boundary or head validation. The message-batch overlay supplies
replacement node values and relationship edges before the immutable state is selected, so no graph is composed,
wrapped, expanded and composed again.

The accepted checkpoint physically removes:

- `Graphs.Context`, `Placement`, `DefaultGraph`, `IdentityGraph`, `ForwardingGraph`, `MappedGraph`, `ChangeGraph` and
  `SelectedGraph`, together with their parallel context and traversal implementations;
- `MaterializedGraphFactory.Context`, `Node` and `MaterializedGraph` as an independent Graph object model;
- `DefaultModelRepository.ReconstructionSession`, `GraphComposer`, `ReconstructedGraph`, `GraphSelection`,
  `ModelEventStateBoundary`, parallel graph reconstruction batches and all boundary-conversion helpers;
- the separate `ModelEventBatchLoader` and `ModelEventRequestBatcher` lifecycle owners once their bounded transport
  behavior is part of `ModelReplayCursor`;
- the ancestor-specific reachability traversal and boundary representation once ancestor selection uses
  `GraphState`'s reverse adjacency index.

The public `Graph<T>` and `ModelRepository` behavior remains the contract: lazy detached values, exact typed IDs and
aliases, deterministic placement order, pathless relations, polymorphic/multiple parents, ambiguous lookup policy,
declared empty child paths, immutable filters, response context, staged updates, current and historical revisions,
commit/event before-state semantics and materialized tombstones. Replay additionally preserves upcasting,
ignore-unknown behavior, direct compiled applies, cross-model read boundaries, same-commit substeps, logical
delete/recreate, snapshots, cache fencing and incomplete-history failures. Batch paging stays bounded by stream count,
membership count and payload bytes, and current concurrent reads may still coalesce without moving historical or local
reads off their calling thread.

Qualification starts with focused Graph, materialized-search, replay, loader, cache and integration tests, then the
complete reactor and downstream builds. Performance characterization covers detached no-relationship access, complete
Graph traversal/serialization, current cached single-model loads, cold snapshot/event reconstruction and a multi-node
historical Graph. The full command/model/event/result E2E route receives the same matched control/candidate gate as
CP9 because repository loading is on its conflict/retry path. Allocation or retained-state growth in the one-state
view is a rejection even if throughput remains flat.

The exact rollback point is CP9 documentation commit `ac8bed3cbc5`. Experiments may temporarily add the new owners, but
Macro 3 is accepted only after the superseded Graph implementations, replay session, loaders and adapters are gone; no
bridge that keeps both object models or apply loops survives the checkpoint.

#### Intermediate checkpoint CP10 — indexed Graph state and replay cursor

CP10 implements the first part of the blueprint as a class redesign rather than a sequence of local reductions.
`GraphState` is now the
only structural graph representation and `GraphView<T>` the only `Graph<T>` implementation. Repository, detached,
message-batch and materialized graphs construct or adapt that state; navigation, selection, filtering, mapped values,
response context and history are view options over its indexes. `ModelReadBoundary` is the one current/state/commit/
event boundary. `ModelReplayCursor` now owns both bounded event transport and reconstruction, while
`DefaultModelRepository` chooses document/event inputs and returns entities, commit contexts or indexed graphs.

The following production owners and representations are physically absent from the accepted source:

- `Graphs.Context`, `Placement`, `DefaultGraph`, `IdentityGraph`, `ForwardingGraph`, `MappedGraph`, `ChangeGraph` and
  `SelectedGraph`;
- `MaterializedGraphFactory.Context`, `Node` and `MaterializedGraph` as an independent object model;
- `DefaultModelRepository.ReconstructionSession`, `GraphComposer`, `ReconstructedGraph`, `GraphSelection`, parallel
  graph reconstruction batches and `ModelEventStateBoundary`;
- standalone `ModelEventBatchLoader` and `ModelEventRequestBatcher` lifecycles;
- the ancestor-specific boundary and reachability implementation.

Their tests are correspondingly owned by `ModelReplayCursorTest` and `ModelReplayReadBatcherTest`; the deleted owner
names do not remain as a second conceptual test boundary. There is no compatibility adapter back to the old graph or
replay object model.

The CP9 six-owner footprint was 7,685 production lines. Their CP10 owners and direct successors contain 6,120 lines:

| Owner | CP10 lines |
| --- | ---: |
| `Graphs` (`GraphState` and `GraphView`) | 1,601 |
| `MaterializedGraphFactory` | 218 |
| `DefaultModelRepository` | 2,227 |
| `ModelReplayCursor` | 1,808 |
| `ModelReadBoundary` | 211 |
| `ModelAncestorResolver` | 55 |
| **Total** | **6,120** |

The direct successor footprint is therefore 1,565 lines smaller and the complete SDK production tree is 1,671 lines
smaller than CP9, at 160,076. This misses the planned 4,500-line successor budget and does not receive 3,000-line
credit. A later literal audit reclassified CP10 as intermediate: multiple Graph implementations and two batching
lifecycles disappeared, but repository-specific graph loaders and apply loops plus alias-, snapshot- and overlay-replay
mechanisms do not yet all run through one cursor and lifecycle. The measured gains remain valid evidence; they do not
close Macro 3. The missing estimate remains visible in the absolute SDK deficit.

The final matched performance evidence uses Java 25, Runtime `d7c54eb086d`, PostgreSQL on port 64217, an 8 GiB heap
and eight active processors. Every bracket ran candidate-control-candidate with exact data and completion counts:

| Route | CP9 control | CP10 candidate geometric mean | Difference | Exactness |
| --- | ---: | ---: | ---: | --- |
| full command/model/event/result E2E | 306,583/s | 313,957/s | **+2.41%** | 4,194,304 results, model events and global events; 65,536 states |
| current cached model load | 1,289,775 models/s | 1,833,082 models/s | **+42.12%** | 65,536 models with 21 events each |
| cold event reconstruction | 14,681 models/s | 22,986 models/s | **+56.57%** | 20,480 models / 430,080 events |
| representative graph projection, inclusive command plus catch-up | 5,430/s | 5,288/s | **-2.62%** | 4,096 upserts and exact direct/root documents |

The graph run's foreground geometric mean is 5,658/s versus 5,755/s (**-1.68%**). Candidate observed-heap maximum is
2,931.1 MiB versus 2,889.8 MiB (**+1.43%**), with no material retained-state growth. Complete graph traversal,
serialization and projection are exercised by that run. Historical multi-node graph reconstruction is covered by the
focused graph boundary tests; its two material costs are isolated by the graph traversal run and the cold cursor run
rather than assigned an unstable one-shot latency claim.

The matched log digests are retained here so accidental file substitution remains detectable:

| Series | Candidate A | Control | Candidate B |
| --- | --- | --- | --- |
| full E2E | `98586488e94fea62937414ff1afc19fedb691e6bd6d31bd505b5733d0b795258` | `20ff4d9b47438d39d6bbbb8e2f6ac857c04abc71c35247e62d79b559d9d9643f` | `9939f9bda35f2964bf5bf04d2bc96ee4a21f6fa5a62f65672412b4ae8244be69` |
| replay/load | `cf1589c8b75e1e41d330e472c6bfc60db32d14178e5bc6d4bf92b2cd11960c56` | `c9e182a7c8f0e074856d3940db0cd38c8918302e5813c0b8e3e957530ef71149` | `9c1e4f40c7796768681031341674b23e8857dd535a169dff955ae45ed8def430` |
| graph projection | `650c3ed029cb22a89af9d0fa2d8a95b7590c2c22f9d891f3b23935abc96a14b7` | `94177b51524c8b65c8706018cf5569a7b250cad2abd1955a9fd351bcc049bc91` | `861938662e242a716c75cba6cbebb377c60e570f74450f6abb6c6bfb5a34ae55` |

Focused graph, replay, cache, commit and integration qualification ran 176 tests; the renamed cursor boundary was
rerun as an 11-test focused confirmation. The complete nine-module reactor, annotation-processor checks and
Java/Kotlin downstream projects pass from the final production source. Adversarial review caught one metadata-only
head path that incorrectly
required replayable event history; the accepted cursor separates head proof from replayable-head validation, while
event-sourced writes that depend on documents keep the strict incomplete-history failure. Source changes to
branch-internal `ModelAncestorResolver` and repository cache helpers are intentional on this undeployed feature branch.

One heavily loaded parallel reactor attempt produced a five-second cache-test timeout and a concurrent TestFixture
fixed-ID failure. Both exact cases passed together without a source change, after which the complete reactor passed.
They are treated as load-sensitive test isolation rather than accepted product failures. The aggregate site/Javadoc
command still fails at Lombok's generated `ImmutableModelRootBuilder`; exact CP9 and CP10 runs fail identically before
the new boundary documentation. This baseline site-tooling debt remains open for final S60, while the full reactor's
documentation-link check passes.

The behavioral implementation is `f2c6a6ed92b`, its cursor-test ownership rename is `ade0255e517`, and its documented
intermediate boundary is `34f0038e98b`. Macro 3 resumes from CP11 after the SDK-pipeline replacement.

#### Accepted checkpoint CP12 — one Graph state and one repository replay cursor

CP12 closes the ownership gap that kept CP10 intermediate. `DefaultModelRepository` no longer implements current,
historical, direct, batch, ancestor and graph reconstruction variants. It is the public facade, document converter,
deletion/projection registrar and accepted-commit cache owner; all stored-event, head, ancestor and graph reads enter
`ModelReplayCursor`. The cursor now selects cache/snapshot/document bases, resolves aliases and ancestors, pins the
boundary, applies the single replay loop and projects the result as an entity, commit context or graph input.

`GraphState` remains the only structural graph representation and `GraphView<T>` the only `Graph<T>` implementation.
Every node in one state is bound to the same `NodeResolver`; loaded entities, detached identities, materialized JSON
and external expansions are resolver inputs rather than supplier object graphs. Durable edges and batch-local values
are composed before selection into one final state. The old durable-graph-then-overlay-then-rewrap lifecycle is gone,
as are repository graph reconstructors, document-head replay, first-event type reads, current-context projection,
cache refresh replay and ancestor traversal as separate owners.

The six direct owners and successors now contain 5,982 production lines:

| Owner | CP12 lines |
| --- | ---: |
| `Graphs` (`GraphState`, `NodeResolver` and `GraphView`) | 1,674 |
| `MaterializedGraphFactory` | 218 |
| `DefaultModelRepository` | 1,329 |
| `ModelReplayCursor` | 2,495 |
| `ModelReadBoundary` | 211 |
| `ModelAncestorResolver` | 55 |
| **Total** | **5,982** |

This is 138 lines below CP11 and 1,703 direct-owner lines below the 7,685-line CP9 footprint. The full SDK tree is
160,079 lines, 1,668 below CP9. The redesign therefore misses the directional 7,000-8,000 estimate and the earlier
4,500-line successor budget, but it satisfies the macro gate by physically removing the competing lifecycles rather
than compacting their rules. No additional LOC credit is invented; the remaining 24,079 SDK lines above the release
ceiling stay visible.

The first oversized cold candidate exposed a real session-concurrency defect: independent stream replay updated a
plain checkpoint `HashMap` from the parallel direct-replay path and failed with `ConcurrentModificationException`.
That candidate was rejected. The accepted cursor uses concurrent model-key ownership plus a synchronized bounded
per-model checkpoint index, and a deterministic 256-stream integration test now preserves that contract. A second
oversized diagnostic was stopped after it proved the crash was gone because its default cold phase represented more
than 168 million replayed events and was not the matched qualification shape.

The matched CP11-control/CP12-candidate qualification used Runtime `d7c54eb086d`, Java 25, eight active processors and
an 8-GiB fixed heap. It verified exact result, model-event, global-event, state, relationship, search and graph counts.
The qualifying pairs were:

| Route | CP11 control | CP12 candidate | Difference |
| --- | ---: | ---: | ---: |
| profiled full command/model/event/result E2E | 220,697/s | 222,027/s | **+0.60%** |
| current cached model load | 1,371,285 models/s | 1,396,742 models/s | **+1.86%** |
| sustained cold reconstruction | 22,407 models/s | 23,943 models/s | **+6.85%** |
| graph command plus exact projection catch-up | 5,627/s | 5,742/s | **+2.04%** |
| graph foreground command completion | 5,985/s | 6,239/s | **+4.24%** |

An earlier non-profiled B0 ABBA measured a 3.63% candidate deficit while host capacity was moving; the immediately
adjacent profile pair reversed it to a 0.60% gain, and the load qualification's full E2E phase also favored CP12 by
2.79%. It is retained as diagnostic evidence rather than hidden. Graph composition time was neutral (319.449 versus
318.656 ms). The candidate's one-shot observed heap maximum was 2,309.2 MiB versus 2,221.7 MiB control, still below
the CP10 qualification maxima and with lower pending-signal/root pressure; no unbounded state or ownership growth was
observed.

Focused graph/replay/commit/search/TestFixture qualification passed 346 tests. The final concurrency regression test
passed separately and both complete nine-module reactors eventually passed with Java/Kotlin downstream compatibility.
One intervening full run hit a load-sensitive existing document-cascade assertion; the exact test passed immediately
in isolation and the next unchanged full reactor was green. The accepted implementation is `71e43a18924`; exact
performance logs and rejected-candidate history are retained in the SDK model capacity log.

#### Accepted checkpoint CP13 — shared Aggregate/Model mechanics

CP13 completes the shared Aggregate/Model mechanics boundary. The neutral owners are `EntityMetadata`,
`PersistedRoot` and `ImmutableRoot`; Aggregate and Model remain distinct annotations, programming facades and persisted
protocols. `EntityMetadata` is the one `ReflectionUtils.TypeMetadata`-owned plan for identity, aliases, parents,
handlers, root configuration, transition policy and snapshot policy. `PersistedRoot` owns common revision identity,
and `ImmutableRoot` owns immutable revision state, replay, transition construction and bounded previous-revision
traversal.

The Aggregate and Model repositories retain their protocol-specific storage facades, but both now consume the neutral
root configuration, transition settings, snapshot settings and revision operations. Their current and historical
reconstruction share the same root replay primitive. `TestFixture` likewise has one protocol-boundary event apply loop
instead of a second model-only apply lifecycle. Stateful handlers deliberately remain neither Aggregate nor Model;
they use only the neutral identity metadata when they need it.

The checkpoint increases the SDK tree by 115 lines because the source-visible neutral contracts and binary-compatible
Lombok builder shims cost more than the deleted duplicate mechanics. It receives no reduction credit. The directional
6,000-7,000 estimate is missed in full and the resulting 24,194-line SDK deficit remains visible for the final macro
and absolute-ceiling audit.

The matched qualification used CP12 `71e43a18924` as control, CP13 source `3603102f5de` as candidate and Runtime
`d7c54eb086d` throughout. The ordinary model E2E route verified 262,144 results, model events and global events plus
4,096 final states. The no-model route verified 1,048,576 results and zero model/global events. The Aggregate route
used blocking command dispatch with asynchronous event consumption so verification remained behind the aggregate
commit boundary; every run stored exactly 4,096 events and replayed 1,746 root and leaf events.

| Route | CP12 control | CP13 candidate | Difference |
| --- | ---: | ---: | ---: |
| command -> Model apply -> atomic model/event commit -> durable result | 108,165/s | 109,980/s | **+1.68%** |
| command -> explicit void handler -> durable result | 455,965/s | 502,274/s | **+10.16%** |
| Aggregate command/event/replay, order-balanced geometric mean | 881.8/s | 870.1/s | **-1.33%** |

Aggregate allocation geometric means are effectively neutral (about +0.2% for CP13), and latency ranges overlap.
An initially attempted asynchronous-dispatch shape let its verifier run before the aggregate batch commit on both
control and candidate. Those symmetric failures are retained as rejected benchmark-shape evidence and are not treated
as product failures. The complete SDK reactor, Test Server, Proxy, annotation processor and Java/Kotlin downstream
projects passed from final source; site/Javadocs also passed. One model-cascade assertion and one proxy websocket close
timed out in separate intervening full runs, passed immediately in isolation, and the next unchanged complete reactor
was green.

#### Accepted checkpoint CP14 — final wire, update and integration lifecycle

CP14 completes Macro 5. CP1-CP8 had already removed the unreleased binary generations, handwritten websocket codec
variants, preview readers, parallel Runtime block formats and duplicate update feeds. The retained wire surface is
JSON, CBOR and one negotiated `BINARY` transport over the native `SerializedMessage` envelope, with one shared
primitive binary reader/writer, thin model/tracking dispatchers and one Runtime `ModelBlockCodec`. The canonical
`model_update_feed` remains the only durable model update source; SDK cache tracking consumes that public cursor and
does not own a second storage replay path.

Runtime `ddb6bbd8` closes the remaining lifecycle split. `ModelUpdateLifecycle` now owns the live cursor generation,
long-poll wake-up, waiter bound, coalesced scheduling, independent retry/backoff and controlled shutdown for the model
block locator, direct-document materialization recovery and graph projection drain. Each consumer retains its own
domain action and durable cursor, so locator batching, direct result completion and graph `ASYNC`/`AWAIT` remain
distinct policies rather than new parallel engines. The private locator, materialization-recovery and graph worker
loops and their separate running flags, delayed executors, retry state and shutdown joins are gone.

The first scheduling candidate was rejected after a reverse run exposed overlapping locator actions and a duplicate
`model_lookup` key. Strict per-action serialization fixed that race. A later candidate was also rejected because it
started locator work before the final commit boundary and reduced B0 throughput; restoring the published-boundary
gate, the original complete locator drain and three bounded platform lanes removed that regression. Deletion and
materialization-only boundaries wake trackers without incorrectly replaying unrelated projections.

The final large absolute pair used 65,536 models and 4,194,304 measured commands. The active host was below its
historical absolute band for both sources, but the candidate completed at 319,613/s versus 306,503/s control
(**+4.28%**) with exactly 4,194,304 results, model events and global events plus 65,536 final states. A short
order-balanced bracket produced candidate/control geometric means of 127,692.7/s and 130,198.5/s (**-1.92%**). The
final reverse no-model pair was 526,315/s versus 517,164/s (**+1.77%**), and G2 was 3,264/s versus 3,260/s
(**+0.12%**) with exact relationships, documents, roots, children and awaited boundary.

Relationships, aging/deletion, cold restart and long-stream reconstruction were qualified separately against exact
CP13 Runtime control `d7c54eb086d`. R1 was +1.27%; Q1 completed 98,304 exact updates in 3.234 s versus 3.538 s;
L1 reconstructed 4,096 exact states after Runtime and SDK restart and improved the first update phase by 10.1%; and
the 73,984-event long-stream replay was 282,343 versus 282,866 events/s (-0.18%). The final Runtime reactor passed all
four modules and 736 Runtime tests. The unchanged CP13 SDK source had already passed its complete nine-module reactor,
Java/Kotlin downstream compatibility and site/Javadocs.

CP14 adds 110 Runtime production lines because the explicit shared lifecycle and its concurrency contract are larger
than the net private-worker deletion. It receives no new reduction credit. SDK remains at 160,194 lines and Runtime is
36,919, leaving **29,113** lines above the hard combined ceilings. CP14 called Macros 2-5 structurally complete under
its now-superseded owner model. The canonical architecture reopens those outcomes because the technical helper domain
it retained is itself the release blocker.

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

### 7. One durable update-consumer mechanism — CP14 complete

Cache tracking, direct materialization recovery, graph projection and derived locator work retain distinct domain
actions but share one cursor/wakeup/retry lifecycle over the canonical block-derived update feed. No capability owns a private
polling, retention or completion platform. Direct materialization remains awaited, graph projection retains
`ASYNC`/`AWAIT`, and restart recovery remains autonomous.

### 8. Aggregate compatibility through shared mechanics

`@Aggregate`, its public types and its persisted/event wire contracts remain supported. Shared entity application,
reflection plans, snapshots, revision traversal, handler invocation and cache mechanics move behind aggregate-neutral
primitives used by both Aggregate and Model. Aggregate becomes a compatibility/configuration shape over those
mechanics rather than justification for keeping a second SDK replay and apply engine.

## Historical removal budget

These are planning budgets, not credit for moving code. Macro 2's explicit shortfall remains in the repository total;
later work starts from new class/state designs rather than attempting to shave that gap from execution rules.

| SDK workstream | Required structural removal |
| --- | ---: |
| final binary envelope, metadata and wire protocol | 6,000–7,000 |
| single commit plan, handler pipeline and batch scope | 7,000–8,000 planned; **3,397 delivered at CP9, ownership completed at CP11 without further LOC credit** |
| one graph state and one repository/replay cursor | 7,000–8,000 planned; **1,668 delivered from CP9, ownership completed at CP12** |
| shared Aggregate/Model and in-memory transition mechanics | 6,000–7,000 planned; **ownership completed at CP13 with a 115-line increase and no LOC credit** |
| preview migration, campaign diagnostics and thin integration cleanup | 3,500–4,500 planned; **wire ownership completed across CP1-CP8 and update lifecycle at CP14 with a 110-line Runtime increase and no new LOC credit** |
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

## Superseded CP8–CP14 execution order

1. Freeze CP7's exact functional matrices, schemas and matched performance pins as immutable controls.
2. **Complete at CP8:** replace the complete Runtime commit/storage subsystem according to the blueprint above, with
   no adapter layer or coexistence state. The old representations are gone; Runtime is 5,229 lines below S60 start and
   the direct CP8 parent delta is 4,809 lines.
3. **Complete at CP11:** replace the SDK execution subsystem with one executable compiled payload plan, one pipeline,
   one batch scope and one protocol boundary. Registration, manual invocation, retry and automatic paths now share it;
   alternate lifecycle owners are deleted. CP9 supplied the 3,397-line reduction and CP11 completes ownership without
   additional LOC credit.
4. **Complete at CP12:** replace Graph and repository loading together with one indexed graph state, one shared node
   resolver, one replay cursor and no repository-specific traversal/apply variants. CP10 supplied most physical
   reduction; CP12 removes the remaining ownership split without claiming the unmet estimate.
5. **Complete at CP13:** move Aggregate and Model behind neutral metadata, persisted-root, transition, snapshot,
   revision and replay mechanics while retaining their distinct public and persisted contracts. The ownership target
   is complete; its missed LOC estimate remains in the absolute deficit.
6. **Complete at CP14:** retain JSON, CBOR and one negotiated binary envelope, remove preview/codecgeneration residue,
   and put tracker wake-up, locator replay, direct-materialization recovery and graph projection behind one bounded
   update lifecycle. CP14 receives no new LOC credit.
7. **Superseded next step:** audit the absolute ceilings and remove the largest surviving duplicate owners. The exact
   remaining debt is retained, but the canonical architecture now supplies the domain-driven deletion order.

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
