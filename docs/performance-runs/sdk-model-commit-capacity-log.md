# SDK-inclusive model-commit capacity log

## Live scoreboard

| Route | Current exact pin | Runtime store path | Store service capacity | Role in campaign |
| --- | ---: | --- | ---: | --- |
| Full command -> model -> event + result E2E | P5 matched no-JFR **425,606/s** versus 420,193/s control (**+1.29%**); **426,108/s** best run; corrected current E833 **358,973/s** after a clean Docker restart | `commit-packed-update` | **0.539M/s** in the matched P5 profile; **0.519M/s** in fresh E636 | Accepted S60 release pin remains 425,606/s; E833 is the current active-host floor |
| Synthetic tracked SDK apply -> model + event durability | Best **834,806/s** without JFR / **846,441/s** with batch JFR; fresh E694/E696 **812,491 / 816,815/s** | `commit-packed-update` | Best **0.995M/s** in E589; fresh **0.955 / 0.967M/s** | SDK-inclusive model upper-bound diagnostic without command/result logs |
| Low-level SDK `CommitModels` update round trip | **595,877/s** without JFR | `commit-packed-update` | **0.781M/s** in E488 | Runtime/wire upper-bound diagnostic |
| Direct SDK `assertAndApply(command)` without tracked context | 80,074/s without JFR | `commit-general` | **0.108M/s** in E491 | Separate direct-API/idempotency diagnostic; not a proxy for tracked E2E |
| Tracked command context -> public `Fluxzero.assertAndApply(command)` | **283,002/s** without JFR; 278,239/s with batch JFR | `commit-packed-update` | **0.315M/s** in E698 | Public blocking-API diagnostic; preserves source-index correctness but fragments SDK transport/store batches |

Production is Runtime `0c23c91f` (`perf(modeling): reduce stream locator commit pressure`) on top of P4
`c98f47e6`. The full model campaign remains
scoped to model work. Command and ordinary result paths remain present only in the canonical E2E acceptance route and
are not optimization targets.

Campaign decision 2026-08-16: the exact approximately 400k/s full command -> model -> event + result level is accepted
for the current `@Model` release phase. The historical 500k and 1M targets remain useful future stretch goals, but are
no longer merge gates for S60.

This is the current technical entry point for the model-capacity campaign. Earlier campaign runs and the historical
acceptance protocol remain in
[`../model-e2e-throughput-campaign.md`](../model-e2e-throughput-campaign.md); practical feature costs and their exact
correctness status are maintained in
[`sdk-model-feature-characterization-log.md`](sdk-model-feature-characterization-log.md). The corresponding CSV files
remain the machine-readable run registers. Application-specific downstream evidence belongs to its owning repository
and is intentionally not duplicated here.

## Route definitions

### Low-level SDK model-update round trip

This route retains:

1. SDK `EventStoreClient.commitModels`;
2. model wire encoding, CBOR, LZ4 and two SDK event-sourcing sessions;
3. the Runtime WebSocket endpoint;
4. the real `JdbcModelCommitStore`, global event log and PostgreSQL commit;
5. the durable model-stream locator gate;
6. `CommitModelsResult` encoding, WebSocket return and SDK result completion.

It deliberately starts from already formed `CommitModels` requests. It therefore excludes SDK model loading and
caching, assertions, interceptors, `@Apply`, transition planning and model-event serialization. It also excludes the
command log, command tracking and ordinary result log.

### Direct SDK model apply without a tracked command context

This route calls the public instance extension point behind `Fluxzero.assertAndApply(command)`. It retains SDK model
loading/cache lookup, assertions, interceptors, `@Apply`, transition planning, event serialization, SDK commit
batching, the WebSocket/Runtime boundary and durable model plus global-event storage. Sixteen SDK worker threads keep
at most one update per model in flight, with 65,536 conflict-free model slots. It excludes command publication,
command tracking and the ordinary result log.

This direct API does **not** have a tracked source message index. `ModelCommitter` must therefore leave
`possibleDuplicate` unknown so a transport retry can still be recognized through durable commit receipts. Runtime
correctly routes these commits through the general idempotent path. The route is valuable, but it is not the packed
automatic-command route minus two cheap stages.

### Tracked public `Fluxzero.assertAndApply` call

This route starts from the same serialized, monotonically indexed command envelopes as the synthetic tracked route.
It activates each deserializing command as the current SDK message and then invokes the public blocking
`Fluxzero.assertAndApply(command)` method. The outer command context supplies genuine idempotency evidence, so Runtime
may safely use `commit-packed-update`; no index is forged inside the model commit.

The public call deliberately bypasses ordinary command handlers and their decorators, and waits for real model/event
durability before returning. It therefore measures an explicit handler delegating synchronously to
`assertAndApply`, not the default automatic asynchronous model-handler path. Command append/tracking and ordinary
result storage remain excluded.

### Synthetic tracked SDK model apply

This diagnostic begins with serialized command envelopes whose source indexes are valid, monotonic Fluxzero command
indexes. It then performs normal lazy command deserialization, SDK handler resolution, assertions, interceptors,
automatic `@Apply`, event serialization, SDK model-commit batching, WebSocket transport and durable Runtime model plus
global-event storage. Sixteen virtual workers use the same conflict-free model set as canonical E2E.

It excludes command append/tracking and ordinary result publication/completion. Unlike the public direct-call route,
the synthetic source index gives the automatic handler the same tracked idempotency evidence as a real consumed
command, so Runtime can correctly use `commit-packed-update`. It is diagnostic-only: the complete command/model/result
route remains the acceptance gate.

## E483-E488: qualify the low-level SDK boundary

E483 tried the older direct `JdbcModelCommitStoreBenchmark`. It failed at commit 65,536 because a second cycle reused a
stale read-state boundary. That benchmark is rejected as the campaign's main isolated route: it neither includes the
SDK transport boundary nor models repeated updates correctly.

E484 and E486 used `WebSocketModelCommitBenchmark`, but profiling exposed a benchmark-semantic error:
`expectedSequenceNumber` was always `-1`. The Runtime therefore measured `commit-initial`, not model updates. These
runs remain useful initial-create diagnostics but are not comparable to the full update route. E485 never started the
Runtime or touched the database because the destructive-benchmark opt-in was omitted.

E487/E488 maintain the exact expected sequence for every repeated model. Both verify exactly 1,048,576 durable
results, model memberships and globally published events.

| Run | Semantics | Profiling | Round-trip throughput | Store path | Store batches / mean size | Store mean | Store service capacity |
| --- | --- | --- | ---: | --- | ---: | ---: | ---: |
| E484 | initial create, misqualified as update | none | 566,957/s | `commit-initial` | not observed | not observed | not observed |
| E486 | initial create | profile JFR | 537,064/s | `commit-initial` | 257 / 4,080 | 5.661 ms | 0.721M/s |
| E487 | exact repeated update | none | **595,877/s** | expected packed update | not observed | not observed | not observed |
| E488 | exact repeated update | profile JFR | 580,456/s | `commit-packed-update` | 256 / 4,096 | 5.243 ms | **0.781M/s** |

E488's measured model batches have p50/p95/p99/max sizes of 3,904 / 6,934 / 7,399 / 8,071. Its caller window is
8,192, so the route cannot create the 20k-30k transactions seen in canonical E2E.

## Why 0.781M/s does not contradict E459's 0.407M/s

Both profiles use the same packed Runtime update implementation, but feed it materially different work:

| Measurement | E459 full E2E | E488 low-level SDK update |
| --- | ---: | ---: |
| Store throughput during profiled run | 0.332M/s | 0.542M/s |
| Active ordered-store capacity | **0.407M/s** | **0.781M/s** |
| Transactions | 152 | 256 |
| Mean batch | 6,899 | 4,096 |
| p95 / max batch | **21,323 / 30,176** | **6,934 / 8,071** |
| Mean transaction storage | **16.937 ms** | **5.243 ms** |
| Model-store observed bytes | 1,334 MiB | 1,138 MiB |
| Global-event observed bytes | 362 MiB | 178 MiB |
| Stream-block insert mean | 3.744 ms | 1.091 ms |
| Co-located event work mean | 6.749 ms | 1.995 ms |
| State lock/read mean | 1.773 ms | 0.501 ms |
| State advance mean | 1.097 ms | 0.380 ms |

The low-level request contains a compact 32-byte event payload and stops at 8,192 open calls per wave. Canonical E2E
serializes the real `UpdateModel` event and the SDK commit feedback occasionally forms transactions above 30,000
updates. Larger batches reduce commit frequency, but these profiles show strongly nonlinear transaction cost at that
shape. This is a causal candidate to validate on the full E2E route; the isolated 0.781M/s number is not itself an
acceptance result.

## E489-E492: direct `assertAndApply` is a different durability contract

| Run | Type | Profiling | Updates | Throughput | Main observation |
| --- | --- | --- | ---: | ---: | --- |
| E489 | smoke | none | 131,072 | 151,619/s | Exact durable direct-call route works |
| E490 | qualification | none | 1,048,576 | 80,074/s | Long route is far below tracked E2E |
| E491 | profile | batch JFR | 1,048,576 | 106,820/s | 33 general transactions, mean 31,775 and 293.428 ms; store capacity 0.108M/s |
| E492 | causal diagnostic | none | 131,072 | 141,720/s | Every fast-path rejection is `possible-duplicate` |

E492 enabled the Runtime's existing packed-outcome counter. Across create, warm-up and measured phases all 262,144
candidates reported `possible-duplicate`; no shape, policy, read-set, cache-head or sequence rejection occurred.
Forcing `possibleDuplicate=false` would change retry/idempotency correctness and is forbidden. Improving the direct API
would require a separately designed idempotency mechanism and is not selected ahead of the canonical 500k target.

## E493-E507: transaction-count cap rejected after clean BAAB

E459 showed that canonical E2E occasionally formed packed model transactions above 30,000 jobs and that their
transaction cost grew nonlinearly. E493-E503 therefore screened the Runtime's existing, no-delay
`fluxzero.maxModelCommitBatchSize` override. This cap only limits how many already available jobs one backlog drain
takes; it introduces no timer or deliberate wait. The independent 512-MiB byte cap remained unchanged.

The first, chronologically separated comparisons looked promising:

| Setting | Non-JFR runs | Geometric mean | Apparent effect versus nearby 65k controls |
| --- | --- | ---: | ---: |
| 65,536 control | E497 344,128; E503 342,911/s | 343,519/s | control |
| 16,384 | E500 357,110; E502 351,765/s | 354,427/s | +3.18% |
| 8,192 | E495 356,989; E496 356,949/s | 356,969/s | +3.73% versus E494/E497 |
| 4,096 | E499 330,368/s | n/a | clearly worse |

The profiles confirmed that the cap worked mechanically. E501 at 16,384 had no transaction above 16,384 jobs,
mean 6,722 jobs, 16.872 ms mean active storage, 0.398M/s active store capacity and a 32.860 ms p95 queue wait.
E498 at 8,192 had mean 6,394 jobs, 17.935 ms active storage, 0.357M/s active store capacity and a 66.685 ms p95
queue wait. Neither profile reported durability or correctness failures. These measurements support the transaction
shape hypothesis, but do not by themselves prove E2E gain.

Before checkpointing, the proposed 16,384 default was rebuilt from Runtime `7ac06794` in a clean worktree with no
observer or benchmark-source changes. `JdbcModelCommitStoreTest` passed 99/99 and `./mvnw -B install` passed, after
which the exact full route was run as a tightly adjacent BAAB comparison on one binary:

| Setting | Clean runs | Geometric mean | Matched effect |
| --- | --- | ---: | ---: |
| 16,384 candidate | E504 343,323; E507 354,537/s | **348,885/s** | **-0.12%** |
| 65,536 property control | E505 355,395; E506 343,304/s | **349,297/s** | control |

This clean BAAB result explains the earlier apparent improvement as time/host correlation rather than a causal
production gain. The default change was reverted and is not a checkpoint. The useful retained result is methodological:
transaction count materially changes store shape, but 16,384 does not raise canonical E2E on the clean production
binary. Future work must target the measured model-store work itself rather than tune this cap further.

## E508-E512: obtain a clean full-route profile before selecting code

Three profiler configurations were deliberately rejected before drawing a hotspot conclusion:

| Run | Throughput | Qualification | Decision |
| --- | ---: | --- | --- |
| E508 | 272,061/s | Internal JFR included request-stage trace metadata | Invalid for CPU/allocation ranking |
| E509 | 337,564/s | External recording missed the measured phase | Invalid timing window |
| E510 | 266,464/s | External `profile.jfc` still enabled Fluxzero custom events | Invalid clean-profile control |
| E511 | 335,997/s | External CPU/allocation profile with Fluxzero request/batch events disabled | Accepted diagnostic profile |
| E512 | 339,271/s | Clean non-JFR route plus PostgreSQL statement/table statistics | Accepted causal baseline |

E511 recorded 32,030.7 MiB allocations, seven garbage collections totalling 262.8 ms and a 7,385.7 MiB maximum
heap sample. Generic command/result work remained visible, but the campaign scope forbids optimizing those already
qualified routes. E512 instead exposed a model-specific multiplicative write: the derived stream locator wrote one
row for every model-hash membership. Across seed, warm-up and measurement this was about 1.18 million locator rows,
even though the authoritative model stream consisted of only about 1,361 stream blocks.

## E513-E519: prove locator headroom and reject timing-based coalescing

The existing diagnostic locator bypass preserved the complete command, model/event durability and result route but
omitted the derived lookup index. It is not production-correct; it is an upper-bound ablation used only to measure how
much full-route throughput is available in this one cost.

| Variant | Runs | Geometric mean | Effect versus matched control | Decision |
| --- | --- | ---: | ---: | --- |
| Locator enabled | E514 346,542; E515 349,382/s | **347,959/s** | control | retain |
| Locator bypassed | E513 375,923; E516 382,196/s | **379,047/s** | **+8.93%** | causal headroom only |
| 25 ms collection delay | E517 352,499/s | n/a | no convincing gain | reject |
| 50 ms collection delay | E518 350,182/s | n/a | no convincing gain | reject |
| 100 ms collection delay | E519 348,226/s | n/a | no gain | reject |

The delay series rules out “too many locator commits” as the primary mechanism. Waiting longer can coalesce work, but
does not remove the row and index amplification and violates the no-artificial-pause batching principle.

## E520-E524: isolate index maintenance from locator row materialization

| Variant | Runs | Geometric mean | Effect versus control | Decision |
| --- | --- | ---: | ---: | --- |
| Per-hash btree control | E521 353,063; E523 339,723/s | **346,329/s** | control | retain temporarily |
| Per-hash PostgreSQL hash index | E520 343,017; E522 341,222/s | **342,118/s** | **-1.22%** | reject |
| Per-hash heap rows, no lookup index | E524 359,843/s | n/a | diagnostic upper bound | no production use |

PostgreSQL used the hash index, so its loss is not a planner accident. E524 shows that both the 1.18 million physical
rows/COPY values and index maintenance matter. Merely changing the index implementation cannot remove the dominant
multiplicity.

## E525-E531: compact one stream-block membership set into one locator row

The accepted representation stores one `integer[]` of model hashes per stream block and hash partition, with a GIN
overlap lookup. It changes only the derived, rebuildable locator; authoritative model events, stream blocks, sequence
numbers and the global event log are unchanged.

| Run | Route/variant | Throughput | Additional result | Decision |
| --- | --- | ---: | --- | --- |
| E525 | Full E2E compact locator | 354,651/s | matched candidate A | accept after pair |
| E526 | Full E2E compact locator | **364,430/s** | matched candidate B | accept after pair |
| E527 | Full E2E per-hash control | 343,236/s | matched control B | control |
| E528 | Compact locator plus large read validation | 369,693/s writes | hot 2.196M models/s; cold run later hit the existing event-join/spill path | write-valid, read run incomplete |
| E529 | Compact locator, matched small read route | 115,780/s writes | hot 795,903; cold 48,157 models/s | read-valid |
| E530 | Per-hash control, matched small read route | 108,328/s writes | hot 623,934; cold 47,520 models/s | control |
| E531 | Full E2E compact locator, batch JFR | 358,453/s | active ordered store capacity **0.431M/s** | accepted profile |

Using E523/E527 as the matched controls gives 341,475/s geometric mean. E525/E526 give **359,508/s**, a causal
**+5.28% full-route improvement**. Physical locator rows fell to 10,841 for 1,179,647 model-hash memberships: about
109 times fewer heap/COPY rows. The matched small read route shows no lookup regression: sustained cold reconstruction
rose 47,520 -> 48,157 models/s (+1.34%), while hot lookup also improved. After E531 every GIN index reported zero
pending-list pages and tuples, so the write gain is not deferred maintenance hidden in `fastupdate`.

Startup migration recognizes both the immediately preceding per-hash/btree schema and the older array/GIN schema.
It transactionally truncates and rebuilds only this unlogged derived locator; it never mutates authoritative history.
The existing stream-tail fallback remains available while the locator catches up. Focused migration tests and the
complete Runtime reactor (681 Runtime tests plus benchmark module) pass.

### E531 composite and fundamental active store phases

All values are milliseconds per packed model/event transaction. `Co-located model task` is a composite of the model
rows below it; it is not the global-event insert. Percentiles are per transaction and therefore must not be added
across columns.

| Kind | Segment | Exact meaning | Mean | p50 | p95 | p99 | max |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| Composite | Packed model-store total | Ordered active work from accepted batch through durable publication | **16.891** | 15.835 | **29.817** | 53.159 | 63.595 |
| Composite | Co-located model task | Model callback inside the eventlog transaction; contains the model rows below | **6.952** | 5.168 | **15.685** | 46.749 | 57.410 |
| Fundamental | Global-event direct insert | Insert the globally visible event rows | **5.184** | 4.110 | **12.141** | 17.496 | 20.458 |
| Fundamental | Database commit | Commit the joint event/model transaction | 1.760 | 1.087 | 4.788 | 23.984 | 27.413 |
| Fundamental | Insert model stream blocks | Persist per-model event-stream blocks/envelopes | **4.018** | 2.843 | **9.004** | 43.048 | 51.315 |
| Fundamental | Lock current model state | Read and lock the durable state head used for conflict validation | 1.692 | 1.019 | 4.360 | 9.563 | 25.969 |
| Fundamental | Advance model state | Persist the new durable state index/current head | 1.143 | 0.766 | 3.782 | 6.376 | 8.061 |
| Fundamental | Stage event rows | Move any staged event rows into the transaction | 0.056 | 0.001 | 0.026 | 2.146 | 2.647 |
| Fundamental | Ensure model types | Validate/cache the model-type registry rows | 0.005 | 0.005 | 0.009 | 0.026 | 0.031 |
| Residual | Not separately named in E531 | Preparation, publication and transaction administration | **3.026** | n/a | n/a | n/a | n/a |

The separate admission queue wait averaged 12.178 ms (p95 26.733 ms); it is not active service time and is excluded
from the 16.891 ms total.

### E532: split preparation, completion and the asynchronous locator

E532 uses the accepted compact-locator code plus diagnostic batch observers. It completed the exact full route at
351,947/s and measured 0.432M/s active store capacity. This is 1.8% below E531 and is treated as a profile, not a new
throughput pin.

| Segment | Execution relation | Mean | p50 | p95 | p99 | max |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Validate packed fast path | Before durable store starts | 0.840 | 0.574 | 2.234 | 3.289 | 3.968 |
| Collect event references | Before durable store starts | 0.111 | 0.047 | 0.379 | 0.609 | 0.927 |
| Encode packed model stream | Runs concurrently with event-store admission/preparation | **1.502** | 0.767 | 4.267 | 7.299 | 22.084 |
| Publish packed boundary | After durable commit, before next ordered batch | 0.328 | 0.227 | 0.869 | 1.119 | 2.143 |
| Cache packed heads | Async completion work | 0.900 | 0.688 | 2.317 | 3.477 | 6.746 |
| Complete packed results | Seven-way async completion chunks | 0.530 | 0.226 | 1.472 | 2.839 | 49.507 |

These means cannot all be added to store time because stream preparation deliberately overlaps the event transaction.
They explain most of E531's formerly unnamed residual without selecting a production change.

The derived locator is a separate background pipeline that competes for the same PostgreSQL resources:

| Locator segment | Mean | p50 | p95 | p99 | max |
| --- | ---: | ---: | ---: | ---: | ---: |
| Read authoritative source blocks | 2.189 | 1.479 | 5.095 | 7.306 | 51.262 |
| Decode source blocks/model hashes | 0.454 | 0.364 | 1.149 | 2.562 | 3.134 |
| Write locator partitions | **9.650** | 8.337 | **19.029** | 36.746 | 39.019 |
| Advance and commit locator cursor | **2.545** | 1.930 | **6.729** | 9.971 | 12.818 |

There were 147 write rounds for 1,093 compact source blocks: only 7.4 blocks per round on average. Each non-empty
round currently creates up to eight parallel partition transactions and, only after they all commit, one additional
cursor transaction. This is the next concrete mechanism to test: reduce commit amplification while preserving the
complete locator and immediate no-timer behavior. Parallel inserts are not assumed to be dispensable; the full E2E
route must decide whether fewer commits outweigh less insert parallelism for these very small locator batches.

### E533-E534: one locator transaction loses to parallel partition inserts

E533 wrote the same compact locator rows to the same eight tables and advanced the same cursor, without a timer or
functional ablation. The only change was executing all eight COPY operations plus the cursor update on one connection
and one transaction. E534 immediately restored the accepted parallel-partition implementation.

| Run | Locator write mode | Full E2E | Mean model dispatch batch | Decision |
| --- | --- | ---: | ---: | --- |
| E533 | One connection/transaction, eight serial COPY operations | 336,696/s | 8,322 | reject |
| E534 | Eight parallel partition transactions plus cursor transaction | **357,728/s** | 8,525 | control |

The candidate is **-5.88%** against its adjacent control. Commit amplification alone is therefore not a sufficient
optimization target: for the current rows and PostgreSQL topology, partition insert parallelism is worth more than the
eight saved commits. The production implementation remains unchanged.

### E535-E540: atomic state advance is real but too small for its complexity

E532 measured two fundamental state-table roundtrips inside every ordered packed model/event transaction: a
`select ... for update`, followed after the stream-block insert by an `update`. The E535 candidate replaced those with
one conditional `update ... returning`. It still acquired the state-row lock, required the reserved first state index
to match the durable head and time index, required every read state to be durable, returned the erasure and graph
flags, and remained in the same transaction so later failures rolled the early advance back. The full
`JdbcModelCommitStoreTest` passed 101/101 before performance qualification.

| Variant | Non-JFR runs | Geometric mean | Matched effect |
| --- | --- | ---: | ---: |
| Separate lock + update control | E536 353,849; E539 365,074/s | **359,418/s** | control |
| Atomic advance candidate | E535 358,021; E540 366,849/s | **362,408/s** | **+0.83%** |

The profile pair proved that the intended work disappeared rather than merely moving observer labels:

| Fundamental segment | E538 control mean | E537 candidate mean | Effect |
| --- | ---: | ---: | ---: |
| State lock/read | 1.362 ms | - | removed |
| State head update | 1.062 ms | - | removed |
| Atomic state advance + lock | - | 1.841 ms | replacement |
| Total state-row work | **2.424 ms** | **1.841 ms** | **-24.1% / -0.583 ms** |
| Stream-block insert | 3.394 ms | 3.362 ms | effectively unchanged |
| Co-located model task | 5.923 ms | 5.237 ms | -11.6% |
| Profile E2E | 344,755/s | 352,313/s | +2.19% |

This is a causally valid local improvement, but the full qualifying route gained only 0.83% across the balanced
non-JFR pairs. The candidate also needs a more complicated conditional SQL predicate and a fallback read solely to
preserve existing conflict diagnostics. That trade is not checkpoint-worthy at this effect size. It remains reverted;
production still uses the simpler separate lock and update. The next selected fundamental cost is the approximately
3.4 ms packed stream-block insert, followed by the remainder of the co-located model callback.

### E541-E551: set-based stream insert improves the SQL phase, not sustained E2E

The packed route writes only about seven initial stream-block rows per model/event transaction in this workload. The
previous `COPY ... FROM STDIN BINARY` path therefore paid its fixed protocol startup cost for roughly 7 MiB and 1,100
rows over the entire million-command run. The candidate retained the exact fifteen stored columns, event and state
ranges, model ids, segments, payload bytes and empty model-hash marker, but sent each transaction as one typed
multi-array `unnest` insert. It changed neither transaction boundaries nor stored format. The complete
`JdbcModelCommitStoreTest` passed 101/101.

The profile pair proves a real local reduction:

| Segment | E544 binary COPY control | E543 set-based candidate | Effect |
| --- | ---: | ---: | ---: |
| Stream-block insert mean | **3.476 ms** | **2.186 ms** | **-37.1%** |
| Co-located model callback mean | 6.165 ms | 4.783 ms | -22.4% |
| Active packed-store capacity | 0.451M/s | **0.509M/s** | **+12.9%** |
| Physical blocks / data | 1,100 / 7.206 MiB | 1,101 / 7.371 MiB | equivalent work |
| Profile E2E | 351,963/s | 353,064/s | +0.31% |

The short one-million-command runs initially looked checkpoint-worthy but did not remain stable:

| Order | Control | Candidate | Candidate effect |
| --- | ---: | ---: | ---: |
| candidate then control, E541/E542 | 355,677/s | 363,305/s | +2.14% |
| control then candidate, E545/E546 | 348,140/s | 372,073/s | +6.87% |
| candidate then control, E547/E548 | 365,630/s | 356,979/s | -2.37% |
| control then candidate, E548/E549 | 365,630/s | 366,569/s | +0.26% |

Batch counts varied from 83 to 153 in these roughly three-second measured phases. To prevent that feedback from
selecting production code, E550/E551 lengthened warm-up to 262,144 and the otherwise identical complete measured
route to 4,194,304 commands:

| Run | Implementation | Full E2E | Dispatch batches / mean | Decision |
| --- | --- | ---: | ---: | --- |
| E550 | Existing binary COPY control | **408,185/s** | 352 / 11,916 | accepted control |
| E551 | Set-based typed-array insert | 410,110/s | 348 / 12,053 | reject candidate |

The sustained effect is only **+0.47%**. That is too small to justify specializing the production write protocol for
this benchmark's tiny stream blocks, especially because other valid workloads can produce larger blocks for which
binary COPY remains the safer general path. The candidate is reverted and the production Runtime remains unchanged.

E550 also corrects how the older 0.407M/s figure should be read. E459's 0.407M/s was calculated active service
capacity from one profiled ordered store lane, not an E2E ceiling. With longer warm-up, the accepted full
command -> automatic `@Apply` -> durable model/global-event commit -> stored result route itself sustained
**0.408M/s**. Canonical progress still uses the fixed one-million-command matched protocol; E550 is a non-canonical
stability diagnostic, not a silently substituted acceptance baseline.

### E552-E554: sustained route split identifies two equal storage costs and GC pressure

E552 re-profiled the unchanged accepted binary-COPY implementation with the longer E550 protocol. The complete route
processed 4,194,304 commands at 395,492/s under batch JFR, in 340 SDK model-dispatch batches averaging 12,336
commands. The resulting event/message-store event is a fundamental sequential boundary: its preparation and storage
fields do not overlap and sum to its active duration.

| Fundamental part of one durable global-event + model job | Mean | p50 | p95 | p99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: |
| Event preparation and remaining global-event insert | **5.501 ms** | 4.550 ms | 12.000 ms | 25.500 ms | 40.800 ms |
| Co-located model-table update | **5.748 ms** | 4.614 ms | 10.643 ms | 32.897 ms | 35.619 ms |
| PostgreSQL commit | 1.454 ms | 1.006 ms | 3.679 ms | 6.604 ms | 28.402 ms |
| Staging and monitor administration | about 0.050 ms | - | - | - | - |
| Complete active event-store job | **12.781 ms** | 11.900 ms | 20.800 ms | 42.500 ms | 52.100 ms |

The first row still contains one nested measured SQL segment: the global event-log insert itself averaged 4.686 ms
(p50 3.699, p95 10.521, p99 25.417, max 40.099 ms). The second row is the complete co-located model callback; inside
it the packed stream-block insert averaged 3.311 ms, state locking 1.438 ms and the state update 0.917 ms. Those three
model phases are sequential and account for nearly all of the callback.

This resolves two previously conflated capacities. E552 calculated 0.509M/s for the model-store observation that
includes its admission/queue interval, while the actual co-located model callback performs 1.282M commands/s of active
work. The enclosing global event store reaches only 0.577M/s because it must first finish event preparation/insertion,
then the model callback, then the ordered commit. The full E2E route at 0.395M/s therefore still has too little margin
for a stable 0.500M/s guarantee.

E553 recorded the same accepted long route with normal CPU/allocation sampling and request-stage events disabled to
avoid profiling instrumentation dominating the sample. It completed at 364,943/s under JFR. The recording contained
2,912 execution samples, 3,396 allocation samples and 61 stop-the-world pauses totalling about 1.09 seconds
(17.8 ms mean, 38.4 ms p95, 51.1 ms max); several collections were triggered by G1 humongous allocations. The largest
flat allocation sites were Zstd compression (12.00%), tracking-wire output (7.91%), Zstd decompression (7.64%), Runtime
native message serialization (5.34%), `SerializedMessage.encode` (4.68%) and model-commit wire output (1.86%). The
model-specific sampled allocation groups represented about 12.9 GiB in SDK evaluation/preparation, 7.3 GiB in the
Runtime model store and 5.0 GiB in model-commit wire encoding. These are allocation-pressure estimates from sampled
weights, not retained-heap sizes.

The apparent `MemoryAwareCacheSupport.updateAll` monitor hotspot was not an independent cache lock: every wait burst
aligned with a garbage-collection pause. E554's non-JFR detailed counters still show real post-commit cache work of
about 0.95 microseconds per model, but no evidence that replacing or weakening `AdaptiveCache` is warranted. E554
completed the unchanged full route at 401,677/s. The next candidate must therefore target one of the two measured
5.5-5.7 ms storage blocks or demonstrably remove model-specific allocation/GC from the full route; it must not infer a
cache-contention mechanism from stop-the-world attribution.

### E555: PostgreSQL execution is not the dominant part of the model boundaries

E555 repeated the unchanged long route at 398,896/s after resetting `pg_stat_statements`. Java/JFR boundary time was
then compared with PostgreSQL's server-side execution time for the same operation counts:

| Operation | Java boundary mean in E552 | PostgreSQL execution mean in E555 | Approximate time outside server execution |
| --- | ---: | ---: | ---: |
| Global event insert | **4.686 ms** | 0.614 ms | **4.07 ms** |
| Packed model stream COPY | **3.311 ms** | 1.679 ms | **1.63 ms** |
| State lock/read | **1.438 ms** | 0.013 ms | **1.43 ms** |
| State-head update | 0.917 ms | 0.019 ms | **0.90 ms** |

These values are correlated aggregates rather than one transaction trace, and PostgreSQL planning was not separately
enabled. They nevertheless reject “make the SQL executor faster” as the primary hypothesis. JDBC protocol, result
handling and repeated client/server boundaries dominate three of the four operations. This justified testing a
boundary-removal mechanism rather than another isolated SQL micro-optimization.

### E556-E565: P4 fuses the packed state and stream boundaries

P4 applies only to fresh packed updates (`possibleDuplicate=false`) with no new model-type registration. One
PostgreSQL data-modifying CTE now validates and advances the singleton state row and inserts the prepared initial-stream
blocks from typed arrays. It retains all surrounding work: SDK handling, automatic `@Apply`, global event insertion,
the same PostgreSQL transaction and commit, durable ordinary result storage and complete route verification.

