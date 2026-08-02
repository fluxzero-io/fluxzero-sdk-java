# Model command E2E throughput campaign

This document is the source of truth for the cross-repository performance campaign that accompanies dynamic model
boundaries. The detailed history before this campaign remains in
[`dynamic-model-boundaries-phase-21d-performance.md`](dynamic-model-boundaries-phase-21d-performance.md). This ledger
records the accepted comparison point, every candidate, the evidence required to accept it and the reason rejected
experiments were rejected.

## Live scoreboard

| Field | Current state |
| --- | --- |
| `route` | stored/tracked command -> automatic `@Apply` -> atomic independent-model event/state commit -> globally published event -> durable ordinary result -> tracked caller completion |
| `accepted_base` | P3: SDK production source `e94188b5876` with JFR-only observer source `9f14c8c25d9`, Runtime `9d0bed30b643` |
| Accepted matched throughput | 341,679/s candidate geometric mean versus 328,161/s P2 control; **+4.12%**, exact paired-bootstrap 95% CI **+2.49% to +6.02%** |
| Completion target | five consecutive canonical qualifying runs above 1,000,000/s |
| Best non-qualifying ceiling | 405,700/s in E61 with the complete ordinary-result route removed |
| Latest accepted checkpoint | P3 stores only the underfilled tail of a sufficiently large co-located transaction directly; E75 cut event staging 8.382 -> 0.037 ms and packed-model service 24.555 -> 20.268 ms without changing the atomic transaction |
| Current production code | accepted P3 Runtime `9d0bed30b643`; low-rate/small isolated appends still stage, conditional rollback preserves the predicted head layout, and all 672 Runtime tests pass |
| Latest causal diagnosis | E165-E178 complete and reject the event/model-sidecar family. After old/new reads, derived-locator rebuild, partition rollover, hard-delete rewrite and retention migration all passed, the cheapest zero-byte tagged locator produced **253,045/s** versus **254,313/s** control across balanced E175-E178: **-0.50%**. A btree lost 13.44%; a repeated locator pointer lost 1.90% geometrically. The earlier local **-46.08%** model-task result is real, but lifecycle support plus closed-loop transaction fragmentation consumes its route gain. |
| Next evidence target | fully revert sidecars, rerun the immutable exact P3 route under dual JFR/stage tracing, and rerank fundamental route segments and active service capacity before selecting another production mechanism |
| Durable run register | [`model-e2e-run-registry.csv`](performance-runs/model-e2e-run-registry.csv) |

Last updated after E178 on 2026-08-02. This table is updated whenever a run changes the accepted base, current
diagnosis, code state or next target.

## Objective and non-negotiable boundary

Reach more than 1,000,000 commands per second locally for the complete production-default path:

```text
command -> stored and tracked -> handler/@Apply -> atomic model commit
        -> event published once -> ordinary result stored and tracked -> caller completion
```

The target is not a publish-only, fire-and-forget or result-free number. Every measured run must verify exactly:

- 1,048,576 successful command results;
- the expected model state and relationship memberships;
- one globally published domain event per command, even when linked to multiple model streams;
- no missing, duplicate or reordered observable outcomes;
- latest SDK defaults (`2026.07.27` at campaign start), including adaptive caching and
  `ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH` model commit behavior;
- bounded memory, queues and retained compression buffers, with no hidden unbounded cache or backlog;
- unchanged wire and stored formats and unchanged retry, idempotency, context and error semantics.

The completion gate is five consecutive qualifying full-E2E runs above 1,000,000 commands/s on the fixed local setup.
No concession to result semantics, correctness checks, payload size, tracking or durability qualifies.

## Fixed benchmark identity

Unless an experiment explicitly investigates one of these variables, both sides use:

| Setting | Value |
| --- | --- |
| Models | 65,536 |
| Warm-up updates | 65,536 |
| Measured updates | 1,048,576 |
| Maximum in flight | 65,536 |
| Command consumers | 16 |
| Maximum fetch size | 65,536 |
| Payload | 32 bytes |
| Searchable models | false |
| Event-sourcing sessions | 2 |
| Model-load measurement | false |
| JVM heap | `-Xms8g -Xmx8g` |
| Database | PostgreSQL 18.3, Docker container `fluxzero-codex-s1-postgres`; record the current Docker host port per run |
| Benchmark driver SHA-256 | `1b85bb6ec7ce53d3e4565bf3f8dc07edef57228c2a41e1344bf7c97e3ea22feb` |

Each run prints the complete configuration, uses a newly created benchmark schema and retains its log hash. The run
record also captures source commits, dirty-diff hashes, artifact hashes, Java version, host load, thermal/power state and
whether JFR or another profiler was active. A run lacking that identity can suggest a hypothesis but cannot accept a
checkpoint.

## Operational run registration

Every measurement is appended to the machine-readable
[`model-e2e-run-registry.csv`](performance-runs/model-e2e-run-registry.csv) before the experiment is closed. Its minimum
contract is:

| Field | Contract |
| --- | --- |
| `run_type` | exactly `canonical`, `profile` or `smoke` |
| `route` | explicit measured lifecycle; result-free or non-durable diagnostics never reuse the canonical label |
| `accepted_base` | immutable production checkpoint used as control |
| `candidate` | code or diagnostic mechanism under measurement |
| `command_count` / `warmup_count` | exact workload sizes |
| `profiling` | `none`, JFR or the exact async-profiler mode |
| `control_throughput` / `candidate_throughput` | observed route throughput; command routes use commands/s and a non-command microdiagnostic names its unit in the evidence |
| `canonical_comparable` | `true` only for unprofiled full-route runs on the fixed workload that are eligible for the acceptance protocol |
| `decision` | current evidence-based disposition |
| `code_status` | exactly `accepted`, `reverted` or `diagnostic-only` |

`run_id` and `evidence` are additional mandatory operational keys. The normalized register starts at the accepted E48
checkpoint and backfills the later experiments that determine the current decision tree; E0–E47 retain their existing
hashed ledger and per-experiment CSV evidence. Every new invocation is registered without exception.

### Major checkpoint anatomy report

Every accepted performance checkpoint and later “super checkpoint” also receives one human-readable route-anatomy
report. It is measured on the complete qualifying route but explicitly separates its profiled diagnostic throughput
from canonical non-profiled acceptance throughput. The report must contain:

1. immutable SDK/Runtime source identity, workload, hashes and accepted canonical comparison;
2. a table of direct fundamental marker-to-marker intervals with exact semantics and n/mean/p50/p95/p99/max;
3. a separate composite table naming the fundamental components and any asynchronous overlap;
4. stage capacity, batch size/count, queue, bytes and active-overlap evidence;
5. corrections to earlier broad interpretations, current smoking guns and the next evidence required before code.

P2's first complete instance is
[`model-e2e-p2-route-anatomy.md`](performance-runs/model-e2e-p2-route-anatomy.md). The machine-readable registry remains
the operational source for every individual invocation.

## Acceptance protocol

Functional and throughput evidence are separate gates:

1. A focused test must protect the changed contract and the relevant reactor build must pass.
2. Screening uses at least four balanced alternating runs per side (`A B B A` and its inverse), without profilers.
3. A candidate that appears faster is confirmed with at least eight runs per side. Order remains balanced and is logged.
4. The comparison uses paired log-throughput differences. Acceptance requires a 95% bootstrap confidence interval
   wholly above zero and a practically worthwhile net improvement. There is no mechanical 5% floor: a reproducible
   3–4% gain may be accepted when the change is simple, low-risk, maintainable and neutral or better on operational
   characteristics. Smaller gains demand stronger evidence; complex or high-risk changes demand proportionally more
   benefit. Every run must pass all exact correctness checks.
5. p95/p99 latency, allocation, retained memory, queue growth, database work and batch shape are checked for a displaced
   bottleneck or resource regression. A local hotspot reduction alone is not an E2E throughput win.
6. JFR and async-profiler runs are compared only with equivalently profiled controls. Their absolute throughput is not
   mixed with non-profiled runs.
7. Only a confirmed improvement advances the accepted baseline and earns a performance checkpoint commit. Neutral or
   slower candidates are reverted or left uncommitted, and their lesson is recorded below.

## Candidate-selection protocol

Verification after implementation is not a substitute for validation before implementation. Every new production
candidate must pass three distinct phases in order:

1. **Understand.** Repeated canonical full-result runs establish a route-wide causal model. The model identifies the
   critical-path dependency, parallel work, queue growth, worker/resource saturation and feedback into batch shape.
2. **Causally validate.** Keep the complete canonical route and its durability/results contract intact by default.
   Instrument, accelerate or relieve one suspected limiter as narrowly as possible and measure the immediate effect on
   full-route throughput, latency, queues and batch feedback. A benchmark-only ablation is optional diagnostic evidence,
   not the preferred or final proof; removing a lifecycle stage may estimate an upper bound but cannot select or accept
   production work by itself.
3. **Optimize.** Production code changes only after the limiter, mechanism and plausible canonical E2E impact are all
   established. The candidate then enters the matched acceptance protocol above.

The evidence hierarchy is strict: qualifying full-route E2E runs are the source of truth. Detailed correlated traces
and controlled perturbations within that intact route establish causality. Ablations, profilers, microbenchmarks and
deliberately incomplete routes such as result-free runs only explain, exclude or bound mechanisms. Supporting evidence
cannot independently select a production target or advance the score.

## Baseline and candidate register

### Accepted source comparison point A0

- SDK: `8ccbce0becc` (`perf(serialization): reuse encoded metadata keys`)
- Runtime: `d867f8e21203fabf67e485171924cb9ce58ab2b0`
- Valid non-JFR observation: 283,183 commands/s, 143 commit batches averaging 7,332.7 items,
  p50/p95/p99/max 194/267/318/348 ms.
- Log: `/private/tmp/sdk-model-metadata-key-cache-final-run1.log`, SHA-256
  `3743a8484dfbc2f7008bf996c5d417f60085a23ac2b907052ca6964ff50ead5f`.
- Adjacent JFR observation: 197,502 commands/s with the same implementation and correctness boundary.

The 283,183/s run is a valid observation, not an outlier. One observation is insufficient to estimate stable baseline
throughput, so A0 must be remeasured through the matched protocol before accepting or rejecting later candidates.

### Rejected runtime candidate R1

- Runtime: `6099bed6` (`perf(serialization): reuse ZSTD compression buffers`), compared with `d867f8e2`.
- SDK in its recorded runs: `8ccbce0becc`.
- JFR: 203,909 commands/s; non-JFR: 197,258 commands/s; all correctness checks passed.
- Runtime compression-output sampled allocation fell from about 2,304 MiB to 121.5 MiB (94.7%).
- JFR: `/private/tmp/sdk-model-zstd-buffer-final2.jfr`, SHA-256
  `e245e957ae23626b2d36b963400babc9f2e2e1ae9d97eb44ede688f85d372e7e`.
- Non-JFR log: `/private/tmp/sdk-model-zstd-buffer-final2-run1.log`, SHA-256
  `5c14ba1abbb5a185a10643da029087ff32a39de0b1e5e8b10a7fd7c415e4e337`.

R1's targeted allocation improvement is real, but matched A/B experiment E2 rejected it as a throughput checkpoint.
Its +0.71% geometric-mean result had a paired 95% interval that included a regression. It therefore fails the current
positive-evidence rule independently of the campaign's former 5% threshold.
The implementation must therefore be reverted before C1 is assessed.

### Rejected common/SDK candidate C1

- Base SDK: `3da2fdc604807cd480a375133261ba28dac95996` plus dirty diff SHA-256
  `f0f7b6da84837b4922844cd5b1ef02d682c011587e9d45a7df918cf622862df8`.
- Files: `CompressionAlgorithm.java` and `CompressionAlgorithmTest.java`.
- The WebSocket ZSTD path uses pooled bounded destination buffers: 16 resources, at most 4 MiB retained each.
- Focused tests: 10 passed.
- JFR E2E observation with R1: 205,017 commands/s, exactly 64 batches of 16,384 and all correctness checks passed.
- Total sampled allocation fell from 35,915.4 MiB to 32,655.1 MiB versus the adjacent R1 recording;
  `ZstdCompressCtx.compress(byte[])` fell from 2,912 MiB to zero.
- JFR: `/private/tmp/sdk-model-common-zstd-buffer-final.jfr`, SHA-256
  `60946ec944a06f03a06f720f8f893e0eabab2c362a51877afd5ea977e883c0e6`.
- Log SHA-256: `e07fcd60c2867c660ae4dc3c46435b709643353d659018cc18186b0be9ff214d`.

C1's targeted allocation reduction is real, but matched A/B experiment E3 rejected it as a throughput checkpoint. Its
geometric-mean result was 0.38% slower than A0 and its paired interval was extremely wide. The two-file dirty candidate
must be discarded rather than committed.

## Instrumentation plan

The next diagnostic pass stays on the complete canonical route and produces one operational timing table. Stable stage
IDs cover sender command mapping/serialization; SDK outbound queue/compress/write; Runtime decode and durable command
append; store-to-tracker wait and fetch; SDK decode/interceptors/handler/automatic `@Apply`; model cache/load,
evaluation and commit planning; commit serialization/queue/transport; Runtime model/event transaction and acknowledgement;
SDK cache/proof update and completion barrier; result mapping/interception/serialization; result transport and durable
append; result tracking/outbound transport; and sender decode/callback completion.

For every stage the canonical report records:

- invocation and item counts, input/output bytes and batch-size avg/p50/p95/p99/max;
- wall duration and per-item service duration avg/p50/p95/p99/max;
- queue-wait avg/p50/p95/p99/max, queue depth and active/available workers;
- CPU demand where measurable, compression ratio, JDBC round-trips/transaction time, WAL and I/O deltas;
- upstream/downstream throughput and backlog slope, so a batching feedback loop is visible rather than mistaken for a
  local cost.

Batch events describe capacity, while a deterministic 1-in-4096 command trace correlates the critical path across
stage boundaries. The timing table is interpreted together with per-window arrival/completion rates, queue and worker
occupancy, batch formation and bytes: high latency alone does not prove a throughput limiter. Nested and parallel wall
times are not added together; each report distinguishes elapsed critical path, local service demand and queueing. Field
collection is guarded by `Event.shouldCommit()` and measured against an uninstrumented canonical control. Separate CPU,
wall, allocation and lock profiles remain explanatory only and are never mixed numerically with non-profiled throughput.

## Experiment ledger

