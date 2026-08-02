# Ordered message-appender anatomy

Measured on 2026-08-02. This report records E300-E315 and separates two questions that must not be conflated:

1. how much capacity the shared ordered JDBC message appender has for different batch and payload shapes;
2. whether that capacity can be converted into intact durable command/result E2E throughput.

The isolated probe is supporting evidence. The complete no-model command/result route remains the truth for progress.

## Identity and retained contracts

| Item | Identity |
| --- | --- |
| SDK production source | `c6105e865dab98c0a896eded330b7faeac959ba5` |
| Runtime production classes | identical to E290-E299: `FluxzeroRuntime` `85876c16...`, `ReadWriteMessageStore` `dc465a89...`, `JdbcMessageStore` `a40dfc024...` |
| Runtime benchmark probe | committed as `30ff16cb969a`; final source SHA-256 `a6670ba937fe4844fb2b7b52052f830ad02428eab4aff752dc3d9b682d046e8f` |
| Dependency classpath | `a3165817c255f6dbfe9f486a49531a8e7f493ab6e1150a35a9dfddad5802cea7` |
| Java / heap | OpenJDK 25; `-Xms8g -Xmx8g` |
| PostgreSQL | 18.3; `synchronous_commit=on`; `wal_sync_method=fdatasync`; 16 GiB WAL / 30-minute checkpoint policy |
| Appender input | already native/envelope-backed `SerializedMessage`, matching the Runtime boundary after WebSocket decode |
| Retained writer path | ordinary `ReadWriteMessageStore` backlog, index assignment, compression, parallel inserts and single ordered commit lane |
| Verification | exact durable message count plus monotonically increasing, non-overlapping index ranges |

Numeric index gaps are valid. Indices encode time, and a rejected conditional reservation may leave unused positions.
Safety requires that a later visible transaction never publishes at or below an earlier visible range; it does not
require `nextIndex == previousIndex + 1`.

The probe was checkpointed after the measurements. Its two measured source revisions are reconstructed exactly from
the committed source so later output-only cleanup cannot be confused with a storage-path change:

| Runs | Probe source SHA-256 | Difference from committed `30ff16cb969a` |
| --- | --- | --- |
| E300-E302 | `125c7e3ece5f631afb9dfb96717517dc0ceecfc49254cab9f5696bbade9e6da5` | Unconditional post-warmup checkpoint; envelope GiB/s uses the representative envelope size |
| E303-E310 | `ddf6ceb5a425fb0abdabe31699519c31e0869f2660e4b10e9e50cacdc08ebcfc` | Adds the externally coordinated checkpoint toggle; envelope GiB/s still uses the representative size |
| Committed probe | `a6670ba937fe4844fb2b7b52052f830ad02428eab4aff752dc3d9b682d046e8f` | Output precision only: sums exact generated envelope bytes |

The envelope-accounting cleanup does not change message generation, the offered workload, the writer, JFR or measured
throughput. Every retained log also prints its complete workload configuration; the artifact hashes below bind each
run to that output.

## Small-message count and transaction curve

All rows use 32 payload bytes, batch-only JFR and a fixed 8-GiB heap. `svcM/s` is messages divided by summed active
time on the single commit executor; wall throughput includes envelope generation, offering and durable completion.

| Run | Shape / intentional change | Messages | Backlog batch avg / max | Wall throughput | Writer svcM/s | Mean commit | WAL | Decision |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| E300 | Result shape; 256-message offers | 4,194,304 | 1,184 / 4,096 | **1.791M/s** | 1.913 | 0.609 ms | 46.1 MiB | Fragmented single-store reference |
| E301 | Command shape; 256-message offers | 4,194,304 | 1,252 / 4,096 | **2.057M/s** | 2.133 | 0.584 ms | 56.6 MiB | Both logical store shapes exceed 1M/s in isolation |
| E302 | Result shape; full 4,096-message offers | 4,194,304 | 4,096 / 4,096 | **4.653M/s** | 5.203 | 0.760 ms | 46.0 MiB | Proves transaction-amortization headroom, not E2E availability |
| E303/E304 | Command + result in parallel, but 64 connections each | 4,194,304 each | 1,190 / 1,197 | 1.326M / 1.318M/s JFR wall | 1.401 / 1.404 | 0.839 / 0.843 ms | global | Invalid: exceeded PostgreSQL `max_connections` after the workload |
| E305/E306 | Command + result in parallel, 32 connections each | 4,194,304 each | 1,899 / 1,892 | **2.222M / 2.152M/s** | 2.361 / 2.355 | 0.792 / 0.793 ms | global | Valid dual-writer diagnostic; two writers alone do not reproduce the E2E slowdown |

E279's intact 0.948M/s route had mean command/result commits of 1.847/1.748 ms and result batches averaging 2,476.
The isolated dual-store probe stays around 0.79 ms per commit. PostgreSQL cannot therefore be labelled a fixed
1M-message/s limiter: other full-route activity and feedback materially change both commit residence and batching.

