# SDK-inclusive model-commit capacity log

## Live scoreboard

| Route | Current exact pin | Runtime store path | Store service capacity | Role in campaign |
| --- | ---: | --- | ---: | --- |
| Full command -> model -> event + result E2E | P5 matched no-JFR **425,606/s** versus 420,193/s control (**+1.29%**); **426,108/s** best run; fresh unchanged E668/E671 controls average **420,348/s** | `commit-packed-update` | **0.539M/s** in the matched P5 profile; **0.519M/s** in fresh E636 | Sole acceptance gate for the 500k target |
| Synthetic tracked SDK apply -> model + event durability | **834,806/s** without JFR; **846,441/s** with batch JFR | `commit-packed-update` | **0.995M/s** in E589 | SDK-inclusive model upper-bound diagnostic without command/result logs |
| Low-level SDK `CommitModels` update round trip | **595,877/s** without JFR | `commit-packed-update` | **0.781M/s** in E488 | Runtime/wire upper-bound diagnostic |
| Direct SDK `assertAndApply(command)` | 80,074/s without JFR | `commit-general` | **0.108M/s** in E491 | Separate direct-API/idempotency diagnostic; not a proxy for tracked E2E |

Production is Runtime `0c23c91f` (`perf(modeling): reduce stream locator commit pressure`) on top of P4
`c98f47e6`. The full model campaign remains
scoped to model work. Command and ordinary result paths remain present only in the canonical E2E acceptance route and
are not optimization targets.

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

### Direct SDK model apply

This route calls the public instance extension point behind `Fluxzero.assertAndApply(command)`. It retains SDK model
loading/cache lookup, assertions, interceptors, `@Apply`, transition planning, event serialization, SDK commit
batching, the WebSocket/Runtime boundary and durable model plus global-event storage. Sixteen SDK worker threads keep
at most one update per model in flight, with 65,536 conflict-free model slots. It excludes command publication,
command tracking and the ordinary result log.

This direct API does **not** have a tracked source message index. `ModelCommitter` must therefore leave
`possibleDuplicate` unknown so a transport retry can still be recognized through durable commit receipts. Runtime
correctly routes these commits through the general idempotent path. The route is valuable, but it is not the packed
automatic-command route minus two cheap stages.

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

## Current decision

1. Runtime `0c23c91f` is the accepted P5 checkpoint on top of P4 `c98f47e6`. Four locator write lanes retain parallel
   inserts while reducing total transaction pressure; matched no-JFR E2E improves +1.29%, with a best observation of
   426,108/s.
2. Use E597/E598 as the current matched long storage profiles. The ordered model/event store shows 0.539M/s active
   capacity at P5 versus 0.502M/s in the same-binary eight-lane control; these derived rates vary with natural
   transaction shape and are not E2E ceilings.
3. Keep the low-level SDK update route as a fast secondary physical/wire check, never as the 500k acceptance gate.
4. Keep direct `assertAndApply` as a separate public-API/idempotency observation, not a packed-route proxy.
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