| ID | Date | A | B | Evidence | Decision / lesson |
| --- | --- | --- | --- | --- | --- |
| E0 | 2026-08-01 | A0 | R1 | Historical adjacent non-JFR and JFR runs | Inconclusive. Targeted allocation fell sharply, but unmatched absolute rates cannot establish E2E improvement. Run strict A/B. |
| E1 | 2026-08-01 | R1 | C1 | Adjacent JFR runs | Inconclusive. Common ZSTD allocation fell and correctness passed, but C1 needs a matched control after R1 is resolved. |
| E2 | 2026-08-01 | A0 | R1 | Four balanced non-JFR runs per side; [`model-e2e-e2-screening.csv`](performance-runs/model-e2e-e2-screening.csv) | Reject R1 as throughput checkpoint. A0 geometric mean 194,075/s; R1 195,453/s; delta +0.71%, paired bootstrap 95% interval -1.76% to +3.24%. All correctness checks passed. |
| E3 | 2026-08-01 | A0 | C1 | Four balanced non-JFR runs per side; [`model-e2e-e3-screening.csv`](performance-runs/model-e2e-e3-screening.csv) | Reject C1. A0 geometric mean 217,418/s; C1 216,586/s; delta -0.38%, paired bootstrap 95% interval -13.41% to +21.61%. Both sides varied sharply, so the candidate has no positive signal and the next step is stage instrumentation, not more blind micro-optimization. All correctness checks passed. |
| E4 | 2026-08-01 | A0 | First pipeline-event smoke | JFR `/private/tmp/model-e2e-pipeline-e4.jfr` | Diagnostic smoke only. It proved that the cross-repository event type could be recorded, but mixed an older SDK jar label and is not used to rank stages. |
| E5 | 2026-08-01 | A0 | Pipeline events before handler/JDBC service split | JFR `/private/tmp/model-e2e-pipeline-e5.jfr`, SHA-256 `90f16068dcabd4d169c2bc3fb5d501fe420b74739deb7e911259991212e5a50c` | 219,178/s, exact checks passed. It exposed a result-store queue of 14,720 and 228-ms p95 outer completion, but that duration still combined executor wait and storage service. Do not optimize from that aggregate alone. |
| E6 | 2026-08-01 | A0 | Canonical batch/service diagnostics | JFR `/private/tmp/model-e2e-pipeline-e6e.jfr`, SHA-256 `28985f2b07663fc402ef4712aaa72b0447464c50680c35d9758e052122345540` | 260,506/s, exact checks passed. The result JDBC writer supplies only 0.305M items/s of serial service against 0.261M/s observed E2E, with 18,591 queued outer messages and 109.750-ms p95 executor wait. It is the first proven limiter. |
| E7 | 2026-08-01 | E6 implementation | Measured-phase-only async-profiler CPU/wall/alloc/lock | Four separate async-profiler 4.5 recordings described below | Wall-clock attributes 96.7% of the result commit thread to PostgreSQL stacks and 61.2% specifically to `forceFlushStagingRows`. Optimize the partial-tail storage boundary first; cache and semaphore locks are observations for later reranking, not permission to leave the result writer saturated. |
| E8 | 2026-08-01 | Legacy staged tails | Always-direct tail diagnostic | Equivalent JFR, `/private/tmp/model-e2e-direct-tail-e8.jfr`, SHA-256 `9049c3b174093eb6d885b49c0ddeb553c83cf1a3f9024e58e167d06655461e60` | Causal hit: 311,377/s versus E6e's 260,506/s. Result service capacity rose 0.305M to 0.520M/s, p95 executor wait fell 109.750 to 29.646 ms and outer completion fell 114.921 to 33.170 ms. Exact checks passed. |
| E9/E10 | 2026-08-01 | Legacy staged tails | Always-direct tails | Eight balanced non-JFR runs per side | Candidate geometric mean 320,399/s versus 207,045/s, +54.75%; paired bootstrap 95% +50.23% to +58.84%. Replaced by an adaptive form before acceptance because always-direct storage can retain one LTS row per isolated low-rate append. |
| E11 | 2026-08-01 | Forced legacy staged tails | Adaptive direct tails under storage backlog | Eight balanced non-JFR runs per side; [`model-e2e-e11-adaptive-tail-confirmation.csv`](performance-runs/model-e2e-e11-adaptive-tail-confirmation.csv) | Accept after verification. Adaptive geometric mean 271,464/s versus 206,862/s, +31.23%; all eight pairs +17.13% to +53.54%; paired bootstrap 95% +24.68% to +38.92%. Exact checks passed in all sixteen runs. |
| E12 | 2026-08-01 | Accepted adaptive default | Final equivalent JFR | `/private/tmp/model-e2e-adaptive-tail-e12.jfr`, SHA-256 `4874af77a902a20930b3d695183ecb29dbe0cfc087fef54a7d03401f84877e9b` | 274,466/s, exact checks passed. Result service capacity remains 0.510M/s with 29.959-ms p95 queue wait. Allocation is 35,240 MiB versus E6e's 35,207 MiB, so the throughput gain does not hide a memory increase. The packed model store at 0.391M/s is now the lowest measured serial capacity and becomes the next profiler target after checkpoint P1. |
| E13 | 2026-08-01 | P1 with ordinary locator | Skip only the asynchronous derived locator | JFR `/private/tmp/model-e2e-no-locator-e13.jfr`, SHA-256 `dd31892fd49eeb679be0b0311e7b5a9b4d50c76ce3151445c936d0849dc1df33`; log SHA-256 `ed93baad9afa866bfce01afc83bf451fc6fb1445553fbc8ee5cc5267a51c971d` | Diagnostic only: 316,038/s, +15.1% versus unmatched E12. Exact E2E checks passed. The model store improved only 0.391M to 0.403M/s and remained the foreground limit, while removing locator contention improved every JDBC writer. The locator is material database load, but not the sole durable limiter. |
| E14 | 2026-08-01 | P1 | Measured-phase async-profiler wall plus JFR/SQL | Wall profile `/private/tmp/model-e2e-wall-e14.collapsed`, SHA-256 `f94ca529d976e02a8fa15d03f1dc58384c3648558def211e3a361b2d62e83e1f`; JFR SHA-256 `327538303aa6f997f7c99b07d92ca70a0b94faec9cd4568ab9481ff7cc108856` | 279,545/s. The event commit thread had 724 active wall samples: 215 staging flush, 206 model lock/COPY/update, 152 serialization wait and 63 commit. Across shared insert workers another 911 samples were in direct LTS inserts, 890 ending in PostgreSQL socket wait. Locator COPY wrote one scalar row per membership and consumed about 4.87 s aggregate server execution. Foreground event/model durability and direct LTS writes, not SDK `@Apply`, are the next target. |
| E15 | 2026-08-01 | P1 adaptive tails | Force direct tails for command, event and result | Equivalent JFR `/private/tmp/model-e2e-force-direct-e15.jfr`, SHA-256 `ebc2bfb99af164b0da0f8c0c2110b3f72e202ed6131080e98bacac1339509366`; log SHA-256 `7a91ec60123d8897a52c30175add0a6f99f5ae3337c04cf1ab56e8b0851eeb05` | Reject. Event storage p95 improved 35.151 to 25.737 ms and staging deletes disappeared, but global row fragmentation displaced work into command/result and GC rose from 7/219 ms to 26/1,256 ms. Full E2E fell to 249,001/s. Do not generalize direct tails beyond the accepted adaptive rule. |
| E16 | 2026-08-01 | Scalar B-tree locator | Same scalar locator without its index | Equivalent JFR `/private/tmp/model-e2e-unindexed-locator-e16.jfr`, SHA-256 `51cbd4ca22f6f488257ffc73c6088297925254afc07c5ee8317525045af31a46`; log SHA-256 `3c6b0cefa56069a5c46d93a78ecc3b49bce0592d4912149e140b0ed6e243f1e9` | Reject: 240,206/s. Removing index maintenance reduced locator shared-buffer hits but still required about 4.17 s aggregate COPY execution for 1.05M scalar rows. The index is not the dominant locator write cost, and removing it destroys the cold-read contract. |
| E17/E18 | 2026-08-01 | Immediate locator wake-up | 10-ms then 100-ms locator coalescing | E17 JFR SHA-256 `03113c6f945df458e2700cf0fb6ecaf7d4643aaadc85e7811f2c09794cb26994`; [`model-e2e-e18-locator-coalescing-screening.csv`](performance-runs/model-e2e-e18-locator-coalescing-screening.csv) | Reject and revert. At 100 ms, per-partition COPY/commit rounds fell from about 116 to 30 while writing the same rows and ending with the durable cursor exactly at the authoritative stream head. Three alternating non-JFR pairs were +0.035%, -4.013% and +2.501%; geometric means 261,112/s control and 259,732/s candidate, delta -0.529%. Lower derived-index load did not improve the full boundary. |
| E19 | 2026-08-01 | P1 | Nested foreground JDBC storage phases | JFR `/private/tmp/model-e2e-storage-phases-e19.jfr`, SHA-256 `ff4943b5f116b860d7172c9384a90c5822e505cadef4bf72b2084812eb8eead2`; log SHA-256 `5e7bc22732a6aaebb1491d45e3d492e30c40f234e1ac5a44206eafabc30cb931` | 233,355/s diagnostic run, exact checks passed. Result direct LTS insert was the largest foreground phase: 1,043,860 messages in 471 invocations, about 2.38 s total service and 0.439M/s capacity. Event staging, direct insert and co-located model work were about 1.21 s, 0.884 s and 0.753 s respectively. Optimize result direct insertion rather than the locator, cache or serialization format. |
| E20 | 2026-08-01 | Immediate unordered message-store backlog | Opt-in 1-ms idle collection delay | JFR `/private/tmp/model-e2e-result-microbatch-e20.jfr`, SHA-256 `255997dfaff61a31552e3fcda73a793e82149f1ba77912974dd6ad52e56f6682`; log SHA-256 `c3b8cea306298e4d24d3e04e5b7a46f079e71666c5f69679ab5a8a5ab9c04509` | 233,023/s, exact checks passed. Result batches fell 471 to 409 and average direct insert size rose 2,216 to 2,552; direct-insert capacity rose 0.439M to 0.558M/s. Command direct-insert capacity rose 0.779M to 0.971M/s. Full E2E was unchanged and service shifted into other phases, so continue only through matched screening. |
| E21 | 2026-08-01 | Immediate unordered message-store backlog | Opt-in 1-ms idle collection delay | Four balanced non-JFR pairs; [`model-e2e-e21-result-microbatch-screening.csv`](performance-runs/model-e2e-e21-result-microbatch-screening.csv) | Reject and revert. Paired deltas were +2.118%, +3.404%, -4.812% and +3.518%. Geometric means were 254,048/s control and 256,581/s candidate, only +0.997%, with one clear throughput and latency regression. The targeted JDBC phase improved, but a fixed delay does not remove enough full-route service demand and is not an acceptable low-rate latency trade. |
| E22 | 2026-08-01 | P1 plus nested storage phases | Fresh PostgreSQL statement accounting | JFR `/private/tmp/model-e2e-pg-insert-e22.jfr`, SHA-256 `72c050b8440dfaaae11226a41e37934ab78d3810445a3527546a04517dfb2798`; log SHA-256 `e69cc49803e7469466f96c787da9feb9bcdc298112381eee2a4771966fbd93bb` | 224,235/s diagnostic run. Result direct inserts occupied 2,189 ms of client/JDBC time while all measured result inserts used only 224 ms PostgreSQL execution time. Command and event showed the same roughly 3–5-ms non-server cost per call. Per-call protocol/driver/VM round-trips dominate the direct-insert phase. |
| E23/E24 | 2026-08-01 | Unbounded runtime storage attempts | Maximum four then two in-flight storage batches | JFR SHA-256 `c0f87f82eed2e4ffcb97d814d66a4f569e856518063bbd84e69c76b67ac60f90` and `924c32f1e80e5afb73ce08b8baea80f1b728dafe9533fb4ef284ba5568e4568e` | Reject and revert. A bound of four capped observed pending result jobs at three but left transaction count 460→465. A bound of two reduced it only to 435 and yielded 227,853/s with worse tail latency. Runtime backpressure starts after the SDK has already fragmented results. |
| E25/E26 | 2026-08-01 | Default 1-ms SDK result collection | Diagnostic 5-ms then 20-ms collection | JFR SHA-256 `7e2e6deabff05e48f626933037c7bf303f113f51fabdb7d57b2963e1b6dbc2f1` and `7d455e0f8b325a78cb8eb8ffd98c365190aaeecf7b215d64e4f7afa0e036626a` | Causal diagnostic, not a candidate. At 20 ms, SDK result publishes fell 453→192, runtime result transactions 460→318 and result-store capacity rose 0.433M→0.731M/s, yet E2E remained 226,916/s and latency worsened. Result batching is no longer the foreground gate; the model/event durability boundary is. |
| E27 | 2026-08-01 | P1 | Nested packed-model phases plus fresh PostgreSQL accounting | JFR `/private/tmp/model-e2e-model-phases-e27.jfr`, SHA-256 `1a0e9ef123a4ed9af58bd3d4f3330bed0dcae3effcd278115fbbeb7c2a32e5ba`; log SHA-256 `8b3729057e522a7a8aa96da2626d347b47b0184a7d493e07f2a6bf2790893d84` | 233,637/s, exact checks passed. Across 127 packed commits: stream-block COPY 363 ms client / 219 ms server, state lock 154 / 2.3 ms and state update 168 / 3.2 ms. The co-located event transaction additionally spent 1,027 ms staging tails. Small sequential protocol rounds, not server compute, dominate. |
| E28/E29 | 2026-08-01 | COPY plus separate state lock/update | Multi-row INSERT, then conditional state reservation | JFR SHA-256 `74a1426bbef21fc8c6baae1f1bc5ae00c730b4f055722ac9a8569f266d876212` and `b540a83b20f209858d161f1d9e2902554a9578fd12b73cbd458f5e6510b5a18f` | Microphases improved but no full-route hit. INSERT reduced stream-block time 363→277 ms. Combining lock/update reduced those state calls to one 177-ms reservation, but model capacity remained about 0.291M/s because event staging and transaction service dominated. Continue only as part of a structural event/model candidate. |
| E30/E31 | 2026-08-01 | Adaptive tails only under prior storage backlog | Direct underfilled tail for large co-located model transactions | JFR SHA-256 `c3f8b7e1a118e53ab11b37d2b33d3899213ddae1f0e50f07675b70f9e6e031ed` and `7f7da4ecd7b6f8019e97e33322ace750f8b204ccfca03e2f90244783316b7b8f` | The combined prototype cut event staging 1,235→63 ms and raised model capacity about 0.291M→0.323M/s. Tail-only E31 still delivered only 0.287M/s because faster drainage produced more, smaller model transactions and amplified COPY/lock overhead. Proceeded to matched screening only for the combined candidate. |
| E32 | 2026-08-01 | P1 | Multi-row stream insert + state reservation + selective co-located direct tail | Two balanced non-JFR pairs; [`model-e2e-e32-packed-write-screening.csv`](performance-runs/model-e2e-e32-packed-write-screening.csv) | Reject early and revert. Pairs were +1.261% and -4.294%; geometric means 249,936/s control and 246,048/s candidate, delta -1.555%. The second pair also regressed every latency percentile. Faster per-transaction work caused smaller storage batches and more transactions, cancelling the local phase gains. |
| E46 | 2026-08-01 | Clean P1 plus tracing checkpoints | Matched client/Runtime JFR rerank after rejected storage experiments were removed | Client/Runtime JFR SHA-256 `d3594ce20f792891b61f9852320ce2fb6f469749974850a020c96f4b1f81875e` / `cce1dbc928f88f78261726ceb9abc1411d1b94a2752d967e4f74809a0417d5ef` | 218,600/s diagnostic. It proved that 1,048,576 commits crossed 98,321 small Runtime intake boundaries while Runtime partial-binary WebSocket handling was the largest CPU cluster. Reopen only contract-safe ready-commit transport batching; generic delays and `AFTER_BATCH` remain rejected. |
| E47 | 2026-08-01 | E46 clean control | Bounded 256-ready-commit transport chunks under the unchanged default policy | Equivalent dual JFR plus exact checks | Causal hit: 276,404/s, 5,081 SDK sends and 13,713 Runtime intake boundaries. Model-store capacity rose 0.267M→0.344M/s and p50/p99 fell 236/475→187/366 ms. Proceed to strict matched confirmation. |
| E48 | 2026-08-01 | Same build with ready batching disabled | Bounded ready-commit transport default | Eight balanced non-JFR pairs; [`model-e2e-e48-ready-transport-confirmation.csv`](performance-runs/model-e2e-e48-ready-transport-confirmation.csv) | Accept as P2. Candidate geometric mean 330,222/s versus 275,049/s, +20.06%; all pairs +13.47% to +29.43%; paired bootstrap 95% +16.61% to +24.13%. All exact checks and 2,038 common/SDK tests passed. |
| E49 | 2026-08-01 | Accepted P2 forced to `BINARY_V2` | Negotiated `BINARY_V3` with native model-update tracking | Four balanced non-JFR pairs; [`model-e2e-e49-native-model-update-screening.csv`](performance-runs/model-e2e-e49-native-model-update-screening.csv) | Reject and revert. Pair gains were +6.02%, +2.46%, +11.75% and -3.86%; geometric means were 294,659/s control and 306,260/s candidate, only +3.94%, with exact paired bootstrap 95% -1.48% to +9.35%. All exact checks passed. Removing the 109-page tracker CBOR fallback is real local work reduction but is not a stable critical-path improvement. |
| E50 | 2026-08-01 | Accepted P2 storage path | Opt-in fused event-LTS/model-state/model-stream write under P2 readiness | Same-binary equivalent dual-JFR pair; 99 store tests; [`model-e2e-e50-fused-write-jfr.csv`](performance-runs/model-e2e-e50-fused-write-jfr.csv) | Reject and revert. Candidate/control were 247,471/275,485/s (-10.17%). Fusion reduced total event-JDBC service 3.981→3.568 s, but the faster individual transaction drained the Runtime backlog: packed transactions rose 142→263 and average size fell 7,846→4,236, reducing complete model capacity 0.314M→0.298M/s. P2 stabilizes transport readiness, not storage group commit. All exact checks and 99 fused-path tests passed; no E50 production code remains. |
| E51 | 2026-08-01 | Accepted P2 storage control | PostgreSQL statement/I/O attribution plus separate Runtime CPU profile | Clean schema, reset database statistics, exact full-E2E control and async-profiler; [`model-e2e-e51-postgres-profile.md`](performance-runs/model-e2e-e51-postgres-profile.md) | Diagnostic hit. The eight locator-partition COPY statements made 2,751 calls and consumed 7.413 s PostgreSQL execution, versus 0.351 s for authoritative model-stream COPY. The locator was only about 0.5% of sampled Runtime Java CPU: the cost is eager database protocol/transactions for an asynchronously derived index. Next: retain real hashes in authoritative blocks, hash-filter the bounded unlocated tail and materialize only full 384-block locator pages, with forced completion for purge/recovery. |
| E52 | 2026-08-01 | Accepted P2 eager locator | Hash-filtered authoritative tail plus full-page asynchronous locator materialization | Same-binary PostgreSQL-stat pair and exact full-E2E checks; [`model-e2e-e52-e53-locator-experiments.csv`](performance-runs/model-e2e-e52-e53-locator-experiments.csv); [`model-e2e-e52-e53-locator-experiments.md`](performance-runs/model-e2e-e52-e53-locator-experiments.md) | Reject and supersede. Locator COPY calls fell 2,594→72 and server execution 7.429→2.928 s, but 3,960 synchronous hash-filtered tail queries cost 2.444 s. Candidate/control were 275,022/284,519/s (-3.34%) with worse tail latency. Moving asynchronous work out of PostgreSQL did not help once recent reads paid for locator lag synchronously. |
| E53 | 2026-08-01 | E52 deferred locator rejected | Real hashes plus eager single-transaction server-side locator materialization | Same-binary PostgreSQL-stat pair, focused hashed-tail/legacy-rebuild test and exact full-E2E checks; [`model-e2e-e52-e53-locator-experiments.csv`](performance-runs/model-e2e-e52-e53-locator-experiments.csv); [`model-e2e-e52-e53-locator-experiments.md`](performance-runs/model-e2e-e52-e53-locator-experiments.md) | Reject and revert. PostgreSQL locator execution fell from 6.953 s of client COPY to 2.025 s across server-side inserts, but 270 wakes still issued 2,160 partition statements and 270 cursor updates. Candidate/control were 279,032/336,033/s (-16.96%) with p95 306.365/221.813 ms. The derived locator is a real database resource hotspot, but these pairs disprove it as the current E2E critical-path limiter; rerank from a control critical-path profile. |
| E54 | 2026-08-01 | Accepted P2 model-store batches | Opt-in collection window between durable model-store batches | Four balanced 1 ms same-binary pairs plus 2 ms screening; [`model-e2e-e54-inter-batch-delay.csv`](performance-runs/model-e2e-e54-inter-batch-delay.csv); [`model-e2e-e54-inter-batch-delay.md`](performance-runs/model-e2e-e54-inter-batch-delay.md) | Reject and revert. Control JFR proved a large fixed cost and size-dependent capacity, but a time-based delay did not reliably create larger Runtime transactions. The four 1 ms pair gains were +5.43%, -3.81%, -0.86% and +1.73%; geometric means were 315,150/s candidate and 313,388/s control, only +0.56%, with exact paired-bootstrap 95% -2.46% to +3.82%. The 2 ms screen was +4.28%. Next: reservation/arrival-boundary-aware merging or a correctness-safe parallel durable lane, not sleep-based batching. |
| E55 | 2026-08-01 | Accepted P2 ready chunk 256 | SDK ready-commit chunks of 1,024 and 4,096 | Two same-binary configuration pairs; [`model-e2e-e55-ready-chunk-screening.csv`](performance-runs/model-e2e-e55-ready-chunk-screening.csv); [`model-e2e-e55-ready-chunk-screening.md`](performance-runs/model-e2e-e55-ready-chunk-screening.md) | Reject without code changes. Chunk 1,024 was 302,721/304,438/s (-0.56%); chunk 4,096 was 306,288/314,081/s (-2.48%) with worse p95/p99. Neither setting consistently increased SDK dispatch batches, so ready-chunk reservations are fragmented again by physical WebSocket intake and closed-loop completions before the Runtime durable queue. The 256 P2 default remains. |
| E56 | 2026-08-01 | Accepted P2 clean control | Nested event-transaction attribution plus fresh PostgreSQL statement accounting | Equivalent dual JFR, fresh non-JFR PostgreSQL statistics and exact checks; [`model-e2e-e56-event-transaction-attribution.md`](performance-runs/model-e2e-e56-event-transaction-attribution.md) | Diagnostic hit. The packed model route spent 3.328 of 3.723 s inside its co-located event transaction and only 0.051 s waiting for that writer. Direct compact-row insertion, staging/prior-tail flush and model SQL consumed 0.969/0.948/0.828 s. A fresh 291,045/s control needed 472 event-LTS inserts, 166 staging inserts and 158 select/delete tail flushes, but only about 221 ms of aggregate PostgreSQL execution. Repeated client/protocol boundaries, not server compute or another queue delay, are the current limiter. |
| E57 | 2026-08-01 | Accepted P2 128-message event blocks | Direct 1,024-message blocks for large co-located model transactions | Same-dirty-binary equivalent dual-JFR pair, 40 focused tests and exact full-E2E checks; [`model-e2e-e57-co-located-large-blocks.md`](performance-runs/model-e2e-e57-co-located-large-blocks.md) | Reject and revert. Compact output rows fell 9,131→1,752 and staging service 1.071→0.380 s, but packed transactions rose 148→192 and their average fell 7,528→5,803. Repeated model SQL and commit work made complete packed service worse. Candidate/control were 247,367/274,520/s (-9.89%) and every latency percentile regressed. This closes physical block/tail reduction as an isolated fix; transaction formation must admit already-arrived work before a safe seal point. |
| E58 | 2026-08-01 | Accepted P2 ordered packed transaction | One non-blocking admission of compatible jobs already queued before a safe event/model seal | Equivalent dual-JFR mechanism pair, balanced four-pair non-JFR screen, 8 backlog tests, 41 message-store tests, 99 model-store tests and exact checks; [`model-e2e-e58-active-transaction-extension.csv`](performance-runs/model-e2e-e58-active-transaction-extension.csv); [`model-e2e-e58-active-transaction-extension.md`](performance-runs/model-e2e-e58-active-transaction-extension.md) | Reject and revert. The mechanism worked: packed transactions fell 155→119, average size rose 7,188→9,362, packed storage fell 3.711→3.044 s and staging nearly halved. Full screening was neutral: candidate/control geometric means 318,936/319,158/s (-0.07%), paired bootstrap 95% -3.41% to +3.38%. Tail latency improved, but p50 worsened and throughput had a -5.00% pair. Local transaction service is no longer the full-route throughput limiter under P2. |
| E59 | 2026-08-01 | Accepted P2 clean route | Measured-phase dual JFR plus threaded CPU, wall, allocation and lock profiles in both JVMs | Exact full-E2E checks in every run; [`model-e2e-e59-full-route-profile.md`](performance-runs/model-e2e-e59-full-route-profile.md) | Diagnostic hit. Command consumers spent 59.2% of wall samples in the real batch completion await and model-handler lifetime was 48.975 of 55.078 cumulative command-batch seconds. However E58 already proved that reducing only packed storage is neutral. Result append remained a 6.423-s/447-operation serial tail while the machine averaged 84.7% CPU. Runtime ZSTD, result output and LTS encoding were the largest independent CPU clusters; client encoding, callbacks, model preparation and adaptive cache were distributed secondary demand. Next run is a same-source compression-algorithm diagnostic, not another transaction or cache micro-tweak. |
| E60 | 2026-08-01 | Accepted P2 negotiated ZSTD | Same-source negotiated LZ4 with measured-phase JFR in both JVMs | Equivalent direct pair, exact full-E2E checks and wire/resource accounting; [`model-e2e-e60-wire-compression.md`](performance-runs/model-e2e-e60-wire-compression.md) | Reject without production changes. LZ4 delivered 265,448/s versus 272,212/s for fresh ZSTD (-2.48%), with p50/p99/max latency +3.33%/+4.99%/+8.80%. It expanded model-commit wire bytes 81.01% and Runtime result bytes 84.86%, while providing no compensating CPU, GC or downstream storage advantage. Retain ZSTD and attack the structural serialized commit-then-result boundary. |
| E61 | 2026-08-01 | Accepted P2 complete model route | Same route without ordinary command-result publication | Equivalent dual JFR and exact final event/model checks; [`model-e2e-e61-result-pipeline-upper-bound.md`](performance-runs/model-e2e-e61-result-pipeline-upper-bound.md) | Diagnostic hit, not a qualifying run. Removing the second result route raised 272,212/s to 405,700/s (+49.04%), reduced command/model-handler cumulative time 31.78%/29.55%, and improved packed-model transaction count/duration 14.93%/32.39%. This proves a large cross-pipeline feedback budget. E62 will carry a context-prepared native ordinary result with the model commit and use explicit negotiated/fallback semantics. |
| E70 | 2026-08-02 | Accepted P2 complete model route | Cross-JVM sampled route correlation and capacity-aware stage summary | Seven 131,072-command JFR smokes; corrected log/JFR/summary SHA-256 `8e582536b26fb26343437dc6b0327ad0ae9590a05510b45ed1a47a7951566a86` / `b47bac4dc6d834e90c3feba00468e1c1470152e9127d9f56cd71ac1258e6c23f` / `0907c463324074111851546f51eb4a398652c513902a06b4c4a11e847d3abecf`; [`model-e2e-e70-route-trace.md`](performance-runs/model-e2e-e70-route-trace.md) | Diagnostic instrumentation complete at smoke scale. The corrected run correlated every required processing stage for 33/33 sampled commands and joined 32 to sender registration while retaining the ordinary durable result route. Output separates latency distributions from serial service demand, observed wall rate, concurrency, queues and bytes. Two trace-observer allocation sites that sampled about 247 MiB in a prior smoke disappeared. Throughput 170,765/s is non-comparable because JFR was active and WindowServer/Codex activity consumed substantial host CPU before the run. |
| E71 | 2026-08-02 | Accepted P2 complete model route plus E70 observers | Atomic splitting of tracking, WebSocket, SDK callback, model-result and result-barrier intervals | Full 1,048,576-command JFR profile; log/JFR/refined-summary SHA-256 `2ff88c5b70e588eacb67b04ec4a4a8b96d2bce3bdbccb21c27744c0c9dac6fa1` / `3a6139446b82a1b8cee320cae68e2d0e205b4b579e3fead610eae078fecf3e3b` / `8c526b87514e2a7ca488478ca7a86b10ef1e600e0ebe752ed0446a1954009656`; [`model-e2e-p2-route-anatomy.md`](performance-runs/model-e2e-p2-route-anatomy.md) | Diagnostic hit. All 376 complete processing samples contained every new marker. The former command-durable-to-tracker aggregate is 44.728 ms, of which tracking `onUpdate -> batch resolved` owns 40.641 ms. The old post-commit suspicion resolves to only 0.970 ms through the per-message result barrier plus a separate 7.988-ms handler-batch tail. The one-lane packed model/event store supplied 0.295M/s service at 0.250M/s wall rate with 130 queued jobs and 41.168-ms queue p95. E71's 247,536/s is profiling-only and does not replace P2's 330,222/s canonical anchor. |
| E72 | 2026-08-02 | E71 atomic observer | Direct numeric metadata tracing and pre-sampled static route components | Complete 131,072-command JFR smoke; log/JFR/refined-summary SHA-256 `26de6fccd6446cd4213c0faee2ba71fb466db0df3a9cb885766f9b21a836f56b` / `242596aaaf319e3a52ab5297d6939937f5626a2a9dcb1f1ef453243481db5d8f` / `4fb75d5ec951590cad895be8298c77e9ff75674b78173cbf1badd2bce0beaa74` | Diagnostic observer correction accepted. All 32 expected routes remained fully staged. The prior smoke's 263.8-MiB SDK and 163.3-MiB Runtime observer-allocation sites disappeared, while tracking resolution and model queue/durability remained the largest structural intervals. Throughput 184,154/s is a loaded-host JFR smoke and is not canonical-comparable. |
| E73 | 2026-08-02 | Accepted P2 plus E72 observer | Atomic notification scheduling, notification-selected tracker resolution, all tracking scans and delegate store reads | Full 1,048,576-command JFR profile; log/JFR/summary SHA-256 `575dc911850168afc03c04301bd0f8bfd117903864a473ddab6694867c486ecf` / `ff1701f38150aaeb1726456a68f369a4dc5b74aa8c0d223af70056e8cff07e3a` / `85229f5b46472014fdb7b01a9b27f754bc2ff9a2998a8c756cad1cf68b51628a` | Diagnostic correction. C06 remained 41.621 ms, but all 852 command scans averaged only 0.480 ms (p95 2.057 ms), notification-worker queue averaged 0.077 ms (p95 0.263 ms), and only 26 scans/resolutions were notification-selected. The normal path is a new read after the SDK tracker finishes its preceding ordered batch. Parallelizing the rare notification fan-out would optimize a tail symptom, not the full-route limiter. All exact checks and 375/375 fully staged processing traces passed; 263,353/s is profiling-only. |
| E74 | 2026-08-02 | E73 accepted behavior | Existing `fluxzero.messageStoreDirectTailRows=true` on the complete route | Full 1,048,576-command JFR profile; log/JFR/summary SHA-256 `11f9352cd027ff535d6f69dd1070848c11852d3f1f4b15e4cfdd82a2a6f04ea2` / `b187a8710512a84cd58291dc4bed71f43949cf7a0461688a413064d61f2522d0` / `71d02f9308eb37d592099dfb1a3daa226a656b5dd929551b7236ce0a34f34a72` | Causal mechanism hit, not accepted code. Event staging fell from 8.382 to 0.001 ms mean and packed-model service from 24.555 to 19.431 ms, but smaller batches (7,944 -> 6,679) displaced work into co-located storage/commit and profiled E2E improved only 2.16% (263,353 -> 269,039/s). Exact checks and 332/332 fully staged traces passed. Build a narrow large-co-located-tail candidate; do not globally force one LTS row per low-rate tail. |
| E75 | 2026-08-02 | Accepted P2 plus E73 observers | Direct underfilled tail only for a co-located transaction containing at least one full storage group | Full 1,048,576-command JFR profile; log/JFR/summary SHA-256 `66916aaeb549768370361259887a2634b96ba05583ccf2009afd199ae2f8fd31` / `c6a58ff6836981f927e734e4247fdcfc9cce48ab0f97dac1dc50449ebcdf0013` / `b8908ec82d88e715522de872ef77f8d2aee27c9da2de5139938e36c887085a12`; [`model-e2e-p3-route-anatomy.md`](performance-runs/model-e2e-p3-route-anatomy.md) | Causal production mechanism hit. Event staging fell 8.382 -> 0.037 ms and staged event rows 8,064 -> 59; packed-model service fell 24.555 -> 20.268 ms while the complete profiled route rose 263,353 -> 270,497/s. Small and isolated appends retained staging. Exact checks and 297/297 fully staged processing traces passed. |
| E76/E77 | 2026-08-02 | P2 with the candidate disabled in the same binary | Large co-located direct tail default | Eight balanced canonical non-JFR pairs; [`model-e2e-e76-e77-large-colocated-tail-confirmation.csv`](performance-runs/model-e2e-e76-e77-large-colocated-tail-confirmation.csv) | Accept as P3. Candidate/control geometric means were 341,679/328,161 commands/s: **+4.12%**; all eight pairs were positive and the exact paired-bootstrap 95% interval was **+2.49% to +6.02%**. p50/p95 improved 5.78%/2.57%; p99/max were neutral (+0.03%/+0.34%). Every invocation passed the exact command/result/event/model checks. |
| E78 | 2026-08-02 | Accepted P3 candidate source plus rollback-head hardening | Final committed P3 success path | One final canonical 1,048,576-command verification on the exact production class; log SHA-256 `4f8b5ec817f0e07265092577a91d4ee3e51d99dc49cda5ecd4cf7a9605e0882a`; 42 message-store tests, 140 focused message/model-store tests and 672 full Runtime tests | Final-source confirmation: 341,117/s, p50/p95/p99/max 152.876/247.105/275.361/316.869 ms. Adversarial tests first reproduced stale staging-head state after a rejected large conditional transaction, then verified both no-successor CAS restoration and already-reserved-successor reconciliation. Runtime checkpoint `9d0bed30b643`. |
| E79 | 2026-08-02 | Accepted P3 plus resolution-origin observer | Distinguish notification-woken delivery from a later client poll for every sampled command/result | Full 1,048,576-command JFR profile; log/JFR/summary SHA-256 `7b7189f96f2d60751656cf7dd95205c9da62863346b0c0d5a6d36116e8fbd768` / `39735991b3ab60385101e37fe4a6c85c256b3c35e84f560c98575625f4656c44` / `47eefd265968a18e3f80f2655eafcd91a4a5f1db84fda4e8fd8d557425b96d02` | Diagnostic correction. Of 365 complete sampled command routes, **359 (98.4%)** were delivered by the consumer's later client request and only **6 (1.6%)** directly from a notification wake. C06 averaged 43.147 ms while all 910 scans averaged 0.682 ms. C06 is predominantly residence behind preceding ordered consumer batches; parallel notification fan-out or SQL scan tuning cannot remove it. Exact route checks passed; 270,677/s is profiling-only. |
| E80-E83 | 2026-08-02 | Accepted P3 ordered model/event boundary | Bounded depth-2 packed pipeline, followed by arrival-driven minimum batch admission | Three 131,072-command candidate smokes and one adjacent dual-JFR depth-1 control; machine-readable hashes in [`model-e2e-run-registry.csv`](performance-runs/model-e2e-run-registry.csv); detailed stage comparison in [`model-e2e-p3-route-anatomy.md`](performance-runs/model-e2e-p3-route-anatomy.md) | Reject and revert. Naive depth 2 proved real overlap (`max_active=2`) but fragmented 38 control transactions into 51 and ran 1.55% slower. A 4,096-job arrival gate preserved transaction shape (40 transactions, 3,277 average versus 38/3,449), yet model wall span changed only 0.872 -> 0.864 s while summed storage service rose 0.753 -> 0.935 s; complete E2E remained 1.31% lower. Parallel transaction submission merely contends on the same PostgreSQL write resources. Future parallelism must prewrite logically invisible immutable rows and retain one atomic ordered visibility boundary. All candidate code was reverted. |
| E84/E85 | 2026-08-02 | Accepted P3 ordered model/event boundary | Copy model-stream blocks into an invisible unlogged table concurrently with event insertion, then atomically promote rows and advance the state head in the event transaction | Adjacent 131,072-command dual-JFR candidate/control smokes; exact complete-route checks and machine-readable hashes in [`model-e2e-run-registry.csv`](performance-runs/model-e2e-run-registry.csv); stage distributions in [`model-e2e-p3-route-anatomy.md`](performance-runs/model-e2e-p3-route-anatomy.md) | Reject and revert. Final model insertion plus state update fell from 5.785 to **2.655 ms**, but the prewrite cost 9.994 ms and concurrent event work rose 7.934 -> 11.839 ms. Packed service increased 17.234 -> **21.859 ms** and complete E2E fell 150,339 -> **144,221/s**. Separate durable prewrite adds rather than removes PostgreSQL work; no production hardening is justified. |
| E86 | 2026-08-02 | Accepted P3 canonical conflict-free producer | Preserve a 65,536-command publisher wave by raising the existing SDK serialization chunk | Adjacent E85 control and 131,072-command dual-JFR candidate; exact checks and hashes in [`model-e2e-run-registry.csv`](performance-runs/model-e2e-run-registry.csv) | Reject without code changes. The canonical producer replenishes model slots from completed ordinary results; it is not a two-wave publisher. Handler batches changed only 167 -> 148, model transactions stayed exactly 37, Runtime model-intake fragments rose 2,103 -> 2,909 and E2E fell 150,339 -> 136,377/s. Upstream serialization grouping does not own the durable boundary. |
| E87-E91 | 2026-08-02 | Accepted P3 ordered model/event boundary | Preinsert model-stream blocks on the existing message-log insert executor using the same connection/transaction, allow at most two preparations, then run validation/state publication/commit in original reservation order | E87/E88/E89 adjacent 131,072-command dual-JFR smokes plus E90/E91 adjacent qualifying 1,048,576-command full-route candidate/control; focused transaction rollback, final-order, backlog completion-order and packed idempotency tests passed; hashes in [`model-e2e-run-registry.csv`](performance-runs/model-e2e-run-registry.csv) | Reject and revert. Ungated overlap fragmented 37 transactions into 123. A 4,096-job arrival gate restored 46 candidate versus 47 control transactions. It cut the serial co-located task 7.463 -> 4.161 ms but raised packed lifetime 16.397 -> 20.054 ms. The smoke showed +3.96%, yet the full canonical pair showed **238,000 vs 278,144/s (-14.43%)**. Longer simultaneous transactions damage the complete route; neither the bounded-backlog primitive nor the storage hook remains in production. |
| E92 | 2026-08-02 | Accepted P3 after all E87-E91 code was reverted | Simultaneous measured-phase async-profiler CPU profiles in the SDK and Runtime | Exact fixed 1,048,576-command full-result route; benchmark 237,458/s under profiling; collapsed-profile and log hashes in [`model-e2e-run-registry.csv`](performance-runs/model-e2e-run-registry.csv) | Diagnostic rerank. Runtime compression is 13.99% inclusive; overlapping message-store/tracking/WebSocket clusters are 19.42%/21.62%/24.73%, model-store Java 10.57%, PostgreSQL driver/COPY 5.81%, GC 8.34% and waits 24.77%. SDK modeling/tracking/results/WebSocket are 19.86%/21.82%/16.41%/27.49%, with waits 20.50%. The 8.93% SDK metadata/trace-observer cluster is measurement overhead, not a production target. No isolated Java hotspot explains the serial model/event wall boundary; continue with physical representation, not microtuning. |
| E93-E99 | 2026-08-02 | Accepted P3 physical row shape | Actual P3 compressed event/model row sizes with one or two binary COPYs per batch, logged commits, durable batch markers and either a tiny ordered visibility commit or bounded PostgreSQL prepare/ordered-commit | Two complete 1,048,576-item plain-commit curves, one unbounded 2PC curve, one deliberately retained resource failure and two corrected bounded 2PC curves; exact row/head verification; hashes in [`model-e2e-run-registry.csv`](performance-runs/model-e2e-run-registry.csv) | Causal architecture hit. Plain immutable writes scaled from 1.007M to 4.849M/s at 8,192 items and 0.623M to 2.929M/s at 4,096. Bounded 2PC, which also prevents premature visibility, reached **1.774M/s at four lanes** for 8,192 and **0.984M/s at eight lanes** for 4,096. The first 4,096 2PC invocation exposed and then fixed unbounded prepared-transaction residency; exactly ten diagnostic leftovers were rolled back. Build the complete route at depth four; probe rates are not E2E acceptance evidence. |

