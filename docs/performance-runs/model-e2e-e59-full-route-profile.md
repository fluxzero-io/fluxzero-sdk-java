# E59 full-route CPU, wall, allocation and lock profile

E59 reranked the clean accepted P2 route after E58 had removed 18% of packed transaction service without improving
matched non-JFR throughput. There was no production-code candidate. The source identities were:

- SDK `16ae2eb071a7e70b3a7cd2e8b5367e8ddaaabf21`, whose changes after `e94188b5876` are documentation only;
- Runtime `ed9cb3419e0b61e49869886f81f742f1c8bf6a77`;
- empty SDK and Runtime dirty-diff hashes;
- PostgreSQL 18.3 in `fluxzero-codex-s1-postgres` on port 64217;
- Java 25, 14 physical/logical cores and an 8-GiB heap in each JVM.

Every run recreated the benchmark schema, used defaults `2026.07.27` and verified exactly 1,048,576 ordinary results,
model updates and globally published events. Profiling started only after the canonical 65,536-update warm-up and
stopped before final validation through the benchmark profiler barrier. Client and Runtime were recorded separately.

## Profile runs

| Profile | Throughput | p50 / p95 / p99 / max | Dispatch batches |
| --- | ---: | ---: | ---: |
| Dual JFR | 274,825/s | 185.685 / 286.009 / 381.625 / 422.549 ms | 136 |
| Threaded CPU, 1-ms interval | 261,101/s | 202.740 / 286.128 / 329.266 / 358.211 ms | 138 |
| Threaded wall, 2-ms interval | 285,794/s | 184.985 / 282.023 / 327.743 / 375.338 ms | 152 |
| Threaded allocation, 1-MiB interval | 292,068/s | 177.530 / 261.421 / 302.973 / 406.777 ms | 123 |
| Threaded lock, 0.1-ms threshold | 118,905/s | 482.521 / 589.564 / 629.403 / 708.898 ms | 126 |

The lock rate is an observer effect, not a comparable throughput result. A first wall recording without `--threads`
was also discarded for attribution: idle pool threads dominated its aggregate. The repeated threaded wall run is the
accepted wall diagnostic.

## Critical-path wall and batch evidence

The dual-JFR run's command tracker accumulated 55.078 s across 666 concurrent handler batches.
`sdk.model-handler/commit-after-handler` occupied 48.975 s of that sum, or 88.9%. In the independent threaded wall run,
59.2% of all samples on the 16 command-consumer threads were inside
`DeserializingMessage.completeBatch -> AsyncCompletionScope.await -> CompletableFuture.join`. This is the real default
`ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH` barrier, not idle tracker polling.

The Runtime boundaries for the measured 1,048,576 items were:

| Boundary | Operations | Aggregate duration | Aggregate storage-labelled time |
| --- | ---: | ---: | ---: |
| Result append | 447 | 6.423 s | 6.387 s |
| Packed model commit | 144 | 3.160 s | 3.160 s |
| Command append | 322 | 3.155 s | 3.122 s |
| Co-located event store | 144 | 2.846 s | 1.850 s |
| Result JDBC store | 447 | 2.332 s | 1.578 s |

Result direct-LTS insertion used 444 calls and 8,266 compact rows; its aggregate phase was 1.867 s. Result commits
added 0.972 s and result staging 0.594 s. Model/event durability and result durability are therefore consecutive
capacity constraints. E58 proves that reducing only the former can improve tail latency while leaving full E2E
throughput neutral.

During the dual-JFR measurement, whole-machine CPU averaged 84.71%. The SDK JVM averaged 32.53% user plus 5.42%
system CPU (about 5.3 of 14 cores); Runtime averaged 13.33% plus 4.11% (about 2.4 cores). The route is not merely parked
on PostgreSQL: substantial work continues on sender, websocket, result, compression and cache workers while command
consumers await durability.

## CPU and allocation demand

The threaded async-profiler percentages below are inclusive fractions of all samples in one process. Related rows can
overlap and are not additive, but their rank is stable enough to reject a single small-Java-method explanation.

| SDK inclusive cluster | Samples |
| --- | ---: |
| Full `DefaultTracking.doHandleBatch` route | 17.30% |
| Model handler registry execution | 11.37% |
| Result callback processing | 5.35% |
| `SerializedMessage.encode` | 5.03% |
| Model commit preparation | 3.83% |
| ZSTD compression | 3.84% |
| Command request preparation / send | 3.29% / 3.25% |
| Adaptive-cache get / batch update | 2.73% / 2.38% |
| Metadata binary encoding | 2.33% |
| Native tracking wire codec | 2.26% |