If the state predicate cannot advance because of an erasure fence or a changed state boundary, the CTE inserts no
rows and the existing lock/check/COPY/update path runs unchanged. An insert failure rolls the state advance back in
the same SQL transaction. The previously rejected stand-alone typed-array candidate is not being resurrected: its
0.47% sustained effect retained all state round trips. P4's mechanism is the removal of those round trips together.

The matched batch profiles show the local mechanism and its full-route effect:

| Segment | E557 parent control | E556 P4 | Effect |
| --- | ---: | ---: | ---: |
| Full E2E under batch JFR | 349,024/s | **356,884/s** | **+2.25%** |
| Complete active event-store job | 15.939 ms | **11.585 ms** | **-27.3%** |
| Co-located model callback | 6.907 ms | **3.196 ms** | **-53.7%** |
| Stream/state SQL work | COPY 3.870 + lock 1.806 + update 1.124 ms | **one fused statement 3.181 ms** | **-53.2%** |
| PostgreSQL commit | **1.812 ms** | 2.611 ms | +44.1%; batch feedback changed |
| Admission-inclusive model observation | 0.437M/s | **0.445M/s** | +1.8% |

Because the short route remains sensitive to natural batch feedback, E558-E561 used a balanced BAAB sequence. The
candidate geometric mean was 368,604/s versus 361,320/s for the parent, or +2.02%. The decisive sustained comparison
then used 262,144 warm-up and 4,194,304 measured commands per run:

| Sustained pair | P4 | Parent control | Effect |
| --- | ---: | ---: | ---: |
| E562 / E563, before checkpoint | **421,981/s** | 401,055/s | **+5.22%** |
| E564 / E565, committed P4 then immediate parent | **410,455/s** | 398,547/s | **+2.99%** |
| Geometric mean | **416,178/s** | 399,799/s | **+4.10%** |

Every sustained candidate and control run verified exactly 4,194,304 ordinary results, stored model events and global
events. E564 proves the committed Runtime tree, not just the exploratory worktree. The 421,981/s result remains the
best complete-route observation but is not treated as the stable pin by itself; P4 is accepted on the two matched
long pairs and their +4.10% geometric-mean effect.

Correctness verification added two focused contracts. A transient trigger failure inside the fused stream insert must
retry with exactly one state-index advance and one copy of each model/global event. After an actual erasure of another
model, a fresh packed update of a live model must still pass through the checked fallback. The complete
`JdbcModelCommitStoreTest` passed 103/103 and `./mvnw -B install` passed all 683 Runtime tests plus the benchmark test.

### E566-E569: direct event-block COPY is slower and rejected

E566 established the next long, full-route P4 profile before changing production code. It completed at 404,282/s and
verified exactly 4,194,304 ordinary results, stored model events and global events. Its 692 ordered model/event jobs
contained 6,061 commands on average. The fundamental service segments were:

| Segment | E566 mean | Meaning |
| --- | ---: | --- |
| Event-job preparation | **5.649 ms** | Wait for serialization/compression and the direct global-event block insert |
| Direct global-event block insert | **4.855 ms** | Insert the already compressed physical event-log rows before model work |
| Fused state advance + model-stream insert | 2.720 ms | P4's single state/stream SQL boundary |
| PostgreSQL commit | 2.075 ms | Durable commit of global events and model changes together |
| Model-store admission wait | **9.503 ms** | Queue pressure before the single ordered model/event store; not active service time |

The active ordered model-store capacity was 0.497M commands/s. This supersedes the older E459 0.407M/s estimate: both
are derived active-service rates under their particular full-route profiles, not E2E throughput or fixed ceilings.

The same E566 execution was correlated with `pg_stat_statements`. Its global event inserts produced 35,653 physical
rows from 4.19M logical events. PostgreSQL executed 936 insert statements in 541.436 ms, about 0.78 ms of server
execution per event job, versus 4.855 ms at the Java boundary. E567 then used full CPU/allocation JFR on the unchanged
route. Its 310,259/s throughput is profiling-distorted and not a progress number. Allocation sampling estimated only
244 MB in the defensive `JdbcBinder.setBytes` copy over all 4.19M events, or roughly 58 bytes per logical event, so
removing that copy could not explain the multi-millisecond boundary gap. Thresholded DataSourcePool socket reads did
show 2.487 ms mean latency, supporting a JDBC/protocol wait rather than a byte-copy hotspot.

E568 therefore tested one narrowly scoped mechanism: binary COPY only for the already compressed global-event rows of
an atomic model/event transaction. It retained the same connection, transaction, state/model work, commit, rollback,
index order and completion future; command and result stores remained on their accepted paths. The candidate passed
145 focused `JdbcMessageStoreTest` and `JdbcModelCommitStoreTest` cases with the feature enabled.

The immediate same-binary profile pair rejected it:

| Measurement | E568 binary COPY | E569 multi-value control | COPY effect |
| --- | ---: | ---: | ---: |
| Full SDK E2E | 404,326/s | **413,792/s** | **-2.29%** |
| Direct event-block insert | 6.649 ms | **4.407 ms** | **+50.9% slower** |
| Commands per model/event job | **7,037** | 6,205 | Larger COPY feedback batch |
| Complete active event-store job | 11.905 ms | **10.032 ms** | **+18.7% slower** |
| Fused state/stream statement | **2.484 ms** | 2.715 ms | Secondary feedback change |
| Commit | **1.848 ms** | 2.046 ms | Secondary feedback change |

Both runs verified the complete 4,194,304-command route. COPY's startup/streaming protocol is too expensive for only
about 49 compressed physical rows per event job. Its slower insertion induces larger batches and partially masks the
damage, but does not improve E2E. The candidate patch was archived as diagnostic evidence and fully reverted; P4
remains the clean production state.

### E570-E571: model/event SQL fusion removes useful insert parallelism

E570 tested whether the already compressed global-event rows could join P4's state advance and model-stream insert in
one data-modifying CTE. The candidate preserved one JDBC transaction, conditionally inserted neither event nor stream
rows when the state predicate rejected, retained the checked erasure fallback, and completed the existing future only
after the real commit. Its direct-row ownership and rollback contract plus the model retry/erasure contracts passed
146/146 focused `JdbcMessageStoreTest` and `JdbcModelCommitStoreTest` cases with the feature enabled.

The immediate same-binary long profile pair nevertheless rejected the mechanism:

| Measurement | E570 fused model + event | E571 P4 control | Fusion effect |
| --- | ---: | ---: | ---: |
| Full SDK E2E | 403,544/s | **413,086/s** | **-2.31%** |
| Active ordered-store capacity | 0.487M/s | **0.509M/s** | **-4.3%** |
| Model/event jobs | 888 | **703** | **+26.3%** |
| Commands per job | 4,723 | **5,966** | **-20.8%** |
| Combined model/event CTE | 4.828 ms | n/a | Candidate-only boundary |
| Parallel direct event insert | n/a | **4.507 ms** | Control-only, overlaps before commit lane |
| P4 state/stream CTE | n/a | **2.517 ms** | Control-only, ordered callback |
| Complete co-located callback | 4.921 ms | **2.527 ms** | **+94.7%** |
| PostgreSQL commit | 2.527 ms | **2.102 ms** | **+20.2%** |

The apparently separate 4.507 ms event insert and 2.517 ms P4 statement in the control are not additive serial costs.
`JdbcMessageStore` starts direct event inserts on its insert executor before each job reaches the ordered commit lane,
so inserts for several storage jobs can overlap. E570 deferred that work into the one ordered callback in order to
fuse the SQL, removing the useful overlap. The changed feedback then produced more, smaller jobs and more commits.
Boundary count alone was therefore the wrong objective; future candidates must retain parallel physical inserts before
the ordered atomic publication/commit boundary. The candidate was archived and fully reverted.

### E572: PostgreSQL planning is not the missing event-insert time

E572 ran the unchanged complete P4 route with batch JFR and PostgreSQL
`pg_stat_statements.track_planning=on`. It verified all 4,194,304 results, stored model events and global events at
412,297/s. Planning tracking makes this a diagnostic rather than a progress comparison.

The run produced 988 global-event insert calls across 150 distinct multi-value statement shapes. Their weighted mean
planning time was only **0.040 ms per call** and their mean PostgreSQL execution time was 0.514 ms. The corresponding
Java/JDBC direct-insert boundary averaged **4.060 ms**. P4's fixed-shape state/stream CTE likewise used only 0.021 ms
planning and 0.429 ms execution per call versus a 2.620 ms Java/JDBC boundary. Caching or normalizing query shapes can
therefore recover at most a few hundredths of a millisecond; it cannot explain the measured client-side gap. The
database setting was restored to `track_planning=off` immediately after collection.

### E573-E576: direct SDK event-envelope encoding is real but too small

E567 attributed substantial sampled model-stack allocation to the SDK first building a reusable native event envelope
and then copying that envelope into the native model-commit batch. E573 tested a byte-identical direct writer from the
materialized `SerializedMessage` into the final commit-batch buffer. The existing encoder remained unchanged for the
same-binary control. The candidate covered materialized, reusable and dirty/lazy representations and passed 37 focused
envelope/codec tests in both feature modes plus 75 model-commit and handler tests with direct encoding active.

The complete-route measurements were consistently positive but small:

| Pair | Direct encoding | Existing encoding | Effect |
| --- | ---: | ---: | ---: |
| E573/E574 batch-JFR | 411,416/s | 408,598/s | **+0.69%** |
| E576/E575 non-JFR | 419,912/s | 417,353/s | **+0.61%** |

Every run verified exactly 4,194,304 ordinary results, stored model events and global events. E573 formed 645 model
store jobs averaging 6,503 commands and measured 0.540M/s active ordered-store capacity; E574 formed 741 jobs averaging
5,660 commands and measured 0.516M/s. That feedback difference is larger than the E2E effect and is another reason not
to overstate the local allocation removal. A stable gain of about 0.65% does not justify a new public direct-write API,
duplicated envelope implementation and permanent feature path. The patch was archived and fully reverted. The result
causally excludes temporary SDK event-envelope allocation/copying as the current primary limiter.

### E577-E583: SDK durability backpressure shifts rather than removes small store jobs

The E574 store profile showed a highly nonlinear job-size distribution: jobs of at most 4,096 updates carried only
13.69% of the commands but consumed 38.70% of active ordered-store time. The candidate therefore kept SDK
model-commit backlog slots occupied until their real durable futures completed, rather than releasing a slot once the
evaluations and dispatches had merely started. It introduced no delay and removed no route stage. A value of zero
preserved the existing behavior exactly; positive values bounded the number of durable SDK commit batches in flight.

The initial 1,048,576-command screens used only 65,536 warm-up commands. They measured 367,308/s at one batch,
381,483/s at four, 368,976/s at eight and 363,593/s for the same-binary zero control. Because the control itself was
far below the P4 pin, these screens were retained only as diagnostics. Process, memory and thermal checks found no
active host interference. The actual cause was insufficient run length: E581 restored the exact 262,144 warm-up and
4,194,304-command route and established a fresh P4 high state of **423,424/s**.

The long matched candidate did not improve that pin:

| Run | Durable SDK batches in flight | Profiling | E2E | SDK dispatches / mean size | Decision |
| --- | ---: | --- | ---: | ---: | --- |
| E581 | existing behavior (`0`) | none | **423,424/s** | 344 / 12,193 | accepted control |
| E582 | 4 | none | 420,315/s | 357 / 11,749 | **-0.73%; reject** |
| E583 | 4 | batch JFR | 418,447/s | 347 / 12,087 | mechanism profile |

E583 proved that the limit changed the intended physical distribution, but not in the useful direction. Percentages
below are shares of all 4,194,304 model updates and of total active ordered-store time:

| Runtime model-store job size | E574 control jobs | E574 items / active time | E583 limited jobs | E583 items / active time |
| --- | ---: | ---: | ---: | ---: |
| <=4,096 | 387 | 13.69% / **38.70%** | 326 | 11.79% / **34.62%** |
| 4,097-8,192 | 143 | 20.26% / 20.45% | 160 | 23.00% / 24.11% |
| 8,193-16,384 | 169 | 45.85% / 30.92% | 174 | 48.20% / 32.82% |
| >16,384 | 42 | 20.21% / 9.93% | 37 | 17.00% / 8.46% |
| Total | 741 | 8.129 s / **515,956/s** | 697 | 8.221 s / **510,204/s** |

The candidate removed some tiny jobs, but also reduced the share of the largest jobs that already serve above one
million updates per active second. Medium jobs absorbed the work, total active store time increased by 1.13%, and
complete-route throughput did not improve. Testing still looser limits has no causal basis after the eight-batch
screen moved in the same direction. The candidate patch was archived and fully reverted. The durable-backpressure
idea is rejected at this SDK backlog layer; the remaining target is the fixed per-job work inside the ordered
model/event-store boundary, not another feedback cap.

### E584-E592: isolate the full SDK model handler and identify database contention

The literal public `assertAndApply` route cannot safely claim a tracked source index and therefore measures the slower
general idempotent store path. E584-E589 added a diagnostic-only synthetic tracked route to the benchmark. Commands
are serialized before timing and receive valid monotonic indexes in the same epoch-based range as real Fluxzero
messages; the normal SDK handler pipeline then performs deserialization through durable model and global-event
storage. Command and ordinary result logs are the only omitted stages.

E584-E586 initially used indexes around 10^12, below current Fluxzero epoch indexes around 10^17. Runtime correctly
classified every update as a possible duplicate and used `commit-general`; these runs are invalid as packed-route
capacity measurements. After fixing only the benchmark index source, all 131,072 diagnostic updates selected the
packed path and exact durable verification passed. The sustained measurements were:

| Route | Run | E2E | Model-store jobs / mean | Mean active store | Active store capacity |
| --- | --- | ---: | ---: | ---: | ---: |
| Synthetic tracked SDK apply -> model + event | E588 none | **834,806/s** | not profiled | not profiled | not profiled |
| Synthetic tracked SDK apply -> model + event | E589 batch JFR | **846,441/s** | 637 / 6,585 | 6.619 ms | **0.995M/s** |
| Command + tracking + model + event, no ordinary result | E590 none | **561,987/s** | not profiled | not profiled | not profiled |
| Command + tracking + model + event, no ordinary result | E591 batch JFR | **564,370/s** | 722 / 5,809 | 8.965 ms | **0.648M/s** |
| Full command + model + event + result | E571 batch JFR | 413,086/s | 703 / 5,966 | 11.728 ms | **0.509M/s** |

The model-only diagnostic includes the SDK side the user wanted: handler resolution, assertions, interceptors,
automatic `@Apply`, model loading/cache access, transition planning, event serialization, model batching and the
WebSocket round trip. Its 0.835-0.846M/s result proves that neither SDK model handling nor one abstract 0.407M/s lane
is a fixed current ceiling. Adding the command route lowers active model-store capacity by about 35%; adding ordinary
results lowers it by another 21%. Model batch sizes stay in the same order of magnitude, while event insert, state/
stream and commit times all rise. That makes shared PostgreSQL/WAL pressure the route-wide causal model rather than a
missing SDK compute optimization.

E592 retained the full canonical route and reset PostgreSQL statistics exactly at measured-phase start. It completed
at 418,035/s. The 64-connection application pool was not saturated: samples normally found roughly 24-28 idle
backends and almost never caught an active wait. PostgreSQL reported 14,597 commits, 346.9 MB WAL, 5,862 client WAL
fsyncs and no WAL-buffer exhaustion. The derived locator performed roughly 600 rounds; each round issued eight
parallel partition COPY transactions plus its cursor transaction. The COPY execution itself remained parallel and
fast, but those small partition transactions were a measured source of avoidable commit pressure.

### E593-E605: P5 halves locator partition transactions while retaining parallel inserts

P5 does not delay, coalesce by timer or serialize the locator into one large insert. Every ready locator round still
starts immediately. Its eight physical hash partitions are assigned to four parallel write lanes; each lane writes
two partitions sequentially on one connection and commits once. The cursor still advances in its separate ordered
transaction only after every lane completes. The existing cleanup still waits for all lane futures and removes rows
from every affected partition after any partial failure.

Four versus eight lanes was positive in every matched comparison:

| Pair | Four lanes | Eight-lane same-binary control | Effect |
| --- | ---: | ---: | ---: |
| E593 / E594, no JFR | 425,104/s | 422,254/s | **+0.68%** |
| E596 / E595, reverse order, no JFR | **426,108/s** | 418,143/s | **+1.90%** |
| E597 / E598, batch JFR | 413,567/s | 408,101/s | **+1.34%** |
| E599 / E600, measured PostgreSQL stats | 418,179/s | 406,711/s | **+2.82%** |

The unprofiled matched geometric means are **425,606/s versus 420,193/s (+1.29%)**. Across all four matched pairs the
effect is +1.68%. E597 also raised active model-store capacity from 0.502M/s to **0.539M/s (+7.4%)** and increased
mean natural model batches from 5,615 to 6,141. The PostgreSQL pair proves the intended mechanism: total measured
transactions fell from 13,698 to **11,696 (-14.6%)** and total active PostgreSQL time from 22.94 to **18.73 seconds
(-18.3%)**. WAL volume stayed comparable and neither run exhausted WAL buffers.

Two lanes were screened because the already rejected one-transaction E533 implementation established the lower end.
Across E601/E602 and clean reverse-order E605/E604, two lanes gained only 0.31% geometric mean over four. That is below
the campaign's noise floor and sacrifices useful insert parallelism for heavier real-world locator rows, so four is
the selected default. E603 is explicitly excluded: `mediaanalysisd` consumed 96% CPU during the run and inflated p99
latency to 335 ms. The process was paused for E604/E605 and immediately resumed afterwards.

Runtime commit `0c23c91f` is the accepted P5 checkpoint. The new focused test deterministically covers all eight
physical locator partitions through the grouped lanes; the complete `JdbcModelCommitStoreTest` suite passes 104/104.
Authoritative model/event storage, physical locator layout, cursor-gated visibility, failure cleanup and wire formats
are unchanged.

### E606-E610: detailed P5 model trace and rejected cursor-transaction fusion

E606 is a fresh exact full-route P5 trace: 4,194,304 commands, results, stored model events and global events at
419,153/s with batch-only JFR. The ordered model/event transaction processed 672 natural batches averaging 6,242
commands. Its mean active storage time was 11.378 ms, corresponding to 0.549M commands/s of active service capacity.

The following are the fundamental or near-fundamental synchronous stages. They are nested in the same model/global-
event transaction and must not be added to the 11.378-ms composite without accounting for that nesting.

| Fundamental stage | Exact work | Mean | p50 | p95 | p99 | Max |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| **Direct global-event insert** | Insert the durable event rows before the shared transaction can commit | **4.289 ms** | 3.591 | 9.766 | 23.190 | 35.404 |
| **Advance state + insert packed model-stream blocks** | Atomically advance `model_state` and insert the encoded initial-stream blocks | **2.562 ms** | 2.167 | 5.452 | 8.652 | 28.590 |
| **Global-event transaction commit** | PostgreSQL commit/WAL durability after event and co-located model writes | **1.999 ms** | 1.476 | 5.221 | 8.601 | 27.191 |
| Prepare packed stream | Form entries, partition blocks and encode their payloads before storage consumes them | 1.071 ms | 0.718 | 2.642 | 4.033 | 33.654 |
| Cache packed heads | Publish the newly durable model heads to the Runtime cache | 0.739 ms | 0.565 | 1.922 | 2.463 | 2.928 |
| Validate packed fast path | Verify unique commit/target ids and that every cached previous head is still current | 0.710 ms | 0.490 | 1.931 | 2.519 | 3.985 |
| Publish packed boundary | Publish recent updates, invalidate locations and advance the visible model boundary | 0.290 ms | 0.214 | 0.803 | 1.081 | 1.357 |
| Complete packed results | Complete ordered result chunks after the durable boundary | 0.230 ms | 0.208 | 0.545 | 0.782 | 1.189 |

The derived stream locator runs asynchronously and overlaps subsequent canonical work. Its stages therefore are not
part of the per-command latency sum, but they compete for PostgreSQL, WAL, CPU and connections:

| Derived locator stage | Exact work | Rounds / mean commands | Mean | p50 | p95 | p99 | Max |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| **Write locator rows** | Four parallel transaction lanes COPY hash arrays into eight physical locator partitions | 518 / 8,097 | **11.834 ms** | 9.344 | 30.462 | 48.835 | 79.025 |
| **Advance locator cursor** | Publish the contiguous durable locator boundary in a separate final transaction | 518 / 8,097 | **2.779 ms** | 1.999 | 6.809 | 10.307 | 31.850 |
| Read source blocks | Read newly durable authoritative packed stream blocks through the current visible boundary | 519 / 8.5 blocks | 2.410 ms | 1.713 | 5.918 | 9.222 | 34.964 |
| Decode source blocks | Decode block memberships and derive sorted unique model hashes | 519 / 8.5 blocks | 0.497 ms | 0.403 | 0.956 | 1.728 | 26.351 |

The apparently attractive cursor commit was tested on the complete route. E608/E609 reused the locator's existing
read/cursor connection for one of the four write lanes, waited for the other three lane commits and then committed
that fourth lane together with the cursor. Visibility stayed correct: the cursor could only commit after every other
partition was durable, and `JdbcModelCommitStoreTest` passed 104/104 with the candidate active. It nevertheless changed
natural feedback and reduced mean model dispatches.

| Matched pair | P5 control | Fused locator/cursor transaction | Effect | Mean model dispatch |
| --- | ---: | ---: | ---: | ---: |
| E607 / E608 | 425,427/s | 417,748/s | **-1.81%** | 13,574 -> 11,716 |
| E610 / E609, reverse order | 422,384/s | 415,671/s | **-1.59%** | 12,373 -> 11,848 |

The matched geometric means are **423,903/s control versus 416,708/s candidate (-1.70%)**. Eliminating one measured
commit per locator round is therefore not sufficient: this implementation damages the complete-route batching
feedback by more than its local PostgreSQL saving. The candidate was fully reverted and is not P6.

### E611-E614: rejected eager locator-hash prepartitioning

The E567 allocation profile showed `partitionModelHashes` among the larger sampled model-store allocation sites. The
production P5 locator first assigns each source row to its physical partitions and later derives each partition's
sorted hash array in the four parallel write lanes. The candidate instead calculated and retained all eight arrays
once while assigning the source row, then reused them during COPY. It changed neither physical rows, transactions,
write lanes nor cursor publication. The complete `JdbcModelCommitStoreTest` suite passed 104/104 with the candidate.

The complete route nevertheless rejected it in both run orders:

| Matched pair | P5 control | Eager hash prepartitioning | Effect |
| --- | ---: | ---: | ---: |
| E611 / E612 | 410,489/s | 399,332/s | **-2.72%** |
| E614 / E613, reverse order | 414,650/s | 407,035/s | **-1.84%** |

The matched geometric means are **412,564/s control versus 403,165/s candidate (-2.28%)**. This is an important
counterexample to selecting production work from an allocation sample alone: eliminating repeated allocation can
move work from the parallel locator lanes into the earlier preparation stage and alter route feedback without raising
the full-route ceiling. The candidate was fully reverted and is not P6.

### E615-E621: P5 CPU/allocation trace and rejected larger event rows

E615 is a fresh full-JFR P5 observation of the complete route. Full sampling lowers throughput to 309,679/s and makes
it non-canonical, but it adds CPU and allocation evidence to E606's batch-stage timings. Within the model route, the
largest new correlated SDK interval is accepted-result post-processing: `model-result-matched` through
`model-post-commit-complete` averaged 2.673 ms, of which `afterCommitBatch` averaged 2.202 ms. Runtime encoding of a
`TrackModelUpdatesResult` page averaged 1.681 ms. Sampled model allocations were led by locator binary-COPY buffers,
recent `ModelUpdate` materialization and packed-stream preparation; those samples remain supporting evidence rather
than acceptance criteria.

E616 then measured the SDK callback with its narrow existing timers and no JFR. It completed the fully verified route
at 418,703/s. Across the measured 4,194,304 commands, committed-model assembly consumed 714.131 ms
(0.170 microseconds/command) and repository/cache plus tracker publication 4,140.632 ms
(0.987 microseconds/command), for 1.157 microseconds/command together. This is material CPU work, but its isolated
single-lane service capacity is about 0.864M/s; it is not the first 500k boundary.

The direct event insert remained E606's largest synchronous database stage. P5 stores a measured 4.19M events in
roughly 33,000 compressed rows because the legacy row group is 128 events. A table-specific diagnostic tested 256
events per row while leaving command and result stores untouched. E617-E619 initially did not reach the shared-
executor Runtime construction path; physical row counts proved that the setting had not changed. They are retained as
invalid diagnostics, not evidence against the mechanism.

After correcting only the diagnostic wiring, E621 halved physical event rows versus the E620 control but made the
complete route worse:

| Matched profile | Event rows | Direct event insert | Event commit | Model-store active | Full E2E |
| --- | ---: | ---: | ---: | ---: | ---: |
| E620, 128 events/row | 33,059 | 4.323 ms | 2.106 ms | 11.446 ms | **413,395/s** |
| E621, 256 events/row | **16,648** | 4.275 ms | 2.205 ms | 11.988 ms | 397,088/s |
| Effect | **-49.6%** | -1.1% | +4.7% | +4.7% | **-3.95%** |

The row count was therefore not the cost driver. Larger compressed rows saved almost no insert time and worsened
commit/database feedback. The diagnostic property and wiring were fully reverted; 256 is not P6 and 512 was not tried
after the mechanism had already failed.

### E622-E625: preserve the one-millisecond model-backlog collection delay

The ordered model-commit backlog normally collects ready jobs for one millisecond before it drains a batch. Because
this is an explicit pause on the canonical model path, E622-E625 tested `PT0S` without changing batch-size, byte,
ordering, durability or command/result behavior. Two alternating full-route no-JFR pairs remained completely verified:

| Matched pair | P5, 1 ms | Zero-delay diagnostic | Effect |
| --- | ---: | ---: | ---: |
| E622 / E623 | 413,239/s | 408,676/s | **-1.10%** |
| E625 / E624, reverse order | 411,864/s | 409,406/s | **-0.60%** |

The matched geometric means are **412,551/s control versus 409,041/s candidate (-0.85%)**. Removing the pause did
not raise full-route capacity. The delay apparently earns back its latency by improving natural downstream model-batch
formation; it must not be characterized as one millisecond of pure serial waste. No production code changed and the
zero-delay setting is rejected as P6.

### E626-E635: split model result codecs and reject streaming update CBOR

E626 first corrected a trace ambiguity without changing the route. SDK WebSocket decode events now distinguish
ordinary results, `CommitModelsResult` and `TrackModelUpdatesResult`. The SDK-inclusive synthetic tracked-model route
then showed that commit acknowledgements were already cheap, while the large model-cache update pages still used
generic Jackson CBOR:

| Synthetic profile | Throughput | Update pages | Runtime update-page encode | SDK update-page decode | Active model-store capacity |
| --- | ---: | ---: | ---: | ---: | ---: |
| E626 generic CBOR control | 796,999/s | 504 | 2.177 ms | 3.191 ms | 0.934M/s |
| E627 streaming CBOR diagnostic | **824,814/s** | 582 | **1.628 ms** | **2.335 ms** | **0.946M/s** |

The candidate remained on the ordinary CBOR object contract: old generic decoders could read its bytes and its
decoder could read old generic bytes. It manually streamed the nested update and target fields instead of asking
Jackson to construct the same shape reflectively for every update. Local encode/decode means improved by 25-27%, and
the synthetic route improved 3.49%. However, it also produced 15% more update pages with fewer updates per page. That
is a route-feedback change, not a free CPU removal.

The first full-route alternating sequence looked positive but was not accepted on that preliminary evidence:

| Preliminary sequence | Generic control | Streaming candidate | Effect |
| --- | ---: | ---: | ---: |
| E628 / E629 | 413,064/s | 420,390/s | +1.77% |
| E631 / E630, reverse order | 420,535/s | 423,782/s | +0.77% |
| Geometric mean | 416,783/s | **422,083/s** | **+1.27% apparent** |

Adversarial wire review then found that the hand-written decoder needed to preserve the response timestamp and any
configured Jackson string/enum behavior. Those contracts were restored, with a direct fast path for the standard
mapper. The fully compatible implementation was recompiled and tested in a new candidate/control/candidate/control
sequence:

| Final sequence | Generic P5 control | Compatible streaming candidate | Effect |
| --- | ---: | ---: | ---: |
| E633 / E632 | 423,796/s | 414,600/s | -2.17% |
| E635 / E634, reverse order | 424,918/s | 414,950/s | -2.35% |
| Geometric mean | **424,357/s** | 414,775/s | **-2.26%** |

Every run verified exactly 4,194,304 ordinary results, model events and global events. The final full route therefore
overrules the faster codec and synthetic diagnostic. Both streaming implementations were removed, the wire format and
production CBOR path remain unchanged, and this is not P6. The retained improvement is only the JFR result-type
classification, which makes future SDK-inclusive traces causally clearer.

### E636: fresh P5 route split with classified SDK results

E636 reran the unchanged full command -> automatic `@Apply` -> durable model/global-event -> ordinary durable result
route with batch JFR after the result-classification change. It verified exactly 4,194,304 ordinary results, model
events and global events at **413,737/s**. The new labels show that neither model acknowledgement encoding nor SDK
decoding is the current limiter:

| Route component | Batches / mean items | Mean | p50 | p95 | p99 | Max | Interpretation |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| SDK `commit-models` request send | 18,796 / 218.7 | 0.209 ms | 0.107 ms | 0.554 ms | 1.465 ms | 32.859 ms | Request encode plus WebSocket send; overlaps across two sessions. |
| Runtime `CommitModelsResult` encode | 2,866 / 1,449.3 | 0.155 ms | 0.084 ms | 0.510 ms | 0.944 ms | 7.570 ms | Packed model acknowledgement preparation. |
| SDK `CommitModelsResult` decode | 2,866 / 1,449.3 | 0.117 ms | 0.058 ms | 0.370 ms | 0.936 ms | 7.201 ms | Typed acknowledgement decoding after WebSocket receipt. |
| Runtime model-update page encode | 543 pages | 1.978 ms | 1.584 ms | 5.162 ms | 6.171 ms | 23.908 ms | Cache/tracker update page; asynchronous route-wide supporting work. |
| SDK model-update page decode | 543 pages | 2.511 ms | 2.023 ms | 6.431 ms | 7.898 ms | 32.788 ms | Same asynchronous pages at the SDK. |
| SDK handler commit interval | 2,671 / 1,570.3 | **48.960 ms** | 49.243 ms | 80.664 ms | 98.359 ms | 124.198 ms | Composite wait from handler completion through its batched durable commit; not SDK CPU and not additive. |

The active ordered model/event store remained the closest full-route capacity boundary: 725 transactions averaging
5,785 updates, 11.145 ms active storage and **0.519M updates/s** service capacity. Its fundamental synchronous work is:

| Fundamental work in one packed model/event transaction | Mean | p50 | p95 | p99 | Max | Relation to transaction |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Packed fast-path validation | 0.746 ms | 0.471 ms | 1.968 ms | 2.644 ms | 28.371 ms | Before global-event storage is launched. |
| Collect packed event envelopes | 0.067 ms | 0.028 ms | 0.234 ms | 0.344 ms | 1.826 ms | Before event-store submission. |
| **Direct global-event insert** | **4.187 ms** | 3.138 ms | **9.725 ms** | 19.389 ms | 44.189 ms | Largest measured synchronous database stage; runs before the shared commit. |
| **Atomic state advance + model-stream insert** | **2.576 ms** | 1.965 ms | **5.567 ms** | 25.455 ms | 30.489 ms | Co-located on the event transaction's connection. |
| **Shared event/model transaction commit** | **2.044 ms** | 1.466 ms | **4.661 ms** | 11.369 ms | 27.792 ms | Makes global event and model state/stream visible together. |
| Publish in-memory packed boundary | 0.279 ms | 0.202 ms | 0.851 ms | 1.104 ms | 1.562 ms | Runs after durable commit. |