## Diagnostic checkpoint D1 — result writer saturation

The canonical E6 run used the current instrumented jars and a fresh schema on Docker host port 64217. Two earlier E6
starts never entered the benchmark because the container restart changed its dynamic host port. A later completed
211,183/s run (`e6d`) accidentally fell back to installed jars because two hand-written artifact names did not exist;
its absence of `io.fluxzero.*` events exposed that classpath error. It is retained as orchestration evidence and is not
part of the stage analysis.

E6e recorded 25,377 batch events and 3,072 deterministic request-stage events. Each of the 256 sampled original request
IDs appeared at all twelve intended command, model-commit, result-publication and result-delivery boundaries. Its main
serial capacities were:

| Stage | Batches / average items | Serial service capacity | p95 queue wait | p95 storage |
| --- | ---: | ---: | ---: | ---: |
| command `JdbcMessageStore` | 338 / 3,102.3 | 0.740M items/s | 48.089 ms | 6.895 ms |
| result `JdbcMessageStore` | 442 / 2,372.3 | **0.305M items/s** | **109.750 ms** | 18.166 ms |
| global event `JdbcMessageStore` | 126 / 8,322.0 | 0.435M items/s | 1.741 ms | 27.343 ms |
| packed model store | 126 / 8,322.0 | 0.376M items/s of recorded work | 30.098 ms | 38.710 ms |
| SDK result preparation/publication | 409 / 2,563.8 | 1.680M items/s | 4.233 ms | 0.125 ms publication |
| runtime commit-result WebSocket | 633 / 1,639.4 | 5.437M items/s | 8.000 ms | 0.039 ms publication |

