# Model command E2E throughput campaign

This document is the source of truth for the cross-repository performance campaign that accompanies dynamic model
boundaries. The detailed history before this campaign remains in
[`dynamic-model-boundaries-phase-21d-performance.md`](dynamic-model-boundaries-phase-21d-performance.md). This ledger
records the accepted comparison point, every candidate, the evidence required to accept it and the reason rejected
experiments were rejected.

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

## Acceptance protocol

Functional and throughput evidence are separate gates:

1. A focused test must protect the changed contract and the relevant reactor build must pass.
2. Screening uses at least four balanced alternating runs per side (`A B B A` and its inverse), without profilers.
3. A candidate that appears faster is confirmed with at least eight runs per side. Order remains balanced and is logged.
4. The comparison uses paired log-throughput differences. Acceptance requires at least 5% improvement and a 95%
   bootstrap confidence interval wholly above zero. Every run must pass all exact correctness checks.
5. p95/p99 latency, allocation, retained memory, queue growth, database work and batch shape are checked for a displaced
   bottleneck or resource regression. A local hotspot reduction alone is not an E2E throughput win.
6. JFR and async-profiler runs are compared only with equivalently profiled controls. Their absolute throughput is not
   mixed with non-profiled runs.
7. Only a confirmed improvement advances the accepted baseline and earns a performance checkpoint commit. Neutral or
   slower candidates are reverted or left uncommitted, and their lesson is recorded below.

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
Its +0.71% geometric-mean result was far below the 5% threshold and the paired 95% interval included a regression.
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

The diagnostic pass must establish service demand, waiting and backpressure for each batch stage rather than infer the
limiter from top allocation stacks alone.

1. Add cheap batch-level JFR events for command received/stored/delivered, handler completed, model commit queued/stored,
   event published, result queued/stored/delivered and callback completed.
2. Record batch count and bytes, queue depth, active workers, wait duration, compression input/output and JDBC
   round-trips/transaction duration. Guard field collection with `Event.shouldCommit()`.
3. Capture a deterministic 1-in-4096 request trace across those stages. Do not emit an event per command for every
   request.
4. Run separate async-profiler CPU, wall-clock, allocation and lock profiles for the measured phase. Never conflate their
   overhead or conclusions.
5. Reset and capture PostgreSQL 18 `pg_stat_statements`, `pg_stat_io`, per-backend I/O and WAL/timing deltas for each
   profiled run.
6. Correlate throughput loss with stage capacity, worker saturation, queue occupancy, park/lock time, database service
   time and allocation/GC. The proven highest-capacity constraint becomes the next architectural target.

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

## Immediate sequence

1. Keep P1 as the accepted comparison point; locator and global-direct-tail experiments are closed unless a new profile
   changes their ranking.
2. Trace SDK command/model batch identity through model-commit enqueue and runtime storage, then preserve that boundary
   while overlapping preparation; E32 proves that faster writes alone shrink batches and cancel their own gain.
3. Remove fixed transaction work per model batch together with the batch-boundary fix, confirm through matched runs,
   checkpoint it and rerank the full path.
4. Repeat until five consecutive qualifying full-E2E runs exceed 1M/s.

Every new experiment appends to this ledger before the next implementation begins. Superseded candidates remain in the
history with their rejection reason; measurements are never silently relabeled or discarded.