`prepare-packed-stream` averaged 0.999 ms but deliberately overlaps the event-store insert, so it must not be added to
the critical-path rows. Direct event insert, state/stream work and commit account for about **79%** of the measured
11.145 ms active store interval. The derived model-stream locator also writes asynchronously and adds PostgreSQL
pressure (four parallel write lanes average 11.696 ms, followed by a 2.670 ms cursor transaction), but those intervals
overlap canonical work and are not part of the transaction latency sum.

The current same-code route split is therefore:

| Route | Full throughput under batch JFR | Active model-store capacity | What is omitted |
| --- | ---: | ---: | --- |
| E626 synthetic tracked SDK handler | **796,999/s** | **0.934M/s** | Command append/tracking and ordinary result publication/storage/completion |
| E636 full canonical route | **413,737/s** | **0.519M/s** | Nothing |

Both include lazy SDK command decoding, handler resolution, assertions/interceptors, automatic `@Apply`, model-event
serialization, model batching, WebSocket transport and real model/global-event durability. The large difference is
therefore shared command/result database and feedback pressure, not omitted SDK model handling. The literal direct
`Fluxzero.assertAndApply(command)` result remains the separate E489-E492 general-idempotency observation.

### E637: locator COPY and commit are no longer conflated

E637 retained the complete canonical route and added nested JFR phases inside the already asynchronous derived
model-stream locator. It verified all 4,194,304 ordinary results, model events and global events at **415,962/s**.
The active canonical model store measured 0.510M/s in this natural batch shape. The locator split is:

| Derived locator work | Samples | Parallelism | Mean | p50 | p95 | p99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Whole four-lane write round | 514 | one coordinator awaiting four lanes | 11.877 ms | 9.747 ms | 28.603 ms | 52.008 ms | 84.096 ms |
| **Per-lane two-partition COPY** | 2,056 | up to four | **9.289 ms** | 7.614 ms | **18.349 ms** | 40.925 ms | 82.916 ms |
| Per-lane commit | 2,056 | up to four | 1.409 ms | 0.979 ms | 3.890 ms | 6.916 ms | 28.707 ms |
| Cursor row update | 513 | one, after all lanes | 1.753 ms | 1.207 ms | 4.318 ms | 7.813 ms | 25.930 ms |
| Cursor commit | 513 | one, after the update | 1.258 ms | 0.781 ms | 3.379 ms | 5.189 ms | 45.787 ms |

Each P5 lane writes two disjoint physical locator tables in one transaction, so it starts two binary COPY operations.
The lane handled only 17.2 physical compressed rows on average. Commit is therefore not the dominant 11.877 ms stage:
COPY startup and execution consume about 78% of the coordinator's wall interval. The proposed asynchronous lane-commit
experiment is not selected from this evidence. Its theoretical local saving is too small, and it would leave the
measured two-COPY cost intact.

The next bounded hypothesis is one COPY stream per lane while retaining four disjoint parallel lanes, immediate
processing, one transaction per lane and the later ordered cursor. It requires a safe way to route the two physical
partitions from one COPY; no production implementation is accepted by E637 itself.

### E638-E640: one partition-routing COPY per locator lane is locally faster but not E2E-relevant

The candidate introduced a storage-less PostgreSQL partitioned parent above the same eight unlogged physical locator
tables. Each of the existing four lanes could then stream its two physical partitions through one binary COPY while
retaining four-way insert parallelism, one transaction per lane, immediate processing and the later ordered cursor.
No timer, asynchronous commit or command/result change was involved.

E638 was an invalid launch diagnostic: PostgreSQL correctly rejected the legacy child-table column migration after the
tables had become partitions. E639 fixed that candidate-only schema bootstrap and completed an exact 262,144-command
smoke at 313,831/s; its short size is not canonical-comparable. E640 then verified exactly 4,194,304 ordinary results,
model events and global events on the complete canonical route:

| Measure | E637 accepted P5 | E640 one-COPY candidate | Effect |
| --- | ---: | ---: | ---: |
| Full E2E under batch JFR | **415,962/s** | **414,592/s** | -0.33% |
| Active model-store capacity | 0.510M/s | 0.512M/s | effectively unchanged |
| Locator coordinator rounds | 514 | 594 | +15.6% |
| Mean physical locator rows/round | 8,154.7 | 7,061.1 | -13.4% |
| Mean per-lane COPY | 9.289 ms | **6.330 ms** | **-31.9%** |
| Mean per-lane commit | 1.409 ms | 1.621 ms | +15.0% |
| Mean complete four-lane locator write | 11.877 ms | **8.916 ms** | **-24.9%** |

The mechanism did remove a COPY startup and materially reduce each asynchronous locator round, but it also changed
batch feedback enough to create more, smaller locator jobs. Most importantly, the synchronous model/event-store and
the complete E2E route did not improve. Because the derived locator is off the model-commit critical path and the
candidate additionally requires a non-trivial existing-schema migration, this is not a low-risk latent win. The
candidate was removed without a production checkpoint; P5 and the E637 tracing remain the accepted source state.

### E641-E647: SDK-inclusive isolation proves PostgreSQL co-tenancy, not metadata, halves model capacity

E641 refreshed the synthetic tracked SDK route after the classified store instrumentation. It again kept serialized
command decoding, handler resolution, assertions/interceptors, automatic `@Apply`, event serialization, model
batching, WebSocket and real model/global-event durability, while excluding only command and ordinary-result logs.
All 4,194,304 durable handler completions, model events and global events were exact at **834,401/s**, independently
reproducing E588's 834,806/s. Its active model store served 0.984M updates/s.

E642 and E643 then enabled PostgreSQL statistic resets at measured-phase start and ran the isolated and canonical
routes with the same batch JFR. They establish a per-route database comparison instead of relying on accumulated
server statistics:

| Fundamental model work | E642 SDK-isolated | E643 full canonical | Canonical effect |
| --- | ---: | ---: | ---: |
| Full route throughput | **805,092/s** | **406,675/s** | -49.5% |
| Active model-store capacity | **0.924M/s** | **0.512M/s** | -44.6% |
| Mean model batch | 5,622 | 5,738 | +2.1% |
| Direct global-event insert, Java/JDBC | 1.667 ms | **4.069 ms** | **+144%** |
| Direct global-event insert, PostgreSQL execution | 0.146 ms | **0.660 ms** | **+352%** |
| Atomic state/stream statement, Java/JDBC | 1.395 ms | **2.450 ms** | **+76%** |
| Atomic state/stream statement, PostgreSQL execution | 0.290 ms | **0.431 ms** | **+49%** |
| Shared event/model commit | 1.191 ms | **2.115 ms** | **+78%** |
| Derived locator COPY, PostgreSQL execution | 1.315 ms | **2.016 ms** | **+53%** |
| Total active model store | 6.082 ms | **11.211 ms** | **+84%** |

The full route generated 355.6 MB WAL and 11,578 transactions versus 87.4 MB and 5,213 in isolation. It also caused
42,852 physical buffer reads versus 2,057. The same model SQL is therefore materially slower inside PostgreSQL once
command/result durability is active; the gap is not explained by a saturated application connection pool, SDK
handler compute or only Java scheduling. The event insert is the largest amplification, while state/stream, commit
and even the asynchronous locator all show the same shared-resource direction.

E644 measured the physical compressed model data after another exact 845,926/s SDK-isolated run. Canonical E643 had
stored 80.6 MB global-event data and 34.5 MB model-stream data, versus 55.7 MB and 25.6 MB in E644. E645/E646 then
showed that the source command metadata visible to `@Apply` was empty in isolation and exactly
`$requestTimeout=200000`, `$publicationDepth=0` (59 encoded bytes) in canonical. That correlation did not justify
stripping observable metadata: E647 added those exact bytes to the isolated route and still achieved **826,359/s**,
0.967M/s active store capacity and only 90.8 MB total WAL. Compressed global-event data rose merely to 58.2 MB.
Consequently the two metadata fields are not the missing factor; the temporary benchmark option was removed and no
SDK/runtime production behavior changed.

### E648-E649: cross-batch overlap loses by fragmenting natural model batches

E648 tested the most direct use of the corrected bounded SDK `Backlog`: at most two model-store batches could be in
flight, disjoint packed targets received monotonic reserved state-index ranges, event inserts still ran on the
existing insert executor and the event store still committed in index order. Correctness was exact, and the profile
did show two insert workers. It nevertheless cut the mean transaction from 6,088 to 2,998 messages and more than
doubled physical model/event transactions from 689 to 1,399. Locally cheaper half-sized statements could not
amortize their extra state updates and commits; active model-store capacity fell from 0.984M/s to 0.533M/s.

E649 then tested a narrower stage handoff without a second free backlog slot. The ordered model backlog released its
next batch only after the current global-event insert and atomic model state/stream statement had completed, directly
before the current transaction entered its durable commit. The intent was to overlap only event insert N+1 with
commit N. That preserved more natural batching than E648, but still reduced the mean batch to 4,702 and increased
transactions to 892. The 1.29 ms mean commit window was too short: the next insert was ready in only about 3% of
commit-complete samples (`activeWorkers` 1.03), so the extra 203 commits outweighed the overlap.

| Measure | E641 accepted P5 | E648 two in flight | E649 commit handoff |
| --- | ---: | ---: | ---: |
| Exact SDK-inclusive throughput | **834,401/s** | 809,801/s | 819,577/s |
| Model/event transactions | **689** | 1,399 | 892 |
| Mean messages/transaction | **6,087.5** | 2,998.1 | 4,702.1 |
| Active model-store capacity | **0.984M/s** | 0.533M/s | 0.829M/s |
| Mean direct event insert | 1.641 ms | 1.278 ms | 1.504 ms |
| Mean atomic state/stream statement | 1.411 ms | 1.213 ms | 1.254 ms |
| Mean commit | **1.174 ms** | 1.200 ms | 1.289 ms |
| Insert workers ready at commit start | 1.00 | 1.27 | 1.00 |
| Code outcome | accepted base | reverted | reverted |

Both candidates were fully removed. The causal conclusion is stronger than a throughput comparison: in this
feedback loop, releasing a later model batch before current durability removes messages from the next naturally
amortized transaction. Future P6 work must reduce PostgreSQL/model cost within the same large transaction or reduce
shared command/result database contention; it must not reopen this batch-boundary mechanism without new evidence.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E648 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` / E641 | two disjoint model batches in flight | 4,194,304 | 262,144 | batch-only JFR | 834,401/s | 809,801/s | false | reject: transaction count +103%, active store -45.8% | reverted |
| E649 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` / E641 | release next batch at current commit start | 4,194,304 | 262,144 | batch-only JFR | 834,401/s | 819,577/s | false | reject: transaction count +29.5%, insufficient overlap | reverted |

### E650-E657: JDBC execution delay is not binding, socket wait, planning or PostgreSQL cache misses

E650/E651 repeated the SDK-inclusive isolated and complete canonical routes with a deliberately invasive full JFR that
enabled every `jdk.SocketRead` and `jdk.SocketWrite` event at zero threshold. Its throughput is therefore diagnostic,
not progress-comparable. The same global-event insert averaged 1.921 ms in isolation and 4.273 ms under command/result
co-tenancy. Correlating the socket events to that phase found only 22.58 ms across 625 isolated inserts (0.036 ms per
transaction) and 66.93 ms across 825 canonical inserts (0.081 ms per transaction); socket writes were negligible.
Network blocking therefore explains only about 0.045 ms of the 2.352 ms Java/JDBC gap.

E652/E653 next instrumented the three fundamental parts of the same prepared update, without enabling full JFR:

| Direct global-event insert | E652 SDK-isolated | E653 full canonical | Canonical increase |
| --- | ---: | ---: | ---: |
| Full route throughput | **800,845/s** | **410,010/s** | diagnostic route split |
| Model batches / mean size | 728 / 5,761 | 699 / 6,000 | comparable transaction shape |
| Prepared-statement open | 0.008 ms | 0.016 ms | +0.008 ms |
| Java parameter binding | 0.034 ms | 0.039 ms | +0.005 ms |
| `executeUpdate()` | **1.600 ms** | **4.228 ms** | **+2.628 ms** |
| Whole direct insert phase | 1.679 ms | 4.361 ms | +2.682 ms |
| PostgreSQL statement execution | 0.150 ms | 0.671 ms | +0.521 ms |
| Complete active model store | 6.176 ms / 0.933M/s | 11.586 ms / 0.518M/s | +87.6% time |

Parameter materialization is thus not the candidate: practically the entire amplification occurs inside the blocking
driver call. PostgreSQL reports part, but not all, of it as server execution. The remainder is driver/protocol/backend
scheduling time; E650/E651 prove it is not ordinary socket blocking above the kernel boundary.

E654/E655 enabled `pg_stat_statements.track_planning` for one matched diagnostic pair. Global-event planning averaged
0.034 ms in isolation and 0.056 ms in the canonical route. That 0.022 ms delta is immaterial beside the roughly 2.7 ms
whole-phase amplification. The server setting was restored to `off` immediately after the pair.

Finally E656 changed only PostgreSQL `shared_buffers` from 128 MiB to 1 GiB. Normal relation reads collapsed to two
blocks while relation hits rose above 2.1 million, proving the intended cache mechanism was active. Nevertheless the
exact canonical route reached 404,505/s versus 406,820/s in the immediately adjacent restored-128-MiB E657 control
(-0.57%); direct event insertion was 4.546 versus 4.591 ms and the active model store 11.872 versus 12.383 ms. The
former physical reads were already cheap operating-system/cache reads and were not the throughput limiter. PostgreSQL
was restored to 128 MiB and both Runtime repositories were returned to clean production source.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E650 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | zero-threshold socket trace | 4,194,304 | 262,144 | full JFR + socket I/O | n/a | 741,549/s | false | diagnostic: socket wait is negligible | diagnostic-only |
| E651 | profile | full command -> model -> event + result | P5 `0c23c91f` | zero-threshold socket trace | 4,194,304 | 262,144 | full JFR + socket I/O | n/a | 322,360/s | false | diagnostic: socket wait does not explain canonical insert gap | diagnostic-only |
| E652 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | split prepared update phases | 4,194,304 | 262,144 | batch-only JFR | n/a | 800,845/s | false | diagnostic: binding is negligible | diagnostic-only |
| E653 | profile | full command -> model -> event + result | P5 `0c23c91f` | split prepared update phases | 4,194,304 | 262,144 | batch-only JFR | n/a | 410,010/s | true | diagnostic: delay is concentrated in `executeUpdate` | diagnostic-only |
| E654 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | PostgreSQL planning trace | 4,194,304 | 262,144 | batch-only JFR + PG statistics | n/a | 821,171/s | false | diagnostic: 0.034 ms mean event planning | diagnostic-only |
| E655 | profile | full command -> model -> event + result | P5 `0c23c91f` | PostgreSQL planning trace | 4,194,304 | 262,144 | batch-only JFR + PG statistics | n/a | 410,445/s | true | reject planning optimization: 0.056 ms mean | diagnostic-only |
| E656 | profile | full command -> model -> event + result | P5 `0c23c91f` / E657 | PostgreSQL `shared_buffers=1GiB` | 4,194,304 | 262,144 | batch-only JFR + PG statistics | 406,820/s | 404,505/s | true | reject: -0.57% despite two physical relation reads | reverted |
| E657 | profile | full command -> model -> event + result | P5 `0c23c91f` | restored `shared_buffers=128MiB` control | 4,194,304 | 262,144 | batch-only JFR | 406,820/s | 406,820/s | true | accepted adjacent control; no code change | diagnostic-only |

### E658-E661: the canonical JDBC gap is blocking poll time, not Java execution

E658/E659 wrapped the direct global-event `executeUpdate()` call with current-thread CPU time while retaining the
same batch JFR. The temporary timer was removed immediately after the pair. It resolves the broad JDBC interval into
wall and actual Java CPU:

| Direct global-event insert | E658 SDK-inclusive isolated | E659 full canonical | Canonical increase |
| --- | ---: | ---: | ---: |
| Complete route | 831,639/s | 412,155/s | diagnostic route split |
| `executeUpdate()` wall mean | 1.632 ms | **4.173 ms** | **+2.541 ms** |
| current-thread CPU mean | 0.105 ms | 0.115 ms | +0.010 ms |
| CPU share of wall interval | 6.4% | **2.8%** | blocking dominates |

E660/E661 then used async-profiler 4.5 wall-clock sampling around only the measured phase. This corrected E650/E651:
zero-threshold JFR `SocketRead` does not include the time spent in
`NioSocketImpl.park -> Net.poll`. The canonical profile contained 2,494 native `poll` samples below Runtime data-source
work versus 382 in the SDK-isolated route. The three canonical JDBC commit threads contributed 2,318 samples
(result 1,125, event 639 and command 554), while the isolated event route contributed 774. The approximately 2.1
seconds of extra canonical data-source polling closely matches the independently timed event-insert amplification.

The causal conclusion is narrower than “network is slow”: Java encoding, parameter binding and on-CPU driver work are
not the cost. Under shared command/result/model load, JDBC workers spend the added time waiting below the socket API
that JFR had observed. E660/E661 are profiler diagnostics and not progress comparisons; the isolated profiler was
attached before the go signal and therefore also contains an idle prefix, but all PostgreSQL poll samples occur in the
measured interval.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E658 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | current-thread CPU split | 4,194,304 | 262,144 | batch-only JFR + CPU timer | n/a | 831,639/s | false | diagnostic: 0.105 ms CPU inside 1.632 ms wall | diagnostic-only |
| E659 | profile | full command -> model -> event + result | P5 `0c23c91f` | current-thread CPU split | 4,194,304 | 262,144 | batch-only JFR + CPU timer | n/a | 412,155/s | true | diagnostic: only 0.010 ms CPU of 2.541 ms canonical amplification | diagnostic-only |
| E660 | profile | full command -> model -> event + result | P5 `0c23c91f` | native wall sampling | 4,194,304 | 262,144 | async-profiler wall + batch JFR | n/a | 402,445/s | false | diagnostic: canonical JDBC wait is native socket poll | diagnostic-only |
| E661 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | native wall sampling | 4,194,304 | 262,144 | async-profiler wall + batch JFR | n/a | 788,498/s | false | diagnostic isolated comparison | diagnostic-only |

### E662-E672: a smaller shared JDBC executor is benchmark tuning, not P6

The wall profile showed all 32 shared insert-executor workers participating over the canonical interval. E662-E667
therefore screened 24 and 16 workers while preserving all commands, model work, global events, results, transactions
and synchronous commits. E664's 32-worker control is excluded because `fseventsd`, Contacts, ColorSync and Docker VM
work contaminated the host. E665 produced a valid 404,572/s batch profile but its PostgreSQL sampler failed to compile,
so its empty activity file is explicitly not evidence.

The valid E666/E667 activity pair observed only 17-21 non-idle PostgreSQL backends at a time. Dominant states were
completed transactions waiting for the Java client (`idle in transaction / ClientRead`), model-locator work and
WALSync/WALWrite. Sixteen workers improved that profiled pair by 1.79%, but the clean non-JFR screen disproved 16 as a
stable optimum:

| Run | Shared insert workers | Full E2E | Relation to adjacent/current 32 control |
| --- | ---: | ---: | ---: |
| E668 | 32 control | 420,700/s | control |
| E669 | 24 | **432,141/s** | +2.72% |
| E670 | 16 | 420,537/s | -0.04% |
| E671 | 32 control | 419,996/s | control |
| E672 | 24 | 422,830/s | +0.67% |

The two 32 controls average 420,348/s and the two 24-worker observations average 427,486/s: +1.70%, but with materially
different pair effects. A process-wide fixed pool of 24 is tuned to this 14-core laptop, tiny payload and single
PostgreSQL instance. It can reduce throughput for other core counts, databases or store mixes. The temporary pool-size
property was removed, Runtime was rebuilt from clean P5 source, and no production checkpoint was made. The useful
result is the co-tenancy mechanism: reducing simultaneous JDBC work can locally improve the event/model boundary, but
an arbitrary global pool size is not a representative solution.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E662 | profile | full command -> model -> event + result | P5 `0c23c91f` | shared insert workers 24 | 4,194,304 | 262,144 | batch-only JFR | n/a | 415,758/s | true | preliminary screen only | diagnostic-only |
| E663 | profile | full command -> model -> event + result | P5 `0c23c91f` | shared insert workers 16 | 4,194,304 | 262,144 | batch-only JFR | n/a | 412,656/s | true | preliminary screen only | diagnostic-only |
| E664 | profile | full command -> model -> event + result | P5 `0c23c91f` | shared insert workers 32 control | 4,194,304 | 262,144 | batch-only JFR | n/a | 380,762/s | false | exclude host contamination | diagnostic-only |
| E665 | profile | full command -> model -> event + result | P5 `0c23c91f` | 32-worker activity probe | 4,194,304 | 262,144 | batch-only JFR; sampler failed | n/a | 404,572/s | false | activity evidence invalid; throughput diagnostic only | diagnostic-only |
| E666 | profile | full command -> model -> event + result | P5 `0c23c91f` | shared insert workers 16 | 4,194,304 | 262,144 | batch-only JFR + PG activity | 407,714/s | 415,013/s | true | causal server-wait comparison, not acceptance | diagnostic-only |
| E667 | profile | full command -> model -> event + result | P5 `0c23c91f` | shared insert workers 32 control | 4,194,304 | 262,144 | batch-only JFR + PG activity | 407,714/s | 407,714/s | true | matched control | diagnostic-only |
| E668 | canonical | full command -> model -> event + result | P5 `0c23c91f` | shared insert workers 32 control | 4,194,304 | 262,144 | none | 420,700/s | 420,700/s | true | accepted source control | diagnostic-only |
| E669 | canonical | full command -> model -> event + result | P5 `0c23c91f` | shared insert workers 24 | 4,194,304 | 262,144 | none | 420,700/s | 432,141/s | true | promising first pair, continue | reverted |
| E670 | canonical | full command -> model -> event + result | P5 `0c23c91f` | shared insert workers 16 | 4,194,304 | 262,144 | none | 420,700/s | 420,537/s | true | reject 16: flat | reverted |
| E671 | canonical | full command -> model -> event + result | P5 `0c23c91f` | shared insert workers 32 control | 4,194,304 | 262,144 | none | 419,996/s | 419,996/s | true | accepted source control | diagnostic-only |
| E672 | canonical | full command -> model -> event + result | P5 `0c23c91f` | shared insert workers 24 | 4,194,304 | 262,144 | none | 419,996/s | 422,830/s | true | reject global tuning: variable +0.67%/+2.72% and workload risk | reverted |

### E673-E675: exact server statements and derived-locator admission

E673 sampled exact `pg_stat_activity` statement text every five milliseconds during the unchanged complete route.
The sampler itself reduced throughput to 412,336/s, so only its state distribution is evidence. Across 10,390
non-idle backend observations (6.347 mean, 18 max), the largest exact groups were:

| PostgreSQL state and last/current statement | Share |
| --- | ---: |
| idle in transaction after durable result-row insert | **16.78%** |
| idle in transaction after reading packed `model_stream` blocks for the derived locator | **11.16%** |
| idle in transaction after `BEGIN` | 6.71% |
| `COMMIT` in WALSync | **4.55%** |
| idle in transaction after durable command-row insert | 4.49% |
| event insert active on CPU / waiting ClientRead / completed in transaction | about **4.7%** combined |
| `COMMIT` waiting on WALWrite | 2.17% |

The idle result/command/event inserts are not an absent-backpressure bug: `JdbcMessageStore` deliberately prepares
physical inserts ahead of each store's ordered commit thread. That overlap is what E570/E571 proved useful. The
derived model-stream locator is also visible as a material concurrent workload: one source-block reader plus COPY
work spread over the configured partition lanes.

E674/E675 changed only the existing locator write-lane property from the accepted default four to two and one. Both
ran the entire non-JFR E2E route. At the profiler barrier immediately after the final ordinary result, an independent
JDBC probe found `located_state_index == last_state_index` in both runs; no locator debt was moved beyond the measured
window. Throughput was 423,261/s with two lanes and 424,439/s with one, only +0.69% and +0.97% against the fresh
E668/E671 control average. This simple model has one membership per event; reducing the production default would risk
slower locator catch-up for wider trees and multi-membership workloads without a convincing canonical gain. Four
lanes remain accepted P5.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E673 | profile | full command -> model -> event + result | P5 `0c23c91f` | exact PostgreSQL statement-state sampling | 4,194,304 | 262,144 | no JFR + 5 ms PG sampler | n/a | 412,336/s | false | diagnostic statement attribution | diagnostic-only |
| E674 | canonical | full command -> model -> event + result | P5 `0c23c91f` | locator write lanes 2 | 4,194,304 | 262,144 | none + post-route catch-up probe | 420,348/s | 423,261/s | true | reject: +0.69%, simple-model-only setting | reverted |
| E675 | canonical | full command -> model -> event + result | P5 `0c23c91f` | locator write lanes 1 | 4,194,304 | 262,144 | none + post-route catch-up probe | 420,348/s | 424,439/s | true | reject: +0.97%, insufficient for wider-workload risk | reverted |

### E676-E679: exclusive co-located insert admission removes useful overlap

E676-E679 tested the narrow follow-up to the canonical JDBC poll amplification. A temporary fair read/write admission
lock surrounded only the physical direct-LTS insert: ordinary command/result jobs retained parallel read admission,
while jobs with a co-located model callback received exclusive write admission. Commit ordering, futures, connections,
serialization and model-store code were unchanged. The intent was to let the global-event insert complete without
competing direct message inserts while preserving all work before and after that statement.

Two same-binary no-JFR pairs reject the mechanism. Controls averaged 430,490/s and candidates 413,703/s, a **3.90%**
loss. Both candidate runs were tightly grouped, and the reverse control was the fastest run. Correctness counts remained
exact at 4,194,304 ordinary results, model events and global events per run. The blocking JDBC gap is therefore real,
but excluding other inserts sacrifices useful PostgreSQL/worker overlap instead of converting that wait into route
capacity. The lock and property were removed completely and P5 was rebuilt clean.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E676 | canonical | full command -> model -> event + result | P5 `0c23c91f` | admission disabled, same candidate binary | 4,194,304 | 262,144 | none | 427,204/s | 427,204/s | true | pair-one control | diagnostic-only |
| E677 | canonical | full command -> model -> event + result | P5 `0c23c91f` | exclusive co-located direct-insert admission | 4,194,304 | 262,144 | none | 427,204/s | 414,808/s | true | reject: -2.90% | reverted |
| E678 | canonical | full command -> model -> event + result | P5 `0c23c91f` | exclusive co-located direct-insert admission | 4,194,304 | 262,144 | none | 433,775/s | 412,597/s | true | reject: -4.88% | reverted |
| E679 | canonical | full command -> model -> event + result | P5 `0c23c91f` | admission disabled, same candidate binary | 4,194,304 | 262,144 | none | 433,775/s | 433,775/s | true | reverse control | diagnostic-only |

### E680-E682: larger global-event blocks do not remove a storage boundary

E680-E682 returned to the SDK-inclusive isolated model route and tested whether the 32,912 physical global-event rows
seen in E641 represented avoidable boundary pressure. The accepted message store groups at most 128 logical messages
per compressed LTS row. A diagnostic process property raised that value to 256 without changing backlog collection,
transactions, commit ordering, the model store, event payloads or any route stage.

The candidate did not improve the route. Two 128 controls averaged 817,750/s and the 256 run achieved 816,160/s
(-0.19%). Code inspection explains the null result: each model/event job already sends its roughly 48 compressed rows
in one multi-value insert statement and one existing transaction. Doubling the row packing reduces rows and bound
parameters, but not insert round trips or commits. There is therefore no basis for a 512 screen or for accepting the
larger random-read/decompression unit. No source change was made.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E680 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | global-event group size 128 | 4,194,304 | 262,144 | none | 820,426/s | 820,426/s | false | diagnostic control | diagnostic-only |
| E681 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | global-event group size 256 | 4,194,304 | 262,144 | none | 817,750/s | 816,160/s | false | reject: -0.19% versus mean controls | diagnostic-only |
| E682 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | global-event group size 128 | 4,194,304 | 262,144 | none | 815,073/s | 815,073/s | false | reverse diagnostic control | diagnostic-only |

### E683-E693: remove synthetic source-index contention without changing timed capacity

E683-E685 profiled the complete SDK-inclusive isolated model route at the process level. E683's first profiler attach
missed the short measured window but completed exactly at 819,197/s. E684 extended only the measured command count to
16,777,216 and captured eight seconds of CPU samples. The largest Java leaf was
`AtomicLong.updateAndGet` at 5.84% of all samples. A stack-aware E685 capture then attributed 2,876 of 40,434 samples
(**7.113%**) specifically to `SdkModelCommitBenchmark.executeSyntheticTrackedModelApplies`: every parallel command
serializer contended on the same synthetic source-index atomic and called the clock inside its CAS update.

That preparation occurs before the benchmark starts each wave's handler/service timer, so removing it must not be
claimed as model-store throughput. The benchmark was changed to reserve one contiguous monotonic source-index range
per wave and assign `firstIndex + offset` in parallel. Besides removing contention, this makes synthetic command order
match a real command log instead of assigning indexes in parallel completion order.

