# SDK model feature characterization

## Live scoreboard

| Item | Current evidence | Status |
| --- | --- | --- |
| Accepted production base | Runtime P5 `0c23c91f`; graph AWAIT correctness `ef24c66a`; AWAIT pipeline `59faf5eb` | accepted |
| Characterization driver | Runtime `9ca25780` (Phase 2), `5dac6bc2` and `093a4b49` (Phase 3) | accepted |
| B0 current P5 pin | **420,559 commands/s**, 4,194,304 exact results, model events and global events | canonical |
| B0 recent reference | E668/E671 mean **420,348 commands/s** | reproduced |
| Standard metrics | **150,587/s; -64.3%**; 16,815,908 durable metrics for 4,194,304 commands | canonical |
| Direct searchable model | **12,637/s qualified full route; +47.0%** versus the 8,595/s baseline; exact results/events/documents | adaptive parallel materialization accepted; clean-host pin pending |
| Paused S1 durability candidate | Safe deferred search commits plus one store-local synchronous WAL barrier; 161 focused tests green; Runtime stash `574153ff52718648c048c31b45b3e8db4ccb87a3` above `c357aa14` | stashed; full default-database qualification pending |
| Stable relationship | **57,316/s; -86.3%**; exact 65,536 active/historical relationships | canonical |
| Moving relationship | **41,079/s; -90.2%**; exact 41,984 measured moves | canonical |
| Graph ASYNC | Exact 4,096-command representative run: **5,358/s foreground**, 372 ms catch-up and 3,604/s inclusive | noncanonical characterization |
| Graph AWAIT | Exact matched post-fix run: **2,655/s**, zero lag, 4,096 upserts in 43 batches and p50/p95 92.7/105.3 ms; old matched base **31/s** | accepted 85.6x noncanonical checkpoint; quiet-host pin optional |
| Phase 2 cumulative C0-C5 | Representative C2/C3/C4 remained near 4.8-4.9k/s; C5 AWAIT fell to **37/s** with 4,082 batches for 4,096 commands | exact; noncanonical characterization |
| Phase 3 practical workload matrix | Every proposed dimension now has a medium-scale run; RETRY/FAIL contention still exposes the SDK duplicate-sequence loader failure | complete except correctness blocker |

Only the qualifying full command -> automatic `@Apply` -> model/event commit -> durable result route is canonical. A
smoke throughput is never compared with B0 and cannot accept or reject a production optimization.

## Campaign contract

The campaign characterizes real feature cost before choosing the next optimization. There is deliberately no minimum
throughput and a feature is not rejected because it is slow. Every qualifying run must instead prove all of the
following:

- every command and result completes exactly once;
- exact expected final model state;
- exact model-event and global-event counts;
- event ordering without gaps or duplicates;
- exact active and historical relationships;
- exact direct and graph search documents;
- graph projection reaches the required state boundary and exact durable high-watermark;
- no errors, unbounded backlog or unexplained resource growth.

Each durable run entry records `run_type`, route, accepted base, candidate, command and warmup counts, profiling,
control and candidate throughput, canonical comparability, decision and code status. The machine-readable registry is
[`sdk-model-feature-characterization-runs.csv`](sdk-model-feature-characterization-runs.csv).

## Scenario definitions

| Scenario | Exact measured difference from P5 | What it isolates |
| --- | --- | --- |
| B0 | Existing cached non-searchable independent model | Current full E2E P5 reference |
| M1 | B0 with normal SDK tracking, cache-eviction, lifecycle and WebSocket metrics enabled | Cost of normal SDK metrics; no JFR |
| S1 | `@Model(searchable = true)` | Direct document serialization and synchronous search-store update |
| R1 | Separate root and child models; child has stable `@ParentId(RelationshipRoot.class)` | Relationship persistence, cycle validation and general model-store path |
| R2 | R1, but model ordinals divisible by 100 alternate between their own and next parent on every update | Real relationship mutation for approximately 1% of commands |
| G1 | Searchable root with graph collection plus child `@ParentId(path = "children")`; completion `ASYNC` | Foreground throughput, asynchronous coalescing, lag and catch-up |
| G2 | Same physical model and graph workload as G1; completion `AWAIT` replaces `ASYNC` | Result completion including graph materialization |

Graph scenarios first create all roots and children and let seed/warmup projection fully catch up. Measurement then
starts from an exact projection boundary. After command completion the driver records source and processed state,
state-index/time lag, pending signals and roots, catch-up time, inclusive throughput, real root upserts, projection
batches and bytes, active materialization capacity, maximum observed backlog and maximum observed heap. It finally
verifies every searchable root, full graph document and the exact high-watermark.

State indexes encode milliseconds in their upper bits. Raw state-index differences are retained for exactness, but the
driver now also reports the corresponding millisecond difference. For example, G1's raw lag of 14,614,528 units was
**223 ms**, not fourteen million queued model updates.

## Phase 1 implementation and correctness qualification

Two real production issues surfaced before G2 could complete:

1. A compact model-update row could span several state boundaries. An AWAIT waiter for a boundary inside that row
   could never select it, so projection never advanced. Runtime `ef24c66a` now slices the decoded compact block at the
   requested boundary while preserving the persisted format.
2. Concurrent status/AWAIT queries held one pool connection and asked `modelStore.getStateIndex()` for a second one
   while mapping the result. At pool saturation every caller waited for itself. The status queries now read the durable
   model high-watermark in the same SQL statement and connection.

Focused graph-boundary tests and the full Runtime suite passed after the fixes: 685 tests, zero failures and zero
errors. The G2 128-model/1,024-update qualification then completed exactly. These fixes are independent of benchmark
throughput and were committed separately from the driver.

The accepted driver adds exact post-measurement verification without putting verification work in the timed command
interval. B0 retains its original command records and hot dispatch branches. Backward compatibility is retained:
without `sdkModelCommit.scenario`, the old `sdkModelCommit.searchable=true` property selects S1 and otherwise B0.

### B0 canonical bracket

All runs used Java 25, embedded Runtime, 8 GiB fixed heap, 65,536 models, 262,144 warmup updates, 4,194,304 measured
updates, 65,536 maximum open requests, 16 consumer threads, 65,536 max fetch size, 32-byte payload and no JFR.

| Run | Driver | Throughput | p50 / p95 / p99 / max | Correctness |
| --- | --- | ---: | --- | --- |
| F1-B0-1 | new Phase-1 driver | 407,342/s | 133.022 / 187.313 / 215.224 / 260.687 ms | exact |
| F1-B0-D0 | original pre-Phase-1 driver from the same `ef24c66a` source | 412,985/s | 131.244 / 186.882 / 217.681 / 268.114 ms | exact route |
| F1-B0-2 | new Phase-1 driver, reverse bracket | **420,559/s** | **128.377 / 180.855 / 205.696 / 231.356 ms** | exact |

The reverse new-driver observation reproduces the recent E668/E671 P5 mean of 420,348/s within +0.05%. The bracket
therefore shows ordinary host/run movement rather than a persistent driver regression. F1-B0-2 is the current Phase-1
pin; F1-B0-1 remains retained evidence of the observed variance.

### Deliberately small correctness smokes

These runs used 128 models, 129 warmup updates and 257 measured updates. The non-divisible counts deliberately exercise
phase-boundary and final-state calculations. Their throughput is startup/batch dominated and is not canonical.

