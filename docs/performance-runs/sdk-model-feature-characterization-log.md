# SDK model feature characterization

## Live scoreboard

| Item | Current evidence | Status |
| --- | --- | --- |
| Accepted production base | Runtime P5 `0c23c91f`; graph AWAIT correctness `ef24c66a` | accepted |
| Characterization driver | Runtime `9ca25780` (Phase 2), `5dac6bc2` and `093a4b49` (Phase 3) | accepted |
| B0 current P5 pin | **420,559 commands/s**, 4,194,304 exact results, model events and global events | canonical |
| B0 recent reference | E668/E671 mean **420,348 commands/s** | reproduced |
| Standard metrics | **150,587/s; -64.3%**; 16,815,908 durable metrics for 4,194,304 commands | canonical |
| Direct searchable model | **8,595/s**; 4,194,304 exact results/events/documents; lifecycle locks bounded to 64 per transaction | canonical correctness baseline; optimize |
| Stable relationship | **57,316/s; -86.3%**; exact 65,536 active/historical relationships | canonical |
| Moving relationship | **41,079/s; -90.2%**; exact 41,984 measured moves | canonical |
| Graph ASYNC | Small route exact; former full-size lock blocker fixed by `6374fa74` | pending full-size requalification |
| Graph AWAIT | Exact documents/high-watermark; **257 projection batches for 257 commands** and 89 commands/s in the smoke | smoke only |
| Phase 2 cumulative C0-C5 | All six exact correctness smokes passed; C0/C1 reuse the already-canonical physical routes | smoke-qualified |
| Phase 3 practical workload matrix | All dimensions implemented and smoke-exercised; RETRY/FAIL contention exposes SDK sequence-loader failure | partially blocked |

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
CPU. The next candidate must therefore test parallel search materialization across independent commit jobs while
keeping every individual atomic model commit in one transaction. It must not split a multi-model commit. The durable
receipt cleanup remains one ordered post-apply step. Separately, the pending-min query was observed scanning dead
prefix entries in every commit partition; a monotonic lower-bound seek is a second concrete candidate after the
parallel writer is causally tested.

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