Two same-binary no-JFR pairs placed the timed effect at noise: controls averaged 840,699/s and range reservation
839,571/s (-0.13%). E691's stack-aware candidate profile captured 24,994 CPU samples and **zero** through the old
benchmark atomic path, while exactly verifying 8,388,608 handler completions, model events and global events. Final
source-only qualification E692/E693 remained exact but ran in a later lower 809-811k system/batching state. Those
observations are retained rather than hidden, but they do not overturn the same-binary comparison because the changed
preparation is outside the reported timer. Runtime benchmark commit `b716a6ed` is accepted as a resource/stability
checkpoint, not as P6 or a higher model-capacity pin.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E683 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | profiler attach timing check | 4,194,304 | 262,144 | attach missed measured window | n/a | 819,197/s | false | exact run; no profile evidence | diagnostic-only |
| E684 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | long CPU leaf profile | 16,777,216 | 262,144 | async-profiler CPU, 8 s | n/a | 875,977/s | false | identify process CPU leaves | diagnostic-only |
| E685 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | stack-aware old index generator | 8,388,608 | 262,144 | async-profiler CPU collapsed, 5 s | n/a | 825,518/s | false | 7.113% samples in benchmark atomic | diagnostic-only |
| E686 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | direct range-reservation screen | 4,194,304 | 262,144 | none | prior old-binary cluster ~818k/s | 839,003/s | false | promising, require same-binary pairs | diagnostic-only |
| E687 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | same-binary per-command atomic control | 4,194,304 | 262,144 | none | 839,891/s | 839,891/s | false | pair-one control | diagnostic-only |
| E688 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | one source-index range per wave | 4,194,304 | 262,144 | none | 839,891/s | 841,557/s | false | +0.20%; timed-neutral | accepted |
| E689 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | one source-index range per wave | 4,194,304 | 262,144 | none | 841,507/s | 837,584/s | false | -0.47%; timed-neutral | accepted |
| E690 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | same-binary per-command atomic control | 4,194,304 | 262,144 | none | 841,507/s | 841,507/s | false | reverse control | diagnostic-only |
| E691 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | range-reservation CPU verification | 8,388,608 | 262,144 | async-profiler CPU collapsed, 5 s | n/a | 820,919/s | false | atomic path 7.113% -> 0%; exact | accepted |
| E692 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | final source-only range qualification | 4,194,304 | 262,144 | none | n/a | 811,117/s | false | exact lower-state observation; no throughput claim | accepted |
| E693 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` | clean-host final range qualification | 4,194,304 | 262,144 | none | n/a | 809,631/s | false | exact lower-state observation; investigate separately | accepted |

### E694-E696: current isolated store capacity and rejected extra idle collection delay

E694 traced the unchanged P5 production code plus the accepted synthetic range-reservation benchmark cleanup. It
completed exactly 4,194,304 handler calls, model events and global events at 812,491/s. The active packed-store
service capacity was **0.955M/s**: 754 transactions averaged 5,563 models and 5.827 ms active storage. This is the
current corresponding active-store measurement, but deliberately not a like-for-like route number, for E459's old
**0.407M/s** active-lane estimate. E459 used pre-P4/P5 code on the complete command/result-contended route; E694
includes the full SDK model handler but excludes command and ordinary-result logs. Neither number is an abstract
ceiling for every workload feeding one ordered store lane.

Relative to E641's higher 834,401/s state, E694's database leaves were not slower. Its event insert, packed
state/stream work and commit were all slightly faster, but it formed 754 rather than 689 transactions for the same
4,194,304 models. The current throughput gap is therefore transaction-shape/feedback variation, not a hidden
production-code regression.

E695 doubled only the caller window to 131,072 while retaining 65,536 models. That admitted two concurrent updates per
model. The second update could not name the not-yet-durable first update as its predecessor, so the packed contract
correctly failed and the whole batch entered the general load/conflict/idempotency path. Warm-up dropped to roughly
75,923/s, retained about 8.7 GiB RSS and spent its time reconstructing initial streams. The run was intentionally
terminated after a thread dump established the cause. It is an invalid capacity test, not evidence for or against a
larger caller window.

E696 changed only the existing idle-only model-backlog collection delay from 1 to 2 ms. It reduced SDK commit requests
57,880 -> 52,101 and packed transactions 754 -> 726, increasing mean transaction size 5,563 -> 5,777. Active store
capacity rose to **0.967M/s**, but route throughput improved only 0.53% to 816,815/s in one non-matched profiled pair.
The setting adds low-load latency and mainly compensates for the synthetic caller's burst/tail shape. It is rejected
without a canonical screen; the accepted one-millisecond default remains unchanged.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E694 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` + benchmark `b716a6ed` | current one-millisecond control | 4,194,304 | 262,144 | batch-only JFR | 812,491/s | 812,491/s | false | current lower-state capacity trace | diagnostic-only |
| E695 | smoke | synthetic tracked SDK apply -> model/event durability | same | caller window 131,072 with only 65,536 models | 4,194,304 planned | 262,144 | thread dump | n/a | invalid | false | terminate: concurrent same-model updates changed store semantics | diagnostic-only |
| E696 | profile | synthetic tracked SDK apply -> model/event durability | same | idle collection delay 2 ms | 4,194,304 | 262,144 | batch-only JFR | 812,491/s | 816,815/s | false | reject: +0.53%, artificial idle latency and non-canonical burst compensation | diagnostic-only |

### E697-E699: tracked public `assertAndApply` preserves correctness but fragments batching

The original E489-E492 direct API route had no tracked source message, so Runtime correctly selected
`commit-general`. E697-E699 answer the narrower proposal to begin with real serialized commands, activate each
deserializing command context and invoke `Fluxzero.assertAndApply(command)` without first appending the command or
later storing an ordinary result. The benchmark uses Java 25 virtual threads because the public method deliberately
blocks until the model/global-event commit is durable.

All three runs retained exact model/global-event counts, and E698 selected `commit-packed-update` for all 1,048,576
updates. The real command context therefore solves the idempotency-evidence problem. It does not make the route
equivalent to the default automatic handler:

| Measured shape | E694 automatic handler | E698 tracked public call | Direct-call amplification |
| --- | ---: | ---: | ---: |
| Measured models | 4,194,304 | 1,048,576 | normalized below |
| SDK `commit-models` requests | 57,880; 72.5 models/request | **108,949; 9.6 models/request** | **7.5x more requests/model** |
| Packed Runtime transactions | 754; 5,563 models/transaction | **767; 1,367 models/transaction** | **4.1x more transactions/model** |
| Active packed-store service capacity | **0.955M/s** | **0.315M/s** | 67.0% lower |
| SDK-inclusive route throughput | **812,491/s** | **278,239/s** with JFR / **283,002/s** clean | about 65% lower |

`Fluxzero.assertAndApply` bypasses ordinary command handlers/decorators and waits for true durability before every
call returns. Even with many virtual callers, that feedback exposes the SDK commit backlog to many tiny ready groups;
the Runtime can fuse some of them, but not enough to recover the automatic handler's batch shape. The existing
synthetic tracked route remains the representative SDK-inclusive model-capacity diagnostic because it preserves lazy
decode, handler resolution, assertions/interceptors, automatic `@Apply`, asynchronous commit policy and batch
coordination. The new public-call mode remains useful for explicit-handler/API performance, not for choosing P6.
Runtime benchmark commit `1ac27472` retains that isolated public-API diagnostic; production Runtime code remains P5.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E697 | smoke | tracked command context -> public `Fluxzero.assertAndApply` -> model/event durability | P5 `0c23c91f` | initial exact route | 131,072 | 65,536 | none | n/a | 303,866/s | false | route works; require sustained/profile evidence | diagnostic-only |
| E698 | profile | tracked command context -> public `Fluxzero.assertAndApply` -> model/event durability | same | sustained batch anatomy | 1,048,576 | 262,144 | batch-only JFR | n/a | 278,239/s | false | packed path confirmed; blocking API fragments transport/store batches | diagnostic-only |
| E699 | profile | tracked command context -> public `Fluxzero.assertAndApply` -> model/event durability | same | clean sustained confirmation | 1,048,576 | 262,144 | none | n/a | 283,002/s | false | retain benchmark as public-API diagnostic, not a P6 candidate | accepted |
| E700 | smoke | synthetic tracked SDK apply -> model/event durability | same | shared command-preparation regression smoke | 131,072 | 65,536 | none | n/a | 470,629/s | false | exact functional smoke only; two measured waves are not a capacity qualification | accepted |

### E701-E703: full SDK-handler trace and rejected independent-lane driver

E701 retained the complete synthetic tracked SDK handler and added deterministic end-to-end request stages to 1,024
of 4,194,304 exact updates. Full profiling reduced observed throughput to 761,936/s, so the throughput value is not a
replacement pin. The trace is useful because it separates the actual per-request work from scheduling, durability and
the deliberately overlapping batch policy:

| Fundamental segment | Exact boundary | mean | p50 | p95 | p99 | max |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Handler admission | registered -> model evaluation start | 0.008 ms | 0.002 | 0.011 | 0.341 | 0.467 |
| Model evaluation | evaluation start -> evaluation complete | 0.029 ms | 0.002 | 0.246 | 0.414 | 0.549 |
| Commit handoff | evaluation complete -> preparation start | 0.014 ms | 0.001 | 0.014 | 0.736 | 0.880 |
| Commit preparation | preparation start -> preparation complete | 0.007 ms | 0.002 | 0.010 | 0.171 | 0.387 |
| Dispatch scheduling | preparation complete -> dispatch start | 0.002 ms | 0.000 | 0.002 | 0.067 | 0.271 |
| Synchronous dispatch | dispatch start -> dispatched | 0.002 ms | 0.000 | 0.002 | 0.041 | 0.188 |
| SDK commit-backlog scheduling | dispatched -> transport encoding start | **2.830 ms** | 2.486 | 5.280 | 9.326 | 25.507 |
| Transport encode/compress | encoding start -> SDK send complete | 0.103 ms | 0.068 | 0.228 | 0.512 | 3.543 |
| Wire plus Runtime decode | SDK send complete -> Runtime request received | 0.117 ms | 0.079 | 0.239 | 1.125 | 2.725 |
| Runtime intake | request received -> model store enqueued | 0.094 ms | 0.058 | 0.204 | 1.027 | 3.391 |
| Runtime model-store queue | store enqueued -> store start | **2.248 ms** | 1.644 | 5.386 | 10.946 | 17.701 |
| Model/event durability | store start -> durable | **5.927 ms** | 5.463 | 12.794 | 16.872 | 16.992 |
| Runtime post-durable work | durable -> store complete | 0.479 ms | 0.364 | 1.643 | 2.058 | 2.436 |
| Runtime response handoff | store complete -> response queued | 0.011 ms | 0.007 | 0.028 | 0.076 | 0.182 |
| Runtime response queue | response queued -> send start | 0.250 ms | 0.119 | 0.933 | 3.191 | 4.417 |
| Response encode | send start -> encode complete | 0.182 ms | 0.103 | 0.571 | 1.531 | 2.957 |
| Runtime socket send | encode complete -> send complete | 0.039 ms | 0.027 | 0.095 | 0.251 | 0.421 |
| Wire plus SDK decode/context | Runtime send complete -> SDK context restored | **1.026 ms** | 0.697 | 2.672 | 4.436 | 28.848 |
| SDK result preparation entry | context restored -> result preparation start | 0.036 ms | 0.023 | 0.106 | 0.187 | 0.562 |
| SDK response processing | result preparation start -> commit response received | 0.101 ms | 0.048 | 0.345 | 0.754 | 3.204 |
| SDK request matching | response received -> result matched | 0.000 ms | 0.000 | 0.001 | 0.002 | 0.014 |
| SDK result match handoff | result matched -> post-commit start | 0.165 ms | 0.075 | 0.579 | 1.379 | 4.367 |
| SDK post-commit | post-commit start -> post-commit complete | **0.851 ms** | 0.536 | 2.410 | 4.630 | 8.966 |
| Result preparation finish | post-commit complete -> result preparation complete | 0.063 ms | 0.029 | 0.243 | 0.493 | 0.853 |
| Callback admission | result preparation complete -> callback queued | 0.034 ms | 0.021 | 0.102 | 0.171 | 0.314 |
| Callback queue | callback queued -> callback start | 0.085 ms | 0.044 | 0.302 | 0.498 | 1.299 |
| Callback execution | callback start -> model execution complete | 0.020 ms | 0.012 | 0.064 | 0.105 | 0.726 |
| Result callback finish | model execution complete -> result callback complete | 0.026 ms | 0.023 | 0.068 | 0.126 | 0.300 |

The intervals above are fundamental and sequential only along one sampled request. The following intervals are
composites or deliberate batch effects and must not be added to that table:

| Composite interval | Components / exact meaning | mean | p50 | p95 | p99 | max |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| SDK transport plus Runtime admission | SDK backlog scheduling + encode + wire/decode + Runtime intake/queue | 5.392 ms | n/a | n/a | n/a | n/a |
| Durable model boundary | Runtime queue + model/event durability | 8.175 ms | n/a | n/a | n/a | n/a |
| Post-commit return path | Runtime post-durable + response transport/queues + SDK match/post-commit | 3.342 ms | n/a | n/a | n/a | n/a |
| Handler batch tail | one handler completed its execution -> all sibling handlers reach the await-after-batch barrier | **66.043 ms** | n/a | n/a | n/a | n/a |

The trace therefore rejects SDK evaluation, preparation and envelope encoding as the next large target. It identifies
commit-backlog scheduling, Runtime queueing, actual durability and SDK post-commit as material fundamental segments.
The 66 ms batch tail is not handler compute or a separately removable sleep: the benchmark submits a 65,536-command
wave as sixteen 4,096-command handler tasks and waits for the complete wave, matching the current
`ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH` batch contract.

E702 established a same-binary, short clean control at 765,166/s with exactly 1,048,576 durable handler completions,
model events and global events. E703 tested a diagnostic driver with sixteen persistent lanes, each advancing to its
next 4,096-model slice immediately after its own future completed. That created non-contiguous source-index progress:
one lane advanced by 65,536 while the other fifteen intervening source ranges were still active. It changed the
ordered tracking/commit feedback, reached only 66,905/s, and is not representative of a real contiguous command
batch. The driver was removed immediately. This result neither rejects independent real clients nor changes the P5
production conclusion; it only prevents the synthetic benchmark from silently measuring an invalid scheduling model.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E701 | profile | synthetic tracked SDK apply -> model/event durability | P5 `0c23c91f` + benchmark `1ac27472` | deterministic complete request-stage trace | 4,194,304 | 262,144 | full JFR | n/a | 761,936/s | false | retain trace; evaluation/encoding are minor, scheduling/durability/post-commit are material | diagnostic-only |
| E702 | profile | synthetic tracked SDK apply -> model/event durability | same | ordinary contiguous-wave control on candidate binary | 1,048,576 | 262,144 | none | 765,166/s | 765,166/s | false | exact short adjacent control | diagnostic-only |
| E703 | profile | synthetic tracked SDK apply -> model/event durability | same | sixteen persistent non-contiguous source lanes | 1,048,576 | 262,144 | none | 765,166/s | 66,905/s | false | reject invalid scheduling model; source-index progress no longer resembles a command batch | reverted |

## Current decision

1. Runtime `0c23c91f` is the accepted P5 checkpoint on top of P4 `c98f47e6`. Four locator write lanes retain parallel
   inserts while reducing total transaction pressure; matched no-JFR E2E improves +1.29%, with a best observation of
   426,108/s.
2. Use E597/E598 as the current matched long storage profiles. The ordered model/event store shows 0.539M/s active
   capacity at P5 versus 0.502M/s in the same-binary eight-lane control; these derived rates vary with natural
   transaction shape and are not E2E ceilings.
3. Keep the low-level SDK update route as a fast secondary physical/wire check, never as the 500k acceptance gate.
4. Keep both direct `assertAndApply` variants separate from the automatic model-capacity route. Without a tracked
   context it correctly selects the general idempotent path; with a real tracked command it selects the packed path
   but the blocking durability contract creates 7.5x more SDK requests and 4.1x more Runtime transactions per model.
5. Preserve parallel locator partition writes; the single-transaction alternative is causally rejected at -5.88%.
6. Preserve binary COPY as the general and checked fallback. The P4 typed-array statement is restricted to the packed
   no-type-registration path and is accepted because it removes state boundaries as one atomic unit.
7. Treat E550's 408,185/s and E562's 421,981/s as demonstrated high states, not standalone stable pins. Matched
   comparisons remain the progress evidence.
8. Do not use the old 0.407M active-lane estimate as an E2E ceiling. E589 proves 0.995M/s active model-store capacity
   and 0.846M/s SDK-inclusive model throughput when command/result logs are absent.
9. Use the E589/E591/E571 route split and E592/E599/E600 PostgreSQL statistics when selecting P6. The current loss is
   shared database/WAL contention that slows every model boundary, not a saturated connection pool or fixed SDK
   handler ceiling. Validate one complete mechanism before changing production code.
10. Accept production work only through matched full command -> model -> event + result runs with correctness and read
   validation proportional to the affected path.
11. Do not replace the conditional event-block multi-value insert with binary COPY at the current physical row shape;
    E568/E569 causally rejects it at -2.29% E2E and +50.9% direct-insert time.
12. Do not fuse the direct global-event insert into the ordered P4 model statement. E570/E571 causally rejects it at
    -2.31% E2E because it removes existing insert parallelism, produces 26.3% more jobs and reduces mean job size by
    20.8%.
13. Do not select statement-shape caching as P5: E572 measures only 0.040 ms mean planning against a 4.060 ms direct
    event-insert boundary. Investigate measured SDK/model CPU and allocation pressure around the JDBC scheduling gap.
14. Do not retain direct SDK event-envelope encoding: E573-E576 measure only +0.61% to +0.69% complete-route gain for
    materially duplicated envelope code. Treat the envelope allocation as secondary and return to the ordered
    model/event-store boundary.
15. Do not hold SDK model-commit backlog slots until durable completion. E581-E583 show -0.73% complete-route effect
    and slightly lower active store capacity because the limit shifts work from both the smallest and the largest jobs
    into medium jobs. Preserve the existing overlap and optimize measured fixed store work instead.
16. Use E606 as the detailed P5 stage baseline. The largest synchronous fundamental stages are direct event insertion,
    the packed state/stream statement and the event transaction commit. The derived locator remains a material shared-
    resource consumer, but its intervals overlap canonical work and cannot be added to model transaction latency.
17. Do not fuse a locator write lane into the cursor transaction using the tested coordinator-lane implementation.
    E607-E610 reject it at -1.70% matched E2E because natural model dispatches shrink by roughly 5-14%.
18. Do not eagerly retain all eight partitioned locator-hash arrays per source row. E611-E614 reject that allocation
    optimization at -2.28% matched E2E. Allocation profiles remain supporting evidence, not a production acceptance
    criterion.
19. Treat SDK accepted-result post-processing as material secondary model work: E616 measures 1.157 microseconds per
    command, dominated by repository/cache and tracker publication, but about 0.864M/s isolated headroom means it is
    not the first 500k limiter.
20. Do not increase global-event compressed row groups from 128 to 256 for the measured payload. E620/E621 halve rows
    but reject the change at -3.95% full E2E and +4.7% active model-store time.
21. Preserve the one-millisecond ordered model-backlog collection delay. E622-E625 reject `PT0S` at -0.85% matched
    full E2E. It is a batching input, not one millisecond that can simply be subtracted from store service time.
22. Do not replace `TrackModelUpdatesResult` generic CBOR with the tested manual streaming codec. E626/E627 prove the
    local codec and synthetic route can improve, but the fully compatible final E632-E635 sequence rejects it at
    -2.26% full E2E. Faster per-page work changed tracking feedback and page formation; canonical E2E remains truth.
23. Use E636 as the fresh classified full-route trace. Model acknowledgement encode/decode is sub-millisecond per
    large batch, while direct global-event insert, atomic state/stream work and the shared commit form about 79% of
    the active ordered-store interval. Keep the complete SDK handler in all isolated model measurements, but select
    the next production candidate from this measured database boundary.
24. Do not test asynchronous locator lane commits merely because commit latency is visible. E637 splits the locator
    and shows 9.289 ms mean per-lane COPY versus 1.409 ms commit. Preserve commit semantics and first investigate
    eliminating the second COPY startup per lane without reducing parallelism or adding a wait.
25. Do not retain the tested partition-routing parent solely to remove the second locator COPY startup. E640 improves
    per-lane COPY by 31.9% and a whole asynchronous locator round by 24.9%, but leaves the complete route effectively
    flat at -0.33%, creates 15.6% more locator jobs and introduces migration complexity. Return candidate selection to
    the synchronous global-event insert, packed state/stream statement and shared commit boundary.
26. Use E642/E643 as the current co-tenancy proof. With nearly equal model batches, command/result durability makes
    the same event insert 4.5x slower inside PostgreSQL, the state/stream statement 49% slower, commit 78% slower and
    the complete active model store 84% slower. Preserve event-insert overlap and investigate model-boundary
    scheduling/resource contention rather than another isolated SDK micro-optimization.
27. Do not strip `$requestTimeout` or `$publicationDepth` from model events for performance. E645-E647 prove those
    two fields account for only about 3.4 MB extra WAL and no meaningful isolated throughput loss; metadata propagation
    remains an observable contract and is not the current limiter.
28. Do not overlap complete model batches or release the ordered model backlog at commit start. E648/E649 prove both
    variants reduce natural transaction size and lose SDK-inclusive throughput despite locally cheaper statements.
    Keep one durability-sized model batch and optimize within that transaction or the shared PostgreSQL workload.
29. Keep the SDK side in every representative isolated model run. The synthetic tracked route starts with a serialized
    command envelope and retains lazy decode, handler resolution, assertions/interceptors, automatic `@Apply`, model
    event encoding, SDK batching and WebSocket transport. Only command append/tracking and ordinary result storage are
    excluded; low-level `CommitModels` remains a secondary storage/wire diagnostic.
30. Do not target global-event Java parameter binding or normal socket blocking. E650-E653 locate only 0.039 ms in
    canonical binding and 0.081 ms in phase-correlated socket reads, while `executeUpdate()` itself takes 4.228 ms.
31. Do not pursue prepared-statement caching/planning for P6. E654/E655 measure only a 0.022 ms isolated-to-canonical
    planning delta and independently confirm E572's earlier conclusion.
32. Preserve PostgreSQL `shared_buffers=128MiB` for this campaign host. E656/E657 eliminate practically all physical
    relation reads at 1 GiB without improving canonical throughput, so buffer misses are not the model limiter.
33. Correct the earlier JFR socket interpretation with E658-E661. The canonical event insert spends only 0.115 ms on
    its Java thread but 4.173 ms wall-clock; async-profiler places the missing time in native socket poll below the JFR
    `SocketRead` interval. Do not target event encoding, binding or Java execution for this gap.
34. Do not change the process-wide shared JDBC executor from 32 to a laptop-specific 24. E668-E672 average +1.70% but
    vary from +0.67% to +2.72%, and 16 is flat. Preserve the default while investigating model-aware admission that
    retains available parallelism for other store mixes.
35. Preserve the accepted four derived-locator write lanes. E674/E675 fully catch up before the result boundary but
    improve this one-membership benchmark by less than 1%; that does not justify risking wider-tree catch-up capacity.
36. Interpret `idle in transaction` correctly. E673 shows completed result/command/event inserts waiting for their
    ordered commit threads; this is intentional ahead-of-commit overlap, not evidence to serialize job creation.
37. Do not rediscover model/event SQL fusion from the new sequential timing table. E570/E571 already implemented and
    rejected that exact mechanism because it removes ahead-of-commit insert overlap and shrinks natural batches.
38. Do not prioritize co-located model/event inserts by excluding ordinary direct message inserts. E676-E679 lose
    3.90% across two canonical pairs. Preserve insert overlap and seek lower transaction/round-trip cost or better
    parallel progress without serializing otherwise independent physical inserts.
39. Keep 128 logical messages per compressed message-store row. E680-E682 show that 256 is flat on the SDK-inclusive
    model route because rows within a job already share one multi-value statement and transaction; physical row count
    is not transaction or round-trip count.
40. Use one contiguous synthetic command source-index range per benchmark wave. E685/E691 remove a 7.113% process-CPU
    artifact while same-binary E687-E690 keep the timed SDK/model capacity neutral. Do not count this benchmark-only
    cleanup as P6 or use E684's longer-run 875,977/s as a canonical pin.
41. Treat E694's 0.955M/s and E696's 0.967M/s as the current isolated active-store range. The old 0.407M/s E459
    estimate was older code under complete command/result PostgreSQL contention, not a contradiction or fixed lane
    ceiling.
42. Do not raise the one-millisecond idle collection delay to two milliseconds from E696. It changes synthetic tail
    batching for only +0.53% in one non-matched profile and adds low-load latency; production remains unchanged.
43. Use the tracked public `assertAndApply` mode only to observe explicit blocking delegation. E697-E699 prove exact
    packed correctness, but durability-per-call fragments SDK and Runtime batches and reaches only 283,002/s clean.
44. Use E701's fundamental request stages to select further SDK-inclusive work. Model evaluation, preparation and
    encode/compress together are only 0.139 ms mean; commit-backlog scheduling (2.830 ms), Runtime queueing (2.248 ms),
    durability (5.927 ms) and SDK post-commit (0.851 ms) are the material boundaries. Do not add the 66.043 ms sibling
    batch-tail composite to these sequential stages.
45. Do not replace the contiguous synthetic handler wave with E703's persistent source-partition lanes. They advance
    source indexes non-contiguously, alter the ordered feedback and lose 91.3% against the adjacent short control. The
    experiment was a benchmark-driver diagnostic and was fully removed; production remains P5.

## Evidence

- E483 log: `/private/tmp/model-e2e-e483-integrated-model-store-qualification.log`, SHA-256
  `0c019327a486eded08179c903d65502b831167f03b3525775cb8d24a57f0ac69`.
- E484 log: `/private/tmp/model-e2e-e484-sdk-model-commit-only-qualification.log`, SHA-256
  `6c04859f124d08ace85f8dbc962c437f056e38a843d060ad4d32d86d97816074`.
- E485 launch-failure log: `/private/tmp/model-e2e-e485-sdk-model-commit-only-profile.log`, SHA-256
  `40f5ae714df16849b11ba99de63f219b87db2f9d729f6d740b72b2a71542d07d`.
- E486 log/JFR/summary SHA-256: `eddc5fc32bc3652bd2aea85c80e52358f4678fa9d650330bb8469a696bf67aa1`,
  `1dc2c3b17e446ec61370052f6a8dd382f19d215bd13cdb8de780d464b7757fea`,
  `c00079791800678e7bbe7a1ccaaba624394fcaeb83b6d0cf09869d1ea26ec8ef`.
- E487 log: `/private/tmp/model-e2e-e487-sdk-model-update-only-qualification.log`, SHA-256
  `b24ff8f4a0c356904db7a04325fa0e25af84edcb8ac03249bb2991726b5f5f12`.
- E488 log/JFR/summary SHA-256: `97fe6c163508766540481be1501b4654a995f6c4825843b1870a24ed7a037bc1`,
  `102e0628894ac8b29e2d45fb0f795574882225cacf5d3758e7fc04777ad12465`,
  `89fc6890704b99aee01a4380bcee56dbc634b885c0a95c6c19e76422918b314d`.
- E489/E490 logs SHA-256: `4382f36323eb281cc5b60a2c0d8b5707da936e4b52e490809ea18d855341a865`,
  `5d05d9574caf3b4da375fe44c0d50654db6af819f1e12e883a50d039c4f562ac`.
- E491 log/JFR/summary SHA-256: `86e7c6a1b41e4fbf9dcae4a25b6c3b40cb5efb6b0b0ae8bef5850c6983d1f2cc`,
  `35315e0e66d722f2f65cecfe6faf05d3eacea65be930eb34e07f6da591e718eb`,
  `1aa6ca48d0aae84169c4933c24ec3e2827cbf412703acf011355c90a2336a9f5`.
- E492 log: `/private/tmp/model-e2e-e492-sdk-assert-apply-packed-diagnostic.log`, SHA-256
  `d0657567d0bd4226a246e56dbdc36be266d786e1c83de34e6895b6f1612e77d5`.
- E493-E497 log SHA-256: `4e4fc7b19387844eaca026095a5a4735b5e0c0be2b8604e4ee32ed73eda4c601`,
  `cf1b1596b5f884090e0c948636205a95bc03fc289b60b01f87424343325c117a`,
  `3f289ac9ce8e0f3b6d79c2f067ca1020d089d6805bfc35e4bda8553528ad3ee3`,
  `a019e7b5dbd307cc16c9df16ee3d0d57a782dcc108d2f933ca03e9f2cf43f6f6`,
  `37d6cfae23fa2483ae0372b8c8139c1db142c8636dd4444ac28f8abbc6a2633d`.
- E498 log/JFR/summary SHA-256: `6429526f0b4736e88aba77ad424678ad6a8c2265d730965ddc09229c2482aa73`,
  `3d41384c8f1178643d237d9c6e32cd151aba58ae48616be4902822302a14db07`,
  `8a0e0073d1c8a4f88b20e21c6a71e4ac3280bfe0a4a8c564be7b89972f55c8d5`.
- E499/E500 log SHA-256: `35f21e414a8bcdf59224fdb17383ef0b4b470debd64ff71f17f4a4b6a681e57c`,
  `166f570d55bea5896a83a2ed3052de5c028318b1005be1d22fc490292c0e1eea`.
- E501 log/JFR/summary SHA-256: `a5d59d5839c9407b79c69af5562fd781a5c62dcdcb1a086062e8e2d14f28347e`,
  `ca2a2922a6af1629fd00af9d4f0417c3fe322e148b39ae3fc4998923a6a5beed`,
  `116b22dd17596f57fdd9004a0e4d287cbaeb48024325bea160b6e7f60f7c5882`.
- E502/E503 log SHA-256: `a90a8940a1b18d06091582c63f366ba8ccd3530eb88b3d6566686acafa655f0e`,
  `5c91a28e855d63871461836843c4cf29d3048bfd54ecbf27f00d18998067039b`.
- E504-E507 clean log SHA-256: `0295b2d23e694976059ea05e42f62b220eaaecdfede24601a49cab1f473db592`,
  `65fdd781ff5bb9c6ac328e5cd29f9c6634859095b331f359069bf87e2c3d6d6c`,
  `d9e8d4f1971314405f8cb2ccfa4890c141f592599bf3898c2809c21ddc6dd266`,
  `dda0ea3421eff57f9e09191d69b12bf11859fd74207df3d6891dce90b3fdf62c`.
- E508 log/JFR/summary SHA-256: `47ee9e723ee1376851530081c7c46f3418e0916fbee98391698f06253f7e2da2`,
  `7c43b8f99234c02a5f746463a19c8cb7dd13f5e08b5c91da68386ebfbdbaf3ff`,
  `004bcf04a3b4a4bf5cf7c4ce64353c16ca356b48775e50a08399ccc56fddff70`.
- E509 log/JFR/summary SHA-256: `8e9ffa90a9fad5b72a84b1484c68cf2e716e1860d23df627ebed0b51f31ea02d`,
  `4050fdd45333aa688a98d2064936b79076f895b4c4dc5811235bd2f662fe871c`,
  `78149b27d6bc10633f5e94f91614a103d3341815a9bc98271b78f8ec418c4abc`.
- E510 log/JFR/summary SHA-256: `fe740e71450e5a5b08a15d2de2a7072c0aad6dba3c9e743a633bcf5ffa3beb0c`,
  `48b246ea92c68c01735269bc7115705e03485be30e617d1ac85a8af9a8a0f5ca`,
  `b3c4e71a95b54a9cbce4ab2f4e3d3d0437f6bf420e6687ec66ca588dc7d49902`.
- E511 log/JFR/summary SHA-256: `34859a9395a4d92bd3da43320dc5b8b07f6275cf581a2f7b299de82f08546b4c`,
  `dcb9d9ffd136bf140e973dd63dfea762a51b7abfa339dd020953b9af78003ea9`,
  `4c0e18c1393f1cfc05c50a684de455c0bb8588c9b28d6af5e5f71ea591121ba0`.
- E512-E519 log SHA-256: `616959fc25055ebccbba5555ce04cd67399bc872e460fe8b7adce73c79949c6b`,
  `051565d01da5a5e164e95df58070778150c8733c8977a908bcd06601eb89d7c6`,
  `a47cdb5aa3ad82ada81eef4c2c187545faa2639816e5f6a2e50f68fe1ab41283`,
  `b800d8cc70a260e02d37ba3cac8508f22162940cf7442afa326efb879dc9373d`,
  `644fef72b7c9a934714c3d11e34b7628f2fa23a21b7354d6be2dc957881dc04b`,
  `5d400307d4c327154f7df3e0872b290fd8fa25dc01a17f330a19ae30b9dbdd24`,
  `78ad851ac59f7fa8eb46ded6bc3c7e3b716fbef0fa73f895d9157746a48933b7`,
  `394b7c19c0ddad1d939e4fa09a93c24b6a67f2ad9e4533b8431eee5dfc04b845`.
- E520-E527 log SHA-256: `1e666110f64bc0652871c20ce4310c26b864af9b1cde41e6437bec270d6b658c`,
  `652b2217a272213fec5e7884dce36d5abfc429a003aa90b8745af3592a412002`,
  `d1a0d132d055c8d47d20ec4a73b3034608e49b6c6d2d426c4f089acb690a9d42`,
  `474df847a7f9d5468b1b5bc8dd98d9aa6576a3b89f3a1d7fd859df4f2ecec1b3`,
  `a30df0639f940eb93149471138ab82b4d29091a4300d38a4ba81ed5aed41fd7d`,
  `a669de4913eb04e5d0e655cea9a50c6fdea3b2a8f9f41731e0c92922adef9a52`,
  `10ce90f5c49b45982619ce76c0c703efdaeed0b6f944e84b51b94a41289dbf1a`,
  `8fabdbf8715d1fb8606c2792b19e7cc4d29b1146302d4c76f4b137ac7b134d11`.