| Run | Scenario | Throughput | p50 / p95 / p99 / max | Exact feature evidence |
| --- | --- | ---: | --- | --- |
| F1-M1-S | M1 | 3,554/s | 24.690 / 35.088 / 35.936 / 35.961 ms | 257 results/events; 128 exact models |
| F1-S1-S | S1 | 2,517/s | 44.902 / 47.428 / 47.463 / 47.470 ms | 128 exact direct documents |
| F1-R1-S | R1 | 5,961/s | 14.439 / 18.301 / 18.325 / 18.335 ms | 128 active; 128 historical; zero moves |
| F1-R2-S | R2 | 4,024/s | 21.826 / 27.428 / 27.449 / 27.465 ms | 128 active; 136 historical; **5 measured moves** |
| F1-G1-S | G1 | 2,346/s foreground | 42.083 / 48.891 / 48.938 / 49.092 ms | exact graph/search/high-watermark |
| F1-G2-S | G2 | **89/s** | **797.729 / 1,377.965 / 1,433.350 / 1,454.293 ms** | exact graph/search/high-watermark |

G1 finished the command interval with four pending signals, zero pending roots and 223 ms encoded-time lag. It caught
up in 99.448 ms. The projection performed 101 real root upserts in 11 batches, showing useful coalescing across 257
child updates. Active materialization was 1,011 roots/s; command plus catch-up throughput was 1,230 commands/s. The
maximum sampled heap was 809.4 MiB.

G2 had zero lag at result completion, as required by AWAIT, but performed 257 root upserts in **257 separate batches**.
It reached 188 active materialized roots/s and 89 inclusive commands/s; p95 result latency was **1.378 s**. Its maximum
sampled encoded-time lag while requests were still pending was about 1,446 ms and maximum sampled heap was 1,801.1
MiB. This is not a rejected candidate: it is the first clear characterization of the current AWAIT contract and a
strong optimization target.

### Full-size M1, S1, R1 and R2

M1, R1 and R2 used the same 65,536-model, 262,144-warmup, 4,194,304-command identity as B0. Full B0 controls
immediately after M1 and after the relationship pair reached 423,665/s and 416,031/s respectively, so the feature
results are not explained by a lasting host slowdown.

| Run | Scenario | Throughput | Difference from matched B0 band | p50 / p95 / p99 / max | Correctness/resource result |
| --- | --- | ---: | ---: | --- | --- |
| F1-M1-1 | M1 | **150,587/s** | **-64.3%** | 363.356 / 558.256 / 622.469 / 731.312 ms | exact; **16,815,908 durable metrics** |
| F1-S1-1 | S1 | n/a | n/a | n/a | excluded: 30,698 command failures after PostgreSQL lock exhaustion |
| F1-R1-1 | R1 | **57,316/s** | **-86.3%** | 1,035.466 / 1,586.485 / 1,670.540 / 1,735.369 ms | exact 65,536 active/historical; zero moves |
| F1-R2-1 | R2 | **41,079/s** | **-90.2%** | 1,448.118 / 2,393.348 / 2,543.870 / 2,628.300 ms | exact 110,144 history rows; **41,984 measured moves** |

M1 emitted about 4.01 durable metrics for every measured command. Its reverse B0 control recovered immediately to
423,665/s; the throughput loss is therefore a real cost of the standard metricflow on this workload, not JFR or host
contamination.

R1 proves that the general relationship path is already expensive when the relationship never changes. R2 moved the
children whose ordinal is divisible by 100 and produced 41,984 real measured moves, 1.001% of all measured commands.
R2 was a further 28.3% slower than R1, but most relationship cost precedes real mutation. Both routes completed exact
results, model events, global events, final models and relationship history.

S1 is a correctness/scalability failure, not a slow result. During warmup, one natural search materialization job
contained 30,698 unique model documents. `JdbcSearchStore.advanceModelDocumentFences` currently obtains one
transaction-scoped PostgreSQL advisory lock per model to exclude concurrent lifecycle erasure. The benchmark database
uses ordinary `max_locks_per_transaction=64` and `max_connections=100`; the transaction exhausted the shared lock
table and 30,698 command futures failed. Raising the local PostgreSQL limit or shrinking the benchmark window would
hide rather than solve this unbounded per-transaction lockset, so F1-S1-1 is retained as `failed-correctness`. Full-size
G1/G2 therefore require the same lock problem to be understood rather than a larger database setting.

A bounded-root-seed G1 diagnostic then tested whether only prerequisite creation caused the problem. Root and child
seeding completed, but full-size child updates also create direct documents needed by graph composition. Concurrent
direct-document and 128-root graph transactions exhausted the same shared advisory-lock table; even a ten-root graph
write then failed because other transactions already held the available lock entries. Exactly 44,386 command futures
failed. The temporary root-seed property was removed and no benchmark or production code was accepted from this
diagnostic. This proves full-size G1/G2 and cumulative C2+ share the S1 prerequisite bug rather than having an
independent graph-only failure.

## Phase 2 cumulative matrix

The cumulative driver keeps one workload and adds features in order:

| Scenario | Cumulative workload |
| --- | --- |
| C0 | Current P5 |
| C1 | C0 + standard metrics |
| C2 | C1 + searchable direct documents |
| C3 | C2 + `@ParentId` relationship |
| C4 | C3 + graph projection `ASYNC` |
| C5 | Same workload as C4, but completion `AWAIT` replaces `ASYNC` |

Every step retains the Phase-1 correctness contract. C5 does not add a second graph projection or completion mode.

Runtime benchmark checkpoint `9ca25780` implements all six routes. The first qualification used 128 models, 129
warmup updates, 257 measured updates, 128 maximum open requests, 16 consumers, 32-byte payloads, Java 25, embedded
Runtime and no JFR. As in Phase 1, these deliberately tiny runs qualify behavior; their throughput is not canonical.

| Run | Scenario | Throughput | p50 / p95 / p99 / max | Exact cumulative evidence |
| --- | --- | ---: | --- | --- |
| F2-C0-S | C0 | 5,326/s | 17.455 / 21.309 / 21.348 / 21.361 ms | 257 exact results/events; 128 exact models |
| F2-C1-S | C1 | 3,127/s | 29.117 / 35.664 / 41.100 / 41.217 ms | C0 + 1,204 durable metrics |
| F2-C2-S | C2 | 1,861/s | 56.944 / 63.209 / 63.902 / 63.921 ms | C1 + 128 exact direct documents |
| F2-C3-S | C3 | 2,122/s | 49.747 / 56.123 / 56.177 / 56.184 ms | C2 + 128 exact roots, children and stable relationships |
| F2-C4-S | C4 | 1,849/s foreground | 56.233 / 60.093 / 60.120 / 60.828 ms | C3 + exact graph documents and catch-up high-watermark |
| F2-C5-S | C5 | **82/s** | **854.490 / 1,502.329 / 1,552.146 / 1,562.835 ms** | Same C4 workload with exact AWAIT completion |

C4 completed commands with four pending graph signals, zero pending roots and about 114 ms encoded-time lag. It caught
up in 153.566 ms to the exact source boundary. Projection performed 243 real root upserts in 25 batches and the
inclusive command-plus-catch-up rate was 879/s. C5 had zero lag and no pending work when command results completed,
but performed 257 root upserts in **257 batches**; this again proves that AWAIT currently suppresses coalescing rather
than merely shifting the point at which results complete.

C0 and C1 are aliases for the already-canonical B0 and M1 physical routes, whose full results are 420,559/s and
150,587/s above. C2-C5 remain blocked from honest full-size qualification by the unbounded advisory-lock set found in
S1. The smokes deliberately do not raise `max_locks_per_transaction` or reduce a run while calling it canonical.

## Phase 3 practical workload matrix

Runtime checkpoints `5dac6bc2` and `093a4b49` add the practical matrix without changing production code. Generic
properties vary payload size/entropy and relationship fan-out; dedicated scenarios isolate document size (`D1`), one
atomic order+inventory commit (`A1`), Zipf contention (`K1`), a cold SDK/Runtime restart with a model set larger than
the cache (`L1`), and database aging (`Q1`). All numbers below are correctness smokes, never canonical throughput.

### Payload size and entropy