E302 also shows why the next production mechanism cannot be selected from a bulk benchmark. Full batches make the
same small-result appender 2.6 times faster than E300, but E311-E313 below prove that merely raising the full-route
backlog count does not make those batches arise usefully.

## Payload and compression curve

Large-payload offers stay at or below roughly 64 MiB. The 1-KiB rows stop at 8 MiB because their 8,192-message count
ceiling wins; the 64-KiB and 1-MiB rows use roughly 64-MiB offers. Message rate is meaningless without the accompanying
byte rate and compression identity.

| Run | Payload / templates | Batch | Throughput | Payload rate | Stored LTS delta | WAL delta | Interpretation |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| E307 | 1 KiB random, 16 repeating templates | 8,192 | **1.161M/s** | 1.107 GiB/s logical | 142.3 MiB | 154.8 MiB | Repetition occurs inside LZ4's useful window; this is a compressible workload |
| E308 | 1 KiB random, 256 templates | 8,192 | **122,428/s** | **0.117 GiB/s** | 1,041.3 MiB | 1,117.1 MiB | Incompressible byte/WAL boundary |
| E309 | 64 KiB random, 16 templates | 1,024 | **1,820/s** | **0.111 GiB/s** | 2,054.1 MiB | 2,526.0 MiB | Same byte boundary at a lower message rate |
| E310 | 1 MiB random, 16 templates | 64 | **98/s** | **0.095 GiB/s** | 2,048.4 MiB | 2,666.0 MiB | Same boundary including larger per-row/WAL overhead |

The 1M-command/s campaign target is explicitly a 32-byte-payload profile. It cannot be generalized to arbitrarily
large query/model responses: at 1 MiB, 1M messages/s would require roughly one tebibyte per second before metadata,
WAL or replication. Production batching must consequently use both a count ceiling and a heap-derived byte budget;
a count selected from this benchmark is not a safe universal default.

## Intact E2E backlog ablation and pin audit

These runs retain command serialization, WebSockets, durable command/result logs, typed no-op handling, tracking,
result deserialization and caller-future completion. They use the same fixed heap, Java 25, latest SDK defaults and
16,384-command producer as the high-state controls.

| Run | Only intentional change | Throughput | p50 / p95 / p99 / max | Disposition |
| --- | --- | ---: | ---: | --- |
| E311 | Backlog 4,096 control 1 | 905,191/s | 62.635 / 85.977 / 101.634 / 140.338 ms | Interfered control |
| E312 | Backlog 8,192 | 904,047/s | 62.319 / 87.099 / 114.228 / 188.263 ms | **Reject** |
| E313 | Backlog 4,096 control 2 | 912,520/s | 63.257 / 82.736 / 91.748 / 119.801 ms | Interfered control |
| E314 | Backlog 4,096; Spotlight reniced but still active | 911,218/s | 62.919 / 84.062 / 94.740 / 131.663 ms | Interference diagnosis |
| E315 | Backlog 4,096; runaway Spotlight instance stopped and post-matrix autovacuum complete | **962,888/s** | 59.183 / 79.693 / 87.655 / 106.967 ms | Clean production pin restored |

E311/E313's control geometric mean is 908,848/s. E312 is 0.53% lower and has worse tail latency, so a count-only
8,192 backlog is causally rejected. No production code was written for it.

The temporary 905-913k band was not a code regression: production class hashes and dependency classpath were identical
to E293-E299. During that band a user-owned `spotlightknowledged` process continuously consumed one core and I/O, while
PostgreSQL also vacuumed large TOAST relations after the multi-GiB probes. Renicing Spotlight alone did not restore the
pin. Stopping that one instance and waiting for autovacuum restored 962,888/s. The experiment establishes combined
machine interference; it does not attribute an exact percentage to either background task separately.

## Current conclusion and next evidence

- Command and result stores use the same generic appender; optimize neither by name alone.
- Small-message capacity has large transaction-amortization headroom, but count-only backlog growth is not the route
  mechanism that realizes it.
- Incompressible large payloads are byte/WAL-bound near 0.10-0.12 GiB/s on this machine. A production solution must
  bound outstanding and batched bytes relative to Runtime heap as well as item count.
- The current durable no-model production pin is **962,888/s**; no production candidate was accepted in E300-E315.
- Next, capture a clean high-state batch profile on the intact route and compare result batch formation, commit,
  direct insert and concurrent read pressure with E279. Select a mechanism only after that full-route limiter is
  causally narrower than “make batches bigger”.

## Immutable evidence

Full SHA-256 hashes are in [`model-e2e-run-registry.csv`](model-e2e-run-registry.csv). Principal summaries:

- E300 result isolation: `6b3a2c35...`; E301 command isolation: `3583f656...`;
- E302 full-result batches: `47cc915a...`;
- E305/E306 valid dual-store profiles: `8bd68e4f...` / `6580db2f...`;
- E307-E310 payload profiles: `b7f207c5...` / `8bfff8eb...` / `165b9005...` / `084c8a45...`;
- E315 clean E2E pin log: `8da4a4a8...`.
