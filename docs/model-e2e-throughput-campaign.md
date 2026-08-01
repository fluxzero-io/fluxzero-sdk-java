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
| Database | PostgreSQL 18.3, Docker container `fluxzero-codex-s1-postgres`, port 55966 |
| Benchmark driver SHA-256 | `b6aacd128b916a37547dc28d21c2c5562648cf7bf7dcc7e25fd53c17f925da18` |

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

### Uncommitted common/SDK candidate C1

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

C1 remains uncommitted until R1 is resolved and C1 then passes its own matched comparison against the accepted runtime
side. Exact full builds are also required before a checkpoint.

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

## Immediate sequence

1. Revert R1 while preserving its allocation lesson and retain A0 as the accepted runtime side.
2. Compare C1 against A0 using the same protocol; commit it only if it improves full E2E.
3. Add the batch JFR events, sampled causality trace and per-run PostgreSQL delta capture without changing message
   semantics.
4. Establish the primary capacity limiter using JFR plus separate async-profiler CPU/wall/alloc/lock recordings.
5. Remove that limiter architecturally, confirm it through matched A/B, checkpoint it and repeat.

Every new experiment appends to this ledger before the next implementation begins. Superseded candidates remain in the
history with their rejection reason; measurements are never silently relabeled or discarded.
