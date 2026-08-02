# SDK-inclusive model-commit capacity log

## Live scoreboard

| Route | Current exact pin | Runtime store path | Store service capacity | Role in campaign |
| --- | ---: | --- | ---: | --- |
| Full command -> model -> event + result E2E | P4 short matched **368,604/s**; sustained P4 **416,178/s** matched geometric mean, **421,981/s** best run | `commit-packed-update` | **0.497-0.509M/s** active ordered-store capacity in long P4 profiles E566/E571 | Sole acceptance gate for the 500k target |
| Low-level SDK `CommitModels` update round trip | **595,877/s** without JFR | `commit-packed-update` | **0.781M/s** in E488 | Runtime/wire upper-bound diagnostic |
| Direct SDK `assertAndApply(command)` | 80,074/s without JFR | `commit-general` | **0.108M/s** in E491 | Separate direct-API/idempotency diagnostic; not a proxy for tracked E2E |

Production is Runtime `c98f47e6` (`perf(modeling): fuse packed state and stream writes`). The full model campaign remains
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

## Current decision

1. Runtime `c98f47e6` is the accepted P4 checkpoint. Across two sustained matched pairs its full E2E gain is +4.10%,
   with a best complete-route observation of 421,981/s.
2. Use E566 and its fresh same-behavior control E571 as the current long storage profiles. The ordered model/event
   store shows 0.497-0.509M/s active capacity; these derived rates vary with natural transaction shape and are not E2E
   ceilings.
3. Keep the low-level SDK update route as a fast secondary physical/wire check, never as the 500k acceptance gate.
4. Keep direct `assertAndApply` as a separate public-API/idempotency observation, not a packed-route proxy.
5. Preserve parallel locator partition writes; the single-transaction alternative is causally rejected at -5.88%.
6. Preserve binary COPY as the general and checked fallback. The P4 typed-array statement is restricted to the packed
   no-type-registration path and is accepted because it removes state boundaries as one atomic unit.
7. Treat E550's 408,185/s and E562's 421,981/s as demonstrated high states, not standalone stable pins. Matched
   comparisons remain the progress evidence.
8. Continue from the post-P4 profile rather than the old 0.407M active-lane estimate. That estimate was never an E2E
   ceiling and has now been superseded by complete-route measurements.
9. Use E555's server/client split when selecting P5: repeated JDBC boundaries contain more headroom than the raw
   PostgreSQL executor time. Validate the next complete block before changing production code.
10. Accept production work only through matched full command -> model -> event + result runs with correctness and read
   validation proportional to the affected path.
11. Do not replace the conditional event-block multi-value insert with binary COPY at the current physical row shape;
    E568/E569 causally rejects it at -2.29% E2E and +50.9% direct-insert time.
12. Do not fuse the direct global-event insert into the ordered P4 model statement. E570/E571 causally rejects it at
    -2.31% E2E because it removes existing insert parallelism, produces 26.3% more jobs and reduces mean job size by
    20.8%.
13. Do not select statement-shape caching as P5: E572 measures only 0.040 ms mean planning against a 4.060 ms direct
    event-insert boundary. Investigate measured SDK/model CPU and allocation pressure around the JDBC scheduling gap.

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