These B0-shaped runs used 200 models, 201 warmup updates and 401 measured updates. `UNIQUE` generates deterministic
one-byte printable characters per command; it is reproducible but intentionally poorly compressible.

| Payload | Repetitive throughput / p95 | Unique throughput / p95 | Correctness |
| ---: | ---: | ---: | --- |
| 32 B | 7,758/s / 23.982 ms | 7,011/s / 23.960 ms | 401 exact results/events; 200 exact models |
| 256 B | 7,251/s / 24.231 ms | 6,987/s / 26.702 ms | exact |
| 1 KiB | 6,997/s / 24.906 ms | 4,963/s / 36.865 ms | exact |
| 4 KiB | 6,298/s / 30.740 ms | **3,008/s / 64.537 ms** | exact |

The tiny runs are noisy, but the 1 KiB and 4 KiB pairs prove that entropy materially changes the route and that the
driver is not merely padding an object after transport.

### Search-document size

`D1` keeps the command payload at 32 B and sends only document size, seed and entropy. The `@Apply` deterministically
constructs the large model field, isolating model/document serialization from a same-sized command payload. The
driver also reads and reports the actual serialized document bytes.

| Requested body | Throughput | p95 | Actual serialized mean / min / max | Correctness |
| ---: | ---: | ---: | ---: | --- |
| 256 B | 2,123/s | 87.998 ms | 426.5 / 425 / 427 B | 200 exact models and documents |
| 2 KiB | 683/s | 403.781 ms | 2,225.6 / 2,224 / 2,229 B | exact |
| 16 KiB | **105/s** | **2,079.757 ms** | 16,619.6 / 16,615 / 16,632 B | exact |

### Relationship fan-out

`relationshipFanOut` changes the actual number of root models and parent IDs. Exact graph verification no longer
assumes one child at list index zero; it validates every child independent of graph list order.

| Fan-out | Roots / children | R1 throughput / p95 | Correctness |
| ---: | ---: | ---: | --- |
| 1:1 | 200 / 200 | 7,111/s / 25.860 ms | 200 exact active/historical relationships |
| 1:10 | 20 / 200 | 7,094/s / 23.491 ms | exact |
| 1:100 | 2 / 200 | 3,032/s / 65.523 ms | exact |

A separate C4 fan-out-10 smoke also passed: 20 exact roots, 200 exact direct child documents, relationships and graph
children, with exact projection catch-up. Its 2,364/s foreground rate remains noncanonical.

### Atomic multi-model command

`A1` updates an order and inventory from the same pinned begin state in one commit. For 257 measured commands it
verified 257 results, **514 modelstream memberships**, 257 global events and 128 exact order/inventory pairs. Both
models recorded the other's exact begin-state value. The smoke reached 2,164 commands/s with p95 56.700 ms.

### Zipf contention and conflict policies

`K1` uses 32 keys, Zipf exponent 1.2, unique routing keys and 16 handler threads. The hottest model received 334 of
1,024 measured commands (32.62%). It reports attempted and durable throughput separately and counts resolver calls,
selected retries, terminal resolver failures and failure types.

| Policy | Attempted / durable | Resolver evidence | Qualification |
| --- | ---: | --- | --- |
| ACCEPT | 436/s / 436/s | 0 resolver calls; 1,024/1,024 exact | passed smoke |
| RETRY | 1,707/s / 78/s | 18 real conflict callbacks and 18 selected retries | **failed correctness: 977 command failures** |
| FAIL | 1,306/s / 108/s | 90 real conflict callbacks and 90 terminal resolver decisions | **failed correctness: 939 command failures** |

ACCEPT is exact, but the current in-process commit coordinator serializes/re-evaluates hot models before Runtime
conflict rejection; this run must not be misrepresented as a count of server-side rebases. RETRY and FAIL do prove
real Runtime conflict rejection. They then expose a separate SDK failure such as
`Model stream 'sdk-contended-16' returned sequence 3 after 3`; most failed commands did not reach the resolver at all.
Final SDK reconstruction fails with the same duplicate-sequence observation. These two policy routes remain
`failed-correctness` until that loader/concurrent-read problem is understood.

### Cold cache and component restart

`L1` seeded 512 models, closed the SDK and embedded Runtime, restarted both against the retained schema, and measured
the first 513 full E2E updates with no warmup. The handler used an explicit adaptive cache of 64 entries, so the model
set was provably 8x larger. All 512 final states and 513 model/global events were exact. The small smoke reached 968/s
at p95 529.602 ms; new Runtime construction took 70.649 ms and SDK construction plus tracker readiness 1,025.430 ms.
PostgreSQL itself and its page cache were intentionally retained; this is a cold SDK/Runtime-component run, not a cold
database-host run.

### Database aging and soak

`Q1` owns the full history: each round performs updates, deterministic delete/recreate churn, exact result/event
counting, full model reconstruction, a real PostgreSQL `CHECKPOINT`, a stats flush, then reports schema/index bytes,
live/dead tuples, autovacuum count and cumulative WAL since the soak baseline. One JFR/profiler boundary spans the
whole soak rather than being restarted per round.

The qualification smoke used 128 models, three rounds of 257 updates and 5% churn (six deletes plus six recreates per
round). All 771 updates and all 36 churn commands completed exactly. Schema bytes grew 3,358,720 -> 3,743,744 ->
3,981,312; cumulative soak WAL grew 117,826 -> 236,287 -> 393,837 bytes. The inclusive 807-command rate was 1,959/s;
the update-only round rates were 2,806/s, 4,528/s and 5,074/s. Every round reconstructed all 128 exact
models. No autovacuum occurred during this deliberately short 0.412-second smoke; longer canonical soaks must run
long enough to observe it rather than infer its cost here.

### Supported practical dimensions

The driver now supports controlled runs for:

- payload size 32 B, 256 B, 1 KiB and 4 KiB with both repetitive and unique/poorly compressible content;
- searchable document size approximately 256 B, 2 KiB and 16 KiB;
- relationship fan-out 1:1, 1:10 and 1:100;
- Zipf/hot-key contention with exact accepted conflicts, retries and failures;
- atomic multi-model commands such as order plus inventory;
- model sets larger than cache and cold SDK/Runtime starts;
- aged databases, autovacuum, several checkpoints, updates/deletes, index growth and a real soak.

This phase remains descriptive: correctness is the gate, while throughput, latency, resource use and stage times decide
which practical route is optimized first.

## Medium-scale characterization completion on the clean production-code base

After pausing the S1 durability candidate, the complete proposed matrix was rerun from clean Runtime `c357aa14` and
the current SDK with Java 25, 8 GiB fixed heap, latest SDK defaults, ordinary PostgreSQL settings
(`synchronous_commit=on`, `autovacuum=on`) and no JFR. No database tuning or production-code candidate was active.
The host was deliberately allowed to remain busy because these runs complete the feature map rather than qualify
throughput. Every number in this section is therefore `canonical_comparable=false`: large differences are useful
characterization, but small differences must not be interpreted as optimization wins or regressions.

The existing full canonical B0, M1, S1, R1 and R2 results above remain the applicable Phase-1 evidence. C0 and C1 are
the same physical B0 and M1 routes, so they were not rerun merely under another scenario name. The runs below close
the previously missing graph, cumulative and practical cells.

### Graph completion and cumulative C2-C5

The matched representative graph pair used 512 models, 1,024 warmup commands, 4,096 measured commands and 256 maximum
open requests. Both routes verified exact results, model/global events, models, relationships, direct documents,
complete root documents and the durable projection high-watermark.

| Run | Route | Foreground | Inclusive | p50 / p95 / p99 / max | Projection behavior |
| --- | --- | ---: | ---: | --- | --- |
| MX-G1-R | Graph ASYNC | **5,358/s** | 3,604/s | 46.193 / 59.780 / 60.153 / 60.241 ms | 715 ms completion lag; 372.048 ms catch-up; 918 upserts in 93 batches |
| MX-G2-R | Graph AWAIT | **31/s** | 31/s | **1,698.263 / 30,274.710 / 31,579.460 / 32,059.424 ms** | zero completion lag; 4,096 upserts in **3,727 batches** |