The capacity is `items / sum(preparation + storage)` for the recorded stage. For the single-threaded JDBC message-store
commit executors this is a hard service-rate estimate; it is not interpreted that way for parallel stages. The result
writer is close to full utilization at the measured 0.261M/s E2E rate, while the SDK result and WebSocket stages have
large headroom. This distinguishes the bottleneck from the surrounding asynchronous completion chain.

PostgreSQL statistics were reset immediately before the measured phase with `pg_stat_statements`, `pg_stat_io`, I/O
timing and WAL timing enabled. The result staging table alone executed 264 delete/flush statements totalling 676.5 ms
of server time and touching 1,691,912 shared buffers. Client backends performed 1,802 WAL fsyncs totalling 806.8 ms.
The complete phase generated 299,722 WAL records, 2,714 full-page images and 113,571,082 WAL bytes. These numbers show
that server compute is not the full JDBC duration: transaction round-trips and durable flushes are material.

Async-profiler 4.5 was downloaded from its official release; archive SHA-256
`46d04ef81f532a065a0b3877e488aa706afa14aa2ea14433b323db9e6fda76dc`. A benchmark barrier started and stopped it
strictly around `recordMeasured`, excluding schema setup, seed creation and warm-up:

| Profile | Diagnostic throughput | Artifact SHA-256 | Result |
| --- | ---: | --- | --- |
| CPU, 1-ms interval | 245,653/s | `224312b33dd14e90f87219f06e0497d82077b999de0ebe52375bd6191d45899b` | Zstd stacks 9.3%, `SerializedMessage` stacks 9.0%, model handler 10.3%, JDBC message store 5.2%; the process is not CPU-saturated across all cores. |
| wall, 1-ms interval, per thread | 200,021/s | `4e6eac948439794379760121cd721456b5c753941858a18e3a46a39816df4891` | The result commit thread had 1,939 samples: 96.7% under PostgreSQL, 90.2% ending in socket `poll`, and 61.2% below `forceFlushStagingRows`. |
| allocation, 512-KiB interval | 195,330/s | `c8d3e4237411eaa71a9e09d444ac5e4fba9a72a1a2f8a49114dd3ae2e748f2ec` | Byte arrays 45.5% of sampled allocation; Zstd stacks 17.1%, serialized-message stacks 21.1%, JDBC message-store stacks 13.0%. |
| Java locks, 100-us threshold | 114,439/s | `53bfb850d624231714b2ac359502b5d5b6bf8973c583f7d0501f82c99b0c7f72` | Sampled delay is dominated by adaptive-cache support and the benchmark in-flight semaphore. Lock profiling is highly intrusive here; ordinary CPU/wall recordings do not identify either as the current E2E gate. |

Profiler throughput is diagnostic only. The recordings have different overhead and are never compared with ordinary
non-profiled runs. Production jars for E6/E7 were
`1c7e68f172175c52bba51b859fe9ade3e9d3b1ab5513d77b54240f71ab551b44` (common),
`e90853537fcb1f2ae2f608ef6abc43f7f8dea523641571d4c3e70e7c3b3c318b` (SDK) and
`8ff9554dde9fc44bb4280a0f6fd182635459ea973845ec46d4d04ed58441829b` (runtime). The next candidate must remove the proven result-tail
flush/round-trip work without increasing the generic backlog: larger message-store batches were already rejected in
Phase 21d.

## Accepted candidate P1 — adaptive direct tail rows

`JdbcMessageStore` now monitors its already ordered storage jobs. An underfilled tail is written directly as one normal
compressed LTS row only while an earlier storage job is still in flight. At idle, the existing staging behavior remains:
small appends accumulate to the count/byte boundary and compact as before. An explicit `false` property retains the
legacy path for matched controls; `true` forces direct tails for diagnostics. With no property, production is adaptive.

The direct row uses the existing LTS schema, MessagePack/native-envelope bytes, compression header, index range and
read path. There is no new wire or stored format. If an adaptive runtime encounters pre-existing staged rows, its next
backlogged job first compacts those rows in the same ordered transaction and only then inserts the newer direct tail.
This preserves visibility order, repeatable-read behavior, retry/idempotency handling, monitor order and future
completion. The commit executor remains single-threaded; the optimization removes redundant tail round-trips rather
than weakening durable ordered commits.

The always-direct prototype proved the hotspot but was not accepted unchanged. The adaptive guard preserves long-term
row compaction for low-rate logs, while E11 proves that it activates under the sustained full-E2E workload. E11 includes
a marked machine-speed shift in its second half; no observation was discarded. Balanced pairing carries that shift and
still leaves the entire confidence interval above +24%. E11 and E12 used benchmark source SHA-256
`1b85bb6ec7ce53d3e4565bf3f8dc07edef57228c2a41e1344bf7c97e3ea22feb`; E12 used runtime artifact SHA-256
`0c0ff04f58f11ed63c22032829c52fb8d56b6a239f5af995536d724f63a4c1f2` (unshaded runtime jar).

Checkpoint identity:

- SDK/common pipeline diagnostics: `a82093b84e1` (`feat(performance): trace model E2E pipeline`);
- Runtime adaptive-tail implementation and measured-phase tooling: `0b313069ae68`
  (`perf(tracking): write backlogged tails directly`).

The final adversarial review corrected two diagnostics-only lifecycle issues before committing: model-store JFR now
returns the completion stage carrying its callback, and request-stage completion remains recorded when its batch event
is independently disabled. Verification then passed in sequence against the same installed SDK artifacts:

- `./mvnw -B install` in `fluxzero-sdk-java`: all nine modules succeeded, including common, SDK, test-server, proxy,
  annotation-processor tests and Java/Kotlin downstream compatibility;
- `./mvnw -B install` in `fluxzero-runtime`: all four modules succeeded, including 666 runtime tests and benchmark
  test compilation;
- `git diff --check` passed in both repositories before staging.

## Diagnostic checkpoint D2 — derived locator versus foreground durability

The scalar model-stream locator is intentionally derived and unlogged. Reads remain correct while it lags because they
combine the locator prefix with the authoritative packed stream tail at the reader's own repeatable-read boundary.
That does not make its capacity irrelevant: E14 wrote about 1.05 million locator memberships through eight partitioned
COPY streams, which made those statements the largest aggregate PostgreSQL consumer.

Several attractive shortcuts are conclusively excluded:

- the previous compact `integer[]` plus GIN representation wrote far fewer rows, but had already been replaced because
  random cold model loads were materially slower; returning to it would trade away the read target;
- a scalar PostgreSQL hash index had previously lowered locator SQL work about 26% but produced 221k/s versus 228k/s
  for the B-tree control, so it was not retained;
- skipping the locator (E13), skipping its index (E16), globally forcing direct tracking tails (E15) and delaying the
  locator writer (E17/E18) are all diagnostics, not acceptable production changes;
- E18 proves that cutting locator transaction count by roughly three quarters is E2E-neutral. The locator remains a
  future sustained-capacity concern, but it is not the next foreground optimization.

E14's wall profile supplies the new ranking. During its 5.14-second measured window, the three single commit threads
were active for only 393 command, 724 event and 476 result samples; their largest classified work was durable commit,
staging flush, packed model storage and serialization wait. Separately, direct LTS inserts accumulated 911 wall samples
over the shared JDBC workers, of which 890 ended in PostgreSQL socket wait. This explains why summing only the commit
thread understates storage service: full compressed rows are inserted asynchronously on the transaction connection
before the ordered commit thread can proceed.

E18 paired log identities, in execution order:

| Pair | Immediate control | 100-ms coalescing | Delta |
| --- | --- | --- | ---: |
| 1 | 267,711/s, SHA `19101af69500696857c93e271a1747805bc5fa5e1a6226dce0a9565985243e42` | 267,805/s, SHA `ed0b334fe327c844faa17bd14b682732a71d8a20956c5d3e6e1043d0b00cd146` | +0.035% |
| 2 | 259,456/s, SHA `3ae0aab52f9cc706949f438962fa536eae22965df32eb8a2421c01cacbf8b93b` | 249,044/s, SHA `3a096e6fcdb566818c6d88b89471a9e7c7eb9dd38408c93e2bb257cbbd6a3be5` | -4.013% |
| 3 | 256,301/s, SHA `bad480e3ba900053c9a9cecbab5b53cdc1ca6ea8703e2245d52ad6be7f4c7a37` | 262,712/s, SHA `54cf8c176b38c9a0d9718260777fda74a72cb83d619e97832732618cd87956ca` | +2.501% |

## Diagnostic checkpoint D3 — direct result-row insertion

Nested JFR timing separates transaction work that E14 could only infer from wall stacks. E19 records the existing
single storage attempt without changing its execution order: direct LTS insertion on the transaction connection,
staging work, the optional co-located model task, durable commit and monitor publication. Field aggregation is guarded
by the JFR batch enablement check; ordinary runs execute the original task directly.

The foreground phase totals were:

| Message / phase | Batches | Messages / average | Aggregate service | Capacity |
| --- | ---: | ---: | ---: | ---: |
| result direct LTS insert | 471 | 1,043,860 / 2,216 | about 2.38 s | **0.439M/s** |
| event staging | 126 | 1,048,576 / 8,322 | about 1.21 s | 0.870M/s |
| event direct LTS insert | 118 | 1,040,384 / 8,817 | about 0.884 s | 1.177M/s |
| event co-located model task | 126 | 1,048,576 / 8,322 | about 0.753 s | 1.393M/s |
| command direct LTS insert | existing safe 4,096 bound | measured batches averaged 3,177 | recorded service | 0.779M/s |

E20 tested whether underfilled result rows, rather than row insertion itself, explain this demand. A bounded 1-ms wait
filled more of the existing 4,096-message maximum and raised the isolated result-insert capacity 27%, but E21 found
only +0.997% full-route geometric-mean throughput with a -4.812% pair. The public `Backlog` overload and runtime
property were removed after screening; production behavior remains unchanged. Increasing the maximum batch is also
closed: an earlier 4,096-to-32,768 tracking-backlog experiment yielded only 148,116/s.

The next investigation therefore stays inside the direct-row insert boundary. It must distinguish client-side
statement/preparation and connection serialization from PostgreSQL parse/execute/WAL work, then eliminate the largest
component structurally. It must not reopen fixed sleeps, unbounded batches, the locator representation or generic
serialization micro-optimizations without a new profile changing the rank.

## Diagnostic checkpoint D4 — batch elasticity at the model durability boundary

E22 through E26 establish that the result writer can be made substantially faster without moving full E2E throughput.
At the strongest diagnostic setting, result capacity rose from 0.433M to 0.731M messages/s while the route remained
near 0.227M/s. The packed model/event boundary is the foreground gate, not result serialization or result JDBC.

E27 then decomposed one million packed updates into 127 ordered transactions averaging 8,256 commands. The three model
operations performed almost no PostgreSQL compute beyond the stream-block write, but each paid a synchronous protocol
round-trip. More importantly, the event store staged 7,680 underfilled tail messages over 126 transactions. The next
transaction repeatedly selected, deleted, recompressed and reinserted that prior tail, consuming about 1.0–1.2 seconds
of the measured route.

E28–E31 removed these costs individually and together. Their local effects are real: multi-row INSERT was 24% faster
than binary COPY for only eight or nine stream-block rows; conditional state reservation removed one state round-trip;
and direct co-located tails removed roughly 95% of event staging time. The combined profiled run reached 245,269/s and
0.323M/s model capacity. It did not survive matched E32 because faster completion emptied the model backlog sooner:
batch count rose and the remaining fixed transaction costs were paid more often. This feedback is now an explicit
constraint on future work.

Therefore the next candidate must improve service while preserving or enlarging the natural model batch boundary. A
standalone SQL or tail optimization is insufficient. The investigation should trace the 605 SDK command/model batches
that become roughly 127 runtime transactions, identify an exact lifecycle boundary or bounded pipelining mechanism,
and overlap preparation without publishing, committing or completing out of order. Fixed sleeps, larger unbounded
queues and relaxed durability remain excluded.

## Diagnostic checkpoint D5 — transport fragmentation and batch-capacity curve

The apparent 605-to-127 batch transition hid another boundary. JFR-only tracing at the SDK WebSocket send and Runtime
model intake shows that E35's 629 handler batches became **82,547 Runtime frames averaging 12.7 commits**, before the
ordered model backlog opportunistically merged them into 119 durable transactions averaging 8,812. The Runtime intake
count covers all 1,048,576 measured commits. The SDK event starts at the benchmark recording barrier and therefore
misses about 11,000 already-prepared sends at the leading edge; deterministic request-stage samples link the same
request IDs across both sides.

The generic WebSocket collection delay is not a model-batch control. One millisecond happened to reduce model
transactions to 114 in E33, while five milliseconds increased them to 140 in E34. It also changes command and result
request batching. Both settings are rejected. The explicit `ASYNC_AFTER_BATCH` policy in E36 supplied a diagnostic
upper bound without changing production defaults: roughly 467 large frames carried nearly all commits, accompanied by
4,117 late singleton frames. It improved profiled E2E only modestly and increased model transactions to 136, so fewer
transport frames alone do not stabilize the storage boundary.

Model-store collection delays of 5 and 20 ms improved local service capacity but not enough to justify their latency.
An opt-in per-drain minimum then established the complete curve. A 16,384-commit target more than doubled model
capacity under the default handler policy; combined with large after-batch transport bursts it reached **0.855M model
commits/s**. Full E2E throughput fell because filling the target with the current 0.2M/s arrival rate consumed the
command/result latency budget and fragmented downstream results. The generic minimum-backlog overload, its tests and
the Runtime property were removed after E41; none remains in production code.

| Experiment | Diagnostic setting | E2E | Runtime frames | Model transactions / average | Model service capacity | p50 / p99 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| E33 | generic WebSocket delay 1 ms | 242,055/s | not yet traced | 114 / 9,198 | 0.311M/s | 223.397 / 405.970 ms |
| E34 | generic WebSocket delay 5 ms | 239,506/s | not yet traced | 140 / 7,489 | 0.296M/s | 229.347 / 362.854 ms |
| E35 | production default, boundary trace | 221,043/s | 82,547 / 12.7 | 119 / 8,812 | 0.293M/s | 244.144 / 404.629 ms |
| E36 | explicit `ASYNC_AFTER_BATCH` upper bound | 238,632/s | 4,584 / 228.7 | 136 / 7,710 | 0.313M/s | 223.189 / 407.074 ms |
| E37 | model idle-start delay 5 ms | 229,172/s | 71,153 / 14.7 | 108 / 9,709 | 0.329M/s | 242.455 / 377.484 ms |
| E38 | model idle-start delay 20 ms | 236,099/s | 75,355 / 13.9 | 103 / 10,180 | 0.361M/s | 222.085 / 453.891 ms |
| E39 | default policy, minimum 16,384 / 100 ms tail | 198,529/s | 88,810 / 11.8 | 63 / 16,644 | **0.603M/s** | 244.601 / 484.123 ms |
| E40 | after-batch upper bound, minimum 16,384 / 100 ms | 185,453/s | 1,214 / 863.7 | 50 / 20,972 | **0.855M/s** | 272.445 / 509.676 ms |
| E41 | after-batch upper bound, minimum 16,384 / 10 ms | 240,664/s | 845 / 1,240.9 | 106 / 9,892 | 0.431M/s | 214.880 / 394.962 ms |

All values in this table come from JFR diagnostic runs and are compared only as mechanistic evidence. Recording and log
identities are:

| Experiment | JFR SHA-256 | Log SHA-256 |
| --- | --- | --- |
| E33 | `f106f98e5bfe7442ef6637ed556684c3c393e2b08047fbb4286b4754e7c7d489` | `cc6bf1ea47f38a6f39cd2239bf5a081b6cf9d41bd140d5473dd3d3721e1714e1` |
| E34 | `205cf13cfd5e334bbb00f25486f5f4687e1f5bde55968f4a146e80f0ec83bf11` | `2f8868f63c22fd45fa5bc54016f31bb8f258bf86661dbb81755dfa5baffcbaf6` |
| E35 | `d458b60c3932363485ab15695a85f9ae81c8d12874a467fb21f1c8694edfdc93` | `18924defa10fa709a41a484328a31a1b04fcf190eb93c364d3db891cd3bf4bbf` |
| E36 | `f0c83e2327aff6db334e544519d3d7bda31bdf28caabf60dc3f17639329586bc` | `a054c88e85fe62c731c1da40323277f66632bca668f24e8a4f2d9bf490196acd` |
| E37 | `7185ae4821a78881a1868b6743d6eb8439eb7bd8426ef24a5cc6d5dd5e504b43` | `b360743e79e6b7da460a1f5fb8bda2e9336683942ea2902694d32c1b3554f08b` |
| E38 | `ab1b0499fe4133e5953ed5a1fd6bc46a23f316937aaf1cd9275911fba1dcfdc8` | `8576cee27aa99b44756b904af7dc340defa86fa804981033ef497d09472462a6` |
| E39 | `53a3cf9dc55a33da3927f952486bded5d6b1addc36e78b818a9b6cc3c4024231` | `5876128b5b27f9d0fdb28c087fccfc9e43c807d826bcffeb39e9ceeab0a08027` |
| E40 | `f45450bba9e3c8cd0de694cb98ea6ee393963a3a1a4a825fd1ac5317261fcbcd` | `3424bc3bfdc0cb1a6cbb83e059568294b6099233c5733e41847996f43dc3b25c` |
| E41 | `2db8feec0564536a311a9907571eab7ec611c65336e86ce6925412c0ce7dec5b` | `46a019554fb73e1b20fb9e77fe7041a1d27c0ab231668e4cdefda2af05a6a85a` |

The canonical 65,536 in-flight bound makes latency part of the throughput contract. At E35's 221,043/s it implies
about 296 ms mean residence time, consistent with the observed distribution. One million commands/s requires the full
durable result path to average at most 65.5 ms; intentionally holding a partial batch cannot meet that requirement.
The next structural target is therefore the fixed synchronous PostgreSQL protocol work inside each 8k–10k model/event
transaction. E27 already showed little server compute but several serialized client round trips. Those operations must
be fused or pipelined while retaining one ordered atomic commit, rather than amortized by waiting for a larger batch.

### Rejected experiment E42 — concurrent packed storage transactions

E42 tested whether bounded concurrency across conflict-disjoint packed model commits could overlap that fixed JDBC
work. A diagnostic depth of four reserved non-overlapping state ranges and model identifiers while retaining the
existing ordered `JdbcMessageStore` commit executor. Correctness held in all 99 `JdbcModelCommitStoreTest` tests, but
the mechanism worked against the adaptive batching boundary: it produced **393 transactions averaging 2,668 models**
instead of E35's 119 averaging 8,812. E2E reached 220,122/s with p50/p99 237.320/453.009 ms, effectively unchanged
from the noisy JFR baseline and with worse tail latency.