- E528-E530 log SHA-256: `3a65b26d5e20462f672869bcb7bcd2fa7df9cee9f7c1d09bfdfc7d2f82202bd4`,
  `627875b3c2acbb82c437ef3da5c2560d3237c0a3333c1c26445d6f21189c09be`,
  `5d082695e3c6dd7897435da1a6521d953648480fa3ed05f5348d39fcc2980045`.
- E531 log/JFR/summary SHA-256: `5a863492fc6f322accbec758d659f10fda264580f1783aeaca1cf318a6441946`,
  `f1a0a910b8e58c853257633fbdc1a5e2d7bce69f68c0abb38c50b33cdf94aee7`,
  `0980b2ec6d59d446c5e948304d14b97c8317005c09c9a3e7a32914277acb0f61`.
- E532 log/JFR/summary SHA-256: `9f6d176b6e7340d7d6b441eafd008bcad77f4105528a6b85d5d1db38ae2cb0e2`,
  `196ba3afd02f9397e54c26901d048cfe46b07c76c767e91966a45223cf7ade20`,
  `d4edfdf304e7dab91e326bf4b1e81e4611ff7ce837f738a62a0ca91290d2275b`.
- E533/E534 log SHA-256: `c76451ed6fb4935a963601e7a2ba659149ad10306de7099fd7a7f5e5d5133d95`,
  `70be14ca9b61a8f6e32dd08e09e59a32ca255e0cffa57b47db2718da840e3ff1`.
- E535/E536 log SHA-256: `f9d17a0e65cc250c561cf640e575982a11f8fb9ebe0fb2456fa386b048f6d222`,
  `a3c71258ab838952c161b442ab366d35288428dfa33f81957f1051ad3332b721`.
- E537 log/JFR/summary SHA-256: `419dad97523a29cf960ccc59696d94e1ce2771912c2df38ecc8646680d5f438a`,
  `7c6ee0e6ed36013941ee7b1af706310ec6d5a380793afaf431499395377e0a56`,
  `e4dd36c100499df8efa9fda76e1814f678d6af21d9654fc342259bf3930fe380`.
- E538 log/JFR/summary SHA-256: `31d442be518f41a5502094a17d4b48350b9a4f4ad5a31dcd09881cd11953a7c9`,
  `afd7913bee41cd5dfd38214ed533a7e60542b999bd73b5e483c883c8ef78bbbb`,
  `ae705c61b7e21c3abe2252f287874356f4693e56d6e11031e174bea4813c3ce7`.
- E539/E540 log SHA-256: `97cd9e25b0363fb2c916a5077a82b64f9f5f65e341e06bfb832255ed0fd45ccb`,
  `c5c4f90d908c8dc487dc6b069fc3d068574c915d665a6a6ad27e62f0ff55b742`.
- E541/E542 log SHA-256: `93692e69ea6bbc0c7357470c3d7dd2c8f94f7e609e158222476a42da218aa7c6`,
  `4b7d4ad017258d0d3f0b4a02c4931ad63e6a351f6e969ec4347341d38f6b47e0`.
- E543 log/JFR/summary SHA-256: `72ffc6b2da4157199d3ee4e5d0affa8a22052c4bec9589e6b42c8de42deee382`,
  `de960d68245991bcc164b28cebf3236440630494b2e16a0c148bf6155c0f5392`,
  `32800752ed908f0530df1990be6b4a254d5290c2b172e0ea504bc97ca9ea982d`.
- E544 log/JFR/summary SHA-256: `e0dd66ed887eccef5dd989cbdb2ffc414bc93ecd4a24ff69c35da52fa2360ccb`,
  `e22cc4384f1c28046d5cc45677bce9ebc9acfa660eee8d1808d7d431b4b821ac`,
  `563aa99c8adaad9fbe6b1c4f0e7b610be3894bd06b84bf7c6463caaf5cb5cd69`.
- E545-E549 log SHA-256: `73c5b94167f04c73c57efb0fde32b21f900982e17eff80f4a0bdac5eabc90d04`,
  `fe6badbc94e2cdb7ef3641dac91c834a9c39aa960a0af494e2019c49d129e92c`,
  `df7e4595cec112cf121a0683fa2788a12ac335c176d5a755cc49b8ba87bb1a06`,
  `c26fdb210b1ea0c3fdabd042e265ebc867f0decbb5e173f110f9a695c209238c`,
  `5c850ced03e6f21d4880b5f5c1c9c82862802d0308df5772899c015a764a7b77`.
- E550/E551 long-run log SHA-256: `7ad9f5c92ba39dd679447daa469f48e1f7d45116b916f7025d0f49b519c77321`,
  `bb62e87f3819459887d3bc95be062fab3115f02a57802fce8ce134131bfed068`.
- E552 log/JFR/summary SHA-256: `d6e62512b3ba58c47107b3c7d46599720a1e5c097dc00fd64854453aa1795931`,
  `47221eb724a44994423c946cd2d5bb469f2a151945148fc96d897a71e40b2ca8`,
  `46258fb88029334351f506477e4f75a19d380720bbcfaca2f61dfaab016365c0`.
- E553 log/JFR/summary SHA-256: `666e2516d0fb15226406e70ef70767b6a0a59db4534d14e3f91f2b0cdfb556df`,
  `5e598e5f7087b6f633e409b76d907029a0babd600eb73d12cf9e426ee95abd8f`,
  `da2ba2f9ffe6f0f4c4dd5310c347a214d87695278201dc5a587706f2937efe0e`.
- E554 detailed-timing log SHA-256: `edc8875c4b11fc3fe28deeb8eaf97fbfe0ea3a665da729575d18146c1116916c`.
- E555 log/model-stat/top-stat/aggregate SHA-256:
  `5dcebdbb3c313937b79c07e2dc1f40a925943c19c90e8de7d0cc17b06a792fbb`,
  `c1194a3875b5d08728ab1be7e8e6faef587ceb2bb544e9c734a7507248a0e104`,
  `8818d0122d11a8a9ed6de138d37b270806347555a724fb634894ce80dd84be0b`,
  `05a91abcffa4348de13843e02211d558581096e2919fb90eecc8aee8d68a8281`.
- E556 P4 profile log/JFR/summary SHA-256:
  `b6cee888a9cd9a85133eebe58d386787c4b873d8218f21f94bac9b9a53407c7d`,
  `200d17777946520a0236781235aa8c7508e88fd9efd9b30655b77e292d7b1665`,
  `8c8f15ed074073ff8b6e7168cb615cd06aa0e9bee12fa25004780fef092fe8e2`.
- E557 parent profile log/JFR/summary SHA-256:
  `13f215b41907292a67cf5c644d16ab72c0f7c427f575c8bab791e73129305b6d`,
  `f74a1d073de1efc36af9362cf24b6d427622f386a4151f2062268798e2626178`,
  `c4080f0aad2656e59543c2cff7ccfeb27cafa86d20517065cce51f2a7ae7a1d2`.
- E558-E561 short matched log SHA-256:
  `ec68fc34d47a9e823e4b07d092604dbf8c26d3783a5de198e98321133dce6cb4`,
  `61ceb01257a0bc327acb98eaadef25f0b4a1aa484baf61c2c13333b5c174add4`,
  `66c2cf7058b9af71b4f31c5d99037156d438e6ea45ec07208b672c30e34bc8fb`,
  `b717a7d31e8d08fe7dc641fb60729a107373163f00f8c55426ef1130e8b10fd7`.
- E562/E563 first sustained pair log SHA-256:
  `bc6283275441423583e0d1b8b8f17720c0c5f666373363afb4e3b2c0300f8ddc`,
  `dd17a8e5278e82dc6fdfb42628e0160c4d15434a90ab03a23021ab5accdd4f20`.
- E564/E565 committed P4/parent sustained pair log SHA-256:
  `d70c28fd5ae21786e22c2474a5295b9ccc0ce5dada1fc31e3e4deb23a2b1c299`,
  `94ef4f11eda6c4f77aa474d0bbc1f325217d7f66d0753a577a33cf975b3c2804`.
- E566 P4 long profile log/JFR/summary SHA-256:
  `490cf88e833a6f49c70073334a8e1355ce4fabb80a37e06877dffd29615043c1`,
  `5b51c01cd6252b3dd7bda87b47d51b99123fceb314574802970776b7334b8438`,
  `1e754a4b65cab7236538aacb210574c6919fbdb95b2259e339fb9a82b6341551`.
- E567 full CPU/allocation profile log/JFR SHA-256:
  `f1054ea6377fadf66b450c2ad68604ecaa98cf775cc05418b008d22993c64968`,
  `024f0df86e4eb64bddec5f4c8e076dfc74e00dc7c053e6b2b2eb5da999ab428d`.
- E568 binary-COPY candidate log/JFR/summary SHA-256:
  `ed987016c9ea5ac2474d34bd01f859c66fe53896ca0d434066156c98b556c408`,
  `eaf3260ff4cb8e4db7eb4bcae75ec6566335a78fd832f36d9244cd9152525d00`,
  `1be914eebaf45506a87da16d4bf5cb6ad222aa3d5ac42a2e0be77eed850029c4`.
- E569 same-binary multi-value control log/JFR/summary SHA-256:
  `f79282e79fd05ae7faee90c52562e5b18291bfd8a6b955d272c7af85e9bad218`,
  `11459fbc7f1fbc73fbc14886f5207a078133182bdb5b1caeb51ba5d5c705b782`,
  `f0c0e7add1de4681a856a84e898297e43eef6f3b61efc7ce00a6747a9aa2b5fe`.
- Reverted E568 candidate patch SHA-256:
  `43ffe626f2131c08c84a79021b23741921abfea851a3e45d55769945d70ffce0`.
- E570 model/event SQL-fusion candidate log/JFR/summary SHA-256:
  `9c5964243062d06cfde00aed7d0fdc6eed3e20d28103489754a011a73c3c81d3`,
  `6961479b41c5e1d967fe076f5f12894e119bf6ada2c7a67e5d184f788256e9c4`,
  `91349c863c65640d02f294387be353851ce14cd8f1c3596e45e9732ad71e2f00`.
- E571 same-binary P4 control log/JFR/summary SHA-256:
  `607f33a063bd7930507f8e52c9751a4088a6dc5202056d3159e3d5f9d67156fc`,
  `9bf7b26aaa094a624385ac002d61a42d18d63b3c67d4b97c43b17420f1b11c67`,
  `1c50427f81aefc51651533e08d583267589c14045cdba6e9b585d34d7f2ee96f`.
- Reverted E570 candidate patch SHA-256:
  `087ff111abe919abc30dfda0c5e87b6cde18066803e0f90a4711e881a87891e1`.
- E572 PostgreSQL-planning diagnostic log/JFR/summary/statement-output SHA-256:
  `a8ef7fa6977f08af8730ea3a9cc1aaf0b3e70e983788c449a260be150b137de2`,
  `82029cd6b0537ba37b6a42bf5fd4a950e263cf0f5f199d7600ade4cc80e29f03`,
  `ddc0c2781af5e27127e65701900f75ba0623be45fbd6a2ddd407ac7e4478ce6a`,
  `f0846d943d45866250924011516609c859e4adbb5ad384212a4cf6dc86273bba`.
- E573 direct-envelope candidate log/JFR/summary SHA-256:
  `7d8cce90fa524f88fceb40636838c2af8901a85e97666fff502a754299ce91ad`,
  `96492e22bb02b9d9bffa1749aa22a8242557c47db6d360bf3e43238fc3ad2763`,
  `132d90a80c3bd5981953a839450128df792c8d9d1f0aaee10e751587cf2fd95f`.
- E574 same-binary envelope control log/JFR/summary SHA-256:
  `20b1081c3c580084fe043e527c8d3ac41a383e7830296a9d6816a712fddf5324`,
  `6d24c75a9a88d81fc359527998b949069104fbd4fefc70e0d0c27918eb0a0629`,
  `265249dfbf781f1a243eda228cbb8dd44d225e25d90b301f9cb8f1d4343fbb0d`.
- E575/E576 non-JFR envelope control/candidate log SHA-256:
  `98d8362de3671ac9dd49e3ed638c98d2160915a447fb750c21b799da64158071`,
  `09ae56cb28c9d2853302e0034dbc666a1a8434cf89df25627657b2920974a9a5`.
- Reverted E573-E576 direct-envelope candidate patch SHA-256:
  `708173925cc3c7ad4711fef4cea0f444e0164f28df9e3e0e9dcdc8b89ffe5e75`.
- E577-E580 short-screen log SHA-256:
  `9637f34dd1cc5a52caf07e2812e96f0601c51c8df23019edfde8574ebf500677`,
  `375517b72a12280a7b4ccf040b4eb137cd845fc7be0ddec00779c4ee321194a3`,
  `2cd14a42e3a1dc04d8d77afd3a20af5efd83ed0c37d159cdf9473cd5c3344048`,
  `8e287a558cbe2dadea391edb850eaac6b30de26b63e5ddb80b286c9f5bd22be0`.
- E581/E582 long control/candidate log SHA-256:
  `c8757aa2ff968d4219e701a1cab9430ccbb3e03c9c6ccc229af92fb49dffa9ec`,
  `d799d31ebde1dcabf233955acaaf6407daf450d41c42e4f8c849b71c679e5592`.
- E583 limited-backpressure profile log/JFR/summary SHA-256:
  `27761b74393820e8b029898635daff72985bcd8470213fd3a9502b380b7682c6`,
  `99b05a4a715bbdef99c629d075fef5710aa6c861cde35fef0347aa5d93729fc5`,
  `be3d9cb1a2034dc74a7334387c9090dd1c36dd0e15eaa9be0f52840a3cb6f2c1`.
- Reverted E577-E583 SDK durable-backpressure candidate patch SHA-256:
  `8558c3959c7b716f0ebc583f64248804630ed46f5d886e5c0c1618adf2b89a6c`.
- E584 invalid-index synthetic-route log SHA-256:
  `af308c2d37bbc200e62014f3e6621ce8a1c77a43cc27c33e64eabb3ab0ed058e`.
- E585 invalid-index profile log/JFR/summary SHA-256:
  `83265fa293ca7bb1fc32c3341803da726efe079e487aa74e8ac61b5d73862854`,
  `c5f16a93239cd69096f6f0a2c99272fc306216dc6d1e20b7921f60d136baa546`,
  `d9c23b3a9a075136111ac790104d011ff603631ef578e7590c070421bcfafeac`.
- E586/E587 packed-outcome diagnostic log SHA-256:
  `bb3832b08661a1e3a047027df23e554b28f58684a318a91a4d619f6e8f77f2b1`,
  `65f5a6b21c429f414f76b1d287e1be8219632fd7d676dbe28b84a17b940a379a`.
- E588 synthetic tracked SDK handler long-run log SHA-256:
  `fd3f4a8744ac80d2b8d32ed2218eab94e42e494f1893611844348a4dc73b775c`.
- E589 synthetic tracked SDK handler profile log/JFR/summary SHA-256:
  `7a3db91e543e06338bc55ee2b35a77c222879d771edebc809226d6587d6dee06`,
  `4df829b0c1a80dd65b958c2aa957e909e6438ecd6d873f30b2a77111c278ee4b`,
  `6a75182483516a18a603cd5a1891b031095eb099811464173d36b6f50825c93a`.
- E590 command/model-without-result long-run log SHA-256:
  `bc5ca8fdaea7049551b6538b9e726af9354a9847bd043f72e177aac04eb23a38`.
- E591 command/model-without-result profile log/JFR/summary SHA-256:
  `53f7ae6f6e8e85010249b1e101ae3336f732e5fef726e29ed56675938047628d`,
  `6fb5f592b74c4b7f5687ae4b30bf6e774d4b4e8d570b9413916a4c55b057b644`,
  `197e25e1197219048897b5416e7fe54c5d0af7800f8852a063241706212aff7d`.
- E592 full-route PostgreSQL diagnostic log/activity/stats SHA-256:
  `a5a281e33bc799c062bb2409cccd6134136452369e010d404409112356cbc445`,
  `0c0d11dd526ce6c1840a70e7b2ee6cdf1afbefd23dc18a225ff4bd8d41e1d5c1`,
  `f56a92c2ba43802d736de76cb23326301fdbcbe9b8d20b29903b7f4c42d8cfb8`.
- E593-E596 four/eight-lane matched log SHA-256:
  `f67c9a4d277d861a15f17dca1c1205ddd7f350b9586e8e632d8e3da591a5523f`,
  `3c965f72dd804a3bee3102e1ee95762628c9d7239ebf2ab7fd57dd0c68ab6b0c`,
  `74fc8e5a1b089901f73da67f3f5d8964f152b3e363dae18d4c07196d8fa290d5`,
  `4a48d31536971e3afee5e242ea116beb0d1967e3fb7edf3f1c83d24ab0da1744`.
- E597 four-lane profile log/JFR/summary SHA-256:
  `dac5580f761fc668e0324b6431589cd29fec760f3cd6a28619ea6de9e20f739e`,
  `da7aa64acff670273055e05a433b2eb4f23adccbdb174957c60ea3ec828a4c5a`,
  `70401118415a900662a97fcd702873116748f1baa47348dd6803ef15491daa48`.
- E598 eight-lane control profile log/JFR/summary SHA-256:
  `93d05b6fe1120db29ff740354e828902a975c5b16200f3c7bc64fa899248a22e`,
  `97c9bd507f446b4d0dcf6b4baed00eee3048c16f7755515e057ccd8a06e33196`,
  `c162af3a852a237908389908da186bf99b86f70227155140a6002e3ffc7ab0d0`.
- E599 four-lane PostgreSQL diagnostic log/stats SHA-256:
  `ee4cc6d84903ad914a0e5e188190e66463f460de07351a0bac86218aec5cfbd8`,
  `b73c34bbcc7143b4091653f7b59295f90dcc1bb6ff0bdad2a612629ed1007ed3`.
- E600 eight-lane PostgreSQL control log/stats SHA-256:
  `ff9ce90c48f6915cf1b3f46792253c00deaf163bbdcd26028d7ff242caf1026d`,
  `101e9465923c35d205800f2072bd89d814d1b32aabe769a51a570a74440e95c8`.
- E601/E602 two/four-lane first-pair log SHA-256:
  `86df235d6d7a86ee4093a5ac9471bba9ae6836dd50a5ed00f9284b9bc5c96a2d`,
  `0952c8cdff3b95836797eb286cbd8fd89dd30aafe920d0bc366e7dea0174db82`.
- Excluded E603 host-contaminated log SHA-256:
  `aa99eaeff3bd332bee072a8ff2493bde9f73b64377e59ea0be5a46759bad2e03`.
- E604/E605 clean four/two-lane reverse-pair log SHA-256:
  `949595fa77b472a8d9e5c7b5241162bf00fb70a5b05c5dab33bdd194e3f1d1e3`,
  `c68e424bdae7d88c92bc8e30bf945fb0fb7885c1f594f0a22d375a62fea66d4d`.
- E606 detailed P5 log/JFR/summary/filtered-phase SHA-256:
  `3c19047328b7fba79be0bb53d2259aeb488953cefbcd0d7a3a61f938a1a615a2`,
  `036d44dda717a48ae055552c88d467d156095a09f5607db0a1f88316be27a2cc`,
  `5ff5a0f104c50ae0fe9722591771c8e790d5fdf01e9de9b187c8b024b09da615`,
  `e25f2d88573a109e055879f1e7d976fc3e7633440528ee7aa715ce10052ecf0a`.
- E607-E610 cursor-transaction control/candidate/candidate/control log SHA-256:
  `1f32374f83e3a5f12c7224a35ea3e6ca18b3417788865f204072f9f865bd9f8a`,
  `92a38900e326329af5f61e51942952ec3cca9f1d67f40340a8f3c324004a8b68`,
  `51771be36b5ceaa3261ee450c15df788500b9f530dad3435af149be33ab0510b`,
  `f450fff6cb142878d436580e3e19c6e53dab67d76e5dfc0f3e686e79579b616a`.
- E611-E614 eager-hash-prepartition control/candidate/candidate/control log SHA-256:
  `1568f58d0b724bc42e1710739dcb64420ab97f100bb30d4ca1f5d8001a8a13b9`,
  `2c3a1830dc7b8d897786e3a6f71c469eac688cb0af57246c58430a00283a42b9`,
  `c60a95d000be320020be1f7efcf8a17c422011689a25fae8fa9837c777f15862`,
  `ac770e32fa7fbdad902823673df58c6e4e138eaddb81036cc9a706abf5d06e2c`.
- E615 P5 full-JFR log/JFR/summary SHA-256:
  `62df895a2105bb0205dbe83d0f0a7125b5f9cf76a38670bf9f5d64bf058eccb1`,
  `9d788946c6e5bcf23855cf3f1d769fe3e895d14a1c528db5e0202fb1c32011ca`,
  `0d805d56af65e2ac1bac87851c8e5b50fcfa916bfe4fd69b9a076612096f588a`.
- E616 SDK post-commit diagnostic log SHA-256:
  `e9be16476cd71883497b0c334f703deba470f73343c576f4092bc32ce254aa83`.
- E617-E619 unwired event-group diagnostic log/log/log/JFR/summary SHA-256:
  `f4037d57f17aba043adf692a38e52d4e3090e11b736b023e43b5d0f151ca9704`,
  `499052a1cc563a783b9b2111ce5ca1d4f0593f47b10a5863f3bc643266a73211`,
  `ad33338591edbb08731fee0b392a437aa9827c5214f536fcdbb6a5d2860f9474`,
  `6d9dee584f8b4912122be4b206af2806e80e651197feaf297b69c10692a2d533`,
  `8d7d483df1aac169246c70e2be45eb5049ee2c685a4f8920bcf9aa9affcdead8`.
- E620 128-row control log/JFR/summary SHA-256:
  `c4fef568350b68f9f80684b215614eaade3020c54ddb3260de5616221cb586f3`,
  `af6d3b4ef465fe5e9227dc821b22d17480cf4104dabc8b6176864aeaab88e98f`,
  `d67fbb5ad95988eb8a561f87589d5bd20407f774cf1162ffa74a6396f4aac30c`.
- E621 wired 256-row candidate log/JFR/summary SHA-256:
  `a75f877a9333bf77f6313b7faa2578c1811a182d154654a6ec89af5c063400ef`,
  `3fa31602903f8091fcf1459224b60f8682d5cdcc0da48318e611e603ac970813`,
  `a11c348bab2889c4ff9b2ad3ffcf2e699a30e47c6007bddc268b6fd8dd3dad9d`.
- E622-E625 model-backlog-delay control/candidate/candidate/control log SHA-256:
  `efa5ae28834d85f0146612ac4031af89e55facc2ead7ae68241aebebffd678fc`,
  `2fd9f4f32e2aaf50120ca0bb30c35f39fe9d18d9286b27158f9fe2c1a0981397`,
  `2c5f189f3e70e4fcfe0143eccbf486b5c068d8dc38b76bd61361d971d99f62be`,
  `d3023231e0a16321b3f84538c5f5060bc14a0765f74cdb24d0bb32d852b853b6`.
- E626 generic model-result codec profile log/JFR/summary SHA-256:
  `557acb9b3dba8933f9bac8b8be092a1513347076e2d2a35bb495ce6d632e0e77`,
  `6a1ec6097191fb066a5ab229df3f7b8276316336e15036dd8ca91b38460a486a`,
  `207a03a98a8dad621a832adc7501a1704616894c2af1ef5a61b89f8c05d8f9bd`.
- E627 streaming model-update CBOR profile log/JFR/summary SHA-256:
  `3ba2c35d17db78d60cc9a1cc7d1bd50708e55346b48f331ae3a43814b31dc93b`,
  `8fd4bffd3c38818e0ce446dd2538a9167142a00e16fe694c025bbff190ab156f`,
  `9b1aabe20f2c89c3c135b588d6f40c17c57695625cfd22319cd9110bbb71afea`.
- E628-E631 preliminary generic/candidate/candidate/generic log SHA-256:
  `714d693c5993cf109b7f37f517c70b4e08edde68bf7e112357aaf39a88a46ca2`,
  `b2437c667f7151c502f29675e8e4f59cd216970ab04d5122e6d4b565f40b363e`,
  `de912aeefc8afe9ca7fb50925d33b16b1ad1b8f9c7f4969b2a2d774bee922470`,
  `53c2e2c0d64de4c816eaa421532f6f2095be634ca35cff05deac242175f1e318`.
- E632-E635 final compatible-candidate/control/candidate/control log SHA-256:
  `d4df0c102736537329e70bb39de9148d6511dcc59a3cf74005e040548d15a091`,
  `b724a354a17ae12d51e63f8e95225a05990945d94cd1a2d0b885f8ea41f770e9`,
  `c39c693fe2f233627612650ee7a00cbe9a1e5b6a7838d51bf62950630fc3a685`,
  `a903e1424c39156dc9a73a05b6fa31047a975de8159b5215a6be47d17ddbe548`.
- E636 fresh classified P5 log/JFR/summary SHA-256:
  `4370f2432ae76de862796adf4ef5bbf96e80de050269bd598d08656d7ff469d1`,
  `b78e9c2072bb7e509fc3b5348f15eea45dad540383bdedae19d55d2e04cf0da2`,
  `f322fdaf995d8ab63ab1c53c5504db226ef35ae0176054a435a3721c42b12c71`.
- E637 locator COPY/commit split log/JFR/summary SHA-256:
  `3e5c935dc68a25c58e5113144873fc4fd7944ec044c34569ba6bca2784140491`,
  `2c8a0230181a1673b8aa7af8857ccad59f139d045a4b860c3f04f83c7f6d0cb4`,
  `827756cba597fcd1116b7b33ac21184ca38b593d630ce3904fb353fa7735210f`.
- E638 invalid candidate-schema launch log SHA-256:
  `87a25388c5cbb8a1fa348daef7eefbaed23ef99797857cb0b55497ea44dff04a`.
- E639 corrected candidate smoke log SHA-256:
  `214d5efdcc2c3766349db4134dcd0d7d43420a31789e7b5a24802026ca33f850`.
- E640 one-COPY candidate profile log/JFR/summary SHA-256:
  `c199ddbb3f8e5842b0eabb9b3bf87916d4d967214373aa99b4e78c4656cfb4d4`,
  `34bce1e0fadfa3bf0cc090b28f7763285d17afd7eb53676317e87583f19026e9`,
  `b7fc3b00d91e9a37705dc902920ba361cc92ceaf55a40160545f37d3f0d13070`.
- E641 refreshed SDK-isolated profile log/JFR/summary SHA-256:
  `0c2df7277ef1bc31635ef4c25300944a9760c95b3cce50d3733d0d2499ee9a99`,
  `d3a51d72d8e1aa1e0c0809491c091814347e3006550ffbfaac7306a07035c6e6`,
  `42dc350662be4d319b8d82a07e0d468c0f131e8475716aa84489ee2e454bc40f`.
- E642 SDK-isolated PostgreSQL profile log/JFR/summary/statements/global/I/O SHA-256:
  `5e45131fc20ef31f10c56e377d1927d8e31819e3c078084946f544572b60597f`,
  `e1115e595871ddbee2dad162a17f8b632087cf458176d27e4a678957f9674101`,
  `9d45ebffb7dc094172425eaa89472ffe3ce17fd3f6b0ab497e6eaac091f4ba95`,
  `2be858ccedbc4194b5010afcc34b2fead298d9743757116ef9b5cd7d89fee23e`,
  `3652ad12d21c6bcf511a9552cf25b1e217509adfdf0cc1df3c7463e720212d06`,
  `2a3d0b4a3d16614748b288696a2a215259f57b227430d3a9051c3b9138fa0263`.
- E643 canonical PostgreSQL profile log/JFR/summary/statements/global/I/O SHA-256:
  `3ed341b8fa05c7f6213f3479807f3233d4a08745165cca48a9ef64f029af5de8`,
  `c06c779479cb439c57f6e515d4562c2e5148f971c168cd89b7311396ea755b27`,
  `453ad7aab9cc114db87227f48de468bf8fba2cf53d4f4bfa3f9e56b273d0d9ef`,
  `bf7324df5aeb0434b40f84804897fb402a388b1d3767adbcc87b43076815e8e1`,
  `33f4a8235a6e27d1dcb2ea46239e4605fc33d4e96ecbb61564f3e93c25bc6316`,
  `9e21faa563d2b18a312c6abbde34d2b01fe00ba5ac522d14ac23a82589ac953d`.
- E644 isolated table-size log/table SHA-256:
  `da244dfa5f26410aa4a964ca61b4bbbd9941058ceb6a6182633a87404fb4ddca`,
  `d7dc7ec5e329c34989ed70507be14135456c0aeb8d8c30c3b035d93e44e9ed64`.
- E645/E646 metadata diagnostic log SHA-256:
  `7ed4b55b4dfc6a4d2c2758c94361e9081dd3121cfc96cd615ae7aa46617563f3`,
  `8e76037311134cc6d68ba143ac90abfc6b61e8b38b3ce3d12ccd2f17a1ebe31b`.
- E647 canonical-metadata isolation log/JFR/summary/statements/global/table SHA-256:
  `9a676e534f3463632d0af77aaa418aeba52a0d8de852721f3fc487306eaa9a4a`,
  `f56c33415d6ed9c81b1cf5cfb98122f202af73b8bc1a7f8655bdea66a400fd37`,
  `b7af9d81bc10e8d791b8a317bca449750b8c4a549aafbc8b35ea650c8d1fae77`,
  `e29fa90a4ee875fd0e44dc0db8782f870a37a0f4a9a4b30ff4dd1131175fbca2`,
  `11e7052f07361bbe1d246ee99c26aba0be39850089948098f45f9716d7353a87`,
  `a5c60a57fd8b98ac1ec615fac5d5535aeb772ab7b89fbd33396d77d0b601d7b0`.
- E648 bounded two-batch overlap log/JFR/summary SHA-256:
  `b1903d51978c119853017735a3d7ff5c48178332b9f0a422356523565a8c88fd`,
  `a3232087d1be74d46218f9c5f1d4dc482923d3488540edbe1ec893c675de83ef`,
  `ef6a02fc9538109a6da92eac6250982d04f2fb9647ba82508ecf6fe65e2adff2`.
- E649 commit-start handoff log/JFR/summary SHA-256:
  `7dd5244a0890e077fac6308b4a2a67cd11920abd0371fce99c8c77fe332a3e66`,
  `1c6b2cf1177c0dcb404e6793ad4e16407640c0e5df27f8e3618b3a7d2d906dec`,
  `a65eb8a2e1d2a191edcdcc40945b03fcaa8e1f417c5397ceacdbe27a21c5f82d`.
- E650 SDK-isolated socket-profile log/JFR/summary SHA-256:
  `f2f70684e10f881a018a40fc62ec38cb03673e3017c55405f07f144df43405c0`,
  `3fea599ede147c038800691275f95421bfe8fadd463b85f4809d3be15a8971a4`,
  `95faec1704f5b8e20b642722ea42a6026f9d54706f2665bb20c4c982072a1a89`.
- E651 canonical socket-profile log/JFR/summary SHA-256:
  `aad3904616065d68628380dbb6ef37738ac6f17ae333b6cdbaf7a7f12f5e9a28`,
  `bb67a00421a9601c5ae012d47bae22daf470d4d7f62be4622b0da081c042a5ba`,
  `e1ae72059d5a0f1f3d21e1d3332cc3f6fb1a60122e08670d5f5e78c4e2694dcf`.
