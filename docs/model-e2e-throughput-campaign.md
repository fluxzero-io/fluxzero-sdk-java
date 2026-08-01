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

## Immediate sequence

1. Complete the adversarial review and full relevant reactor verification for P1, then checkpoint it in SDK and Runtime.
2. Treat the packed model store's 0.391M/s as the next provisional limiter; split its queue, PostgreSQL wait,
   serialization and cache-publication work before editing it.
3. Remove that proven limiter architecturally, confirm it through the same matched protocol, checkpoint it and repeat.
4. Continue until five consecutive qualifying full-E2E runs exceed 1M/s.

Every new experiment appends to this ledger before the next implementation begins. Superseded candidates remain in the
history with their rejection reason; measurements are never silently relabeled or discarded.