The AWAIT route is roughly 173x slower in this matched observation. That is not host noise: AWAIT nearly removes
cross-command graph coalescing and holds results for the materialization boundary. A larger 8,192-open G2 diagnostic
correctly failed the Runtime `MAX_AWAITERS=4096` admission guard rather than growing an unbounded waiter set. A
follow-up with 2,048 open requests made progress without errors but root seeding was only tens per second and was
stopped before measurement; the representative 256-open run above is the completed characterization.

A separate high-pressure G1 run with 8,192 models and 65,536 measured commands completed exactly at 10,638/s
foreground. It ended with about 17.2 seconds of projection lag, caught up in 10.652 seconds and reached 3,898/s
inclusive, with a maximum sampled heap of 4,949.2 MiB. It is retained as scale evidence, not as a canonical score.

The matched cumulative runs below used the same 512/1,024/4,096/256 shape. Standard metrics were enabled and all
expected durable metrics and feature state were exact.

| Run | Scenario | Throughput | p50 / p95 / p99 / max | Exact added feature evidence |
| --- | --- | ---: | --- | --- |
| MX-C2 | Metrics + searchable | 4,845/s | 50.086 / 56.180 / 57.367 / 57.686 ms | 512 exact models and direct documents; 17,655 metrics |
| MX-C3 | C2 + stable `@ParentId` | 4,840/s | 50.452 / 58.609 / 59.061 / 60.260 ms | exact roots, children and relationships; 17,640 metrics |
| MX-C4 | C3 + graph ASYNC | 4,925/s foreground; 2,981/s inclusive | 49.081 / 55.108 / 57.584 / 57.669 ms | 597 ms lag; 542.419 ms catch-up; 1,449 upserts in 147 batches |
| MX-C5 | C4 with AWAIT | **37/s** | **2,060.028 / 3,659.729 / 30,319.712 / 30,337.888 ms** | zero lag; 4,096 upserts in **4,082 batches**; 37,937 metrics |

C2-C4 are indistinguishable at this noisy scale and cannot rank their feature costs. C5 independently reproduces the
AWAIT coalescing problem and is the clear functional performance target.

### Payload and document size

The payload matrix kept the full B0-shaped command -> automatic apply -> durable model/event/result route, with 4,096
models, 8,192 warmup commands and 32,768 measured commands. All eight runs completed 32,768 exact results, model
events and global events and reconstructed 4,096 exact final models.

| Payload | Repetitive throughput / p95 | Unique throughput / p95 | Interpretation |
| ---: | ---: | ---: | --- |
| 32 B | 42,279/s / 142.174 ms | 55,448/s / 99.091 ms | host/run ordering dominates this small pair |
| 256 B | 61,423/s / 82.782 ms | 42,212/s / 99.578 ms | unique content begins to cost materially |
| 1 KiB | 57,688/s / 79.923 ms | **16,287/s / 292.978 ms** | strong entropy/size regime |
| 4 KiB | 40,809/s / 141.966 ms | **4,504/s / 932.776 ms** | transport/serialization/storage bytes dominate |

The 32-byte inversion is why these scores are not optimization evidence. The 1 KiB and 4 KiB collapse is large and
monotone enough to establish a real practical byte/entropy dimension.

Document-size runs used 1,024 searchable models, 2,048 warmup commands, 8,192 measured commands and 512 maximum open
requests. The command stayed 32 B; only the deterministic poorly compressible model/document body grew. All runs
verified every result/event, final model and direct document.

| Requested body | Actual serialized mean / min / max | Throughput | p50 / p95 / p99 / max |
| ---: | ---: | ---: | --- |
| 256 B | 427.9 / 425 / 429 B | 3,713/s | 105.508 / 297.608 / 300.583 / 301.975 ms |
| 2 KiB | 2,227.1 / 2,225 / 2,241 B | 686/s | 1,245.043 ms p95 |
| 16 KiB | 16,621.4 / 16,617 / 16,634 B | **92/s** | 4,270.630 / 6,183.418 / 6,184.122 / 7,059.820 ms |

### Relationship and graph fan-out

R1 used 4,000 children, 8,000 warmup commands and 32,000 measured commands. Every run verified 4,000 exact final
children plus active and historical relationships with no moves.

| Fan-out | Roots / children | Throughput | p95 |
| ---: | ---: | ---: | ---: |
| 1:1 | 4,000 / 4,000 | 79,924/s | 58.692 ms |
| 1:10 | 400 / 4,000 | 64,529/s | 84.060 ms |
| 1:100 | 40 / 4,000 | 64,838/s | 73.441 ms |

The stable relationship route has no 1:100 explosion. A matched C4 graph series with 1,000 children, 2,000 warmup
and 8,000 measured commands kept foreground throughput flat at 5,680/s, 5,989/s and 5,726/s for fan-out 1, 10 and
100. Inclusive throughput improved from 3,412/s to 5,274/s and 5,416/s because fewer distinct roots allow much more
projection coalescing: upserts/batches fell from 2,185/219 to 750/75 and 125/25. Every graph and high-watermark was
exact.

### Atomic commits, contention, cold restart and aging

| Run | Shape | Result |
| --- | --- | --- |
| MX-A1 | 4,096 order/inventory pairs; 8,192 warmup; 32,768 measured | **16,908/s**; exact 32,768 results, 65,536 modelstream memberships, 32,768 global events and 4,096 atomic pairs |
| MX-K1-A | 64 hot keys; Zipf 1.2; ACCEPT; 4,096 measured | 401 durable/s; 1,210 commands (29.54%) on hottest key; 4,096/4,096 exact; coordinator prevented Runtime conflicts |
| MX-K1-R | same with RETRY | **failed correctness**: 243 successes, 3,853 technical failures; 71 retry decisions; duplicate model-sequence loader error |
| MX-K1-F | FAIL; 1,024 measured | **failed correctness**: 170 successes, 854 technical failures; 42 terminal decisions; same duplicate model-sequence loader error |
| MX-L1 | 8,192 models; adaptive cache 1,024; cold SDK/Runtime; 32,768 measured | 2,224/s; p95 2,123.917 ms; 8,192 exact states; Runtime 88.856 ms and SDK/tracker 1,021.627 ms restart |
| MX-Q1 | 4,096 models; three rounds of 32,768 updates; 1% delete/recreate churn | 98,304 exact updates and all final models; 3.211 s inclusive; real checkpoints and 24,316,100 B cumulative WAL |

The RETRY and FAIL results are not throughput observations: they remain a genuine correctness blocker in concurrent
model loading and must be fixed before those policies can be characterized. MX-L1 restarts the SDK and Runtime but
retains PostgreSQL and its page cache, as documented for L1.

MX-Q1 used normal autovacuum and synchronous commits. Its three update rounds reached 35,841/s, 35,684/s and 64,345/s
with p95 197.615, 157.376 and 92.549 ms. Schema/index bytes grew 17,924,096/2,424,832 ->
39,182,336/4,120,576; live/dead tuple estimates ended at 7,755/5,896. The 3.211-second soak was too short to trigger an
autovacuum, so it proves the measurement route and exact aging behavior, not long-duration autovacuum stability.

This completed the proposed descriptive matrix and identified AWAIT projection coalescing as the next concrete
target. The checkpoint below addresses that target. The RETRY/FAIL correctness defect remains open, while the stashed
S1 durability candidate remains a separately logged, unaccepted investigation.

## Graph AWAIT optimization checkpoint