| Runtime inclusive cluster | Samples |
| --- | ---: |
| ZSTD compression | 12.28% |
| Websocket result output | 10.55% |
| Message-store LTS compression | 9.23% |
| Message-store job serialization | 7.16% |
| ZSTD decompression | 4.39% |
| Tracking wire codec | 2.85% |
| Model commit-all | 2.78% |
| Jackson/CBOR | 2.66% |

Fresh-process compilation is material: client C2 alone was 5.21% and Runtime's two C2 threads were 10.89% of their
respective samples. The fixed benchmark intentionally retains the agreed 65,536 warm-up; this cost is recorded rather
than hidden by silently extending warm-up.

At a 1-MiB allocation interval, the client recorded 16,830 weighted samples and Runtime 8,715, approximately 16.8 KiB
and 8.7 KiB per measured command. Byte arrays were 33.7% of client and 70.6% of Runtime samples;
`SerializedMessage` objects were 8.36% and 6.95%. This agrees with the CPU evidence that transport and durable batch
encoding are route-wide costs, not a leaf-only hotspot.

GC did not own the measured wall boundary: client paused 107 ms over five pauses and Runtime 104 ms over three pauses.
Normal JFR recorded 67 SDK contentions at `MemoryAwareCacheSupport.get`, averaging 14.2 ms and peaking at 21.7 ms
(roughly 0.95 s aggregate). The invasive lock profile attributed 98.1% of its weighted client events to this monitor and
collapsed throughput to 118,905/s. The cache lock is a real secondary scalability risk, but that observer amplification
does not outrank the normal-run commit/result and compression evidence.

## Decision

E59 is a diagnostic checkpoint with no production-code change. It supersedes the post-E58 leaf-sample guess with a
full-route conclusion:

1. The default command consumers spend most sampled wall time behind the true model completion barrier.
2. E58 already disproves isolated packed-transaction service reduction as the current E2E answer; result storage is the
   next serial durable tail.
3. At the same time the machine is near CPU saturation and compression plus batch encoding form the largest independent
   cross-process CPU cluster. A small model-store or cache micro-optimization cannot plausibly yield the required 3x.

E60 must therefore perform a same-source compression-algorithm diagnostic before changing code. It will retain every
command/result/durability check and compare wire bytes, CPU, storage work and E2E. A positive result justifies a
compatibility-safe negotiated/adaptive compression design; a neutral result closes compression and sends the campaign
back to a structural combined commit/result pipeline, not to another isolated transaction tweak.

## Artifact identities

The measured-phase launcher after adding thread attribution has SHA-256
`8356aa936640737647936bdacf0372d4e381313180110c36fab19126ab3489b1`. The initial equivalent dual-JFR run used the same
barrier and canonical settings before that output-only profiler flag was added.

| Artifact | SHA-256 |
| --- | --- |
| Dual-JFR benchmark log | `628227dc4cd3b2671197dec25654b571a24ee4899c4b3d8613995a4445d16417` |
| Dual-JFR Runtime log | `6904a722f17b01d83f7504b424c8ecd1b0c9386def19a1c07ec9872c0fd287ae` |
| Dual-JFR client / Runtime | `355d70ff4ecd249486247d4fe3fc1364a6806e13e88e7bda59268c3bd80c611c` / `3ab3bce598411be40d046e483940ddd5907a1334a4ca2879d119c3d92766eb6e` |
| Threaded CPU client / Runtime | `79844fc17ce4aaa39e50d947fde15848e8190ff36b599a36dea8a3052024e0d3` / `060cf555929e79ac63bb5c54bcfafc90a03b862e46dbbc41470ff1c376a0cea6` |
| Threaded wall client / Runtime | `9d2e8eecae3c281d2bc1a6f13bf91e08feac52b99c93023435efe39b90503aa6` / `9ae0472ade52a0e2472a28535d8c38185319162af23e248d5d08280e2c968713` |
| Threaded allocation client / Runtime | `e03b000e0d6802c5ff705d70325ce848aea6f44595e6ad4edaa75fe71984b4a6` / `e608b6971bd013ef5878cb0b51cf63dcb5318627e68d837bb9f4aacdb1872ada` |
| Threaded lock client / Runtime | `abc9c74ad4687c43992d6751bc4baebf0447b066e9fdf92a3a50e74de418c90d` / `0c37bbec200102c3d688d3c06a132cbd879c5844694d2097048b8cfaf29dcf1d` |