- E652 SDK-isolated JDBC-phase log/JFR/summary/PostgreSQL-statements SHA-256:
  `e101dea48abaafb920582d30229442e8a63210aefe356d92fb09f92733216237`,
  `ac326e4f604260738fc3d4752c54a2e76fb09b1feb26431ea999c87c2b08177a`,
  `488c1362e176b45a151077d2e5c3b20164406644f6227427d62a2c4f06523e9f`,
  `4227c9eefdbc7167be37b9d96e1236d4124a84fca21fb5fe122e1f9db8146753`.
- E653 canonical JDBC-phase log/JFR/summary/PostgreSQL-statements SHA-256:
  `a6b494ba308b0b8c8f988ca58737e40e6865386479fe36d3ab43db945c5b00e9`,
  `01cc8b0dcb809f9eabc6376c7d929cb6cef838fa05720fe7add829b3f73ef587`,
  `e6434a104a8a2199a84ab05b69abc0fc7b0db56d1209f17421c5021a68785357`,
  `85f7eb10c1c474d78f465016d2d9a6d1f3a8e3ea2a860ffd468655173528ebc2`.
- E654 SDK-isolated planning log/JFR/summary/PostgreSQL-aggregate SHA-256:
  `a6b28a31503a262542f21e2e4a6ae8b36b7aac33c477c80b2d4d6b516a696407`,
  `4f9e7fc41fae91481e340b92ea6f0a70aafbf3fb133411b234bba380b231f371`,
  `00cf6f8a677e996cdcd64058dc67fb4123a20bdeb82fd89a2b272de26d2085fc`,
  `90e737097410a6e52cc7fde0532f4bdcbecb0961a8c01c9af7631e7aa93ace6e`.
- E655 canonical planning log/JFR/summary/PostgreSQL-aggregate SHA-256:
  `d38d115635733048f39ec3f2d4ad78ca977357111e4fd366b50e7edc9fb1e449`,
  `f5e97878bf0bd686684409b42bfa6778d255f1144d5c93a2bc1d9af93873519f`,
  `434b0ba85223cb293e7bb4e9cf5a53e0f8d5f12719f6fb40d53d740f24198399`,
  `37c6e05db39fbc545e03caf17290319936499d94996d1bdaaae9eda751c8fd74`.
- E656 one-GiB-shared-buffer log/JFR/summary/PostgreSQL-I/O SHA-256:
  `670e9fa688b14198638d663d40c55e2a5b2f8eec5b23de9a116e8a5ae5ad7124`,
  `70cee6286c126bada398e41dfb32781b1307021ddde0227eb847e0f4ad95f3b8`,
  `3fc26a09f4341eb960f820ea8b84a981cd6c876a78c99f2d58d57f8b04db6fec`,
  `7eaddfec875350ec68de3e410f6ed7409dce97d2410332558a9a807bfcb474a3`.
  The attempted WAL query produced an empty file (`e3b0c442...`) because the installed PostgreSQL view lacked the
  queried column; it is intentionally not evidence.
- E657 restored-128-MiB control log/JFR/summary SHA-256:
  `72bf1634c2b6459ba34aa38b4f0d85934054edf1e97b444d64e4d9473daaab36`,
  `d2e1b88f2498bafd8c432539b80e4f31b7f36d2dcf87bf548292f113c3d5e887`,
  `e72482a685566009153fe6fa0486d98b65774c547be2774e96f44ad82c1ba563`.
- E658 SDK-isolated JDBC CPU log/JFR/summary SHA-256:
  `627fbed90f8e3a5a48958faf8a0d4c2357fd639012b7200258ff8b06dac22d95`,
  `9492e136a9a6a55a25b66823daf103e0404b50e0c77ae5eb72e44eb3c07b312f`,
  `baad3ceecdc9ef8fe9733a35fb54a687ce5a87f843a656b4c2fa8dd012b4ec94`.
- E659 canonical JDBC CPU log/JFR/summary SHA-256:
  `9f279d83799ac58cde62d10fce82448b24448c127b7f1725f76d97fd014e2eed`,
  `c1be381c8b8269deb30cc38fe96f57061ef2b720f1c750d6cc9e5b9c3a2290ac`,
  `d9ba30e67f8e7d556f4be2f5920d2d941a0bf674c5d02800ba1dd22a9e206100`.
- E660/E661 canonical/isolated wall-profile log/JFR/collapsed SHA-256:
  `d33d607de622d9125e27b2af3a3fbc1b182ee6791346b7b2508b0ac2a63ec73d`,
  `4f02a3b599c968d41741d31545b6ba264bb2c03aef74b4f34a6490167ea963aa`,
  `629d28c157b02a5c1d015763d47fd2002149374267aec2fbd53f1be769317c1a`,
  `789346da474240e500935534f405f5efc13ec8ba77d388077604b04c36f74baf`,
  `9f25aae53cc5c53dae55d58889bf3f338c9629d0e378c88eb272ae98a2296b7b`,
  `24a57ee7e8742160db09ff67cc9f8519b6a4c531017a45507d6bd475f3015b70`;
  differential flame graph `1fa3cfa5326e966695aebf6c558a2358f41aba7dd18bcc84526bf3fa5cfe3125`.
- E662-E664 24/16/contaminated-32 profile log/JFR/summary SHA-256:
  `cd2cce0a1930d0bb3018d366e3c0ec57529fa5267d1021001ee16708a1fb94a1`,
  `d8f7fece02f77e557594ff42cfb734c8042b39e46f87b082720f2271bcebc642`,
  `182819535ce1b24f9da0881c2652e519a0ccbd3cbbb98fccd2db18ce8e2adfbf`,
  `9521b1157f8631a60815463a500b7083a5ffe1b20792636156f0c50d6f87b0b0`,
  `33c2c247548bd8fd452432cfa51c949b3d6939427dd0dd0d496342b17cb32bab`,
  `b4ae61fea761e8fd086b85d622e5ad7bbb85d3fd99e52bb0bbd19b37e067a177`,
  `0a60375a4faad63ba82f0815fa297d0afe73f4d79d1cf5201a7826536774f099`,
  `15135570d20de948247434249cba62b4ecd442e443caedd40df0caa3e94efe5b`,
  `2abd116cb3a988edbec2143c21e446d97523ffce8326ec64715a7111cd942d61`.
- E665 log/JFR/summary SHA-256:
  `63c57d09b9044137baede98ab4ca4b9fc4be34b583a1ba8cb6535c461b21e2b5`,
  `e4fc6e7943860ed87c01a57f85afa4fa21456ba122fa9bab7a0c132ab7dc49fe`,
  `dfec942202f5274f3a959bef36a69ac333f3afa0fe3bd597bca8d3a9be79887e`;
  the activity output is empty (`e3b0c442...`) because the sampler did not compile.
- E666/E667 16/32 activity-profile log/JFR/summary/activity SHA-256:
  `e944cca01b04bb66862b3596359e6d23a2306651e35f2e8918a759d9348b869d`,
  `4a687c22832ad7ab9ec43fd984d4e863baedce50fc3f02c9f737712c8422539f`,
  `ae2de4814e05ae006730da2daba179713fd2d12835ef6164eb5d4bf3c3865da0`,
  `96d328fdd4d772652f733b87a520db0a2a875ca6817f82f31ea99fd302c5e373`,
  `01ddf1ff568a40866283aa8d93863d5ee41b1a83bdeb1cb1990aed9121e20f18`,
  `a8989e527a2e2956ba5e3460cdc98f6bea7f8d66515266b720f6574ffaa662ac`,
  `1458ecdcc30bebb314dba152157cf3228325b471c6122acf7b6419897c01a4ca`,
  `53b1a80601855b3432a041be7d3cbe5ee9dcc1fa5655f00d1fcd0d48f34715eb`.
- E668-E672 shared-pool screen log SHA-256:
  `3d9cdf107531818c317c8aedc8d1b23ffd977536f7ffbc2aa2c282da5cbf439d`,
  `b3e4079e6a998b67db66d35ce11b301726f95fb00fd0493b5a8a061fbb59398f`,
  `b00aa2ff8caaaf7f301039863f3751bbb20520462baf804b0e9f2b046061a929`,
  `d668bd57b4b61eab82f9cd7122be515230657e460fba8feaf9ae9080227052f5`,
  `8c297eba05255679165fc237bcc497ee6cb523e03d563721f91d19327fa91cf6`.
- E673 statement sampler log/activity SHA-256:
  `5c1a77dd567d4908e367918bde68698f22752908ee9816507d5b92fa8b584bf1`,
  `1271d73f342171051be4b85dbda5a501e4e5cf4d2a678fb5a0d099a71ebb0821`.
- E674/E675 locator-lane log/catch-up SHA-256:
  `2e86aa6580c4fff4ecc7468dc8d149d72ce810463a2f9d8f90506dfcadb5b277`,
  `de19ac7db90d039c5c18737f67a798f6c91bac67fe173e18716e699b47acf4a4`,
  `a7db5445b1022115550e9d281bb699123f8f0f0c7c92c0458e50ba0979813f20`,
  `58ea52de07075d2b34b41fbdd0fbd6996dc0eb11dd9ef3fd037bba6c4c66642d`.
- E676-E679 co-located insert-admission log SHA-256:
  `8fb4997afdb33a283fa3c6d0a0d1c8085f0f3efe36a8c825fe7d9a10eb99a21e`,
  `e110cf26eda50470bbb649e667326ac9bfdaabc70eeb4f446ab2d09c83f69af5`,
  `fc53b226cbb82e88046bf5fdd56f2c031ef0546d8d49f4d4dea48aa96d8475d5`,
  `8a39fab949dd1f6e204ca1fe94fefee3816736f82735f24e0cdbfa065532536e`.
- E680-E682 global-event group-size log SHA-256:
  `d64de3c61afff61bd092e46e060685f5dece8f28f78a887ecfdd0f8de239bb97`,
  `13868db00022bbf097b74bf09167fd08ef0d03c3ffc2adeca62769345c28a515`,
  `bc131785edfffb02d34da533a83e70676f7df93110d9e8e5af12b99c5a965568`.
- E683-E693 synthetic source-index CPU and qualification SHA-256:
  `6f32243ad48b17af6283ef7f40e7f008788e4501bbdad13c755a41963c05daa9`,
  `7fea665bcb286f0ad1a018d4a741dfc07a9ef600e68d11a9b0e8dda3abcda97e`,
  `925c2ee082e944c2817c25b0efca33a1067dbebb9df4d6b4e17e8e4941211f23`,
  `14dea7f3ca43245a975f084d194cc4a890c1913880a71263ac19c7509e9ff844`,
  `9e1d730722d8346fdddf45cdd8f6a559f92423a5f7f267450e9805ebca164531`,
  `2ee539fc253b0136dba2e470d8da4181eeaa19aaab0840a11f94892aae972abd`,
  `b5fb3eef240dec67906e6e529679575a9b5e77eeef22d58b7218db0424a72464`,
  `9e68ffb5361eda0728170a2a3df71f1c2c73c81750e17af3e2fd0d29a33b4d5c`,
  `5007b37397e11c6e4de34725af2630ed16d0713bbc8db348348b4933c3059901`,
  `2182161d8971405214a09a5808d5ff23cf04c84e371be276b97225c2d7ef698c`,
  `b2de5b8ed087a0c9126d2c87aa78efe6ea005428a085a538f894712cb4218ccf`,
  `31d4d36ac1a9369df1ade7bed49e1312d03bde37d025fe2b4408cb3283d4e793`,
  `b54e4de18fcf1f4233985e1c3867712eaaf990805579ab3c38cdb7630e7e56e3`,
  `e3175f79ceca3c33180181f94e9d092c91e9ab969423944a02e3665cf77da8f4`.
- E694 current isolated-capacity log/JFR/summary SHA-256:
  `848630c9f95eea2690802082198c0cb81143e1ba64d9c44cfc468baa9d3098b0`,
  `43631705d64d471bdc7e4b449ac881b9bdc64490fd4887a12db6ec9858d8aacb`,
  `d9e52e11d8a0dd54d79a9ce0e7f449bacbb2d3df1767e02ea9a1f16d89edf614`.
- E695 invalid doubled-window log/thread-dump SHA-256:
  `633a1bc1f52f2bc5276c356a99d11ab5f6b95a3670d265815881d7eb6fb7f418`,
  `2a944543aa4deecff79737e317ebfb8a9f0718957d54acb79eb9ffeecb904a64`.
- E696 two-millisecond idle-delay log/JFR/summary SHA-256:
  `3ae0ccb9aac1d6577fc1842e5fb4ef765ef1a8ec44f0e9823950280613746726`,
  `c0f2a7d445c1d82d966ad81e94f6a489c24569570266722d8f10250128116325`,
  `d86d1080a98d3fded2e9db6f47e8687e4526b1a832dfb45a2a985dc2a336ab86`.
- E697 tracked public-call smoke log SHA-256:
  `f20dbe35ecfdc4932f3ff51b3ce2c5669e8733b9f8e32f860e9743e3b00cdeeb`.
- E698 tracked public-call profile log/JFR/summary SHA-256:
  `0383ea1cf5a479a3869a1c4168b6c0da97eb0559b79f34a2e51d4b9c82d3f956`,
  `22d97e78cc1aa27dc47dcb9cd066bae0c964db04461523e1e401d7b7a26c6c23`,
  `a3e207cce435c4ed1275dd8db1c42f9b3fffd6ce20ac2a6777a8f1c5c67636e1`.
- E699 tracked public-call clean log SHA-256:
  `ccd3ede7fd38e041c7a14b180bc3fe3b69eca1b6f9fb08152178dc08d7e473e6`.
- E700 automatic-handler regression-smoke log SHA-256:
  `93aac8fabae8eff8f3b9057b83ab96d1a8aa76bd941e0fdf94fbbab1887cafc0`.
- E701 full SDK-handler request trace log/JFR/summary SHA-256:
  `a97bb0377c759db496742175cacec2bb05e050a9d5c949f5f2f21d9cb4f62b69`,
  `23521c1d676475b96d964566ef9f37c315cf89f4a4a94ceafac339b6c7061dca`,
  `126d2021bdbb661c77f7ba9014ac6c64e14265dae9f7034c80f3a73ebf09867f`.
- E702 adjacent contiguous-wave control log SHA-256:
  `bbd34b0ae652e19d368effa357bcd63d8472876679975dce84b533c02683d63b`.
- E703 rejected persistent-lane diagnostic log SHA-256:
  `b7baaead775becffb5c006848510eeb51b3c298e26d23c524857616a765b553b`.

## S39-M: tracked model-command registration performance check

SDK `d7506649e96` replaces lazy/local automatic-model dispatch discovery with registration-time asynchronous tracking,
matching `@TrackSelf`; its direct parent is `975a7646197`. Runtime remained clean at `59faf5eb054`. The handler app in
this benchmark already registers `EventModel.class`, so the consumed command still selects the same automatic tracked
handler and the model/event/result storage paths are unchanged. Only the sender can skip the obsolete local fallback
probe.

The full 65,536-model, 262,144-warm-up and 4,194,304-measured command/model/event/result route ran in BABA order without
JFR. All four runs verified exactly 4,194,304 results, stored model events and global events plus all 65,536 final model
states:

| Order | SDK | Warm-up | Measured E2E | Dispatch batches / mean |
| --- | --- | ---: | ---: | ---: |
| B1 | `d7506649e96` | 198,606/s | 253,907/s | 351 / 11,949.6 |
| A1 | `975a7646197` | 232,988/s | 272,744/s | 338 / 12,409.2 |
| B2 | `d7506649e96` | 225,348/s | 269,792/s | 360 / 11,650.8 |
| A2 | `975a7646197` | 257,116/s | 257,080/s | 350 / 11,983.7 |

The geometric means are **261,729/s current versus 264,796/s parent (-1.16%)**. Absolute throughput was far below the
accepted P5 425,606/s pin and natural batch formation varied materially, so this is a loaded-host diagnostic rather
than evidence of a model-route regression. It shows that the large no-model sender improvement is effectively absorbed
once model durability dominates. The accepted 425k pin remains unchanged.

Log SHA-256 in run order: `25569dca5d43ed9c85f4ff9d2ba133b734704d570f7b37f1dfb1314448e5f0dd`,
`ec388bd756a9f0dc0769088647904310472f790aafacdf1cffb05575a143fbd5`,
`d6c47e85eade70f25ed2a27507d3a5f0c97e5eb0b9a74c021aa1767d061b8b0e`,
`76f633994eaab356c15349b7000d569bc27f47136fb3a7ab1fa13c459d317b2c`.

## S40: batch-local model read-your-writes

SDK parent `bd1a58182f5` did not retain staged independent-model changes across commands in one tracking batch. This could
make fire-and-forget command sequences observe a durable predecessor instead of the latest earlier staged value, even
when routing preserved their logical order. The S40 candidate attaches one speculative model view to the tracking
batch. It records exact command ordinals and routing segments, makes direct models and resolved ancestors visible only
to later commands, and records causal commit dependencies. A dependent command waits for its predecessor's real
durable result, then reevaluates completely against canonical durable state before committing. Predecessor failure
fails the dependent without a commit. Independent chains retain their existing parallel path.

Focused tests cover same-model updates, parent/ancestor injection, predecessor failure, and both the default
`ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH` and explicit `ASYNC_AFTER_BATCH` lifecycles. The full `sdk -am` run passed 451
common and 2,079 SDK tests. Every benchmark run below also verified exactly 4,194,304 ordinary results, stored model
events and global events plus all 65,536 final model states.

The first correct implementation paid for dependency futures, concurrent sets and a per-segment staged index on every
command. Its adjacent pair lost 1.31%, so it was not accepted. The final implementation creates dependency state only
for actual overlap and scans the staged map only on the uncommon ancestor-resolution path. Under the same loaded host
state, two current-candidate runs bracketed by parent controls were both faster:

| Order | SDK | Warm-up | Measured E2E | p50 / p95 / p99 | Decision |
| --- | --- | ---: | ---: | ---: | --- |
| B1 | initial correct candidate | 119,305/s | 155,545/s | 322.676 / 682.324 / 933.417 ms | reject hot-path cost |
| A1 | parent `bd1a58182f5` | 135,575/s | 157,614/s | 317.231 / 736.105 / 928.854 ms | matched control |
| B2 | allocation-light candidate | 124,389/s | 173,556/s | 302.708 / 538.352 / 652.098 ms | promising intermediate |
| A2 | parent `bd1a58182f5` | 99,522/s | 160,665/s | 321.235 / 650.072 / 787.971 ms | reverse control |
| B3 | allocation-light candidate | 125,379/s | 177,234/s | 291.613 / 572.146 / 696.725 ms | confirm intermediate |

Adversarial review then added the interleaved `parent -> unrelated model -> child` case. The first implementation had
remembered only the latest writer in a routing segment, which was insufficient when an unrelated writer initialized
before an earlier parent. The corrected planner waits for initialization of all preceding possible writers in that
segment, then retains durable dependencies only for staged models of a required ancestor type. It does not serialize
ordinary direct commands. Two new matched pairs on that exact code were neutral but not yet convincingly positive:

| Pair | Candidate | Parent | Effect |
| --- | ---: | ---: | ---: |
| B4 / A3 | 142,204/s | 141,379/s | +0.58% |
| B5 / A4 | 133,483/s | 135,059/s | -1.17% |

The combined geometric effect was approximately -0.30%, so that form was not accepted. The remaining newly introduced
cost was structural: every default command had both a handler ticket and a model ticket, each with its own execution
future and completion administration. The final form makes the handler ticket itself the model ticket and allocates
gate atomics only for explicit after-batch gates. It removes no result, commit or durability barrier. Two alternating
full-route pairs on this final source were both decisively positive:

| Pair | Final candidate | Parent | Effect | Candidate p50 / p95 / p99 |
| --- | ---: | ---: | ---: | ---: |
| B6 / A5 | 235,440/s | 178,006/s | +32.27% | 234.515 / 353.784 / 440.938 ms |
| B7 / A6 | 242,851/s | 193,944/s | +25.22% | 222.364 / 361.126 / 433.912 ms |

The final candidate geometric mean is **239,117/s** versus **185,804/s** for its adjacent parent controls, or
**+28.69%**. Absolute throughput is far below the historical P5 425,606/s pin because this laptop state is not a clean
absolute qualification environment. The acceptance claim is deliberately narrower: on the exact full route, with
identical Runtime `feee1d9a`, database, Java 25 process and benchmark settings, batch-local consistency introduces no
regression relative to its direct SDK parent. The historical P5 pin remains unchanged.

Log SHA-256 in run order: `b96afe84ad64f68b9a25ff5275f1dfaacc7dd2cde7deca7b29cd3936cf0eb506`,
`43d75e1e0d4322f811c2cc05c9f777d7d75d0fdbbce16eaa534c445252c185d9`,
`f79de494677ebaf73e3e348e6296f074dbba3ffe585374fe4ee1d05d862b9534`,
`47a80e1aaf993eae20d4be2434a4ec293fa58c24070d2a2673d5368a211385a2`,
`da277216bb09d9ba779b79a90cb6224ee2e4b812937633ae1469a7e805aba8bd`,
`94e68ce13c28cb402ffe355e1fab69c190e700c63c2726900cb2f0c7f7db565b`,
`ee683d93f97758624a88ec14279208714e9fd5bc166c8a62e69492b39d6eece4`,
`7182cf850154c28c819e521f74bcb2351d8dfd4e8d933f76e47a88c3f8d40d2e`,
`a3b031bffda284f1cfb5d6b27846483f4e3992aa1edcce111a4e326ae89cda41`,
`a2988679166273878c28cb5e8c16b8044c3e100e70d454c5e8a5453d2b6fc37f`,
`0d8953178c1bf2d36ca9481a08ff236d06145c70f41fb1c3b5c9e9eb50af9b23`,
`e4b6514bc379fb36ac777e50dc2d2c68aec87aac077878f1f3594dde2a89b9cc`,
`b77884f7cf5ffc82687e2794e2b37de9bb57a6b08e9d9a2ca7a5f31f79d2b6ac`.

### S40 follow-up: handler-awaited dependent commit tail

A downstream integration test exposed one circular wait after S40. An outer handler dispatched and awaited two
causally dependent model commands from one handler batch. The dependent command correctly waited for its predecessor's
durable commit, but that predecessor was still retained in the ready WebSocket transport tail until the enclosing
handler batch closed. The enclosing handler could not close while awaiting the dependent command. An exact downstream
A/B confirmed the regression: the focused test timed out with SDK `c2976a4480c`, passed with its direct parent
`bd1a58182f5`, and passed again after the fix.

The follow-up flushes only the ready handler-time transport tail immediately before an actually dependent command is
detached from that transport batch. It does not change independent commands, full transport chunks, explicit
after-batch gates, commit ordering or the dependent command's canonical reevaluation. A focused regression test awaits
the second same-model command before batch close and proves that the predecessor is flushed and both durable updates
complete in order.

Verification:

- full SDK `./mvnw -B install`: success;
- focused downstream regression: 3 tests, 0 failures/errors;
- complete downstream reactor: 0 failures/errors;
- three full-route runs each verified exactly 4,194,304 results, stored model events and global events plus all 65,536
  final model states.

The matched performance sequence compares the fix with exact SDK `c2976a4480c` and Runtime `feee1d9a`. The host was
still too loaded for an absolute qualification claim, so only the adjacent relative result is used:

| Order | SDK | Warm-up | Measured E2E | p50 / p95 / p99 | Decision |
| --- | --- | ---: | ---: | ---: | --- |
| A7 | `c2976a4480c` | 137,478/s | 158,891/s | 323.797 / 617.233 / 699.393 ms | preceding-version control |
| B8 | dependent-tail fix | 120,011/s | 172,644/s | 293.096 / 605.621 / 832.387 ms | no regression; accept |
| A8 | `c2976a4480c` | 115,552/s | 155,710/s | 330.030 / 635.632 / 860.464 ms | reverse control |

The candidate is 8.65% and 10.87% above its adjacent controls. This is not claimed as a causal throughput improvement:
the branch is intentionally narrow and host throughput varied. It does, however, exclude a regression relative to the
immediately preceding S40 checkpoint. The historical P5 425,606/s pin remains unchanged.

Evidence SHA-256: downstream failing candidate `a31824c1dec0f79c6b6dba882cb364ba0fc09175227e633eb84e9356c0ece3b7`,
passing parent control `7fb9b6309c5ea6badb967ef2bb0f20981696237bb9440bcb87d773b779c39b42`, passing fixed
downstream test `73874fa989f911fcabef568c42a7dc7a813187169f38786b1a39a20b4c59322f`, complete downstream suite
`5578d0be48f7a09d4df62b8b1abe5801076a9a8d02e94761c3da07538adfd9cb`, full SDK install
`9be0af7105b6260be4da20c2c4f0eb6cbf327f3ae901d459d837a3fa049f5e0c`. Benchmark logs in run order:
`9735ab783e7ef95f68f107b532bcf5eb422624f4a3a0f62e1395c4ade976330b`,
`07834ed23fbdfcdb403009859e819d16d9eef55bb3521e9bab2b18b427e33ab1`,
`72918e5d2bcf4ce5161ec446330531a6891805435702d26e69680ec1260f3ef8`.

## S49: `IF_MODIFIED` as the original independent-model default

`@Model.eventPublication` now defaults to `IF_MODIFIED`; legacy `@Aggregate` behavior remains unchanged and an
explicit model- or apply-level `ALWAYS` retains intentional no-op events. The hot-path delta for a genuinely changed
model is one `Objects.equals(before, after)` call. It adds no serialization, materialization, transport or storage work;
an unchanged model instead avoids all model-event and global-event work.

The full canonical-shaped route compared the default candidate with the same current SDK/Runtime binary and an
explicit `ALWAYS` on only the benchmark's `EventModel`. Every run completed exactly 4,194,304 command results, stored
model events and global events plus 65,536 exact final states:

| Order | Model policy | Measured E2E | Qualification |
| --- | --- | ---: | --- |
| E728 A1 | explicit `ALWAYS` control | 200,066/s | host initially usable |
| E729 B1 | default `IF_MODIFIED` | 125,025/s | invalid for comparison: Spotlight began consuming substantial CPU |
| E730 A2 | explicit `ALWAYS` reverse control | 71,198/s | invalid; control fell below candidate as host load worsened |

At the post-E729 host snapshot `mds_stores` consumed 84% of one core, `mds` 33%, Docker's VM 22% and load average was
16.08. The 200k -> 125k delta is therefore not attributed to the equality check: the unchanged reverse control then
fell another 43% to 71k/s. These runs are durability/correctness evidence only and make no throughput claim. The
historical P5 pin remains unchanged. The Runtime benchmark source was restored exactly after the diagnostic.

Log SHA-256 in run order: `67a7fafe8c23ca8376fcd330bf5d40dae8237661b87987d004d2e1eb2b1fcdec`,
`16fd92e06ac32fe7c3ad9c40c7fd9006d500fc2436fe80e16fd7539958b1343f`,
`3b65c303493eaf4c1e606491d466d1dd59fcbb289f4379941093770032fdf287`.

## S56: complete current-message-batch model view

S40 made automatic model handlers observe earlier speculative automatic model changes in the same ordered tracking
segment. S56 extends that contract to the rest of the SDK surface: direct `loadModel`/`loadModels`, ordinary handler
model injection, explicit `assertAndApply` and stored-event application, ancestor injection and current graph loads.
The view remains scoped by application namespace, tracking batch and routing segment. Exact historical graph loads stay
durable-only. Pending producers become causal dependencies; their consumers reevaluate after real durability, failure
propagates without committing dependent state, and successful producers stop shadowing the canonical repository.

The first complete implementation indexed every evaluation eagerly in concurrent exact-ID and alias maps. It also
enabled the generic message-batch overlay inside the existing automatic commit loader, duplicating S40's selective
`BatchModelView`. The full route lost about 19%. Making the index lazy did not recover that loss, which causally showed
that the dominant regression was the duplicated automatic load route rather than map allocation alone. The corrected
candidate leaves automatic handlers on their existing selective S40 path and enables the generic overlay only for
ordinary/direct/explicit/graph reads that need the broader contract.

The two reverse-order final-source pairs before the last adversarial concurrency hardening are neutral in aggregate:
candidate geometric mean **223,925/s**, parent geometric mean **223,918/s**, or **+0.003%**. The host changed state
materially between the pairs, but each candidate was directly adjacent to its own parent control and all runs verified
exactly 4,194,304 results, stored model events and global events plus 65,536 final states. The historical clean-host P5
pin of 425,606/s remains the absolute reference; none of these loaded-host runs replaces it.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E731 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | eager generic message-batch index | 4,194,304 | 262,144 | none | 234,669/s | 190,914/s | true | reject: automatic route regression | reverted |
| E732 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | parent control | 4,194,304 | 262,144 | none | 234,669/s | 234,669/s | true | adjacent parent control | diagnostic-only |
| E733 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | eager generic message-batch index | 4,194,304 | 262,144 | none | 234,669/s | 189,596/s | true | confirm rejection | reverted |
| E734 | profile | full command -> model -> event + result | SDK `7555a29a8f9` | eager generic message-batch index | 1,048,576 | 262,144 | full JFR | n/a | 202,339/s | false | locate candidate CPU/allocation only | diagnostic-only |
| E735 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | lazy array journal, generic automatic route | 4,194,304 | 262,144 | none | 234,669/s | 191,498/s | false | lazy index alone does not recover E2E | reverted |
| E736 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | selective automatic route, pair 1 | 4,194,304 | 262,144 | none | 232,827/s | 233,739/s | true | +0.39%; continue reverse pair | accepted |
| E737 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | adjacent parent control, pair 1 | 4,194,304 | 262,144 | none | 232,827/s | 232,827/s | true | reverse parent control | diagnostic-only |
| E738 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | selective automatic route, pair 2 | 4,194,304 | 262,144 | none | 215,349/s | 214,523/s | true | -0.38%; aggregate neutral | accepted |
| E739 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | adjacent parent control, pair 2 | 4,194,304 | 262,144 | none | 215,349/s | 215,349/s | true | confirms host-level fall, not candidate regression | diagnostic-only |
| E740 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | post-pair candidate observation | 4,194,304 | 262,144 | none | n/a | 185,128/s | false | exclude: media analysis, Spotlight and UI CPU | diagnostic-only |
| E741 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | concurrency-hardened array journal, candidate 1 | 4,194,304 | 262,144 | none | 230,481/s | 223,236/s | true | -3.14%; require reverse adjacency | diagnostic-only |
| E742 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | adjacent parent control after E741 | 4,194,304 | 262,144 | none | 230,481/s | 230,481/s | true | parent control | diagnostic-only |
| E743 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | concurrency-hardened array journal, candidate 2 | 4,194,304 | 262,144 | none | 230,481/s | 230,841/s | true | +0.16%; contradicts E741 regression | diagnostic-only |
| E744 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | parent control after E743 | 4,194,304 | 262,144 | none | 170,862/s | 170,862/s | false | exclude: host state collapsed between adjacent runs | diagnostic-only |
| E745 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | concurrency-hardened array journal after E744 | 4,194,304 | 262,144 | none | n/a | 212,730/s | false | host recovered; not adjacent-comparable to E744 | diagnostic-only |