The phase comparison rejects concurrent transactions, not the fixed-work diagnosis:

| Phase | E35 existing ordered transaction | E42 depth four | Interpretation |
| --- | ---: | ---: | --- |
| Event stage | 1.119 s | 0.115 s | Pending jobs forced more direct tails, but this was outweighed below. |
| Direct event insert | 0.793 s / 119 | 1.361 s / 393 | More protocol calls dominated. |
| Co-located model write | 0.745 s | 3.238 s | Smaller concurrent transactions amplified fixed work. |
| Event commit | 0.151 s | 0.941 s | Transaction concurrency increased commit cost. |
| Model stream insert | 0.404 s | 1.502 s | The same data was split across many more COPY operations. |
| State lock | 0.145 s | 0.652 s | Lock round trips multiplied. |
| State update | 0.167 s | 1.035 s | Update round trips multiplied. |

Recording `/private/tmp/model-e2e-storage-pipeline-4-e42.jfr` has SHA-256
`a86e214ce1f275b047dcd7e7d857cbdd4dbe60350b3e523628794cb257f7696d`; its benchmark log has SHA-256
`140a3ef344469f9ce323508be27e2921f4b76f083b2f6cb9aa30dda98a209161`. The entire pipeline candidate and its
configuration were removed after measurement; no E42 production code remains. The next candidate must preserve the
existing single ordered packed transaction and reduce protocol exchanges *inside* it.

### Experiment E43 — fused write exposes a closed-loop batching limit

E43 kept the existing single ordered transaction and replaced its direct event insert, model-type ensure, model-stream
COPY and state lock/update rounds with one conditional data-modifying statement. The event store handed its already
serialized compact LTS rows to the co-located model task; state validation, event rows, initial stream blocks and the
state-head update remained one atomic transaction. The erasure route retained the existing validation and write path
under the same state lock. All 99 `JdbcModelCommitStoreTest` tests passed with the route enabled.

The local mechanism worked but the complete pipeline did not improve. Event stage work effectively disappeared and the
combined event/model write consumed 1.855 s across the measured million commands, versus about 2.657 s for E35's event
stage, direct insert and co-located model phases. The faster completion immediately drained the model backlog, however,
so durable model transactions increased from **119 × 8,812** to **272 × 3,855**. The extra fixed statement and commit
rounds reduced model-store capacity from 0.293M/s to 0.271M/s. Full E2E was 228,551/s with p50/p99 223.803/413.438 ms:
only +3.4% against the unmatched E35 JFR observation and with worse tail latency. It therefore fails the checkpoint
gate despite the real local phase reduction.

| E43 phase | Calls / items | Total or capacity | p95 |
| --- | ---: | ---: | ---: |
| Fused initial write | 272 / 1,048,576 | 1.855 s; 0.565M/s | 17.870 ms |
| Complete packed model store | 272 / 1,048,576 | 3.869 s; 0.271M/s | 32.125 ms |
| Event transaction commit | 272 / 1,048,576 | 0.783 s; 1.339M/s | 6.450 ms |
| Result store | 639 / 1,048,576 | 0.302M/s JDBC service | 19.005 ms |

The Runtime recording `/private/tmp/model-e2e-fused-write-e43b-runtime.jfr` has SHA-256
`1f159fbe4a6721bfa25e3f743bb7584ad1a54f813ceb52fc936195315e32cd88`; the client recording has SHA-256
`2df6022a2831baef5f5b14e9810650fb6e5e452a4d132381f8be072f91ef1062`. The benchmark log SHA-256 is
`7072d04036e7837342e51850226f21ae0f705ec561e838e8e33451b336eef688`; the Runtime log SHA-256 is
`087096dc26dc881010e7011cf3d56f22acaa30cdcced985b74dcd656216f2d23`. Exact result, membership and global-event
checks passed. Because the full route failed the checkpoint gate, the fused implementation was removed after E45; no
production code or opt-in switch from this experiment remains.

E44 then combined the same fused implementation with explicit `ASYNC_AFTER_BATCH` solely as a causal readiness upper
bound. It reduced Runtime intake frames from 100,905 to 8,201 and raised the average model transaction from 3,855 to
5,041. The fused statement reached 0.882M/s local capacity, complete packed-model service reached 0.411M/s and E2E
reached 254,253/s with p50/p99 193.399/433.333 ms. Result-store service also rose from 0.302M/s to 0.436M/s because its
waves grew from 1,641 to 2,411 messages. This proves that ready-wave shape affects every downstream writer, but the
explicit policy is rejected: it delays commit start until handler-batch close and is not the production default.

E44 Runtime/client JFR SHA-256 values are
`6bba05f742a4587918c875bdfb5864980f04e2e569683503eaa691e6a8dbe3b9` and
`54e3365352479a3d3624085467bd26358c9c6712d4349cd19dd065a872d9630e`; benchmark/Runtime log SHA-256 values are
`b8b6dfc65fab214adbc5ee97c96f3fb5a1fdf3bb9dd0a7772c5ae5abc17f8a41` and
`c9fe3d01cf6f3f6bec94f45052e735daba04254da9ec3cdd344f77962a95a97a`. All exact checks passed.

E45 combined the fused write with the previously rejected generic 1-ms WebSocket collection delay, under the correct
default commit policy. It reached 261,963/s, p50/p99 195.232/418.183 ms, 68,882 Runtime commit frames and 204 model
transactions averaging 5,140. Complete model service was 0.370M/s and fused-statement capacity 0.692M/s. This is a
useful +14.6% diagnostic signal versus adjacent E43, but it does not reproduce E33's 114-transaction shape and remains
far below the target. Because the delay also changes command/result request latency and earlier matched screening
already rejected it as a production mechanism, no generic delay is retained.

E45 Runtime/client JFR SHA-256 values are
`f9494370664f8ed7eea2a067b11143623a8a11f17e9e784fcd4d6681f6227411` and
`0c5fd04196012dc3cbfdd8c67c487e5e26e783daaf21ea75d149689e7f8fa010`; benchmark/Runtime log SHA-256 values are
`39cb4b5c6ee2f2c49a6624e34c78d653ffe8d3f8fead14c25a88ac04e92d9760` and
`a02727672391d9ccf614b8e12f713fa185391e400a7e3329607b808461c8b844`. Exact checks passed.

### Accepted checkpoint P2 — bounded ready-commit transport batches

P2 is SDK commit `1b6b3571a1c` (`perf(modeling): batch ready model commits`) against unchanged Runtime commit
`ed9cb3419e0b61e49869886f81f742f1c8bf6a77`. The measured candidate diff was committed without functional changes
after the confirmation matrix and full reactor verification.

E46 reran the accepted and fully reverted source state in separate client and Runtime JVMs. It handled exactly 1,048,576
measured commands at 218,600/s under dual JFR, with p50/p95/p99/max 236.050/383.319/475.474/521.326 ms. The SDK
produced 98,321 Runtime commit intake boundaries averaging only 10.7 commits. Runtime partial WebSocket binary handling
was its largest inclusive CPU cluster, while the packed model writer completed 147 transactions averaging 7,133.2
models at only 0.267M models/s. This clean rerank superseded the weaker E43 profile for the next candidate decision.

The accepted candidate does not add a timer and does not change `ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH`. Each commit is
still prepared immediately after its handler. A handler-batch-local transport scope releases every full bounded chunk
of 256 ready commits immediately; batch close releases at most 255 tail commits before registering the existing
completion barrier. Every commit retains its own Runtime response future, request ID, correlation context, retry state
and result processing. Custom batching clients can retain individual transport, explicit after-batch and synchronous
policies retain their prior paths, and memory is bounded by 255 prepared requests per active handler batch.

E47 confirmed the mechanism under equivalent dual JFR. It reached 276,404/s with
p50/p95/p99/max 186.904/329.153/365.952/415.646 ms. Logical SDK model-commit sends fell to 5,081 averaging 204.7;
physical Runtime intake boundaries fell to 13,713 averaging 76.5. The packed model writer completed 135 transactions
averaging 7,767.2 at 0.344M models/s. Thus the improvement is not merely fewer frames: less Runtime WebSocket work also
fed the serial durable writer more effectively. The client and Runtime recordings have SHA-256
`a8ca46b56f50b9516821b5584d01bc5920eeeddaa2ddd27c2ad35787f26f682f` and
`bc2d878a25cd0b6284fab5001ae89becba7bb53c38e1d3f017c67953845f32a9`; benchmark and Runtime logs have SHA-256
`81458618bbf322ad57a7c931ec6da5d79dbea6b46e1bd80c6b7719c8621c9c5d` and
`da79ab9d6938ecd9b0316cccc7a46803603a9692acbbe12d8b5697502a1e7a60`.

E48 compared the same binaries with only `fluxzero.disableReadyModelCommitTransportBatching=true` on control runs.
The sixteen executions used order `A B B A B A A B` followed by its inverse; every run restarted the external Runtime,
recreated the isolated schema and captured source/diff, host, JVM, database and log identities. Candidate throughput was
321,469–340,707/s and control throughput 249,268–292,349/s. Candidate/control geometric means were 330,222/275,049/s,
a **20.06%** gain. All eight paired gains were positive (13.47–29.43%) and the paired bootstrap 95% interval was
16.61–24.13%. Every run verified the full ordinary-result boundary, expected model state and event count. Candidate
p95 and p99 latency were lower in seven of eight pairs; the one p99 exception remained a throughput-positive pair and
had no error or queue-growth signal. No Runtime log contained an error.

Focused lifecycle tests prove that full chunks release before handler-batch close, the tail releases only at close and
the tracker batch still waits for the true commit response. The complete `common` plus `sdk` test run passed 2,038 tests
with zero failures or errors. The public batching interface gains only a binary-compatible default method. P2 is
therefore accepted as a throughput checkpoint rather than a diagnostic-only optimization.

### Rejected experiment E50 — P2 does not stabilize storage group commit

E50 retested E43's one-statement event/model write on the accepted P2 source pair. This was justified by E47: packed
model/event storage capacity was only 0.344M/s, approximately the accepted route throughput, while P2 had reduced
transport fragmentation and produced 135 transactions averaging 7,767 models. Candidate and control used identical
classfiles; only the candidate Runtime enabled `fluxzero.fusedInitialModelWrites`. Both sides ran equivalent dual JFR,
recreated the schema and passed every full-E2E exact check. The fused path also passed all 99
`JdbcModelCommitStoreTest` tests before measurement.

The result is a clear rejection, not a noisy throughput decision. Candidate throughput was 247,471/s versus 275,485/s
control (**-10.17%**); p50/p95/p99/max all regressed from 189.940/293.589/345.632/390.167 ms to
199.228/317.643/376.806/435.495 ms. Fusion did reduce aggregate event-JDBC service from 3.981 to 3.568 seconds. It
nevertheless made each ordered storage attempt finish sooner, so the opportunistic Runtime backlog drained before the
next ready wave grew: packed transactions increased from 142 to 263 across warm-up plus measurement, average size fell
from 7,846 to 4,236 and complete packed-model service capacity fell from approximately 0.314M/s to 0.298M/s.

This falsifies the assumption that P2 also fixes the storage feedback found in E43. P2 bounds and groups SDK transport
readiness, but the Runtime storage transaction still takes whatever is ready when the preceding transaction completes.
Reducing work inside one transaction therefore changes the next transaction's size. A future durability optimization
must remove the fixed protocol work while preserving an arrival-defined atomic group, or extend an already-started
ordered transaction with work that became ready during its database operation. A fixed delay, larger in-flight bound,
explicit `AFTER_BATCH`, concurrent transactions or a claim based only on local SQL time remains disallowed.

The exact run identities are in
[`model-e2e-e50-fused-write-jfr.csv`](performance-runs/model-e2e-e50-fused-write-jfr.csv). Candidate/control Runtime
JFR SHA-256 values are `78ff3ec43c8fb47921de7f7d119c741212ecc82b85ca3d4f42a457fb3d810edf` and
`3837c4beefc5e00c20d2c0122ba51cea963e481e99489c43d523840169f84ef0`. The experiment was fully reverted; no fused
write API, property or production code remains.

### Rejected experiment E62 — carrying ordinary results through model-commit durability

E61 proved that removing ordinary results raises the adjacent complete-route JFR observation from 272,212/s to
405,700/s. E62 therefore tested the strongest form of result fusion: SDK-side mapping, interception and envelope
serialization; a newly negotiated transport revision carrying that envelope with `CommitModels`; and Runtime ownership
of result durability with an explicit stored marker in `CommitModelsResult`. Unsupported peers and failed fused writes
retained the established SDK result-gateway fallback. This entire experiment remained uncommitted.

The first full run exposed a transport-shape correctness defect rather than a performance result. Single native
`CommitModels` requests and single or mixed `CommitModelsResult` responses fell through generic encoding, losing the
attached result or its stored bit. The route ran at 128,291/s and produced 10,326 duplicate late responses reported as
unknown request IDs. Native single-item wrappers plus a generic V3 stored-bit fallback fixed the shape: focused
common/SDK tests passed and every later run had exact result, event and model counts with no duplicate-response warning.

Correctness did not make the architecture viable:

| E62 variant | Complete-route JFR | Result transactions | Model transactions | Finding |
| --- | ---: | ---: | ---: | --- |
| Result append after each accepted model group | 126,347/s | 3,251 | 888 | A second result transaction held every model request future and destroyed upstream batching. |
| Bounded 16,384-result / 1-ms Runtime result backlog | 146,604/s | 903 | 830 | Result queue wait fell from 25.522 s to 0.503 s, but model request slots still waited for a separate result transaction. |
| Result rows staged in the event/model JDBC transaction | 149,611/s | 26 fallback appends / 3,688 results | 474 | Atomicity and ordering held, but result preparation and storage extended the model acknowledgement loop. The accepted clean route needed only 144 model transactions. |

The co-located prototype reserved result indices and the result store's ordered commit lane before staging rows on the
event store's JDBC connection. Deterministic tests proved caller-transaction visibility, rollback, repeated staging on
transaction retry, ordering against later result appends, and setting `handlerResultStored` only after commit. In the
canonical run, the result store staged the remaining 1,044,888 results in the same event/model transactions. This was
not enough: result serialization plus SQL inside the acknowledgement boundary increased cumulative packed-model time
from 3.160 s to 6.631 s and queue wait from 2.089 s to 5.712 s. Transactions grew from 144 × 7,281 to 474 × 2,212.

Client JFR found another invalid prototype assumption. Per-message preparation used one
`CompletableFuture.supplyAsync` per result. The client consumed roughly 25–30% machine-wide system CPU while the whole
machine remained at 97–99%; `ThreadLocalContext.capture`, concurrent-map operations, metadata encoding and
`SerializedMessage.encode` were prominent. Two boundedness alternatives were screened only as 131,072-command JFR
smokes:

| Preparation mechanism | Smoke throughput | Preparation/model shape | Rejection |
| --- | ---: | --- | --- |
| One async task per result | 114,492/s | 85 model transactions | One million fine-grained ForkJoin tasks; not resource-safe. |
| One existing ordered result worker | 85,856/s | 15,044 preparation batches; 160 model transactions | Preparation shared a serial gate with publication and fragmented handlers further. |
| Fire-and-schedule parallel preparation batches | 11,668/s | 349 SDK dispatch batches; p50 2.666 s | The common pool was flooded by nested preparation work; unbounded scheduling is categorically rejected. |

E62 therefore rejects the complete pre-commit fusion shape, not only one tuning. A result carried inside the
model-commit request must be prepared before send and acknowledged after its durability boundary. Both move result work
onto the serial closed loop that P2 deliberately kept short. A future result redesign must preserve independent model
commit progress while giving Runtime durable ownership—for example through a bounded durable outbox or a native
multi-log write whose preparation is already complete—without claiming result storage before a recoverable handoff.
It must also use a lifecycle-bounded batch executor; per-message ForkJoin tasks, a single shared preparation gate and
unbounded parallel scheduling are closed. All E62 production and test code was removed after recording these results.

Canonical artifact identities:

| Variant | Client JFR | Runtime JFR | Benchmark log | Runtime log |
| --- | --- | --- | --- | --- |
| Initial shape defect | `b3a1460adbd03a1b1f201c09b44d2cbdebf26275d426b06300e754e292bef580` | `d1cb4ab575a09d606a77afbbaeca5f80ec0eaceed391596953318f49b39663e9` | `7643fcbc265b3862003ba83bf9ce9e3f964b884c78c7f5f93193c1606eeaedc6` | `43907215a8ce89c04dc2e9a9f13f2ba3351f8fa88cd7ebcd0e20c63601f19eec` |
| Correct post-model append | `28d02ca43af86c19598996a5529651b134a5713cab183d5bde9e58ce4c7bdff2` | `6a97cdd5fba27db970ab2aaa2ccd263fda1721aa58f43970646f04148e7b0108` | `ab35b94e10a0b2a1d6ca067788ea36a222104f7adfaaa551f024b699c84904d6` | `3dffeae5cec7da0cc2355ff2665b1e11f7f5975e73e2a6e014ce6cdbd84a75e1` |
| Bounded Runtime result backlog | `cdedd0594c18be5494bcc9b7a312f64e990785a5ab9d12187fa1961c8461a1f4` | `2a7b52fa34ac5da5fa63ef5d806e8b931c41111480bb1c6335183fc0a22e2419` | `b1bb44dcd40ae324dcda4391b95640947a3917c217e1d81ec2f34663890a1eb8` | `39caeb1cc6a42a8fed43b3db803ea5bc01f2e4fa52849d87d306a405c3bbfb5c` |
| Atomic result rows | `6a32b045de2fa4d34099a708ad0abb3c7de9873b2e65051840477f48b7cc739f` | `1f0cf88fb0110dc15559160d6459ea02d100bbe9d937d73339d26bafcca0521a` | `876ad392ab8f43a33c5e2dff611a8d53e33b4ad9fa3db8785cc70fb83ba764f1` | `ca7979f2f3238576d71e125c7c3b85eb61473b6536a3998020d38d121d7da8da` |

The three preparation-smoke client/Runtime JFR pairs are respectively
`5c910d2b773817bbbbd16e36e109b0270b4b1d26097769cac63b1f9efab58aea`/
`9572d078b7767f92b19f5e11c1c75c3a6f3035efb7a1f06ec5e5f6b2346a1d54`,
`736b1871f0ce4ac6833a316624a62e41a4f536be3895aa8edb6d0f31fff4c92c`/
`51806341cd0e981b85e89ffacd682bf7479734fc13549799ea311719dead4e34`, and
`1b0cc96038929ea2075e3e5e876dcb1b60ae122a0f17465ba7500ac874a15f17`/
`9c36b12101d010c6af876c0ec37b53e8f4d8848e29574c5cd9e44dcd25032d0d`.

### Rejected experiment E63 — result-only ordered front grouping