Runtime `59faf5eb` addresses the G2 coalescing problem without changing PostgreSQL durability or the AWAIT contract.
The final comparison uses the exact same full route and shape as MX-G2-R: Java 25, embedded Runtime, latest SDK
defaults, 8 GiB fixed heap, 512 models, 1,024 warmup commands, 4,096 measured commands, 256 maximum open requests,
16 consumer threads, 32-byte commands, 256-byte unique searchable documents and ordinary PostgreSQL settings. Neither
run used JFR. The host was not reserved exclusively for benchmarking, so the checkpoint is
`canonical_comparable=false`; the 85.6x difference and batching change are nevertheless too large and directly
explained to be host noise.

| Run | Runtime | Throughput | p50 / p95 / p99 / max | Projection batches | Active projection capacity |
| --- | --- | ---: | --- | ---: | ---: |
| MX-G2-R | `c357aa14` | **31/s** | 1,698.263 / 30,274.710 / 31,579.460 / 32,059.424 ms | 3,727 | 211 roots/s |
| MX-G2-AWAIT-P1 | `59faf5eb` | **2,655/s** | **92.665 / 105.346 / 105.611 / 105.653 ms** | **43** | **6,054 roots/s** |

This is an **85.6x** full-route throughput increase, **98.8% fewer projection batches**, 94.5% lower p50 and 99.7%
lower p95. Both observations completed all 4,096 results, stored model events and global events; reconstructed 512
exact model states and 512 active/historical relationships; produced 512 exact root and child graph documents; and
ended at the exact durable high-watermark with zero projection lag. The post-fix run therefore remains true AWAIT:
the result future completes only after its exact graph boundary is durable and published.

### Causal model and accepted mechanism

The old implementation created one virtual waiter and repeatedly queried PostgreSQL per AWAIT request. More
importantly, projection reads stopped at the earliest outstanding waiter boundary. Under a continuous AWAIT workload
that made almost every command its own projection batch, so the request pattern itself destroyed coalescing.

The accepted implementation makes the following changes as one coherent scheduling fix:

- AWAIT requests become bounded in-memory waiters; one status query completes all satisfied waiters after an ordered
  cursor advance. The existing 4,096-waiter admission guard remains.
- Ready signals may be read through the latest committed awaited boundary, allowing natural cross-command
  coalescing while preserving every individual completion boundary.
- Up to four already-ready projection jobs prepare and write concurrently, without a timer or artificial pause.
  Durable cursor publication, metrics and waiter completion remain strictly in job order. Fenced search writes make
  out-of-order physical completion safe.
- Root batches start at up to 128 roots and split recursively only when graph limits require it; the old theoretical
  `maxModels` calculation had reduced ordinary one-child batches to ten roots.
- A root batch is stitched in one call instead of repeatedly rebuilding the complete edge index per root. JDBC can
  also resolve current fenced descendant documents directly by globally unique model ID; custom search stores retain
  the collection-resolution fallback.
- The event-driven wake signal is allocated only while graph projection is enabled, so ordinary B0/model routes do
  not gain an allocation on every commit.

The final run's summed active stage demand was:

| Fundamental stage | Total | Batch mean / p95 / max | Meaning |
| --- | ---: | --- | --- |
| Root head load | 86.045 ms | 2.001 / 2.835 / 3.019 ms | Resolve each affected root at the job's historical state boundary |
| Root document load | 69.690 ms | 1.621 / 2.401 / 3.018 ms | Load direct searchable root documents |
| Graph traversal | 46.750 ms | 1.087 / 2.043 / 2.454 ms | Resolve current relationship edges for the requested roots |
| Edge/path mapping | 0.306 ms | 0.007 / 0.015 / 0.022 ms | Apply projection path overrides |
| Collection lookup | 0 ms | 0 / 0 / 0 ms | Skipped because JDBC supports direct fenced ID lookup |
| Descendant load | 92.325 ms | 2.147 / 2.954 / 4.168 ms | Load current child documents by ID |
| Stitch | 39.072 ms | 0.909 / 1.326 / 1.411 ms | Construct complete projected root documents |
| Search write | **341.795 ms** | **7.949 / 11.313 / 11.580 ms** | Persist fenced graph document upserts/deletes |

These active durations overlap across at most four jobs and must not be added to E2E latency. They show that search
write is now the largest remaining unit of projection service demand. The measured AWAIT result is about 49.6% of the
earlier 5,358/s ASYNC foreground observation. Reaching ASYNC parity is explicitly deferred; 2.6k/s is accepted as the
current checkpoint rather than forcing another optimization rabbit hole.

### Rejected paths and verification

Native transport batching of `AwaitModelGraphProjection` was neutral (2,060/s versus 2,042/s on its matched profiled
shape) and was fully reverted. A fixed 256-root batch and advancing beyond an exact compact-update boundary both
created liveness/correctness hazards and were reverted. Direct descendant lookup was retained only as a small,
low-risk service improvement with a correct custom-store fallback; it was not credited with the broad E2E gain.

The final code passed nine focused graph/search tests and all **695 Runtime tests**. Coverage includes out-of-order
physical graph writes with ordered cursor/future publication, retry metrics, compact-update boundaries, waiter
coalescing, adaptive batches above ten roots, direct fenced lookup across collections and deletion, pending-waiter
failure during shutdown, and metric publication before AWAIT completion. The exact final evidence is
`/private/tmp/model-g2-await-event-driven-pipeline-4096-post-metric-order.log`, SHA-256
`88583ec4d998f4cfb6b751c6d00be7efab6dc00db20b141fe5278f246a08bd69`.

## S1 optimization campaign

### Bounded model-lifecycle locks and first full-size baseline

Runtime checkpoint `6374fa74` replaces one transaction-level advisory lock per distinct model with 64 deterministic
lifecycle-lock stripes. Ordinary document and snapshot materialization takes shared stripe locks; irreversible erasure
takes the same stripes exclusively and in stripe order. An update that races an erasure still retries and observes the
durable erasure fence, while unrelated document writes remain mutually compatible. This bounds transaction lock use
without raising PostgreSQL's lock settings or reducing the benchmark window.

The focused 8,192-model regression, the explicit concurrent erase/write race, all 51 standard search-store tests, all
52 RUM search-store tests and the complete 686-test Runtime suite passed. The full S1 qualification then ran on
PostgreSQL 18 with the restored defaults `max_locks_per_transaction=64` and `max_connections=100`. Sampling observed
at most **64 advisory locks in one transaction** and 128 across two simultaneous materialization transactions, versus
about 29,700 in a single old diagnostic transaction.

The exact canonical-shape run used Java 25, embedded Runtime, latest SDK defaults `2026.07.27`, 8 GiB fixed heap,
65,536 models, 262,144 warmup updates, 4,194,304 measured updates, 65,536 maximum open requests, 16 consumer threads,
32-byte payloads and no JFR. A user development server remained active on the host, so the number below is the first
correct full-route optimization baseline rather than a clean-host throughput pin.

| Run | Throughput | p50 / p95 / p99 / max | Exact result |
| --- | ---: | --- | --- |
| F1-S1-L1 | **8,595/s** | **7,173.920 / 10,782.290 / 11,619.521 / 12,120.680 ms** | 4,194,304 results, model events and global events; 65,536 exact models and direct documents; zero failures |

`pg_stat_statements` was reset shortly after measurement started and retained 3,848,105 materialized rows. The times
below are cumulative PostgreSQL execution time and may overlap across connections; they are not additive route
latency. They do, however, expose the service demand of the current materialization path.

