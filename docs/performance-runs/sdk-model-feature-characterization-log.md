# SDK model feature characterization

## Live scoreboard

| Item | Current evidence | Status |
| --- | --- | --- |
| Accepted production base | Runtime P5 `0c23c91f`; graph AWAIT correctness `ef24c66a` | accepted |
| Characterization driver | Runtime benchmark `9bc22279` | accepted |
| B0 current P5 pin | **420,559 commands/s**, 4,194,304 exact results, model events and global events | canonical |
| B0 recent reference | E668/E671 mean **420,348 commands/s** | reproduced |
| Standard metrics | Correctness-qualified on M1; full-size measurement pending | smoke only |
| Direct searchable model | Exact model state and 128/128 direct documents on S1 | smoke only |
| Stable relationship | Exact 128 active and 128 historical relationships on R1 | smoke only |
| Moving relationship | Exact 128 active, 136 historical and 5 measured moves on R2 | smoke only |
| Graph ASYNC | Exact documents/high-watermark; **223 ms command-end lag**, 99.448 ms catch-up in the smoke | smoke only |
| Graph AWAIT | Exact documents/high-watermark; **257 projection batches for 257 commands** and 89 commands/s in the smoke | smoke only |
| Phase 2 cumulative C0-C5 | Not run yet | pending |
| Phase 3 practical workload matrix | Not run yet | pending |

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

## Phase 2 cumulative matrix

The next driver layer will keep one cumulative workload and add features in order:

| Scenario | Cumulative workload |
| --- | --- |
| C0 | Current P5 |
| C1 | C0 + standard metrics |
| C2 | C1 + searchable direct documents |
| C3 | C2 + `@ParentId` relationship |
| C4 | C3 + graph projection `ASYNC` |
| C5 | Same workload as C4, but completion `AWAIT` replaces `ASYNC` |

Every step retains the Phase-1 correctness contract. C5 does not add a second graph projection or completion mode.

## Phase 3 practical workload matrix

After C0-C5, characterization expands one controlled dimension at a time:

- payload size 32 B, 256 B, 1 KiB and 4 KiB with both repetitive and unique/poorly compressible content;
- searchable document size approximately 256 B, 2 KiB and 16 KiB;
- relationship fan-out 1:1, 1:10 and 1:100;
- Zipf/hot-key contention with exact accepted conflicts, retries and failures;
- atomic multi-model commands such as order plus inventory;
- model sets larger than cache and cold SDK/Runtime starts;
- aged databases, autovacuum, several checkpoints, updates/deletes, index growth and a real soak.

This phase remains descriptive: correctness is the gate, while throughput, latency, resource use and stage times decide
which practical route is optimized first.

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