The clean accepted production sources are restored exactly: SDK `common/` and `sdk/` match `e94188b5876`, and Runtime
plus benchmark sources match `ed9cb3419e0b`. A first noisy non-JFR recovery observation reached 245,771/s while the
host load was about 6 and WindowServer/Codex consumed visible CPU; it is an environment observation, not a replacement
for E48's matched 330,222/s accepted geomean. No code difference exists to explain it.

E59 provided the E63 hypothesis: 472 SDK result-publication batches became 447 result JDBC transactions. The existing
`ReadWriteMessageStore` backlog is unordered-async. It drains each currently available block immediately and schedules
every resulting JDBC job behind the store's single commit executor, producing 4.009 s cumulative result queue wait plus
2.332 s JDBC service. E63 tested a result-only ordered front backlog: while one real result append was durable, later
already-arrived result requests may form the next bounded group. It adds no collection timer, leaves non-result logs
unchanged and must preserve request order, individual completion/failure, native envelopes and shutdown.

The deterministic ordered-completion test and all six endpoint idempotency tests passed. A same-binary, alternating
131,072-update JFR screen then rejected the candidate before an expensive canonical run. The candidate reduced result
transactions from 69 to 58 (-15.94%) and packed model transactions from 28 to 26, but throughput fell from **177,524/s
to 167,313/s (-5.75%)**. Candidate p50/p95/p99/max was 171.224/254.725/264.108/275.108 ms versus the control's
151.716/261.858/270.997/291.501 ms. The mechanism performed some grouping, but most result requests still crossed the
front backlog in separate waves and its extra completion boundary did not remove the real closed-loop cost. A local
transaction-count win without E2E capacity is explicitly outside the checkpoint gate. All E63 Runtime code and its
test were removed; accepted production source remains unchanged.

The candidate benchmark/Runtime/client-JFR/Runtime-JFR SHA-256 values are respectively
`038204b7e10216d9e1d5635bbb57fa574b6b95980a1c8e9d8d04b756593ae7ab`,
`e6e1f9e763fdef2dbe3469357865bf7236ababd677ef3154bbfbdd06f02ddba1`,
`c92901164a1539e6d64a9f01d5badf9aae5b44185bdb6373ad259eeb1e4f8848`, and
`29620e1aae37b9da4e921d5c493d1855f25ff6c6aaf66dd0ee2f4e58ebae0f92`. The equivalent control values are
`7ebdc3798b0d56ad6a4b52a09041a5d7aa2c70743990c56c16c02dc035f9abfd`,
`64f0a62f905b5a869d18ddbd896b691cbc1f1b3389e562fbed012e2f901f5d00`,
`78fd52619340fa9d8955a6810b687b7693db52bc89b92cb0234f4cf9ac60432c`, and
`8f9fa7b8cd7048d00993a298559260ab3e57010342fc59df5733c477d28e1617`.

### Diagnostic E64 — result durability is not the complete-route limiter

E61's 405,700/s no-result upper bound removed SDK mapping/serialization, result publication, Runtime durability,
tracking output and sender callbacks simultaneously. E59 ranks those clusters but cannot assign their cross-pipeline
feedback independently. E64 retained ordinary result preparation, both WebSocket directions, Runtime result
tracking, callback completion and every exact result/event/model assertion while selecting the Runtime's existing
ephemeral store for `RESULT` only. This is a causal diagnostic and can never qualify as a durability-preserving
checkpoint.

The adjacent same-source 131,072-command JFR screen measured **174,019/s** with the ephemeral result store versus
**177,524/s** for the durable control (-1.97%). The Runtime explicitly selected `InMemoryMessageStore` for `RESULT`,
and its JFR contained zero result-JDBC events versus 69 result transactions in the control. Exact results, events and
models still passed. Removing 0.437 s of observed result-JDBC service and every result commit did not improve E2E, so
neither result transaction grouping nor result SQL is the next route-wide candidate.

A full 1,048,576-command async-profiler run retained the ephemeral store to rerank the remaining result route. Its
217,031/s is an environment observation, not a checkpoint comparison. Inclusive Runtime samples ranked WebSocket
result output at 10.31%, transport compression at 8.53% and `SerializedMessage` work at 6.61%; SDK result preparation
was 6.73% and result callbacks 4.37%. The percentages overlap and C2 still consumed 12.45%/8.76% of the two JVM
profiles, but they unambiguously move E65 to result representation/transport/output rather than durability.

The ephemeral-screen benchmark/Runtime/client-JFR/Runtime-JFR SHA-256 values are respectively
`6690673e5855452e90f818eebc0db7c5cce7b1ccd85b87eb32dbe8b31df37eeb`,
`5cfd950fcfbd5856dcfc16be7f3d1a1d1c1116d62bb61402465b409c3472ac72`,
`862490da0a0ab24765eb7bcb7155a4d9ea3c6dde1083f3fc0666991c41d2de63`, and
`f6c5e20c23f0738e287fbc0e23defee4bcfa850119cc1c97db3bbdc0c65fb965`. The full CPU
benchmark/Runtime/client-JFR/Runtime-collapsed/client-collapsed values are
`4a11bc2d022561d95bffac01d0c2ebd1ce7ee80bb6303cdeb7b1198bf19ba91c`,
`7df66947cbaa7470a22cfaf28d19f3436e19e0cd327e8539697cc1c9fc19a935`,
`f6eb27dba1128c42180d6d7e6acd386fc1e67b27c44cc426c3860a151c957b37`,
`5547cb589fa26d2c67e5baedeeaa72b7b708bab42cad7c80d5411d0b26e0e53a`, and
`1a5da2a2c199a03d703ede601f1a25988cc3d2fa99f328991776b1dadb3b0c04`.

### Diagnostic E65 — the fixed result route dominates its application body

E64 leaves two plausible representations of the remaining cost: repeatedly serializing and moving the returned model
body, or the fixed envelope, routing, compression, tracking and callback work paid for every result regardless of its
payload. E65 keeps the durable result store, both WebSocket legs, ordinary tracking, request correlation and sender
callbacks, but a benchmark-only response mapper replaces the application result body with one shared empty byte array.
The changed response value makes this diagnostic non-qualifying; all event/model assertions and the exact ordinary
result count remain mandatory.

The same-binary adjacent JFR pair measured 176,038/s control and 182,645/s with the minimal body (+3.75%). That single
smoke delta is not a throughput candidate: model transactions also changed from 27 to 24 and result transactions from
71 to 65. The representation counters are decisive. SDK result envelopes fell only from 89,099,414 to 87,266,723
bytes (-2.06%), result direct-LTS compressed bytes fell 2,662,707 to 2,634,507 (-1.06%), and Runtime outbound
`ReadResult` bytes changed from 5,082,716 to 5,086,874 (+0.08%). The ordinary automatic-model result payload itself
was already four bytes and became two bytes. Almost the complete roughly 680-byte pre-compression message is fixed
envelope, routing, identity and metadata representation.

E65 therefore rejects application-body reuse as the next large target and selects attribution/removal of repeated
fixed result representation and copies. The benchmark-only mapper was removed; no production source changed. Control
benchmark/Runtime/client-JFR/Runtime-JFR SHA-256 values are
`e9c0403535d517942ff60666994d7c70e6fbc7d6df7827992fd875e44f84a54f`,
`37b4cdece9ecf203b084669332f41acf9a4a9b533d491b0ae3d1df7ae199a5bb`,
`e386dccb590469d97ee9cbd8ae6a4c62ef0a5d59ea40fc13d14c3b7a64cfa296`, and
`175773d79ad74bcf55e9492afb1241ce8c8cde68c33ce6fec8283ba430a88d7d`. Candidate values are
`e7e09cb3f645789f5600e78d7fa375c77cba1b95acb1893d3110f3a2851afb30`,
`d2be2908dafdbd276cdc192ea8e8864b11f5a7401391b1a3ceb24d020d8ea7a9`,
`cc912a37d68167ba7e75dffcbcca6af3313d713e0eb1012784b70c827325c10a`, and
`425a278c5d7416e52049206226731733b51a233cbdac4aa09a0f27784d10add7`.

### Rejected experiment E66 — a faster ZSTD level is below the E2E gate

E66 first attributed one persisted native result block without changing either repository. Every result was exactly
667 pre-compression bytes: 507 metadata, 72 fixed header, 36 target, 32 message ID, 16 format, two type and two payload
bytes. The metadata had ten entries. Six entries totalling 320 encoded bytes had one value across all 128 messages;
the 89-byte tracker entry had two values. Correlation ID, trace ID and delay plus the entry-count header used the
remaining 98 bytes. This proves that low-cardinality correlation metadata, not the application payload, owns most raw
result representation.

The accepted level-1 ZSTD codec compressed the actual 85,376-byte block to 2,464 bytes at 3,359.9 input MB/s in a
5,000-iteration, 500-iteration-warm-up microdiagnostic. Level -1 reached 4,354.4 MB/s (+29.60%) with a 2,753-byte
result (+11.73%, still only 3.22% of raw). A temporary default-neutral system property allowed the two JVMs to select
level -1 from identical class files. All nine compression tests passed. An adjacent 131,072-command dual-JFR pair then
measured 196,767/s control and 215,089/s candidate (+9.31%), while p95 result latency fell from 421.348 to 314.597 ms.
Its command batches changed from nine to eight, so this remained screening evidence rather than a causal throughput
claim.

The required canonical non-JFR screen used order `A B B A B A A B`, recreated the schema per invocation and passed all
exact result/event/model checks:

| Pair | Level 1 control | Level -1 candidate | Delta |
| --- | ---: | ---: | ---: |
| 1 | 260,933/s | 295,221/s | +13.14% |
| 2 | 319,150/s | 312,611/s | -2.05% |
| 3 | 302,927/s | 292,239/s | -3.53% |
| 4 | 250,133/s | 273,612/s | +9.39% |

Control/candidate geometric means were 281,844/293,094 commands/s: **+3.99%**, with two positive and two negative
pairs. Four sign-conflicting screening pairs do not establish a positive confidence interval and are insufficient for
the eight-pair confirmation gate. E66 therefore rejects ZSTD-level tuning despite the real local codec win; the
decision does not rely on the campaign's former mechanical 5% threshold.
The property was removed and common production source again matches accepted P2 exactly. This also closes metadata
encoding microcaches as the next checkpoint target: result preparation was 5.34% of the E64 client CPU profile, but
actual metadata string encoding inside it was only about 0.8% of total client samples.

The JFR control log/Runtime/client/Runtime hashes are
`fc8887e7cc59c5714119785b32797b44e40d5a47a4cc999a9ff6928b0c7a3652`,
`d2f0802a18b8453bd26a2e459096a8ee6cb4287737b95b7a4a7e5ea6dc071c81`,
`57f36aa75907d970b7c255929a5710cffce0a2fd89705ca2e6901dfcade17c95`, and
`a112489575df7956981864e5631c5d50c8cbe03ebb6af2a703c0092cd2caabe2`; candidate hashes are
`82a51257bf7212f970e1a19ef61dd57c1248427347ccdccb14c8ca4bf432f82f`,
`6f9f7f9fd37bca81d5dc8a2c7583b31ef356e26a3ab9f05986d64f9c516e92b9`,
`86b826bd49900d6657a823551113a527ea501cd0cc619a177bbcbed720842d1a`, and
`91f0d9335df4a29fd9d9e69c77412dd8a68b15598a7d8718f0bc7cbe32ba30f2`.
Canonical benchmark/Runtime log pairs 1–4 are respectively
`86ff7d60267a6d276a3d15b1a9ae56a5e350806257f642fdef2f79e698cb1438`/
`0b177c495609dd1ff6b3c418ebabd5ed4a4cdde74bca5b6304210ffc3faa26b8` followed by
`5b4d1736f73058101e0e5e7222a8d118c69cf4edb821084a426e658aabdcb7e3`/
`948d33461a6130b3b02da06b34c75e493156378ba718827423b8ac4b80633e40`,
`d1e4f118997194db90c92731da366844fb98b9fddadf16f1ccdc1d5d6b7c972a`/
`22432f091eaf037c25ea83b4e95f682dc59ee290f1977ba61f982800d3c8e7d3` followed by
`4983bb8e4258232692615c3eff6ba4015244449d4943cc40fce99b4797781000`/
`f9376f976b96a274e4cb61676e22feb3c5e08b16c1c2249184c3aef6d564015b`,
`05c547a7645d666ce535f6e6ab9dcac8674dc20640398ebe92c4683f3c4bade2`/
`4c553897c5932253ffab21f90cc35b87de3be5160701cf95ce7922114ab37336` followed by
`84ec898c5eacd910dd50b118a328737c73c5f3ca9485def2e4a1c46ca6d98058`/
`66c40401c010d8ef597da86ffaad57af60ae7a929d984f566c8e2a9f74f82a56`, and
`fe819f2ff301c937471967ee62a5c8feb1c092bd7a5a8467cab4dc213f15ed0c`/
`77722f89a69dda6314243a43f99448c74a1a90406a3b575ff16c2c54567be0b2` followed by
`e45eb98e438ef8cb8f9ade8d7622ba7880d4afc52661b32993cc9cc83e2ec5d2`/
`fb746a6c6a9a00a56b5d832ff11b2ece3259967f5f3ac2ff2baafde4f4ae148c`.

### Diagnostic E67 — the result-free client serializes on the AdaptiveCache read monitor

E67 profiled the accepted P2 source on the E61 result-free route: 1,048,576 measured commands after 65,536 warm-up
updates, with ordinary command results deliberately absent. These runs are diagnostic-only and cannot be compared to
the unprofiled 320–330k/s canonical full-result observations. Threaded CPU profiling measured 373,615 commands/s. Of
11,195 client CPU samples, `ModelCommitHandlerRegistry` was 33.96% inclusive, `ModelCommitter` 13.73%,
`SerializedMessage` 12.97% and `CompletableFuture` 12.34%. The cache-specific clusters were
`ModelCacheTracker.currentVersion` at 7.24% and `MemoryAwareCacheSupport.updateAll` at 4.04%. The Runtime profile was
distributed: `JdbcModelCommitStore` was 15.05%, compression 10.93% and `JdbcMessageStore` 9.98% inclusive.

The follow-up async-profiler lock recording measured 107,185 commands/s under its much higher instrumentation
overhead. Its absolute rate is not a regression signal. The ownership evidence is decisive: 580,594 of 597,435 total
client lock-weight units (97.18%) were stacks entering `MemoryAwareCacheSupport.get()`. Cache bulk updates accounted
for only 6,043 units, and no independent `ModelCacheTracker.Entry` monitor cluster remained. The global synchronized
AdaptiveCache/LRU read path therefore serializes the 16 command consumers. This is AdaptiveCache itself, not a
`SoftReferenceCache`; E67 does not propose changing defaults, capacity, memory-pressure behavior or eviction
semantics.

The CPU benchmark/Runtime/client-JFR/Runtime-collapsed/client-collapsed SHA-256 values are
`fc8be8a04e26892faef28b1bb4efd592a288c4ccc2c291c773e5aa9acf6c5d8f`,
`82eb2ce5266b382d3f1f3c78f3ae6c403c61824c2bcbeabe706c2a512591a2bf`,
`ff5de8ad00ae371a6e8f5795f8c36b5f39b6a32c63e381bf1a02872ee1753817`,
`9267da7a350f579a724cd575ce81d60fde7ce8dd50abfc320f718245b2eec2b4`, and
`48497fceb8c3d3df4326152cca2db0043ab2a82c046be092da03245dd0b4ecb2`. The lock
benchmark/Runtime/client-JFR/Runtime-collapsed/client-collapsed values are
`de176df978f051a8ce1846493d21f8f6fdf0a4275e3ddf5e3de4b247789a9cc5`,
`b12c535f9c2aba804a110b541f817aa8dc6b09b2f7a9eef6893df50a94cd9d3d`,
`8aa1cc230e51d8f6d7526942d4adeb7b897fd0cc3f9303542cc247a5d383fde4`,
`be692e4da30092bec8a107fc30828354cb196605cf11405dc514c1204df8b845`, and
`8ce0b8dcd761dadb36fb69390aae5e54aba72d0e6074d0e25a295b0a4aeb035c`.

### Rejected experiment E68 — removing the cache monitor does not raise route capacity

E68 replaced the single access-ordered `LinkedHashMap` monitor with 64 access-ordered segments. Reads touched only
their key's segment, while weight-changing operations remained serialized and exact cache-wide LRU eviction locked all
segments in a fixed order. The implementation retained hard references, exact maximum weights, entry admission,
memory-pressure trimming, global access ordering and eviction reasons. All 73 focused `MemoryAwareCacheSupport` and
`AdaptiveObjectCache` tests passed, including a deterministic cross-segment concurrency test.

The candidate did exactly what the lock hypothesis predicted. Under the same result-free lock profile, total client
lock weight fell from 597,435 to 2,142 (−99.64%); cache-get weight fell from 580,594 to 1,111 (−99.81%). Profiled
throughput consequently changed from 107,185/s to 406,628/s. That fourfold number is profiler observer-effect: the
control emitted and processed vastly more lock events. It cannot be used as an unprofiled route-capacity claim.

Two adjacent unprofiled result-free pairs rejected the candidate before any canonical full-result work:

| Pair | P2 control | Segmented LRU | Delta |
| --- | ---: | ---: | ---: |
| 1 | 361,559/s | 367,338/s | +1.60% |
| 2 | 398,680/s | 379,961/s | −4.70% |

Control/candidate geometric means were 379,666/373,596 commands/s, **−1.60%**. The signs conflict and the aggregate
is negative, so E68 stops without expanding into canonical A/B. The production and test diff was fully reverted;
common and SDK production source again match accepted P2 `e94188b5876`. This corrects the E67 interpretation: the
global cache monitor is real and dominates an instrumented lock recording, but it is not the largest unprofiled route
capacity constraint.

Candidate pair-1 benchmark/Runtime/client-JFR hashes are
`52c96c0030e204f76aff08ddd3ae1e0c68adbaef6555a8611cdc53e21ee5bd44`,
`23a985a3e4adf475358913b9fa980661a1d8eceeba10d32c39295b62af754af2`, and
`5f0e9091c11bf37ae5afb042479c03b80c8e76e17f29edfe7b3159c5fde2b8b8`; control hashes are
`c91b5adc5bb064b85f5b3d1bf3393c2f9005a7ae83c6a3b2e2be60aac8f2e281`,
`1d1cea341e63355de059677cf6248972bf8ec64f0a31b314cc22595ff6dadd58`, and
`a8227c8047cb83fd68cb5e03fe4212a2df59b109c3e21b8f1ed804498ec2ff5b`. Pair-2 control values are
`72525163bef8edb609dce7d7fcf7e04ba7d83c240dd68b490ded43a26351dd77`,
`f746c47f4585db72889e221593be277c499e3e4fe62b4cdb4caaff7a14321165`, and
`b75a5146421420a8205a2f76b57237c0489d36e4203e6fd3e36a860d071ac3bc`; candidate values are
`7b624f404f8d4f6b1cbf6a61e2e558f741b20b6ac3e7625bec0ea572de3c29ee`,
`c0aa97fae3db3931859510d54d73c80e2adc4314275e65f391e660d770d8444b`, and
`c27e7c8f3c1790a3977a046f6a63d3bf739ccc5a353517abaa7f4f3ceabba1fb`. The lock
benchmark/Runtime/client-JFR/Runtime-collapsed/client-collapsed hashes are
`ba5c72117b691c825582fb97fe73d8d87d910f4e1a03797c1590e06c0779bbe4`,
`c44ece9978dcd446222422281570e3b38c64bacd4dca5f9c67ed05ac1ddbeb73`,
`6a85bb90201df981d9b92cd160139248cf257b0813ea7cd9eb781a6f05e20d83`,
`f724809404b9718301d11d82dcd5316c5950ad49d999b32f95f7adae97f274c8`, and
`b68dccddc6ef8f33b1609ba4077e83129dc26177e9409cffd18ec949ad708de1`.