| Fundamental database stage | Calls | Rows | Total DB time | Mean/call | WAL | Meaning |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| **Search-document upsert** | 1,710 | 3,848,105 | **226.893 s** | 132.686 ms | **9.74 GB** | Insert/update the direct document and maintain its full-text, reverse, facet and ordering indexes |
| **Clear durable materialization receipt** | 629 | 3,848,105 | **144.006 s** | 228.944 ms | **1.74 GB** | Null the recoverable `document_projection` after its search write is durable |
| **Recompute oldest pending materialization** | 630 | 630 | **63.613 s** | **100.973 ms** | 23.3 MB | Find the next unfinished durable projection and advance the tracker safety boundary |
| Lifecycle fence and bounded lock acquisition | 629 | 3,848,105 | 39.598 s | 62.954 ms | 571.8 MB | Exclude erasure and advance the per-document state fence before writing |
| Model-commit COPY | 628 | 3,841,127 | 38.494 s | 61.297 ms | 2.88 GB | Persist the authoritative model commits and recoverable projection payloads |

The writer samples showed normally one active search-upsert query and approximately one PostgreSQL core of database
CPU. This established parallel search materialization across independent commit jobs as the next causal candidate.

### Adaptive parallel materialization

Runtime checkpoint `45341bcd` partitions the materializations of one committed model batch across independent search
transactions. One `CommitModels` job is never split: an atomic order-plus-inventory commit, including its direct
documents and snapshots, stays in one search transaction. Lanes are balanced by the exact mutation byte estimate.
All lanes must finish durably before their shared model-commit receipts are cleared once and any command future is
completed. A partial lane failure therefore leaves the exact durable receipts available for idempotent recovery.

Parallelism is deliberately workload-adaptive rather than unconditional. The default maximum is the available JVM
processor count clamped to 4..16 (14 here, with an explicit maximum override of 32). A batch only earns another lane
per 1,024 materialization mutations or 1 MiB of materialization bytes. Small batches remain one immediate transaction;
large batches use the already-available `JdbcSearchStore` workers without a timer or collection pause. The settings
are `fluxzero.modelMaterializationLanes`, `fluxzero.modelMaterializationMutationsPerLane` and
`fluxzero.modelMaterializationBytesPerLane`.

The 524,288-command screens kept the full command -> automatic `@Apply` -> model/event commit -> durable result ->
exact search-document route, 65,536 models, Java 25, 8 GiB heap and no JFR. They were shorter than a canonical run and
the host moved materially, so only the adjacent comparisons are causal evidence.

| Run | Lanes / adaptive thresholds | Throughput | p50 | Interpretation |
| --- | --- | ---: | ---: | --- |
| F1-S1-PC1 | 1 | 10,584/s | 5,508.959 ms | first matched control |
| F1-S1-P4 | 4 / disabled for the mechanism screen | 12,640/s | 4,641.004 ms | **+19.4%** versus PC1 |
| F1-S1-P8 | 8 / disabled for the mechanism screen | 13,639/s | 4,288.156 ms | **+28.9%** versus PC1 |
| F1-S1-P16 | 16 / disabled for the mechanism screen | **15,002/s** | **3,885.477 ms** | **+41.7%** versus PC1 |
| F1-S1-PC2 | 1 | 8,616/s | 6,966.338 ms | reverse control; confirms large host movement |
| F1-S1-PA14 | 14 / 1,024 mutations / 1 MiB | **14,043/s** | **4,340.689 ms** | production-adaptive candidate; exact |

The full 4,194,304-command qualification used the same identity as F1-S1-L1 and printed every resolved
materialization setting in its own log. A separate `fluxzero-dev-server` test JVM and Spotlight became active after
measurement started, so this is a full correctness and performance qualification but deliberately
`canonical_comparable=false`; it is not presented as the final clean-host pin.

| Run | Throughput | Difference from F1-S1-L1 | p50 / p95 / p99 / max | Exact result |
| --- | ---: | ---: | --- | --- |
| F1-S1-PA14-F | **12,637/s** | **+47.0%** | **4,747.708 / 6,794.208 / 7,207.764 / 7,894.615 ms** | 4,194,304 results, model events and global events; 65,536 exact models/documents; zero failures and zero pending receipts |

The final production diff passed 688 Runtime tests. Focused tests additionally prove two concurrent lane futures,
one indivisible multi-model job, no early command completion or receipt cleanup, and one transaction for a small
batch. The full-route backlog stayed bounded: at 2.33 million durable commits it had about 28,000 pending receipts,
then fell to 44 at the final durable boundary and zero after completion.

`pg_stat_statements` was reset immediately after warmup. Cumulative DB service can overlap across lanes and must not
be added as latency; the increased search service reflects real parallel work and contention, while wall-clock E2E
improved. The remaining fundamental stages are now:

| Fundamental database stage | Calls | Rows | Total DB time | Mean/call | WAL | Meaning |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| **Search-document upsert** | 3,166 | 3,908,430 | **619.432 s** | 195.651 ms | 9.53 GB | Parallel direct-document and index maintenance; no longer a single active lane |
| **Clear durable materialization receipt** | 545 | 3,915,363 | **159.341 s** | **292.368 ms** | Shared post-lane cleanup; now the largest non-search stage |
| Lifecycle fence and bounded lock acquisition | 3,155 | 3,883,008 | 68.626 s | 21.751 ms | Per-lane monotone state fence and bounded erasure exclusion |
| **Recompute oldest pending materialization** | 1,089 | 1,089 | **58.234 s** | **53.474 ms** | Still scans old dead prefixes and is the next narrow causal candidate |
| Model-commit COPY | 544 | 3,883,008 | 37.232 s | 68.442 ms | Authoritative model commits and recoverable materialization receipts |

### Exact receipt cleanup and monotone boundary advancement

The first boundary-only candidate sought the next pending receipt from the current monotone lower bound instead of
rescanning old index prefixes. Its adjacent full-route screen was effectively neutral: 14,346/s control versus
14,359/s candidate, or +0.09%. It was therefore not accepted as a throughput optimization. The ordering mechanism was
retained because it closes a real concurrent-completion race: a later materialization may finish first, but the
published materialized state may only advance through the contiguous completed prefix.

The next database profile exposed a different cleanup defect. The old statement independently matched every distinct
segment and every commit ID. PostgreSQL therefore had to consider a broad cross-product rather than the exact durable
receipt keys. Runtime checkpoint `c357aa14` sends equally sized segment and commit-ID arrays and joins their exact
`unnest` pairs. It also commits the heavy receipt update before taking the singleton materialization-boundary lock.
Otherwise each lane holds that global lock while flushing its own receipt WAL and serializes the parallel search
pipeline again. The short follow-up transaction locks the state row, advances only from the current oldest pending
state, and completes the public commit future only after that boundary is durable.

Splitting those transactions creates a narrow failure window in which the receipt is gone while the conservative
boundary still points behind it. The existing durable materialization recovery now repairs that boundary exactly when
it reaches the clean tail and reschedules graph projection. Startup still performs the unbounded exact rebuild needed
after a crash or upgrade. Tests explicitly cover reverse completion order, no premature boundary advancement, receipt
commit while the state row is contended, no premature future completion, and recovery of durable materialization.

The mechanism screen was bracketed by old-code controls of 14,346/s and 13,944/s. Their geometric mean is 14,144/s;
the exact paired-key candidate reached **19,126/s**, or **+35.2%**. The longer 4,194,304-command intermediate run was
materially less impressive at 13,445/s, only +6.4% over the prior 12,637/s full run. It remained exact but ran while
IntelliJ and a development JVM materially loaded the host, so it is deliberately not a clean canonical pin. This
mixed result is retained rather than averaged away.