The adversarial follow-up found two uncommon journal cases after E740: repeated explicit operations in one handler
message and concurrent growth for an iterable without a known size. Both are now covered without adding a collection
to the ordinary one-evaluation path. E741-E745 used atomic slot arrays even though all journal access is already under
the same growth/index lock. Their one valid candidate/control neighborhood is mixed (-3.14% followed immediately by
+0.16%), while the attempted reverse pair is invalidated by an abrupt 230,841/s to 170,862/s host collapse. The final
source therefore replaces those redundant atomic slots with plain arrays; a final adjacent qualification of that exact
source is still required before the checkpoint. The current host is excluded while Spotlight, media analysis,
`knowledgeconstructiond`, a dashboard build and the Angular development server consume material CPU.

Evidence SHA-256: E731 `bd6d14b10eee5ca43ebc8aec11f444edca180a5c19726826fc846669fcf19635`, E732
`571984d6de4e007125c716d6e70f426307984c8667c8eea169d988a8e4c419ab`, E733
`ffa3726b98cd723df6ee426213cb2ab9473616a8d93d5d09fcb4d7c1e306ef53`, E734 log/JFR
`2b6d39195030098170dff4e397bc0166f155e20c018dda9bae6a8fe61d69133a` /
`ff9c78bf67c0c15ba4649ae3b9bf3881e4a37fc0093cf7b410aeb8b4c3cb9573`, E735
`77f11c08beb31d4c3edaedeb7ff621a86d3fadbdff2f9620bc910a4b17bc995c`, E736
`def2c235a729880428030f225dc9a6da586122c005522cda5c1772f207aa0f10`, E737
`5b7a76dd52851f742956e11ba64db0ce0ac246edf1980a6267d87437112f5347`, E738
`81b015c0d3fd39d42a9ebc6c7cd451982156b4aadc5c6fa642e7d2e7bf19e948`, E739
`c46893400a7f5c705a34a40de291c5a4b67ca8e5dc97028fee2a20aedb8c8d73`, E740
`79f209a8c174f3082fe8eac746d419afdfa587e384b35d5c0dd06ff931f8b34d`, E741
`faec82fb58c4b19fc47c8ba9aa9f4d52fbb9b8b206a42e794407d113b5e9bd67`, E742
`cc4ac47f2f2126e5b9840c15d3b841ebeed9a141d679becd60ca7fdfe143e30c`, E743
`9ab1192487e094740b18b3706c88bb84851aed68f3363707d89a26ad3022e625`, E744
`8a9435559943f91cf6313f0310f6e22b893f1b953634f056d56fbcd5df5aa6fd`, E745
`51b3877c62764ff831bff5633ea7514285606ab12e77de9f65d8949f3b120709`.

The first exact-source qualification attempt, E746-E748, remained unsuitable for a relative decision: Spotlight and
media analysis consumed substantial CPU throughout the first candidate, changed state before the parent and remained
active during the final candidate. E749 is also excluded because its invocation accidentally omitted the canonical
1,048,576-entry command-cache override and therefore exercised the 65,536-entry compatibility default. All four runs
still passed the complete durability and final-state verification.

The corrected final candidate-parent-candidate sequence E750-E752 used the same Runtime, Java 25 process, database and
canonical benchmark identity, including the 1,048,576-entry command cache. The laptop remained too busy for an
absolute pin—WindowServer, Codex/Chrome rendering and Docker were active, and Dropbox/IntelliJ activity appeared around
E752—but the parent was below both directly adjacent candidate runs:

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E746 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | final plain-array journal | 4,194,304 | 262,144 | none | n/a | 230,095/s | false | exclude: Spotlight/media analysis host load | diagnostic-only |
| E747 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | parent control | 4,194,304 | 262,144 | none | 261,903/s | n/a | false | exclude: host state changed after E746 | diagnostic-only |
| E748 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | final plain-array journal | 4,194,304 | 262,144 | none | 261,903/s | 240,762/s | false | exclude: background indexers remained active | diagnostic-only |
| E749 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | final plain-array journal | 4,194,304 | 262,144 | none | n/a | 172,947/s | false | exclude: noncanonical 65,536-entry command cache | diagnostic-only |
| E750 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | final plain-array journal | 4,194,304 | 262,144 | none | 163,772/s | 173,922/s | true | +6.20% versus following parent | accepted |
| E751 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | parent control | 4,194,304 | 262,144 | none | 163,772/s | n/a | true | bracketed parent control | diagnostic-only |
| E752 | canonical | full command -> model -> event + result | SDK `7555a29a8f9` | final plain-array journal | 4,194,304 | 262,144 | none | 163,772/s | 194,940/s | true | +19.03% versus preceding parent | accepted |

The two candidate observations have a geometric mean of **184,131/s** versus **163,772/s** for their shared adjacent
parent, or **+12.43%**. This is acceptance evidence that the complete current-message-batch view does not regress the
canonical route; it is not a new absolute throughput claim. The clean-host P5 pin remains **425,606/s**.

Evidence SHA-256: E746 `3a2157c96490baf9a27cd2b67a3a130d31c0e33a42d7d910c6708ef77b8a5214`, E747
`4b35d2985e2e122fee94ba06a01e0bd899960fce8696cd14500e801870ccd275`, E748
`c6fecca2b81c7b6fc422d145d6a52b3e0df38ef709c5576e75dad65971b8c2e7`, E749
`977b15c98a0b2d3aaa58e7e83e7b6ddbc867d210053073fc9c434be78458e6ea`, E750
`1cbeaa3a005b5c170ca36ca46434f32bf44732423fd90a783915358f791c6b4e`, E751
`4b0776389793a58a2db0b97c9d9a0f28eb7595e60925fb4fdb2cda4502bccf39`, E752
`debbd7c47df6ecc959a10e0d249bca06636a7077b70f5306519b2099df18eece`.

Final verification evidence: focused message-batch/model suite 156 tests
`2433758d072d620edbb1e2814ceaf2c457af398ad1754ce653883f75131996a0`; last exact full SDK reactor install
`e120caae549a504899e549d8ff15f0883253d77ee7df5cfcd2389da20d15030a`.

### S57 — lazy pathless graphs and owned descendant deletion

The S57 candidate adds pathless graph traversal and logical descendant deletion for owned `@ParentId` relationships.
The initial implementation made two complete additional normal-route scans: the SDK rediscovered cascade roots after
the cascade planner had already identified them, and the Runtime scanned every prepared model batch before deciding
whether cascade validation was needed. E753-E755 retained those scans. On the consistently overloaded host, their
candidate geometric mean was **144,957/s** versus **150,229/s** for the bracketed parent (**-3.51%**), so that
implementation was rejected rather than checkpointed.

The corrected implementation carries cascade roots out of the existing SDK evaluation pass and records the Runtime's
`hasCascadeDeletes` bit while it already filters duplicate commits. The normal update route therefore performs no
second cascade-discovery scan, while actual deletes still reconstruct and validate the owned graph. E756-E758 use the
same canonical route, Java 25 runtime, PostgreSQL instance, command count, warmup, caches and message-store settings as
the parent:

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E753 | canonical | full command -> model -> event + result | SDK `c208e89f` + Runtime `ed543cd8` | first cascade implementation | 4,194,304 | 262,144 | none | 150,229/s | 139,030/s | false | exclude absolute result: Spotlight and host load | diagnostic-only |
| E754 | canonical | full command -> model -> event + result | SDK `c208e89f` + Runtime `ed543cd8` | parent control | 4,194,304 | 262,144 | none | 150,229/s | n/a | false | loaded-host parent for rejected first candidate | diagnostic-only |
| E755 | canonical | full command -> model -> event + result | SDK `c208e89f` + Runtime `ed543cd8` | first cascade implementation | 4,194,304 | 262,144 | none | 150,229/s | 151,137/s | false | first implementation geometric mean -3.51% | diagnostic-only |
| E756 | canonical | full command -> model -> event + result | SDK `c208e89f` + Runtime `ed543cd8` | cascade hot-path correction | 4,194,304 | 262,144 | none | 142,032/s | 165,554/s | true | +16.56% versus following parent | accepted |
| E757 | canonical | full command -> model -> event + result | SDK `c208e89f` + Runtime `ed543cd8` | parent control | 4,194,304 | 262,144 | none | 142,032/s | n/a | true | bracketed loaded-host parent control | diagnostic-only |
| E758 | canonical | full command -> model -> event + result | SDK `c208e89f` + Runtime `ed543cd8` | cascade hot-path correction | 4,194,304 | 262,144 | none | 142,032/s | 174,438/s | true | +22.82% versus preceding parent | accepted |

The corrected candidate observations have a geometric mean of **169,938/s**, or **+19.65%** versus their shared
adjacent parent. Every run completed exactly 4,194,304 command results, stored model events and global events and
verified all 65,536 final model states. This accepts the absence of a normal-route regression; it does not replace the
clean-host P5 absolute pin of **425,606/s**. Spotlight, Contacts, Chrome rendering and WindowServer remained active, so
all absolute E753-E758 throughput and latency observations are non-qualifying.

Evidence SHA-256: E753 `76bdeaa40f0d7ecd9b9835dd55b3c73d96b989c0dd89e6322a51b380a5786e56`, E754
`d553727968d5865a9f0fb2de2c3f4eea941ddc6a061d5a513de8b1c4990fc9e2`, E755
`ef50f8e705d5a9deba24172b84ed41e6fb7e8e91c6f71336942258b4515b2ece`, E756
`835d8a97bf1c5eb3b2bd60caa5664a20f11f95470cd18f0eee4a4536da9244b9`, E757
`e35364ca1477a068feaf37ad7c3c4510445a69253c0fd11daffb3e2bb951d397`, E758
`54b262949c32b428caf26d9211f2786c9bbc5c1fe4bdd23768ac05c2d4d791e5`.

### S57 — additive Graph ergonomics and cache-eviction liveness

SDK `2848311dafe` completes the public `Graph<T>` convenience surface with value optionals, wrapper mapping and
filtering, direct parent/ancestor values, lazy deterministic traversal, identity/alias lookup, ordered
`assertAndApply` and revision metadata. These are additive interface default methods. Constructing or injecting a
Graph still follows the previously qualified path, and relationship loading still starts only when navigation or a
returned traversal is consumed. SDK `70aa36d7918` separately releases a lookup waiting on a stale refresh when the
corresponding cache entry is evicted; its extra branch executes only in the existing eviction listener and does not
change the cache-hit, model-apply or model-commit path.

The Java 25 full SDK reactor completed all nine modules and 2,170 SDK tests. A representative downstream application
also completed its full backend, utility, reporting and frontend reactor after replacing normal injected `Entity<T>`
history/context usage with `Graph<T>` and moving long-running orchestration out of passive core models.

E759-E762 attempted a direct-parent comparison against SDK `a871fe2f7cf` with Runtime `f274bee8`. Every run completed
exactly 4,194,304 results, stored model events and global events plus 65,536 final states, with JFR disabled and the
canonical 1,048,576-entry command cache. The host changed state too strongly for a throughput decision:

| run | source | throughput | decision |
| --- | --- | ---: | --- |
| E759 | direct parent | 116,485/s | loaded-host control only |
| E760 | candidate | 113,106/s | -2.90% versus E759; inconclusive |
| E761 | candidate | 100,417/s | host continued to fall |
| E762 | direct parent | 178,073/s | abrupt 77.33% rebound invalidates the reverse pair |

The 100,417/s to 178,073/s source reversal, alongside concurrent IDE, UI, dev-server and renderer activity, makes
neither pair canonical-comparable. The runs are retained as exact correctness evidence and are not averaged. The
historical clean-host P5 pin remains **425,606/s**. Source inspection shows that the added Graph defaults are not
invoked by the B0 route and that the cache change runs only from its existing eviction listener, but this does not by
itself prove throughput equivalence. Because both invalid pairs happened to favour the parent, the performance gate
remains open pending a quiet-host repin.

Evidence SHA-256: E759 `3b8448bcd3f68b048d6d206732ca3552d0960c163de7ff54c4b93d44ebb1d921`, E760
`e4864a7e5206749ccf708b2d3fe5622228ccdbe4edeef42eac4c968f59a282fb`, E761
`dbb55120a2686217bc8117b81ec5eaaca8a87faa0fd7573a4ca4857b619835b0`, E762
`29164995ef5de8b9a0f8ff2051ab2eb8fd35d1b48db9e10f8a076632412bf184`.

The loaded-host E763-E768 causal screen deliberately reversed the source order and split the candidate into direct
parent `a871fe2f7cf`, cache-eviction fix only `70aa36d7918` and complete Graph ergonomics `2848311dafe`. It used Java
25, Runtime `f274bee8`, the same database, 1,048,576-entry command cache, 65,536 models and 262,144 warmup updates, but
only 1,048,576 measured commands per run. Chrome renderers consumed roughly 28-78% CPU before most runs and load
averages varied from 6.99 to 12.99, so all six observations are `smoke` and `diagnostic-only`:

| run | source | throughput | exact route result |
| --- | --- | ---: | --- |
| E763 | complete candidate | 149,490/s | 1,048,576 results and both event kinds; 65,536 states |
| E764 | direct parent | 115,454/s | exact |
| E765 | cache fix only | 127,934/s | exact |
| E766 | complete candidate | 151,079/s | exact |
| E767 | cache fix only | 157,136/s | exact |
| E768 | direct parent | 144,695/s | exact |

The complete candidate was stable at 149,490-151,079/s and exceeded both parent observations. Its geometric mean was
150,282/s versus 129,250/s for the parent (+16.27%). This directly disproves that the earlier parent advantage is a
stable consequence of the candidate binary. The cache-only observations ranged from 127,934 to 157,136/s, so this
loaded screen cannot attribute a smaller effect to either commit and does not close the non-regression gate. The next
qualifying step remains an alternating full 4,194,304-command series on a quiet host; production code is unchanged.

Log SHA-256: E763 `6843add1040917a9d169c019247a4f72f3723907016b77dcb3e7fe5c29457e6c`, E764
`2a9abad22d4b3d141a1574c193300577073e0be1d47f83fc66d29b114737dc8e`, E765
`7a773a3de49509204b6237e78a4d03be5b5978b6e901a7dc9c7245d83d43299a`, E766
`4e22130423677fcb3bb4458839dd2d5d5770350085d5b605ae7794cd9680f5a0`, E767
`3b2e559ff9ea7cf4ea88601c684a39a278a0d003136e3c4132aa8ec5c82e99d8`, E768
`686ab5ea0132e41f9ebfadb6587b7c09645c5a41584e24d967ca92a374882df7`.

### S57 — complete graph-change handlers

The graph-change candidate adds an explicitly cold route for handlers whose sole domain parameter is an unqualified
`Graph<T>`. Registration scans those methods once. A handler class without such a method returns the exact existing
prepared handler object, and an ordinary event handler or an explicit `(event, Graph<T>)` handler retains its existing
selection and injection path. The canonical benchmark's automatic command handler therefore executes no new branch,
receipt lookup, graph load or allocation because of this feature.

On the actual graph-change route, the SDK resolves the exact durable commit receipt only after event selection. It
loads and deduplicates every current and previous root affected by the substep, including both roots of a child move,
then supplies a complete `previous()` graph. Focused in-memory integration covers moves, multi-target cascade deletes,
event and notification handlers, creation/deletion boundaries and rejection of ordinary events without model-commit
metadata. The JDBC Runtime test proves exact target IDs, types, event/state boundaries and persistence across restart.
The full Java 25 SDK and Runtime reactors are green.

No throughput run was started for this checkpoint. At qualification time `mediaanalysisd`, `contactsd` and
`mds_stores` each consumed roughly 48-61% of a CPU, alongside long-running Java/dev processes. That host state matches
the already excluded E759-E768 regime and cannot replace the clean P5 **425,606/s** pin. The quiet-host alternating
4,194,304-command performance gate therefore remains open; this checkpoint makes no absolute or matched throughput
claim.

### S57 — atomic interceptor `Graph.delete()` targets

The final candidate lets an `@InterceptApply` result combine its ordinary domain update with one or more deleted
graphs. Every graph target is re-resolved at the commit's pinned state boundary, participates in the same atomic
`CommitModels` request and follows its own model publication policy. The original domain message is serialized once:
one commit substep and one event ID are shared by the ordinary model transition and all graph-deletion targets. An
`ACCEPT` conflict internally replays the ordinary apply and deletions separately against the fresh boundary, then
fuses them back into the same one-event commit step.

The first two internal representations were deliberately rejected. They inspected or enlarged each ordinary pending
substep merely to recognize the rare deletion route. The final representation restores the original two-field
`PendingSubstep`; only an actual deletion uses a private message subtype. The ordinary accepted route retains its
original event and transition representation.

The laptop was not suitable for an absolute repin. Mail, a virtualization VM, `searchpartyd`, IntelliJ, dev servers and
Codex/ChatGPT renderers changed CPU pressure during and even within processes. Full-route observations ranged from
127,075/s to 232,365/s; warmup and measured throughput frequently moved in opposite directions. Every completed E2E
run nevertheless verified its exact result count, both event kinds and all 65,536 final model states. The clean-host P5
pin therefore remains **425,606/s** and none of E769-E784 changes the live scoreboard.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E769 | canonical | full command -> model -> event + result | SDK `d5e30ad8b49` | first graph-delete preflight | 4,194,304 | 262,144 | none | n/a | n/a | false | invalid: obsolete classpath file; JVM failed before Runtime startup | diagnostic-only |
| E770 | canonical | full command -> model -> event + result | SDK `d5e30ad8b49` | first payload-backed deletion | 4,194,304 | 262,144 | none | 179,537/s following parent | 135,763/s | false | exclude: media analysis and host state changed | reverted |
| E771 | canonical | full command -> model -> event + result | SDK `d5e30ad8b49` | parent control | 4,194,304 | 262,144 | none | 179,537/s | n/a | false | loaded-host control | diagnostic-only |
| E772 | canonical | full command -> model -> event + result | SDK `d5e30ad8b49` | first payload-backed deletion | 4,194,304 | 262,144 | none | 179,537/s preceding parent | 146,364/s | false | exclude and remove normal-route payload inspection | reverted |
| E773 | canonical | full command -> model -> event + result | SDK `d5e30ad8b49` | parent control | 4,194,304 | 262,144 | none | 167,300/s | n/a | false | loaded-host control | diagnostic-only |
| E774 | canonical | full command -> model -> event + result | SDK `d5e30ad8b49` | split pending-substep deletion | 4,194,304 | 262,144 | none | 167,300/s preceding parent | 127,075/s | false | exclude; host warmup fell to 91,794/s | reverted |
| E775 | canonical | full command -> model -> event + result | SDK `d5e30ad8b49` | parent control | 4,194,304 | 262,144 | none | 212,796/s | n/a | false | abrupt parent rebound proves host instability | diagnostic-only |
| E776 | canonical | full command -> model -> event + result | SDK `d5e30ad8b49` | split pending-substep deletion | 4,194,304 | 262,144 | none | 212,796/s preceding parent | 163,591/s | false | exclude; replace polymorphic pending layout | reverted |
| E777 | smoke | full command -> model -> event + result | SDK `d5e30ad8b49` | parent control | 1,048,576 | 262,144 | none | 175,173/s | n/a | false | short loaded-host control | diagnostic-only |
| E778 | smoke | full command -> model -> event + result | SDK `d5e30ad8b49` | original pending layout + deletion subtype | 1,048,576 | 262,144 | none | 175,173/s preceding parent | 147,261/s | false | host warmup fell by 18.5% between processes | reverted |
| E779 | smoke | full command -> model -> event + result | SDK `d5e30ad8b49` | parent control | 1,048,576 | 262,144 | none | 186,745/s | n/a | false | reverse short control | diagnostic-only |
| E780 | smoke | full command -> model -> event + result | SDK `d5e30ad8b49` | original pending layout + deletion subtype | 1,048,576 | 262,144 | none | 186,745/s preceding parent | 151,141/s | false | measured throughput fell while warmup rose; extract cold logic | reverted |
| E781 | smoke | full command -> model -> event + result | SDK `d5e30ad8b49` | cold deletion evaluation | 1,048,576 | 262,144 | none | 178,114/s following parent | 161,443/s | false | first half of order-reversed host screen | diagnostic-only |
| E782 | smoke | full command -> model -> event + result | SDK `d5e30ad8b49` | parent control | 1,048,576 | 262,144 | none | 178,114/s | n/a | false | order-reversed control | diagnostic-only |
| E783 | smoke | full command -> model -> event + result | SDK `d5e30ad8b49` | cold deletion evaluation | 1,048,576 | 262,144 | none | 178,114/s preceding parent | 232,365/s | false | +30.5% reversal disproves a stable candidate cap | diagnostic-only |
| E784 | smoke | full command -> model -> event + result | SDK `d5e30ad8b49` | final one-event graph deletion | 1,048,576 | 262,144 | none | n/a | 153,667/s | false | exact final-source correctness; absolute throughput excluded | accepted |

E785-E788 isolate the actual `ModelCommitEngine.evaluate(message, resolver)` route without Runtime, PostgreSQL or
transport feedback. Each process performs 300,000 warmups and 2,000,000 measured evaluations using the same real
model metadata, resolver, `@Apply` and commit-evaluation construction. Parent observations were 2,174,844 and
1,989,167 evaluations/s; final candidate observations were 2,250,159 and 2,142,056/s. Their geometric means are
2,079,935/s and 2,195,442/s respectively (**+5.55% candidate**). This supporting diagnostic rules out a material
engine-service regression; it is not a substitute for the full E2E acceptance route.

Evidence SHA-256: E769 `fd88c6886346b93c810b9b2225007380f56e507d05753d9411d9c4e7e87efc0a`, E770
`ccd994c600cd4c57666b677e5aaf9d937d69208ca702fe4a6451ab4bdbd4f137`, E771
`891952301c9f435da970b4813590a613178da52908e4e4e4613816f3cbd203e7`, E772
`1758d4b92909b40d7930151ca044de6a36a0dc729cf143fa22a1838db97cc5ac`, E773
`c298ecb25e48be5fe662ff23cd1fb4c841501ee9ad4a54d63ca9f4a67cb424c8`, E774
`550726096fac4d913bc1eb5103caf711626c6fd3aa8530294004e6b87b15b068`, E775
`84bd63ba4d091056df3f0f2c85bb085943356bd5bf11788743118b044d3bae78`, E776
`172b6a649b6171934a36093aa7bd7b35ee91eb733912a69668a705e63f4e6528`, E777
`f2a404a856ad4c4b6f0f6d9c378af015be8c5ca3aaa418695ff4e7b38e2baa7a`, E778
`21495c092c28931f741aa69e2f061ab6bd3e106c4f76822a30789b43c6f191a7`, E779
`87202ab2489342e9f8cae7f3580c01306cb8650dcf4e23113259315a83f306db`, E780
`020f9e755bfcabb9faf4b93a7b1c5f4f05d10b777e43ca7c50c41bc11752e5e4`, E781
`2e634e1adfaa1c03e6fad3dc47b5dada2d5acfbcb6b1fb63269b7af9e01427b3`, E782
`1076d2b54d43d45b7f51647d246f6bd16a7dbf23d7323f331f987e2d0f45bb62`, E783
`782e5e7ea4374624d1b207897d6026b48f44c5d387248be7863faed02aae502a`, E784
`bbc24760e694b1bae34010dc53eabd731f272c992af7c1f2b4f47d968fbb69d2`, E785-E788
`bf20737c4bb234c31731b2a00f839cf40abdbd4a9039f6a0da040c68361068d9`.

### S57 — complete typed-Graph downstream rebuild

F18 makes typed graphs identity-lazy, adds opt-in parent-scoped child identity, exposes response-wide typed graph context
and discovers registered model relationship declarations for stable empty arrays. The full B0 route does not navigate a
graph or use parent-scoped models, but it does exercise ordinary model target resolution and apply-result validation.

The first complete candidate accidentally read every ordinary apply result's `@EntityId` twice: once to validate the
result and again through the new parent-scoped convenience. E789-E792 consistently favoured the parent and were
therefore not accepted even though the host was strongly loaded. The corrected implementation restores the exact
single-read ordinary-model path and branches into parent resolution only for a type with
`@EntityId(parentScoped = true)`.

E793-E796 repeat the full 65,536-model, 262,144-warmup and 4,194,304-command route in candidate-parent-candidate-parent
order, without JFR. Every run completed exactly 4,194,304 results, stored model events and global events and verified
all final model states. The first final pair is effectively neutral at -1.82%; the reverse pair is +24.91%. Final
candidate geometric mean is 181,685/s versus 164,062/s for the adjacent parent controls, or **+10.74%**. The host moved
between the two pairs and absolute throughput remains far below the clean P5 **425,606/s** pin, so these observations
only accept the absence of a regression against direct SDK parent `708dcbfec7b`; they do not establish a new absolute
pin.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E789 | canonical | full command -> model -> event + result | SDK `708dcbfec7b` | first F18 parent-scoped identity path | 4,194,304 | 262,144 | none | 138,247/s following control | 124,191/s | false | reject double reflective ID read; host also moved | reverted |
| E790 | canonical | full command -> model -> event + result | SDK `708dcbfec7b` | parent control | 4,194,304 | 262,144 | none | 138,247/s | n/a | false | warmup jumped from 107,410/s to 166,872/s | diagnostic-only |
| E791 | canonical | full command -> model -> event + result | SDK `708dcbfec7b` | first F18 parent-scoped identity path | 4,194,304 | 262,144 | none | 148,159/s following control | 146,762/s | false | confirms no upside; remove duplicate ID read | reverted |
| E792 | canonical | full command -> model -> event + result | SDK `708dcbfec7b` | parent control | 4,194,304 | 262,144 | none | 148,159/s | n/a | false | loaded-host control | diagnostic-only |
| E793 | canonical | full command -> model -> event + result | SDK `708dcbfec7b` | final F18 single-read ordinary path, pair 1 | 4,194,304 | 262,144 | none | 146,558/s following control | 143,889/s | true | -1.82%; require reverse pair | accepted |
| E794 | canonical | full command -> model -> event + result | SDK `708dcbfec7b` | parent control, pair 1 | 4,194,304 | 262,144 | none | 146,558/s | n/a | true | adjacent parent control | diagnostic-only |
| E795 | canonical | full command -> model -> event + result | SDK `708dcbfec7b` | final F18 single-read ordinary path, pair 2 | 4,194,304 | 262,144 | none | 183,656/s following control | 229,410/s | true | +24.91%; aggregate no-regression gate passes | accepted |
| E796 | canonical | full command -> model -> event + result | SDK `708dcbfec7b` | parent control, pair 2 | 4,194,304 | 262,144 | none | 183,656/s | n/a | true | reverse parent control | diagnostic-only |

Evidence SHA-256: E789 `c8e2d1fbaa37b27f75d5f3026c98a1621f239a77f6d6d5b279f74a91ea952c4e`, E790
`80301abf6514223f20e4697ba899ecab45f8d7674d4ae51f89f09150f89a66a1`, E791
`e2e09b158ff314ebfb7760250c0cee1259cb792a78c532606849996c9d1588bb`, E792
`5de60a01ebf6b0155cd0b4e4e4736c0e50e045589a866e5db6355ef92f4ef4db`, E793
`1e63e568d10a566de1906eeead920333520be0037e94119eaa621e95c47ab586`, E794
`45cf3860fc65d3d1b278d1cf6624031c103e26994f22572add601b3dc83d2492`, E795
`3dd06b7c5e2c8550a8b1a7ddbe886a1f06f13fe2b67da3b8ec218703de622a6a`, E796
`b38b9182fbab0aa9296811b9c6e8b194e8d572e15cb72b71d7808173773474a4`.

### S57 — final graph-scoped handling checkpoint under unstable host load

SDK `a3dce65bfe8` completes graph-scoped handling validated by a representative downstream application on direct parent
`c9e280daeac`. It adds an explicit graph target only when `Graph.assertAndApply(...)` supplies one. The ordinary B0
route retains its compiled direct single-target plan; its only new registry operation is one empty message-context
lookup before selecting that existing path. Apply-result validation reuses the already required current value and does
not add another model load or ID reflection. Graph traversal, materialized-graph composition and graph filtering are
absent from B0.

E797-E800 initially appeared to show a severe regression. They used the complete 65,536-model, 262,144-warmup and
4,194,304-command route in candidate-parent-candidate-parent order. Every run was exact, but candidate geometric mean
was 137,685/s versus 175,083/s parent, or -21.36%. This was not accepted as causal because the host was already far
below the clean P5 pin and process-to-process warmup and measured capacity moved independently.

E801-E804 then overlaid the direct-parent registry, engine and target-resolver class clusters independently onto the
candidate. None restored a stable parent level. In particular, the target-resolver overlay reached 177,553/s, after
which the unmodified candidate immediately reached 196,131/s. These overlays therefore exclude a single obvious class
cluster but are not production acceptance measurements.

E805-E807 form an adjacent candidate-parent-candidate bracket on the unchanged 14-processor configuration. Candidate
observations were 196,131 and 126,153/s around a 159,666/s parent. Their geometric candidate interpolation is
157,298/s, only **-1.48%** versus parent despite a 55.47% spread between the two candidate processes. This directly
shows that binary identity did not cause the earlier 21.36% gap.

E808-E811 deliberately reduce only JVM-visible processors from fourteen to eight to lower thermal saturation, then
run a balanced parent-candidate-candidate-parent sequence. The full durable route and every count remain unchanged.
Parent observations were 130,702 and 220,999/s; candidate observations were 233,509 and 180,793/s. Geometric means are
169,956/s parent and 205,467/s candidate, or **+20.89% candidate**. The wide controls still prohibit a new absolute pin
or speedup claim, but the balanced order and the independent 14-processor bracket jointly reject a material regression
against the direct parent. The historical clean-host P5 **425,606/s** remains the only absolute model-E2E pin.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E797 | canonical | full command -> model -> event + result | SDK `c9e280daeac` | SDK `a3dce65bfe8`, long pair 1 | 4,194,304 | 262,144 | none | 195,699/s following parent | 136,304/s | false | loaded-host observation; require causal screen | diagnostic-only |
| E798 | canonical | full command -> model -> event + result | SDK `c9e280daeac` | direct-parent control, long pair 1 | 4,194,304 | 262,144 | none | 195,699/s | n/a | false | loaded-host control | diagnostic-only |
| E799 | canonical | full command -> model -> event + result | SDK `c9e280daeac` | SDK `a3dce65bfe8`, long pair 2 | 4,194,304 | 262,144 | none | 156,639/s following parent | 139,080/s | false | apparent aggregate -21.36%; not causal under moving host | diagnostic-only |
| E800 | canonical | full command -> model -> event + result | SDK `c9e280daeac` | direct-parent control, long pair 2 | 4,194,304 | 262,144 | none | 156,639/s | n/a | false | loaded-host control | diagnostic-only |
| E801 | smoke | full command -> model -> event + result | SDK `c9e280daeac` | complete SDK `a3dce65bfe8` control | 1,048,576 | 262,144 | none | n/a | 164,125/s | false | ablation control | diagnostic-only |
| E802 | smoke | full command -> model -> event + result | SDK `c9e280daeac` | parent registry class overlay | 1,048,576 | 262,144 | none | n/a | 150,980/s | false | registry overlay does not restore capacity | diagnostic-only |
| E803 | smoke | full command -> model -> event + result | SDK `c9e280daeac` | parent engine class overlay | 1,048,576 | 262,144 | none | n/a | 169,951/s | false | small apparent gain is below host movement | diagnostic-only |
| E804 | smoke | full command -> model -> event + result | SDK `c9e280daeac` | parent target-resolver class overlay | 1,048,576 | 262,144 | none | n/a | 177,553/s | false | immediately disproved by faster unmodified candidate | diagnostic-only |
| E805 | smoke | full command -> model -> event + result | SDK `c9e280daeac` | SDK `a3dce65bfe8`, bracket A | 1,048,576 | 262,144 | none | 159,666/s following parent | 196,131/s | false | first candidate side of adjacent bracket | accepted |
| E806 | smoke | full command -> model -> event + result | SDK `c9e280daeac` | direct-parent bracket control | 1,048,576 | 262,144 | none | 159,666/s | n/a | false | candidate interpolation differs by only -1.48% | diagnostic-only |
| E807 | smoke | full command -> model -> event + result | SDK `c9e280daeac` | SDK `a3dce65bfe8`, bracket B | 1,048,576 | 262,144 | none | 159,666/s preceding parent | 126,153/s | false | second candidate side; bracket rejects earlier large binary effect | accepted |
| E808 | smoke | full command -> model -> event + result, 8 JVM processors | SDK `c9e280daeac` | direct-parent P1 | 1,048,576 | 262,144 | none | 130,702/s | n/a | false | balanced reduced-thermal control | diagnostic-only |
| E809 | smoke | full command -> model -> event + result, 8 JVM processors | SDK `c9e280daeac` | SDK `a3dce65bfe8`, C1 | 1,048,576 | 262,144 | none | 130,702/s preceding parent | 233,509/s | false | balanced sequence candidate | accepted |
| E810 | smoke | full command -> model -> event + result, 8 JVM processors | SDK `c9e280daeac` | SDK `a3dce65bfe8`, C2 | 1,048,576 | 262,144 | none | 220,999/s following parent | 180,793/s | false | balanced sequence candidate | accepted |
| E811 | smoke | full command -> model -> event + result, 8 JVM processors | SDK `c9e280daeac` | direct-parent P2 | 1,048,576 | 262,144 | none | 220,999/s | n/a | false | +20.89% geometric candidate; no-regression only | diagnostic-only |