### Diagnostic E69 — allocation recording retained without candidate selection

An accepted-source result-free allocation profile was already running when the candidate-selection protocol above was
corrected. It completed at 399,449 commands/s and is retained as diagnostic evidence, but it will not be interpreted to
select a production target. The route deliberately omits ordinary results, allocation profiling changes execution
costs, and neither condition qualifies it to establish the canonical limiter. Benchmark/Runtime/client-JFR/
Runtime-collapsed/client-collapsed SHA-256 values are
`0f141fc8cd08acd71cede7031e6c413b3c47efcf948ed47d6becd47f40475f14`,
`46e855cf016885177655e7496a00532fde55a855902ddca10bae07eb5292354c`,
`59b054cf7c5ce303535f11b899302d6766b1e01d70e017fc1357b9c42b016985`,
`d8df1d0260927bd20e2d6094b1e0ea399116a369554090fe09e746cd8decc485`, and
`8939bd8074b719d6ca9ddc7484fe3c21c4ee9ee40cf011daf39edf1aa006bac3`.

### Diagnostics E112-E114 — result completion is downstream future work, not lookup or worker type

E112-E113 switched every generic SDK worker pool between eight bounded platform threads and the accepted Java 25
virtual-thread implementation from identical class files. The exact full-result route measured 256,164/s on platform
workers and 268,764/s on virtual workers: **-4.69%**. Latency was respectively
200.777/323.031/419.730/455.931 ms and 187.758/326.568/388.944/466.452 ms. Bounded platform workers therefore do not
relieve the route and the temporary switch was removed.

E114 then split the already measured single ordered request-result completion lane without removing any route stage.
After warm-up, 267 result batches contained exactly 1,048,576 results. Their 2,797.420 ms accumulated active service
time decomposed as follows:

| Fundamental operation inside the ordered completion loop | Active time | Share |
| --- | ---: | ---: |
| callback lookup by request id | 133.837 ms | 4.78% |
| request/trace observer recording | 199.712 ms | 7.14% |
| `ResponseCallback.process`, including synchronous dependent-future work | **2,374.669 ms** | **84.89%** |
| loop and timing remainder | 89.202 ms | 3.19% |

The full route measured 252,289/s with 199.982/351.653/390.013/421.995 ms latency. The bold interval is not merely
the `CompletableFuture.complete` primitive: completing the raw response future synchronously runs gateway response
deserialization, message mapping, callback tracking, the caller's completion action and ensuing bounded-inflight
dispatch. It is therefore a serial route boundary whose active capacity is roughly 441,565 results/s in this run.
Lookup and numeric trace parsing are explicitly too small to select another micro-optimization.

The accepted E59 allocation recording independently attributed 2,980 of 16,830 weighted client allocation samples
(17.71%) to stacks containing `CompletableFuture`. This supports investigating the future graph, but it does not by
itself prove that removing any individual stage raises E2E capacity.

E112 benchmark/Runtime/client-JFR hashes are
`5d36896cbb26e077d3f894ffd13c14b2c90c7d951749e4323d29291804784736`,
`3626f317d815d2cf9f66f142862b1c3d573b908008dadbbe0aafc0f736b3840b`, and
`639d456c46de9aa83b3e4e3c86f1aaa350bb5358efcef3781a7a064537af6f7f`; E113 values are
`d5d15b59149f56d7cd3a37c1ecbfa46d257d90e001f0d27425fbb9f243891202`,
`1856a0e0485600d62cc3c9f6f6544c1dd06e3dc3afc30f53f9dfb8e81cbd1899`, and
`40883fe0e12de57a0227a0f4430a63d1b9d64114da9ad396596a6e88c7ffa99b`. E114 values are
`872992008cddea2f0a6f63c690e843491dec8eb936a852ce79b8c93f627482db`,
`9eb8b41a37f0a5592ec2ee591008a27ecd8bc90bdaa925114e9f69e135b335cc`, and
`246b876ed6adad49f271fd74235e769ca979e36b7523b61f75d2467445b71c58`.

### Rejected experiment E115-E117 — deleting two completion stages does not accelerate the route

The narrowest future-graph candidate replaced two ignored `whenComplete` dependents per request with direct
completion hooks: callback/timeout cleanup and the shared batch-timeout countdown. It preserved the same public
`CompletableFuture`, synchronous downstream completion, timeout cancellation, exceptional completion, external
cancellation, callback removal, result ordering and JFR stages. Fourteen focused timeout/chunk/callback tests passed,
including new cancellation and shared-batch-timeout checks.

E115 screened the candidate at 264,482/s. Because that fell inside the recent machine band, E116-E117 used one binary
with an internal diagnostic switch so only the completion mechanism changed. Legacy dependent stages measured
273,335/s; direct hooks measured 268,184/s, **-1.88%**. Latency moved from
191.947/300.629/347.043/405.746 ms to 189.576/321.741/359.149/399.881 ms. Removing allocations locally did not
increase full-route capacity; the custom future and direct bookkeeping were not cheaper than the JDK completion stack.
The entire production/test/diagnostic diff was reverted. This closes individual cleanup-stage removal, not a future
graph redesign that can eliminate mapping layers or move downstream work off the ordered result loop.

E115 benchmark/Runtime/client-JFR hashes are
`a1119e05422f2f68962aa742b164e828ec9b0d2a0f5e001dcb34d51a833bbaec`,
`d9182fa6e0eb0058890eb144a4ed0bcc345a9d7e228463410d1b5069ea6acd03`, and
`35a21bbbda85180f71fa68b348643412bb16d0a30f54b864570ffa98426201df`; E116 values are
`b0af847ec5dbde8cd8f53325b159b9a107c496eeb790386f8efc7143e6773c62`,
`3d413859eb99f05a33e9ab4badcb07bc48ce881d315d9f80650f6480e12fc3d8`, and
`dd1152f50488f62a8b05ae07bac0d4f5580957598aec774bab0415e906e26c5e`; E117 values are
`814e54beeb0c63f056240e9843da6e5ab1853d775f790b9b697b4633912ad743`,
`15032704b49518637fa62987b72c0c7300d9f079747dd021d7ab279a9362f445`, and
`06706abc3dc21a746ef9440fae61f28d31b0dc4e28c7f76186edd11abac239a8`.

### Diagnostics E118-E121 — exact attribution inside synchronous caller completion

E118 fused serialized-response mapping into the default request handler so it could complete one mapped response
future directly. This removed the raw-response future, `thenCompose` node and per-response completed/failed future while
retaining response deserialization, Throwable propagation, metadata, web wrapping, callback tracking, chunk aggregation,
timeouts and public completion behavior. Forty-five publishing tests passed. The first screen measured 266,754/s.

E119-E120 then compared the legacy and fused paths from identical class files. Legacy measured 275,192/s and fusion
272,888/s: **-0.84%**. Latency was respectively 185.944/304.945/347.431/397.630 ms and
183.505/297.136/364.861/403.264 ms. Three fewer future objects per response did not raise E2E capacity.

E121 retained the exact legacy graph and timed nested fundamental work only while the measured request-stage JFR was
active. Exactly 1,048,576 result completions produced this decomposition:

| Fundamental work nested inside raw future completion | Active time | Share of 2,176.625 ms |
| --- | ---: | ---: |
| response deserialization, metadata/message construction and trace stages | **562.976 ms** | **25.86%** |
| gateway callback removal and command-future trace stage | **274.437 ms** | **12.61%** |
| final `Message.getPayload()` mapping | 21.374 ms | 0.98% |
| future propagation, benchmark completion/semaphore release and request/batch cleanup remainder | **1,317.838 ms** | **60.55%** |

The identity mapping before raw future completion added 33.196 ms outside that total. E121 ran at 282,685/s with
178.765/274.216/319.638/372.533 ms latency; throughput is diagnostic because `nanoTime` observers were active. The
table closes payload extraction and future allocation as large targets. Deserialization is the only independently
parallelizable quarter; most remaining service belongs to completion propagation and the caller's bounded-inflight
feedback rather than an SDK mapper.

E118 benchmark/Runtime/client-JFR hashes are
`04b9eb904384d9b912ee2b729183ece94ae6424d3b4ff6b3dc2a6a8e5af9e74b`,
`7753d6a5b985a9c520ebf5ba66e9c1c80c6bf8e2b3c9b2ee146df9b2eae3cfbf`, and
`387d1e8ad4bfa1bdb7ac9ba55cee3708753989a195558bb1c6c720ad87b0ea22`; E119 values are
`03730270bb6d554e65ea48005e4c44a3ec388546e564a1a1ee798d30c06b3f97`,
`1ffed4347dfb962ddff3269d9906f0181c64acfb28ad92ba01a0cb8e7c9e0bb6`, and
`bbf0ab018abdc913478542b561a5ec4e3dd08c75b86462b5621e59d95bcc9125`; E120 values are
`83082e500edf08b8dd78e3fcc21a295fc830a0f767a18fe75e6609c067b4e2ba`,
`450fa700ebba80b6db0235add0777889a1d80f97233eb39bba0e17a04efae797`, and
`db060b33cdd6e61140319a2cf35c2bba6f05c5e4d379220d06e4267994f418b2`. E121 values are
`61cd81a8bc9aa1233b7fc9b951219181b762017aeffeb2bf679a3d9f935da725`,
`a7832cbe71c199211004bd6edb8155c35718d4ee43dc42f17ca8a4599f4a73f7`, and
`0da46cc3a12b4ede4a15b0e57ac8f308df0dffc5929b91dd1ddc8cef171046d9`.

### Rejected experiment E122-E123 — parallel preparation still loses with ordered completion

E122 parallelized only the 25.86% deserialization/mapping component in bounded batch chunks. It captured and restored
the SDK thread-local context, waited for all preparation, and then completed public futures in original response order.
Chunked responses retained their existing ordered chain. This intentionally avoided E108's independent public
completion lanes and their dispatch-feedback fragmentation. The normal and forced-parallel timeout/chunk suites both
passed all 14 focused tests.

Despite that narrower execution model, the same-binary full route measured 262,152/s candidate versus 282,483/s legacy
control: **-7.20%**. Candidate latency was 192.072/339.321/405.155/446.759 ms; control was
181.924/302.543/410.552/452.502 ms. Array allocation, task scheduling, context activation, the preparation barrier and
added CPU cost more route capacity than parallelizing 563 ms of serial service returned. The production, API, test and
diagnostic diff was fully reverted. Together E108 and E122 close both full parallel completion and
parallel-prepare/ordered-complete on the current result path.

E122 benchmark/Runtime/client-JFR hashes are
`106381eb856ce843a9fb8f6b936e73fb988de62d1bf60ec2c064226eab3a1da8`,
`58e580da9e762badea890354680ccaeabe9c91fbbd000eec64ec17d3feb82678`, and
`92603ef63d6a299a54339c35502341ce7a9d2abe2a3ad402064f3e81cb96d8b7`; E123 values are
`0de5d90d638182dfb54c73be8cec4d98b4aa5608c53928b8a71693b5781582ae`,
`e1d0e00159793063b95e9226391a26f38f1c99f14b30f91deea239e81d3f8b2d`, and
`5a518cd7b779a80e8790648ac468f723696579c4e62f769fb829af8c6d5c7fde`.

### Diagnostics E124-E125 — the packed model/event transaction has a physical cost ledger

E124 enabled the existing exact batch timers on the intact 1,048,576-command route. Across 375 durable packed batches,
the model stream encoded 1,179,183 warm-up-plus-measured memberships in 228.376 ms (**193.7 ns/item**) and copied them
in 976.853 ms (**828.4 ns/item**). The complete durable coordinator used 4,302.780 ms (**3,649.0 ns/item**). Codec plus
stream COPY therefore accounted for only 28% of that service; rewriting the model codec alone could not explain or
remove the remaining 72%.

E125 recorded only the measured interval with Runtime JFR, so every row below covers exactly 1,048,576 commands and
ordinary results. Operations in different stores can overlap; values are accumulated active service and must not be
added into a latency. Inside the single model/event transaction they are sequential and nearly exhaust its active
database work.

| Fundamental measured storage operation | Calls | Output rows | Active time | ns/item | Meaning |
| --- | ---: | ---: | ---: | ---: | --- |
| command log direct block insert | 336 | 8,249 | **1,459.524 ms** | 1,392.2 | Compressed command-log rows prepared on insert workers. |
| command database commit | 342 | n/a | 878.963 ms | 838.2 | Ordered durable command transaction completion. |
| event log direct block insert | 174 | 8,270 | **973.332 ms** | 928.4 | Compressed global-event rows written before the co-located model task. |
| model state lock/read | 176 | 176 | **418.288 ms** | 398.9 | Lock and read the singleton durable model visibility state. |
| model stream block insert | 176 | 1,109 | **832.558 ms** | 794.0 | Copy compact membership/history blocks that reference the global event payloads. |
| model state-head update | 176 | 176 | **281.953 ms** | 268.9 | Advance durable model visibility to the last state index. |
| event/model database commit | 176 | n/a | **363.056 ms** | 346.2 | Atomically make the event rows, stream rows and state head durable. |
| result log direct block insert | 541 | 8,353 | **2,423.432 ms** | 2,319.0 | Compressed ordinary-result rows prepared ahead of ordered commit. |
| result tail staging | 544 | 3,537 | 556.895 ms | 531.1 | Underfilled result tails retained as scalar staging rows. |
| result database commit | 544 | n/a | **1,518.045 ms** | 1,447.7 | Ordered durable ordinary-result transaction completion. |

The model co-located task totals 1,556.893 ms: lock, stream insert and state update explain 98.4% of it. Event insert,
that co-located task and commit together explain the physical model/event boundary. The model stream is already a
membership/reference representation rather than a duplicate event payload, but it remains a second physical write.
The result store is not the current standalone limiter (E64), yet its 544 commits and 8,353 rows are a mandatory later
budget for any route approaching 1M/s.

### Rejected experiments E126-E132 — larger message blocks improve SQL but lose complete-route capacity

E126 raised the generic message-log group from 128 to 1,024 messages. It measured 243,112/s against E127's adjacent
277,727/s control, **-12.46%**. E128 explained the apparently counterintuitive loss: the intended physical operations
did become cheaper, but natural partial waves no longer filled a 1,024-message row.

| Fundamental operation | 128-message E125 | 1,024-message E128 | Change |
| --- | ---: | ---: | ---: |
| event direct rows | 8,270 | **1,061** | -87.2% |
| event direct insert | 973.332 ms | **779.687 ms** | **-19.90%** |
| model stream insert | 832.558 ms | **671.594 ms** | **-19.33%** |
| model state lock | 418.288 ms | **347.099 ms** | **-17.02%** |
| model state update | 281.953 ms | **214.140 ms** | **-24.05%** |
| result direct insert | 2,423.432 ms | **2,002.688 ms** | **-17.36%** |
| event staging rows | **130** | 23,279 | +17,806.9% |
| event staging work | **8.287 ms** | 705.995 ms | +8,419.5% |
| result staging rows | **3,537** | 27,136 | +667.2% |
| result staging work | **556.895 ms** | 1,353.925 ms | +143.12% |

Forcing direct tails recovered 1,024-message throughput only to 258,185/s, still 7.04% below E127. A 256-message
direct-tail compromise first produced a plausible 288,125/s versus 277,727/s (+3.74%), so it was retained for a second
pair rather than rejected by a 5% threshold. The second pair reversed: 281,899/s versus 285,885/s (-1.39%). Across the
two pairs its geometric mean was only +1.14% and inconsistent. No default or production source changed. Physical row
reduction is real, but generic row-size tuning cannot be selected as an E2E checkpoint on this workload.

### Rejected experiments E133-E136 — result fusion needs no-wait grouping, not timers or unbounded submission

E133 and E134 lengthened the result backlog's idle collection window from the accepted 1 ms to 4 ms and 2 ms. The 4-ms
run reduced SDK result publication batches from 648 to 361 and mean batch size rose from 1,618 to 2,905. Summed result
preparation and append lifetime fell, proving that larger result transactions contain real service savings. The added
residence still reduced complete E2E from E131's 285,885/s to 270,018/s (-5.55%); 2 ms reached only 272,987/s (-4.51%).
Timer-based result grouping is therefore closed.

E135 then let the ordered result worker submit later append calls without waiting for the previous durable future.
Every response retained its actual append future, and the handler batch still awaited every response. Removing the
natural ack collector fragmented publication from 648 to **1,686 batches**, cut mean batch size from 1,618 to 622 and
raised active result preparation from 684.905 to 1,171.015 ms. Against E136's identical-binary ordered control it fell
from 254,038/s to 244,497/s (**-3.76%**). The diagnostic switch was fully reverted.

The positive premise and rejected mechanisms are now separate: fusing already ordered logical result appends can save
physical rows and commits, but neither waiting longer nor unbounded SDK submission performs that fusion. Any future
candidate must group at a Runtime ownership boundary without fragmenting SDK waves, reordering responses, weakening
per-append failure completion or allowing an unbounded downstream queue.

### Rejected experiments E137-E141 — combining the state lock and head update is locally real but too small

E137 replaced the packed update path's `SELECT ... FOR UPDATE` plus later `UPDATE` with one conditional
`UPDATE ... RETURNING`. The statement acquired the same singleton-row lock, returned the same erasure and graph flags,
advanced the state inside the same event/model transaction and rolled back with every later write. Seven focused
PostgreSQL tests passed, including packed idempotency, erasure, clock/index jumps, concurrent submit order and a second
Runtime.

The first complete-route pair was negative: 238,056/s candidate versus 278,851/s control (-14.63%). E139-E140 then
measured the physical mechanism from identical class files. The combined statement used 398.860 ms
(**380.4 ns/item**) versus 363.860 ms lock plus 304.775 ms update (**637.7 ns/item**) in control, a real 40.4% state
service reduction. Candidate stream COPY simultaneously used 1,016.742 ms across 207 batches versus 770.612 ms across
181 control batches, so total co-located work changed only 1,439.247 to 1,415.602 ms (-1.64%). E141's bracket candidate
reached 275,028/s, still 1.37% below the same 278,851/s control; the two candidate observations' geometric mean was
8.24% lower. The source was fully reverted. One saved state round trip cannot pay for the route's batch feedback.