| Run | Implementation | Throughput | p50 / p95 / p99 / max | Status |
| --- | --- | ---: | --- | --- |
| F1-S1-BDC1 | old adaptive control | 14,346/s | 3,931.429 / 6,243.415 / 6,576.823 / 6,606.799 ms | diagnostic control |
| F1-S1-BD1 | lower-bound boundary only | 14,359/s | 3,878.918 / 5,208.975 / 5,779.416 / 5,846.177 ms | no causal E2E gain |
| F1-S1-RC1 | exact paired receipt keys, boundary still in receipt transaction | **19,126/s** | **2,997.942 / 4,449.327 / 5,340.988 / 5,365.488 ms** | +35.2% versus bracketed control mean |
| F1-S1-RCC2 | reverse old adaptive control | 13,944/s | 4,015.132 / 5,484.996 / 6,236.790 / 6,796.415 ms | diagnostic control |
| F1-S1-RC-F | 4.19M intermediate paired-key qualification | **13,445/s** | 4,056.345 / 7,196.829 / 7,954.871 / 8,880.816 ms | exact; host-contaminated; +6.4% versus PA14-F |
| F1-S1-RS1 | final split receipt/boundary checkpoint | **17,977/s** | 3,011.382 / 4,404.269 / 4,961.242 / 5,053.360 ms | exact accepted screen |
| F1-S1-RS2 | final checkpoint with post-warmup SQL profile | **18,226/s** | 3,151.384 / 4,732.556 / 4,830.776 / 4,897.880 ms | exact accepted screen |

Every screen completed 524,288 commands, results, stored model events and global events and verified 65,536 exact
model states and direct documents. The full intermediate qualification completed all 4,194,304 of each. No accepted
run left an open receipt or a non-null pending materialization boundary. The final Runtime diff passed all 689 tests.

`pg_stat_statements` was reset immediately after warmup in F1-S1-RS2. The table again reports overlapping cumulative
database service, not additive latency. It shows that paired cleanup remains material work, but the search-index upsert
has become the dominant measured database demand. The boundary lock still waits on ordinary model commits that update
the same state row; it no longer holds the receipt transaction's WAL commit.

| Fundamental database stage | Calls | Rows | Total DB time | Mean/call | WAL | Meaning |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| **Search-document upsert** | 340 | 378,994 | **50.725 s** | 149.191 ms | **847 MB** | Parallel direct-document and search-index maintenance |
| **Clear exact durable receipts** | 64 | 378,994 | **10.425 s** | 162.898 ms | 159 MB | Paired-key cleanup, committed independently per materialization lane |
| Search lifecycle fence | 340 | 378,994 | 7.048 s | 20.729 ms | 63 MB | Monotone document state and erasure exclusion |
| **Acquire materialization-boundary lock** | 65 | 65 | **6.372 s** | **98.030 ms** | Wait mainly for ordinary model commits touching the singleton state row |
| Model-commit COPY | 63 | 378,994 | 3.892 s | 61.777 ms | Authoritative model commits plus recoverable receipts |
| Find next pending receipt | 36 | 36 | 0.064 s | 1.772 ms | Sparse lower-bound lookup; old dead-prefix scan is eliminated |
| Persist new boundary | 99 | 99 | 0.002 s | 0.019 ms | Tiny state update after the correct prefix is known |

A clean-host 4,194,304-command run of the final `c357aa14` checkpoint is still required before declaring a new stable
S1 pin. The next optimization candidate must be selected from a detailed profile of that final route; the current SQL
evidence points first at search upsert/index contention, not another speculative boundary tweak.

The subsequent full-size candidate/reverse-control pair invalidated a throughput interpretation of the short screens.
Final `c357aa14` completed at 10,967/s; old adaptive Runtime `45341bcd` completed at 11,004/s, a negligible **-0.34%**.
Both runs were exact. A second dashboard dev-server started during the reverse control, so the pair is not promoted to
`canonical_comparable=true`, but the equality is enough to reject any claim that receipt cleanup raised sustained
full-size throughput.

The checkpoint is retained only for its ordering fix and measured resource/tail improvements. Compared with the old
control, it formed 404 instead of 554 materialization dispatch batches (-27.1%) and increased their mean size from
7,570.9 to 10,381.9 commands (+37.1%). Search-upsert calls fell 3,156 -> 2,873 and cumulative service fell
729.309 -> 631.035 s (-13.5%); search-lifecycle service fell 101.137 -> 84.257 s (-16.7%); model-commit COPY service
fell 46.156 -> 38.348 s (-16.9%). Result p95/p99 improved 8,388/9,926 -> 7,843/8,610 ms. The explicit cost is that
receipt cleanup plus boundary-lock service rose 244.610 -> 253.078 s (+3.5%) and the separated boundary adds small
transactions. This trade-off is recorded as resource-positive and throughput-neutral, not as progress toward the S1
throughput target. The next accepted performance checkpoint still requires a positive full-size E2E result.

### Paused search-durability investigation

The next investigation kept the complete S1 route and first profiled database aging on `c357aa14`. The 2,097,152
command run reached 17,834/s, while aggressive autovacuum reached 17,071/s and increased search-store service demand.
The tuning was rejected and all PostgreSQL settings were restored. A receipt-cleanup backlog reduced its own measured
service demand, but the autovacuum-disabled matched sequence moved 16,201 -> 16,709 -> 14,180/s and the reverse
candidate under ordinary autovacuum collapsed to 12,876/s. It was a real secondary saving without stable full-route
evidence and was completely reverted rather than retained as production complexity.

An explicitly unsafe `synchronous_commit=off` search-lane diagnostic then bracketed 11,572 -> 12,815 -> 11,421/s.
This established search-commit amortization headroom but could not satisfy the public durability contract by itself.
The production-shaped candidate therefore adds a store-local synchronous WAL barrier after all independent search
lane commits, and delays monitor publication, durable receipt removal and public future completion until that barrier
has completed. Small one-lane batches and custom search stores keep their old synchronous path. Focused
`JdbcSearchStoreTest` and `JdbcModelCommitStoreTest` verification passed 161/161 tests, including a deliberately locked
barrier row and proof that futures and receipts do not advance early.

| Run sequence | Database state | Control | Candidate | Interpretation |
| --- | --- | ---: | ---: | --- |
| Unsafe mechanism bracket | autovacuum off | 11,572/s and 11,421/s | **12,815/s** | approximately +11%; mechanism evidence only, unsafe implementation reverted |
| Safe barrier pair | normal defaults; autovacuum overlapped the candidate | 10,969/s | 10,984/s | throughput neutral under contaminated scheduling |
| Safe barrier reverse pair | autovacuum disabled for diagnosis only | 12,007/s reverse control | **12,787/s** | +6.5% adjacent; diagnostic only, not production DB tuning |

The candidate is paused, not accepted. Its five-file Runtime diff is recoverable from Git stash object
`574153ff52718648c048c31b45b3e8db4ccb87a3` (created as `stash@{0}` with message
`wip(search): deferred materialization durability barrier after S1 diagnostics`) on base `c357aa14`. The attempted
4,194,304-command qualification was stopped after warmup when unrelated heavy host work started; it has no measured
throughput and is explicitly not a benchmark result. The Runtime worktree is clean, normal `autovacuum=on` and
`synchronous_commit=on` are restored, and no search candidate is committed. Resume by applying the stash, rerunning a
full default-PostgreSQL candidate/reverse-control pair on a quiet host, then running the full Runtime suite and an
adversarial durability review before deciding whether to commit or revert it.

## Evidence

- F1-B0-1 log SHA-256: `30fbfdd4dfa1886574b5d3acd711e2ea728f9ccaec0790fb9afa06439e935960`;
- F1-B0-D0 log SHA-256: `7d3b971de4ded3ac94780c97896505dfef73c9f4af33955fdc6664ef788573c1`;
- F1-B0-2 log SHA-256: `e4003d1a3dbab2c06b569db78ab5370a54bd22a42472fb13e261373cda9013c7`;
- M1/S1/R1/R2/G1/G2 smoke SHA-256 respectively:
  `bd729c0070923bf126eb751fde513c5708c130c9f4d3553e0644ae1c06ddf835`,
  `60fe92077b9ae9929113f3c25f7adb21b55acf5fa1f683af1bce307a19ed2688`,
  `f518aeac27057b31709735eadb561da809b96603c944c52fe9597fe6acda3073`,
  `af52f859d370d72df9580f6527b97a5308f8bb74d28b80f1ba9779a8c77373b5`,
  `566ac9f770d4052a6bd638c2f2a8676b8076e8ba3ed3758ccb23d19b525caef2`,
  `ca48ea96996a19e7a2b43933ae428681fbebbee7fbc7f7352fa3ce1418aedced`.