Evidence SHA-256: E797 `3de2b077be915a6445688210ea139a8cc5563cf36294a1c34eb980fe349e4eac`, E798
`f0d32e56d02dd85ad9f3f43d25d0121ff13c6d624c3210089fa75dee24b85e93`, E799
`37a0074b681fad95815349d61bc73bf4966bbf887411899f2a08f2165b58d91f`, E800
`f565e5d65acfbc5c9d27196d0500231d11c6adbb9d54d470996a60d00fa01788`, E801
`dd60681672ce3086662089e673ca6ff8f2c3e4521b45ed050babb62bb5015cc5`, E802
`2740d52a6832165b51479d8259d6079d78085a1034e17c5b8b06ab7113ffd21f`, E803
`3bbfc1d5cc958b34fbadf8b47e011109f0bbab99b2a6adbc6c1e65a6fd13046f`, E804
`7146e0939635b578785a293a9aad9a0038f415f3391a79a0bbc15a645432d9ed`, E805
`e26d2a3b9e1757ff2ecd3f177f7d327f5e75a71c85ab13abe07cb042c05d6cc9`, E806
`e5db65fc7adc0e3bfbe7e0e4001310ee3976300cd3978589cd50ac957c0ffafe`, E807
`c1c2027a277cf9c3aabbd95a290b5e3ffd6810b893c71d89e7b3017596b2b8a0`, E808
`69d6f0b1d69b3636128d0004903124cc8dd29d6cb686a043e8dcdfa4579664b2`, E809
`a6545e0f53cf478ac3fa07e4fcda0e956aa8e3bca9b5c10cc7c2696a6ff9f903`, E810
`dea4b745fd32e2f1a5a0fd1c0b245cac0f5390e35163cda0f3f8c7ca1befcf8d`, E811
`1c547a75ca957c97dc4e45637236c7e0f9349e2cabaddde391e469108b3e15a6`.

### S60 — generic WebSocket/Runtime boundary refactor

SDK `625938a5db8` and Runtime `610a7060` remove modeling-specific knowledge from generic WebSocket and endpoint
infrastructure. Their exact direct parents are SDK `db964fdf027` and Runtime `3ffe241f`. The candidate and control
binaries were compiled separately and run on Java 25 with the latest SDK defaults, an embedded Runtime, PostgreSQL, an 8-GiB
fixed heap, no JFR, 16 command-consumer threads, a 65,536 request window and 32-byte payloads.

E812-E815 attempted the historical 65,536-model route first. Neither parent nor candidate reached the measured phase:
the initial-create phase completed only approximately one 8K wave before the derived stream-locator path exceeded the
benchmark's 60-second completion bound. Changing from the fresh isolated database to an isolated clone of the former
benchmark database and reducing JVM-visible processors to eight did not change that outcome. These runs therefore do
not establish throughput and are not evidence for or against the refactor. They do establish that the clean P5
**425,606/s** pin was not freshly reproduced on this host state.

E816-E822 retained the complete command -> automatic `@Apply` -> atomic model/event commit -> durable ordinary result
route, but reduced the conflict-free model set to 8,192 so the pre-measurement seed could finish. Every non-sleep run
completed exactly 1,048,576 results, stored model events and global events and verified every final model state. E820
is excluded because the laptop slept during the run; the log records roughly 926 seconds of artificial cache lag.

The first bracket experienced a large host collapse at E819: its warm-up fell from 87-109K/s to 14.6K/s and measured
throughput fell to 50K/s. The sleep-protected adjacent E821/E822 pair is the strongest comparison: parent
**151,642/s**, candidate **169,942/s** (**+12.07%**). Across all three non-sleep candidate observations and the two
controls that did not suffer the E819 collapse, geometric means are **160,548/s candidate versus 161,043/s parent
(-0.31%)**. This rejects a material regression from the generic-boundary refactor, but is deliberately classified as a
non-canonical no-regression screen rather than a new absolute pin.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | model_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E812 | canonical | full command -> model -> event + result | SDK `db964fdf027` + Runtime `3ffe241f` | parent long control | 4,194,304 | 262,144 | 65,536 | none | n/a | n/a | false | seed timed out with 57,290 incomplete commands | diagnostic-only |
| E813 | smoke | full command -> model -> event + result | SDK `db964fdf027` + Runtime `3ffe241f` | parent short control | 1,048,576 | 262,144 | 65,536 | none | n/a | n/a | false | seed timed out with 57,588 incomplete commands | diagnostic-only |
| E814 | smoke | full command -> model -> event + result | direct parents | current 14-processor diagnostic | 1,048,576 | 262,144 | 65,536 | none | n/a | n/a | false | seed timed out with 53,740 incomplete commands | diagnostic-only |
| E815 | smoke | full command -> model -> event + result, 8 JVM processors | direct parents | current isolated-clone diagnostic | 1,048,576 | 262,144 | 65,536 | none | n/a | n/a | false | seed timed out with 57,348 incomplete commands | diagnostic-only |
| E816 | smoke | full command -> model -> event + result, 8 JVM processors | direct parents | parent P1 | 1,048,576 | 262,144 | 8,192 | none | 171,026/s | n/a | false | exact loaded-host control | diagnostic-only |
| E817 | smoke | full command -> model -> event + result, 8 JVM processors | direct parents | current C1 | 1,048,576 | 262,144 | 8,192 | none | 171,026/s preceding | 158,621/s | false | exact current observation | accepted |
| E818 | smoke | full command -> model -> event + result, 8 JVM processors | direct parents | current C2 | 1,048,576 | 262,144 | 8,192 | none | 50,155/s following | 153,515/s | false | exact current observation before host collapse | accepted |
| E819 | smoke | full command -> model -> event + result, 8 JVM processors | direct parents | parent P2 | 1,048,576 | 262,144 | 8,192 | none | 50,155/s | n/a | false | exact but host collapsed; warm-up only 14.6K/s | diagnostic-only |
| E820 | smoke | full command -> model -> event + result, 8 JVM processors | direct parents | current C3 | 1,048,576 | 262,144 | 8,192 | none | n/a | n/a | false | laptop sleep; approximately 926-second artificial cache lag | diagnostic-only |
| E821 | smoke | full command -> model -> event + result, 8 JVM processors | direct parents | parent sleep-protected adjacent control | 1,048,576 | 262,144 | 8,192 | none | 151,642/s | n/a | false | exact adjacent control | diagnostic-only |
| E822 | smoke | full command -> model -> event + result, 8 JVM processors | direct parents | current sleep-protected adjacent candidate | 1,048,576 | 262,144 | 8,192 | none | 151,642/s preceding | 169,942/s | false | +12.07% adjacent; aggregate no-regression gate passes | accepted |

Evidence SHA-256: E812 `04fc86fa863d963b16eccfc60757ac1b1f1ae793c7323d0588b8a50eebeca7a7`, E813
`9b7131de6b1f55ee874aba99fdf2cf0fbc85e4051b2b7020ead96672808f17a3`, E814
`5d5e21dd7b895b129c6ae95e705cb79998232b95f893b2c0b236010e67962160`, E815
`ae92eb8e105621c4fb1408c21e48d9825506e6939ef67bd71a2e2d05004ea619`, E816
`0d86fa5c3d8a784a2efafbe2a2f6a2343daaa8248cd682cf0d1c652d30412fdf`, E817
`168049b0cff1541df30e393fa4bcce7b21699657593214152258c2ee86875fb9`, E818
`78f1c4dc232c3e79958f7534663ad9caa674dcf40ce707341431d43882028b01`, E819
`cc37673c9c64bea0f5006914dac48ab7e7622916860f233cbaf032c109971051`, E820
`50e9940f94f66a1ad4f608a6119014c5bca32b249baae3f32d6669a8cf8f25e0`, E821
`7221d72fcaa5b3e62e044ae454c8b645af66dd1374cef34e32f529319723df08`, E822
`83212aeaf79fd51e3efd88ed9c1964fdc09d8eeee0cca78054c9647ff3ea381c`.

PR #286 was subsequently merged into the model branch at SDK `190b82cc5f3`. The exact result-dispatch screens,
rejected adaptive experiment, balanced no-model comparisons, model-route bracket, hashes and final decision are in
[`sdk-pr286-runtime-ingress-gate.md`](sdk-pr286-runtime-ingress-gate.md). The accepted default is eight concurrent
result completions; matched final-source runs improved both complete routes without replacing the clean absolute pins.

## 2026-08-18 absolute-pin audit and packed-route recovery

The reduction campaign was paused after the current 65,536-model route initially failed to finish its seed phase and
later measured far below the clean P5 pin. Treating those observations only as host noise was incorrect. Rebuilding
historical SDK and Runtime pairs exposed two real regressions in the initial-create and packed-update eligibility
contracts:

1. Independent-model aliases introduced a protocol-significant distinction between `null` (the client does not manage
   aliases) and an empty list (remove every alias). Generic deserialization still converted a JSON, CBOR or binary
   `null` collection to an empty collection. `JdbcModelCommitStore` therefore rejected ordinary commits from its packed
   fast paths and silently used the much slower general path.
2. Current clients use strict `FAIL` conflict handling for initial model creation. The packed initial-create route had
   only admitted `ACCEPT`, although the preceding SDK model read had already established that the exact model ID was
   absent. The correction retains a bounded exact-ID missing proof from the current repeatable-read boundary,
   invalidates that proof for every published write to the same ID, and otherwise falls back to the general conflict
   path. Unrelated model writes do not invalidate the proof.

The wire correction preserves `aliases == null` for `ModelCommitTarget` without changing the global null-collection
default. Focused JSON, CBOR and binary round trips prove the distinction. Runtime tests prove strict packed creation,
same-ID invalidation, unrelated-ID preservation, stale proof rejection and post-restart rejection.

The historical pairs below were rebuilt from source and run against the same isolated PostgreSQL database. Every
completed canonical run used 65,536 models, 262,144 warm-up commands and 4,194,304 measured commands without JFR. It
verified exactly 4,194,304 results, stored model events and global events; every post-S39 benchmark also verified all
65,536 final model states.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E823 | canonical | full command -> automatic `@Apply` -> model/event commit -> result | P5 SDK `e0093736de4` + Runtime `0c23c91f` | fresh same-host P5 control | 4,194,304 | 262,144 | none | n/a | 331,330/s | true | exact historical control | diagnostic-only |
| E824 | canonical | same | P5 | S39 SDK `d7506649e96` + Runtime `59faf5eb054` | 4,194,304 | 262,144 | none | 304,393/s following P5 | 294,040/s | true | S39 remains near its adjacent P5 control | diagnostic-only |
| E825 | canonical | same | S39 | S40 SDK `8b47bb3301a` + Runtime `feee1d9a`, broken alias null semantics | 4,194,304 | 262,144 | none | 294,040/s | 202,998/s | true | reject broken transport semantics | diagnostic-only |
| E826 | canonical | same | S40 broken | reverse S40 broken control | 4,194,304 | 262,144 | none | 297,588/s preceding P5 | 189,256/s | true | confirms packed-route loss | diagnostic-only |
| E827 | canonical | same | S40 broken | S40 plus only alias-null preservation | 4,194,304 | 262,144 | none | 189,256/s | 319,513/s | true | packed route restored; exact gain magnitude remains host-sensitive | accepted |
| E828 | canonical | same | corrected S40 | SDK `7555a29a8f9` on corrected S40 Runtime | 4,194,304 | 262,144 | none | n/a | 262,622/s | false | later SDK midpoint diagnostic during host decline | diagnostic-only |
| E829 | canonical | same | corrected S40 | SDK `ca66b547177` on corrected S40 Runtime | 4,194,304 | 262,144 | none | n/a | 275,876/s | false | direct-commit midpoint does not expose a separate cap | diagnostic-only |
| E830 | canonical | same | corrected S40 | corrected S40, bracket A | 4,194,304 | 262,144 | none | 258,142/s | 258,142/s | true | current comparison control | diagnostic-only |
| E831 | canonical | same | interpolated corrected-S40 controls | current SDK `96bb99a3fe0` + Runtime `ecdd6d5a`, both corrections applied | 4,194,304 | 262,144 | none | 236,882/s | 234,561/s | true | -0.98%; no remaining material cumulative regression | accepted |
| E832 | canonical | same | corrected S40 | corrected S40, bracket B | 4,194,304 | 262,144 | none | 217,373/s | 217,373/s | true | confirms host capacity fell across the bracket | diagnostic-only |

The S40 controls around current decline from 258,142/s to 217,373/s. Their geometric interpolation at the current slot
is 236,882/s, versus 234,561/s current (-0.98%). This excludes another material cumulative code regression after the
packed-route corrections, but it does **not** replace the clean absolute P5 pin of **425,606/s**. That pin remains the
required quiet-host qualification target. Future macro-reduction checkpoints must both pass a matched parent bracket
and preserve a freshly reproducible absolute model-route pin; a chain of comparisons against already slow parents is
not sufficient.

Evidence SHA-256, in the most relevant order: E823
`80a90e0e038b95b562ed0d52ce49c57d28e345895532d1c9a4f798db5013252b`, E824
`bfd9ceeb28f8298e7fc4c0e36f600154f3c766e3dc42bef72d698197f828b784` with following P5 control
`91bb609caa0cd448ceaf9dacd985cccdf074eb03a248c43a29b5a9b94483a8a8`, E825
`43de4afbdc2c0c02a21fc920023f491c7129c8c761319fbe7e63380858c6de08`, E826
`a226deab63fbf22623ff3fc986a41aef79788fc5b7b2bbd624a4577f2917ac21` with preceding P5 control
`c282a3fcc0ad7542c5ca1a5111f92374b0ccc31ee3c48b0b7addd81215d02f28`, E827
`7c26e1d812269ba3206671d3d20e8087e1ab359e4e00342d4146b4b24fa2ca90`, E828
`1b5e4b70d32cf4170bf2c9925924b736861230f7918243d095a2f3195e148a3f`, E829
`10d9b146a2f466410bf336640848f86b208c05d2ae4b092c73c6d9a3e1c71a1f`, E830
`b8234b1981ee562996f154c2a6b5f409fd38a3350bb0ce6e0e63f5a59b34f47e`, E831
`f4a347e60bd801352402ce1fcce7accd097cd004608decfd4e03a6469f724321`, E832
`2584455b3eebc6cfa2865b78b33162fdebdab329021f94f6d63f89009ff1f839`.

## E833: clean-host current absolute replay

After the packed-route corrections were committed as SDK `ee59e4c0984` and Runtime `8d5ac5cf`, Docker Desktop was
restarted and only the isolated `fluxzero-codex-s1-postgres` benchmark container was started. Spotlight, Dropbox and
media analysis were idle; no JFR was active. The exact canonical route retained 65,536 models, 262,144 warm-up
commands, 4,194,304 measured commands, two durable event kinds and ordinary durable command results.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E833 | canonical | full command -> automatic `@Apply` -> model/event commit -> result | accepted P5 425,606/s | SDK `ee59e4c0984` + Runtime `8d5ac5cf` | 4,194,304 | 262,144 | none | 425,606/s historical | 358,973/s | false | accept as current active-host floor; retain P5 as quiet-host pin | accepted |

E833 completed the seed at 4,461 creates/s, warmed at 237,905/s and verified exactly 4,194,304 results, stored model
events and global events plus all 65,536 final states. Its p50/p95/p99/max command-result latency was
152.511/206.616/225.268/260.065 ms. Throughput is 15.66% below the clean P5 pin, so this is a substantial recovery from
the earlier 217-258k host state but not a replacement for the quieter-host P5 qualification. The immediately following no-model E834 reached
978,950/s, localizing most remaining headroom to the model/event route rather than generic command/result handling.

E833 log SHA-256:
`0f84eccff6f2d8a489b159688818c0e4a60fde8f1a9817acb91c9a8df9eaec2c`.

## S60 CP8: canonical Runtime model blocks

Runtime `d7c54eb086d` is the first S60 macro-reduction checkpoint. It replaces the general, initial-packed and
update-packed model stores with one `ModelCommitPlan`, one transition/receipt block format, one atomic JDBC executor,
one asynchronous block locator, one bounded head cache and one replay implementation. The independently serialized
`model_update` log is gone; tracking and graph projection read a view derived from canonical blocks and completed hard
deletions. Production Java falls from 41,618 lines at direct parent `8d5ac5cff0d` to 36,809 (-4,809).

The full B0 qualification retained 65,536 models, 262,144 warm-up updates and 4,194,304 measured commands. The direct
same-host control reached 331,383/s; the final candidate reached **346,809/s (+4.65%)**. The candidate completed all
4,194,304 results, stored model events and global events and verified all 65,536 final states. Seed and warm-up were
3,791 and 236,247/s. Result latency p50/p95/p99/max was
160.349/213.648/235.363/281.322 ms.

| Run | Route | Direct control | CP8 | Exactness | Decision |
| --- | --- | ---: | ---: | --- | --- |
| CP8-B0 | command -> automatic `@Apply` -> model/event commit -> result | 331,383/s | **346,809/s** | 4,194,304 results/model events/global events; 65,536 states | accepted matched +4.65% |
| CP8-N0 | no-model command -> result | 876,053/s | **880,980/s** | 10,485,760 results; zero model/global events | accepted matched +0.56% |

Neither B0 process reproduced the active-host 358,973/s floor or quiet-host 425,606/s P5 pin in this later host state;
the absolute identities remain unchanged. Because the adjacent control was equally below the pins and the candidate is
4.65% faster, the pair excludes a causal storage regression without promoting a new absolute result. The no-model
pair is neutral and verifies that changed tracking/completion boundaries did not reduce generic command/result
capacity.

The focused final store/codec run passed 128 tests. The complete four-module Runtime reactor passed from final source.
An adversarial review also corrected graph rebuild enumeration so it reads heads at the projection's captured boundary,
not later current heads. Detailed relationship, graph, deletion/aging, erasure, restart and long-stream evidence is in
the feature-characterization log.

Evidence SHA-256: CP8 B0
`db82ed15479e5aeaaa92c45acb2940bd1587f4b01ebe1e612fd2811fc97fc724`; CP8 no-model
`ad8a8aa92508131176ac4c539bf5e041ae475c3254a85c3dea2e922c5fd0496d`; no-model direct control
`7770c01c7a30f24731a69c94993dd9a78d04abbcb7de27bc00ccff10fee8d656`.

## S60 CP11: final compiled SDK model pipeline

SDK `bc213d593e9` completes Macro 2 after CP9 was reclassified as an intermediate ownership reduction. The exact
control is CP10 `ac872d992423`; Runtime remains `d7c54eb086d`. All runs used the same isolated PostgreSQL database,
Java 25, an 8-GiB fixed heap and eight visible processors. B0 retained the complete command -> automatic `@Apply` ->
atomic model/event commit -> durable result route with 8,192 models, 262,144 warm-up updates and 1,048,576 measured
updates. Every run verified exactly 1,048,576 results, stored model events and global events plus all 8,192 final
states.

| Run | Source | Throughput | p50 / p95 / p99 / max | Decision |
| --- | --- | ---: | --- | --- |
| E835 | CP10 control A1 | 223,175/s | 33.708 / 51.418 / 60.309 / 75.582 ms | matched control |
| E836 | CP11 candidate B1 | 217,579/s | 34.094 / 56.139 / 85.027 / 118.251 ms | accepted candidate |
| E837 | CP11 candidate B2 | 221,357/s | 34.590 / 51.308 / 62.751 / 77.213 ms | accepted candidate |
| E838 | CP10 control A2 | 209,456/s | 34.550 / 63.126 / 90.501 / 124.592 ms | matched control |
| E839 | final CP11 candidate | 215,100/s | 34.401 / 57.006 / 85.924 / 126.050 ms | accepted adjacent confirmation |

The ABBA candidate geometric mean is **219,460/s** versus **216,207/s** control (**+1.50%**). The final candidate,
after making the batch entry itself the one completion future, is **+2.69%** versus the immediately preceding A2
control. Batch sizes remain comparable and the latency distributions overlap the same host drift. These reduced-model
runs do not replace the quiet-host 425,606/s pin or active-host 358,973/s floor; they are the matched Macro-2 causal
gate plus an absolute route-outage screen. No no-model run was required because CP11 changes no common wire, tracking
or generic result-completion owner.

Evidence SHA-256: E835 `c2e42954242f34f1d536a233c36568b6405c950f3f3df3c723be5decddddd2a7`, E836
`9e2433c16fbbb304e9d44aa3586f6e2afc541fb24ea25435570d958058792771`, E837
`fd23f2beea15bc7b322034bd69e08051eb263eadd68f5e1deb3e5c88e997017e`, E838
`7440e7cb8a6abd21d0832bc16f41c4f23464d259529bfb386f9987852e281e5f`, E839
`8ac58dcefadcf781436117c960b771ddf97925ab9ef44dc205d68f31aa76446a`.

## S60 CP12: final Graph state and repository replay cursor

SDK `71e43a18924` completes Macro 3 against exact CP11 control `bc213d593e9`; Runtime remains `d7c54eb086d`. All
completed comparisons used the same local PostgreSQL store, Java 25, an 8-GiB fixed heap and eight active processors.
The host was actively loaded by a virtual machine and indexing/media processes, so CP12 uses immediately adjacent
control/candidate pairs and retains the contradictory early ABBA as diagnostic evidence.

The first B0 ABBA used 8,192 models, 262,144 warm-up updates and 1,048,576 measured updates. Controls reached 235,234
and 238,095 commands/s; candidates reached 229,008 and 227,136 commands/s, a geometric-mean deficit of 3.63%. A third
candidate remained in the same band at 228,628/s. Because method ownership had moved but the hot cached route was
instructionally unchanged, the next adjacent pair recorded the exact measured phase with the JFR profile
configuration. That pair reversed the result: control reached 220,697/s and CP12 reached 222,027/s (**+0.60%**), with
overlapping latency and allocation profiles. Both verified exactly 1,048,576 results, stored model events and global
events plus 8,192 final states.

| Run | Route | CP11 control | CP12 candidate | Difference | Exactness |
| --- | --- | ---: | ---: | ---: | --- |
| E845/E846 | profiled full command -> model/event commit -> durable result | 220,697/s | 222,027/s | **+0.60%** | 1,048,576 results/model-events/global events; 8,192 states |
| E847/E848 | full route inside replay qualification | 77,608/s | 79,770/s | **+2.79%** | 65,536 results/model-events/global events; 4,096 states |
| E849/E850 | current cached `loadModel` | 1,371,285/s | 1,396,742/s | **+1.86%** | 65,536 models / 1,376,256 represented events |
| E851/E852 | sustained disjoint cold reconstruction | 22,407/s | 23,943/s | **+6.85%** | 20,480 models / 430,080 replayed events |
| E853/E854 | graph command plus exact projection catch-up | 5,627/s | 5,742/s | **+2.04%** | 4,096 upserts, exact high-watermark |
| E855/E856 | graph foreground command completion | 5,985/s | 6,239/s | **+4.24%** | 4,096 results/model-events/global events; 512 states |

The profiled B0 latency changed from 34.319/53.266/66.489/97.500 ms to
34.105/51.407/65.235/95.106 ms at p50/p95/p99/max. The replay qualification changed from
42.836/75.372/78.454/79.475 ms to 43.335/68.298/76.288/77.390 ms. The graph foreground changed from
37.860/51.321/53.160/53.242 ms to 40.341/46.801/48.400/48.471 ms: a slightly higher median with lower tail and higher
throughput. Graph composition itself was neutral at 319.449 versus 318.656 ms. Observed heap maximum was 2,221.7 MiB
for control and 2,309.2 MiB for CP12; the latter remains below the prior CP10 candidate maxima, with two rather than
four maximum pending signals and no pending roots. No retained-state or backpressure growth was observed.

The first oversized cold candidate is rejected. Its E2E and hot phases completed, but parallel independent replay
updated a plain checkpoint `HashMap` and failed with `ConcurrentModificationException` during cold reconstruction.
The accepted cursor uses concurrent outer ownership and a synchronized bounded per-model checkpoint index. A
post-correction oversized diagnostic advanced past the former crash and was intentionally stopped instead of spending
the host on its default more-than-168-million-event cold phase. The matched E851/E852 shape then completed, and a new
256-stream integration test exercises the exact concurrent checkpoint contract.

The graph pair verified exact active and historical relationships, graph-root and cumulative-child search documents,
512 graph roots and 512 graph children. Focused Macro-3 qualification passed 346 tests. The final complete nine-module
SDK reactor, Test Server, Proxy, annotation processor and Java/Kotlin downstream projects passed. One prior full run
hit an existing timing-sensitive document-cascade fixture assertion; that exact test passed immediately in isolation
and the unchanged complete rerun was green.

Evidence SHA-256: E845
`e7b071dc7b7b185476d47c5c73a96da65ddd60c92617434e6de0b452b9710f65`, E846
`c5b44dbce71ad175ac1ac4a34b6b0ad8d30d6471ff3e4043c5f8cbacd4d539dc`, E847/E849/E851
`0f2877401b1f6da620e9f4aa1ec3d2925f6bf07d5fad456e6f972cb17d7da94b`, E848/E850/E852
`13405d61cb5303dbbf703ed8bab0289e3612af9011cba3b797ab5c65fc9954b4`, E853/E855
`be257aa135ffaf8bbef45a6fdbb9d159bf8e420ea5bfe05bede6992d34d5d7b5`, E854/E856
`a98cf3c818937c0962bac258c71709fa19d1b0a73e29a86008ae79f28268c8d0`. Profile recordings: control
`5c91fa9d286079e28cecbaafd512483c0af7d5770dc6928b922473038458fae6`, candidate
`49e9e82006ca0fd255999a629896d791d5ed147f2f4523ede6aa262cca709370`.

## S60 CP13: shared Aggregate/Model mechanics

SDK `3603102f5de` completes Macro 4 against exact CP12 control `71e43a18924`; Runtime remains `d7c54eb086d`. The
checkpoint moves identity, reflection plans, root configuration, transition/snapshot decisions, immutable revision
state and replay behind neutral primitives used by both Aggregate and Model. Aggregate and Model retain their distinct
public APIs and persisted protocols, and Stateful handlers remain neither.

The complete Model E2E pair used 4,096 models, 65,536 warm-up updates and 262,144 measured commands. It verified
exactly 262,144 durable results, stored model events and global events plus every final state. The CP12 control reached
108,165/s and CP13 reached 109,980/s (**+1.68%**). Result latency p50/p95/p99/max changed from
32.909/60.969/83.592/96.338 ms to 32.393/56.888/86.252/110.046 ms: median and p95 improved, while the overlapping
one-shot tail widened slightly.

Because common handler inspection and result-facing metadata were touched, the no-model route was rerun. It verified
1,048,576 results with zero stored model/global events. Control reached 455,965/s and CP13 reached 502,274/s
(**+10.16%**); latency remained in the same active-host band.

Aggregate qualification used the Runtime benchmark's Aggregate-only profile with blocking dispatch, asynchronous
event consumption, 8 roots, 1,024 warm-up commands and 4,096 measured commands. Every completed run stored exactly
4,096 aggregate events and replayed 1,746 events for both the selected root and leaf. Two order-balanced observations
per source produced:

| Source | Run A | Run B | Geometric mean | Allocation geometric mean |
| --- | ---: | ---: | ---: | ---: |
| CP12 control | 863.3/s | 900.7/s | 881.8/s | 1,071,681 B/command |
| CP13 candidate | 937.9/s | 808.4/s | 870.1/s | 1,074,052 B/command |

The candidate throughput geometric mean is **-1.33%** and allocation is approximately **+0.2%**. The run ranges and
latency distributions overlap the same host drift, with no material batching, memory-ownership or replay regression.
Both sources emitted the existing bounded cache-refresh diagnostic when an event index arrived for a truncated cached
revision chain. The candidate initially exposed a null dereference at that boundary; the final implementation replaces
it with the same explicit illegal-state guard used by the historical Aggregate root and covers the truncated-chain
contract deterministically.

An earlier `ASYNC` dispatch benchmark shape let `verifyPair` execute before Aggregate batch commit and failed with
`NoSuchElementException` on both CP13 and the exact CP12 control. Those symmetric runs are rejected benchmark-shape
evidence, not product regressions. A combined Aggregate+Model profile completed its Aggregate phase but then stalled in
the Model phase on the exact CP12 control; it was stopped and the already canonical `SdkModelCommitBenchmark` supplied
the Model route instead.

The final nine-module SDK reactor, Test Server, Proxy, annotation processor and Java/Kotlin downstream projects passed.
Site/Javadocs passed after making the already-public Lombok super-builder hierarchy source-visible. Separate
intervening full runs hit a model-cascade fixture timeout and the known proxy websocket-close timeout; each exact test
passed immediately in isolation, and the unchanged final full reactor was green.

Evidence SHA-256: model control E857
`982b7eec5fe1aa4e21be39f1514807a22d933bb16f3b88d3a5f579d5d3b328c7`, model candidate E858
`943483ed4a650a85d717deb711f7abbba0669624106f75466883acb010d8aa49`, no-model control E859
`5aaa85535dd4bf4455ff0dd93940cdbd525469aed87b35dd98f0aa5d6e04d472`, no-model candidate E860
`fee4f77719a484485612fc94050c27230186fdeeecbf65cc2a9a9fb628645e4a`, Aggregate control A/B E861/E864
`6b29e735956fb7302878967b925f81f3cb9c21403a7cb45429defb8bd0e59a67` /
`2fa94eb589a1dc6b1a9de2b07df885b8bbba8dc5f6b77d7812451c11bdc52745`, Aggregate candidate A/B E862/E863
`3bebe521b9df8b78d730f5c9e09486b4f28957d8208f5ca801bf7f99d23ad6bd` /
`a5a32c63a735dc7fb28b6416da7f4deec9d8f9485a882d8e69ee3b4c93f93cbf`, rejected asynchronous candidate/control
E865/E866 `d41700ab4884e56f178375450bbdb0149b79c61b5dae3315f8740f6cb93c4341` /
`fef48d3d4ec61278a94299ee93bb1d8e35a449b0bfda4bb0bd6c0aac50c66e7c`.