### Rejected experiments E142-E145 — denser model-stream blocks do not reproduce route capacity

E142-E145 changed only the existing `fluxzero.modelStreamMembershipsPerBlock` setting from 1,024 to 4,096. The stored
format, membership bytes, transaction, visibility, event/result route and bounded block-cache budget remained intact.
The final candidate database contained exactly 1,179,648 seed-plus-warm-up-plus-measured memberships in 550 blocks,
with an observed maximum of 4,096 entries per block; the intended physical densification therefore happened.

The first full pair measured 272,232/s candidate versus 264,921/s control (+2.76%), so it was retained despite being
below 5%. The reverse-order second pair measured 257,338/s versus 259,550/s (-0.85%). Candidate/control geometric means
were 264,680/262,222 commands/s, only **+0.94%**, with conflicting signs and possible fourfold single-model read
amplification. No source or default changed. Block-size tuning is closed; a subsequent candidate must eliminate a
physical write boundary rather than merely pack its rows more densely.

### Rejected experiments E146-E150 — one faster SQL statement fragments the full route

E146-E150 tested the strongest remaining query-fusion hypothesis without changing the physical model history. On the
packed conflict-free path only, one data-modifying CTE inserted the exact same model-stream blocks and advanced the
same singleton state row in one statement. Initial creation, general/conflicting updates, deletion, erasure and graph
updates stayed on their existing paths. Seven focused PostgreSQL model-store tests passed, including direct/history
reads, packed ordering and deletion behavior.

The local mechanism worked: E148's fused statement processed 1,048,576 measured memberships in 811.667 ms
(774.1 ns/item). E149's separate stream insert, state lock and state update used 848.263 + 337.559 + 253.216 =
1,439.038 ms (1,372.4 ns/item). That is a **43.6% local service reduction**. The intact full route nevertheless lost:

| Exact full-result comparison | P3 control | Fused candidate | Change |
| --- | ---: | ---: | ---: |
| E147/E146, same machine window | **295,763/s** | 248,363/s | **-16.03%** |
| E149/E148, dual-JFR differential | **244,218/s** | 240,624/s | **-1.47%** |
| E147/E150, candidate bracket | **295,763/s** | 272,256/s | **-7.95%** |

The route-wide counters explain the contradiction. The candidate produced 243 event/model transactions rather than
188. Its global-event insert used 1,051.183 ms versus 990.437 ms, co-located model work fell from 1,459.333 to
828.246 ms, but event/model commit service doubled from 335.801 to **670.852 ms**. A locally faster completion releases
and regroups the closed command/result pipeline differently; smaller natural waves erase the query saving through
more transactions, commits and surrounding work. This is direct evidence that the earlier
`DefaultTrackingStrategy.onUpdate -> resolved MessageBatch` residence must be studied as feedback and batching, not
as a slow scan in isolation. The entire Runtime candidate was reverted and accepted P3 source/artifacts were rebuilt.

### Diagnostic E151-E152 — PostgreSQL buffer sizing is not the missing capacity

The container used PostgreSQL defaults `shared_buffers=128MB` and `wal_buffers=4MB`, with `fsync=on` and
`synchronous_commit=on`. E151 changed only the first two values to 1GB and 64MB. It reached 254,948/s; after restoring
the defaults and restarting PostgreSQL, adjacent E152 reached 254,161/s: **+0.31%**, operationally neutral. Durability
was never disabled, and the original settings are restored. Buffer shortage is excluded as the cause of the observed
transaction feedback; it cannot select a production change.

### Diagnostics E153-E156 — one physical event/model COPY has reproducible capacity

The existing physical storage probe was extended without changing its accepted-P3 volumes: every measured invocation
wrote 1,048,576 items in 128 transactions, 8,192 event rows of 2,178 bytes and 1,024 compact model rows of 6,624 bytes.
Control matched the production mechanisms: one multi-row prepared insert for event rows followed by binary COPY into
the model-stream table. Candidate placed those exact model bytes in nullable sidecar columns on every eighth event row,
using the same event insert and no model COPY. Durable markers, ordered visibility commits and exact row/head
verification were identical. The reverse-order pairs produced:

| Physical ordered capacity | Separate COPYs geometric mean | Event-row sidecar geometric mean | Change |
| --- | ---: | ---: | ---: |
| one lane, closest to accepted P3 | 1,056,451/s | **1,231,964/s** | **+16.61%** |
| two lanes | 1,842,828/s | **2,123,937/s** | **+15.25%** |
| four lanes | 3,180,083/s | **3,623,411/s** | **+13.94%** |
| eight lanes, PostgreSQL near saturation | 4,245,853/s | 4,500,526/s | +6.00% |

This validates the physical premise that E146 did not test: a model sidecar can remove a complete COPY/table boundary,
not merely fuse SQL around an unchanged stream write. It still excludes SDK evaluation, reads, hard deletion, transport
and ordinary results, so it cannot accept production code. The full-route prototype must keep old standalone stream
rows readable, prevent event retention from dropping live model history, make hard-delete remove sidecar membership
bytes while retaining globally published event payloads, and preserve the derived locator's recovery contract. Raw
values are in
[`model-e2e-e153-e156-event-sidecar-screening.csv`](performance-runs/model-e2e-e153-e156-event-sidecar-screening.csv);
the benchmark-only Runtime checkpoint is `edb40774`.

### Diagnostics E157-E164 — event/model sidecars survive the intact result route

The gated full-route prototype attaches the already encoded compact initial-stream block to nullable columns on the
same event-log row. Event indices, the singleton model-state lock, state-head update, transaction boundary, global event
bytes, ordinary durable results and all measured exact counters remain present. E157 and E158 failed before seed work:
the first exposed that dynamically altered parent columns were absent from a newly attached child partition; the second
exposed use of a timestamp range for a table partitioned by 64-bit message index. Both runs are retained as failed
diagnostics, and neither produced a throughput value.

After correcting those schema-contract errors, the two same-binary pairs were:

| Pair | Separate event + model table | Event-row model sidecar | Change | Candidate batches |
| --- | ---: | ---: | ---: | ---: |
| E160 / E159 | 246,125/s | **266,991/s** | **+8.48%** | 171 |
| E162 / E161 | 251,670/s | **290,495/s** | **+15.43%** | 179 |
| geometric mean | 248,882/s | **278,495/s** | **+11.90%** | — |

Each successful run completed 1,048,576 commands with exact stored model memberships, global events and ordinary
results. These are smoke comparisons rather than canonical acceptance because the candidate intentionally had not yet
implemented general historical reads, locator rebuild, hard deletion or future partition rollover.

The E163/E164 dual-JFR differential stayed positive at 250,702 versus 241,674/s (**+3.74%**) and locates the mechanism:

| Runtime service across 1,048,576 commands | Control E164 | Sidecar E163 | Change |
| --- | ---: | ---: | ---: |
| co-located model task | 1,376.139 ms | **742.084 ms** | **-46.08%** |
| event-store storage component | 1,763.395 ms | **1,339.037 ms** | **-24.07%** |
| complete packed model-store storage | 3,432.942 ms | **3,367.418 ms** | -1.91% |
| model transactions under dual JFR | 177 | **305** | +72.32% |
| event-row insert service | 1,022.149 ms | 1,249.804 ms | +22.27% |
| event commit service | 369.278 ms | 586.622 ms | +58.85% |

The sidecar removes the intended model-stream COPY/table work, but the faster completion also feeds 72% more, smaller
transactions under profiler pressure. That raises event INSERT and commit service and explains why the profiled route
captures only part of the unprofiled gain. The causal model is now stronger: the physical boundary is a real limiter,
and natural transaction formation is the coupled next constraint. No production checkpoint is allowed until the new
representation preserves every read/lifecycle contract and then wins matched canonical runs.

### Diagnostics E165-E178 — complete sidecar lifecycle support consumes the local gain

The prototype was completed before its final throughput decision. The event table schema owns the nullable columns so
new partitions inherit them. Reads union old standalone model-stream rows with packed event sidecars. The unlogged
membership locator rebuilds from either representation. Hard deletion rewrites or clears only the model sidecar while
retaining the globally published event, including deletion of the first membership in a packed block. Enabling event
retention first migrates all sidecars back to ordinary model-stream rows in the same transaction and then disables new
sidecar writes. The sidecar mode passed **101/101** `JdbcModelCommitStoreTest` tests; the classic mode passed its 100
applicable tests with only the sidecar-specific retention test skipped.

Three locator mechanisms were then measured on the unchanged exact result route:

| Runs | Locator mechanism | Control geometric mean | Candidate geometric mean | Result |
| --- | --- | ---: | ---: | ---: |
| E165/E166 | btree over sidecar first-state rows | 273,051/s | 236,350/s | **-13.44%**; rejected immediately |
| E167-E170 | no sidecar index; recovery scan only | 251,304/s | 256,454/s | +2.05%, but pair signs conflict |
| E171-E174 | event-row pointer repeated in every locator membership | 256,022/s | 251,214/s | **-1.88%** |
| E175-E178 | zero-byte tagged union in existing locator fields | 254,313/s | 253,045/s | **-0.50%** |

The tagged representation encodes classic rows as non-negative stream segments and event-sidecar rows as a negative
segment tag, reusing `block_state_index` for the event-row index. It adds no locator column, index or bytes per model
membership and is therefore the cheapest complete design tested. E175/E176 was -4.47%; the reversed E177/E178 pair was
+3.63%. Their geometric result is still negative. All runs retained 1,048,576 exact commands, model memberships,
events, durable results and tracked completions. Absolute values are marked smoke because `mediaanalysisd` and
`mds_stores` were active, but each decision uses adjacent same-binary controls in both orders.

This closes the family rather than invalidating E153-E164: removing the physical model COPY really does improve the
isolated store and incomplete route, but a deployable representation needs read/lifecycle machinery and alters natural
transaction formation enough that no full-route capacity remains. The production candidate is fully reverted; no
performance checkpoint is created. Reopening requires a new mechanism that both removes the boundary and preserves
or improves transaction grouping on the complete route.

## Immediate sequence

1. Keep P1 and P2 as accepted comparison points. Generic collection delays, explicit `AFTER_BATCH`, concurrent model
   transactions and an isolated repeat of fused SQL or direct tails remain closed.
2. Use SDK `e94188b5876` plus Runtime `ed9cb3419e0b` as the immutable E58 control pair; later documentation-only
   commits do not change that production source identity.
3. E57 and E58 close physical block sizing and active transaction extension as throughput checkpoints. E58 reduced
   packed transaction service 18% and improved tail latency, but its full non-JFR throughput screen was neutral.
4. E59 completed the full-route rerank. The default command consumers are completion-barrier bound, result durability
   is the next serial tail, and the whole machine is simultaneously near CPU saturation. Compression plus transport and
   durable batch encoding are the largest independent cross-process CPU cluster; cache contention is real but secondary
   in normal recordings.
5. E60 closed negotiated LZ4: it expanded both principal wire streams 81-85%, ran 2.48% slower and worsened most latency
   percentiles. ZSTD remains the default; do not reopen a codec substitution without a new ratio/CPU mechanism.
6. E61 established the result-pipeline upper bound at 405,700/s, +49.04% over its adjacent complete-route control. It is
   diagnostic only because results were deliberately absent, but it proves the structural budget and records the E62
   negotiated/fallback contract in the linked report.
7. E62 closes pre-commit result fusion. Do not put result preparation or result-log completion back inside the model
   acknowledgement loop, and do not use per-message ForkJoin tasks, one shared ordered preparation worker or unbounded
   scheduling. Reopen only with a recoverable Runtime ownership boundary that leaves model batching independent.
8. E63 closes result-only ordered front grouping: it reduced result transactions 15.94% but lost 5.75% E2E. Local
   transaction reduction without route capacity is not a candidate-selection rule.
9. E64 closes result durability as the next limiter: replacing all result JDBC work with the existing ephemeral store
   changed E2E by -1.97%. E65 subsequently isolated application-body representation from the fixed result route.
10. E65 closes application-result-body reuse as the large target: removing half the already tiny payload changed
    pre-compression envelopes by only -2.06% and outbound bytes by +0.08%. Attribute the fixed representation next.
11. E66 attributes 76% of raw result envelopes to metadata, but rejects the fastest credible compression-only
    response at +3.99% canonical E2E. Stop result codec microtuning and profile the E61 result-free upper-bound route:
    even deleting all ordinary-result work leaves only 405,700/s, so model/cache/commit work now dominates the 1M gap.
12. E67 proves that the result-free SDK read path is serialized by the global AdaptiveCache/LRU monitor: 97.2% of
    client lock weight enters `MemoryAwareCacheSupport.get()`. E68 then proves this is profiler-amplified: removing
    99.8% of cache-get lock weight produced −1.60% unprofiled E2E. Do not reopen cache synchronization without a new
    unprofiled capacity mechanism.
13. E69 is retained as explanatory allocation evidence only. Before another candidate, instrument repeated canonical
    full-result runs into the route-wide stage timing and critical-path model defined above.
14. E70-E79 establish the full-route anatomy, accept P3 and reclassify command tracking residence as downstream
    backpressure rather than scan or notification service. P3 remains the immutable accepted base.
15. E80-E83 close overlapping complete model transactions; E84-E85 close a separate invisible durable prewrite;
    E87-E91 close preparing model rows in a second open transaction of the same ordered store. All three forms increase
    PostgreSQL contention or transaction residence, even when ordering, atomicity and batching are preserved. E86 also
    proves publisher serialization chunks do not control durable model transaction grouping.
16. E92 reranks the exact accepted P3 route and finds no dominant standalone Java hotspot. The inclusive CPU clusters
    overlap broadly across transport, tracking, storage, compression and the benchmark, while both JVMs spend roughly
    one fifth to one quarter of samples waiting. Retain the traced serial model/event durability boundary as the
    primary target; ignore the trace-only metadata observer hotspot.
17. E93-E99 prove the missing physical premise for true parallel visibility. Two COPYs plus WAL scale strongly without
    the singleton head, and bounded prepared transactions retain invisible atomic data until ordered publication.
    Four lanes saturate the 8,192-item curve at 1.774M/s; prepared depth must remain bounded and crash leftovers must
    be resolved before this can become production behavior.
18. E100-E101 reject naive in-batch four-transaction preparation: the exact full-result smoke route falls from
    196,681/s to 160,342/s (−18.48%) and all latency percentiles worsen. Attribute the intact-route loss before changing
    the visibility pipeline or physical grouping; the storageprobe alone does not select production architecture.
19. E102-E106 attribute and close the family: parallel overlap exists, but same-table insert service rises 52–61%, an
    extra marker is costly, and removing it plus reducing to two lanes still loses 3.72% matched E2E. Production source
    is fully reverted; do not retry lane depth or in-batch multi-transaction variants.
20. E107 identifies another hard full-route boundary: the single ordered caller-result completion lane performs
    1,048,576 completions in 2.982 active seconds, only 351,673/s. E108-E109 prove that eight ordered per-request lanes
    are not the answer: they lose 8.22% and fragment result publication. E110-E111 also reject caching the largest
    trace-only metadata leaf at -4.25% against bracketed controls. Both candidates are fully reverted; retain the
    serial-lane capacity fact, but do not equate parallel CPU demand with free E2E capacity.
21. E112-E114 reject bounded platform workers and attribute 84.89% of the ordered completion lane to
    `ResponseCallback.process` plus its synchronous dependent-future graph. E115-E117 then reject replacing only its
    two ignored cleanup dependents at -1.88%. The next candidate must change the downstream mapping/completion
    structure, not lookup, trace parsing, worker type or isolated cleanup nodes.
22. E118-E121 show that future-object fusion is neutral-negative and split the actual synchronous cascade. E122-E123
    then reject parallel deserialization with ordered public completion at -7.20%. Stop optimizing the result
    completion graph until a route-wide architecture changes its caller feedback or CPU budget; return to the larger
    model/event durability and downstream tracking constraints.
23. E124-E125 split the physical model/event transaction into measured operations. Packed stream encoding plus COPY is
    only 28% of coordinator service; the singleton state lock, stream insert and state-head update explain 98.4% of the
    co-located task. Do not rewrite the model codec before eliminating or combining the larger database round trips.
24. E126-E132 reject generic message-group and direct-tail sizing. Larger blocks save 17-24% inside the intended SQL
    operations but create underfilled staging work; the only positive 256-message pair did not reproduce and its
    two-pair geometric mean was just +1.14%. Keep the accepted 128-message format/default.
25. E133-E136 prove that larger result transactions contain local service savings, while rejecting both longer
    collection timers and unbounded SDK append submission. The latter fragments natural waves and loses 3.76% against
    an identical-binary control. Reopen result fusion only at an ownership boundary that preserves boundedness,
    ordering, per-append failure completion and natural SDK batch formation.
26. E137-E141 reject combining the packed state lock and head update. The SQL mechanism cuts state service about 40%,
    but two complete-route candidates bracketed around one control are negative and production source is reverted.
27. E142-E145 reject denser model-stream blocks as a checkpoint. The first +2.76% pair was correctly retained, but the
    second reversed and the two-pair geometric mean was only +0.94%; keep 1,024 and avoid extra read amplification.
28. E146-E150 reject single-statement stream/state fusion despite **43.6%** lower local database service: the candidate
    forms 243 instead of 188 event/model transactions and doubles commit service. Never infer route capacity from a
    locally faster database statement without measuring the closed-loop batch feedback.
29. E151-E152 exclude PostgreSQL shared/WAL buffer sizing at +0.31%; defaults and full durability are restored.
30. Retain E73/E79's completed split of command tracking residence: 98.4% resolves on a later client request, scans
    average 0.682 ms and notification work averages 0.216 ms. Use C06 as a downstream backpressure gauge, not as an
    independent 40-ms tracking service target.
31. E153-E156 validate one structural premise with fixed physical output: event-row model sidecars improve one-lane
    ordered capacity **16.61%** across two pairs by removing a complete COPY/table boundary. Keep this diagnostic-only
    until old rows, reads, locator recovery, retention and hard-delete pass on the full exact route.
32. E157-E164 validate that incomplete sidecars improve the intact exact command/result route by **11.90% geometric
    mean** and cut co-located model work 46.08%, but E165-E178 close the production family: all lifecycle contracts pass,
    yet the cheapest zero-byte locator is -0.50% across balanced pairs. The physical saving is real; lifecycle support
    and closed-loop transaction fragmentation consume it. Production source is reverted.
33. Causally validate the largest canonical constraint by narrowly relieving or accelerating it while retaining the
    complete full-result route. Use a stage-removal ablation only when intact-route evidence cannot distinguish two
    mechanisms, and never as acceptance evidence.
34. Confirm each positive candidate through matched non-JFR runs. Checkpoint every statistically convincing, correct
    and practically net-positive result against P2—including a safe reproducible 3–4% gain—then rerank the full path
    and repeat until five consecutive qualifying runs exceed 1M/s.

Every new experiment appends to this ledger before the next implementation begins. Superseded candidates remain in the
history with their rejection reason; measurements are never silently relabeled or discarded.