- F1-M1-1 and its B0 reverse control SHA-256:
  `51a0341897ecf06e2b88351c958c77b541c0e50886cdea04b3228104d6eabead`,
  `a3211b9ca7bef5e4bb1073dbccca8ab298f62bd588c479f746c4eaf612c1632e`;
- failed F1-S1-1 log SHA-256:
  `052e9addec03b601b12973dd25774991aaecde3b01a6c4e3e492dbd6f068f3aa`;
- bounded-lock F1-S1-L1 log SHA-256:
  `29dace8c2a27fb24ff0c09887684bc9fa8c2b26b01bd7a5e4a49929db179c21b`;
- parallel-materialization F1-S1-P4/PC1/P8/P16/PC2/PA14 screen SHA-256 respectively:
  `a0528e6f9bff0face7f0ce89b25ed6eaa751a9712676952a62fc8e05452ec098`,
  `048e952625e816c641364404a965cb2e4b2624c7ce74ce42ee3d07731dfddece`,
  `990c488baabd134bf7f3f0a36083a61c9920b5828e36f81731308577d0ce5226`,
  `9c524fd4ac96b4cc9d09ca8a861e9a53fb2e7c3e5e9ce025ad27d6d3f31ee61a`,
  `be01715a7db3de69280486641d4c1dddd509e7ba70e48b035325cd6fe2304bb6`,
  `e82f619b8be7b16f3a1c5c2a1d7aaf209eab90487414ae0ed3366020aacc5db6`;
- full adaptive F1-S1-PA14-F log SHA-256:
  `c7ac020a63899eaaf0e3e1e3d316d81c4f086c672af68118627515f4d81e00fe`;
- boundary control/candidate F1-S1-BDC1/F1-S1-BD1 SHA-256 respectively:
  `7abb43595d0103e09401c5a1afb8321090e0fcc279c689297c7caef28c6464de`,
  `eba95d4f537a4a03ad159716af6a826a6bc4bd027e683eaa674c3cc3813fc2c0`;
- paired-receipt F1-S1-RC1/RCC2 and full F1-S1-RC-F SHA-256 respectively:
  `93f5b6766060a4835573d4ff44a9e3a1f00629f3f377d27b80b48d2009a61653`,
  `289af8d0448668d07fd6848dd7faafea66d8a29959f947bc087491b1a3f2717d`,
  `fd2b1a72386d325d17b281e4be4ec96ea78d15bf02dac6f7f5b6b6bb1d58f604`;
- final split receipt/boundary F1-S1-RS1/RS2 SHA-256 respectively:
  `3537b4718ceeff69ee2fcf6d3d8eb39fb47d7f07526a92f1d0fa3cd056466af2`,
  `969700fe2a76b8528c3d576c5a7777817e2ccc5e9241bc741d4f3d2f36148f1d`;
- full final receipt/boundary and old adaptive reverse-control SHA-256 respectively:
  `1960b15745274b85768a98c18637840effcd9dd13976064e61c51351a65af9d4`,
  `524603de8e5048434a14d8e7c22e7895f3d823030a7d815cd8f36c8d593bd80f`;
- failed bounded-root-seed F1-G1-1 diagnostic SHA-256:
  `d0de94593551800bbe82f478ad1600f4026d24dd4b247f2958eca5393dbb906c`;
- F1-R1-1, F1-R2-1 and their B0 reverse control SHA-256:
  `2ee5bca343d1ee8997f66fecae293626b893c98c7794a1a8fea4eec79ba095cd`,
  `7db2aa9543527fa6e2b7d0d719f2e59bb9f20343881135addfd065ad81193136`,
  `2818772781ef84aac2926e43ab92f39ddb45af64edc01dfd9f3c013c0f8c28c0`.
- C0/C1/C2/C3/C4/C5 cumulative smoke SHA-256 respectively:
  `f0ec092a154e2c5255ba4539dea0368c5a815ef3e055f00bace0ddc8966672a8`,
  `e647837c8558ab903471150f63993bbbd0d6dbd209d3fafc715fa9587b0f64be`,
  `b954e865b89be8c499016156b17497422156f583d0c501094ae113135a8111b5`,
  `18ba72957075f956651e4a0dc0270cd7c9eec593ff0acaa4165381836e53425f`,
  `bc6aa8d06248bc08677645992a31210725e6ad191aa04f0c999108bac8de8277`,
  `53d47bbdbf53d91fef419335e9a1973051c58e5a52870e8005fc66e6f5e47bd2`.
- Phase-3 32/256/1,024/4,096-byte repetitive/unique payload matrix SHA-256 respectively:
  `6872382f4191a4f263b9e19ce447a9382cc7d4957d1a9ab57a6cc945c576cfe6`,
  `748a79aa2cd1dca8afd1350664a8137997741d0a4a6dbaaac982dce2e9f19e9a`,
  `6c441715406143f57797d080c8f346048676411fb3201113c199870478cc109d`,
  `4ffdb25c9ffa3b64e121822693eef76f0a3b481b7fd49806f7242fc8f487ffca`,
  `ea7e6c54807d3a53f33af7e041e1d7e996bfad23615dbfa3a4bb66e9e0f24380`,
  `20845c537cd8bcf9c4d65725d54bea989a528f9ff8e7b42dc16f56d97d97745c`,
  `cb674cafdf8bd58cc4f7d04a23b2857eeabdd08c2962bbc3822a863ff63ee92d`,
  `34f1322dfc214202cf55f7b57ef6af7a0820be072c6da94701211141e3515ce1`.
- Phase-3 fan-out 1/10/100 and C4 graph fan-out-10 SHA-256 respectively:
  `15c035cd71f5b6454b49f2c6f6b0d7a4ca38701bb90ca1a5f23cd5ced82fd663`,
  `9be55252efb65095a96491699e16382919a9b29b1088b50a5bb0f3e97e6fc2e9`,
  `b55979adaa4c6fb03e34b3b98e39e15802ceb096ac0c4681dcb3453968044c5b`,
  `f561a9dd4b190329322161faa53e8535d8bbff31d675c3af67900c610a088522`.
- Phase-3 D1 document 256 B/2 KiB/16 KiB SHA-256 respectively:
  `07dd92a3ddc55e7c4be84c242658eafef5bfeebbf465a8b3c5403326b4874738`,
  `b9f9e373870147853ee62b833f3ac97434d203203d83be14c5bccc7f16374cfe`,
  `e303a0feb0683bfccc5e198492601c6fbdfb0e981a48cb2a4279b95f37879e47`.
- A1 atomic, K1 ACCEPT/RETRY/FAIL, L1 cold restart and Q1 aging SHA-256 respectively:
  `1af1f51371c229f653c2a22b7f48c2242dbcd47c25e45591ebec677308be44bf`,
  `9f9e39ffde561796578a12c500d8d626df388f4cb0514a97701106cb5590c7f2`,
  `30e9801dbde108f0bf6a0debbe0cc3aa1dba0043ed9492affb7d9c12cc60ca5b`,
  `24fc080df49528c99120188c6eab759ea36b6b849c22bb07e0a5f292ba29e845`,
  `2003b9c89e642221b46092f98c821b774137edd6e861b9ba28706b6ab349d070`,
  `586ffd87a14432c11808c1ce75ce5898f4946a8fc1dd394c5f279448ea2444a9`.
