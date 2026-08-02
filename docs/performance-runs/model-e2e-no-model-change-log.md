# Durable no-model E2E change log

This log isolates the durable command/result route used before event and model work is reintroduced. It records the
exact changes between the E279 high state and the later low state so a benchmark-environment regression cannot be
mistaken for a production regression again.

## Route and immutable behavior

Every E2E row below executes:

```text
typed command serialization -> command WebSocket -> durable ordered command log
-> command tracking/deserialization -> explicit typed void handler
-> ordinary durable ordered result log -> result tracking/deserialization
-> original caller future completion
```

Each hot run warms up with 10,485,760 commands, then measures another 10,485,760 commands with 65,536 maximum in
flight. Every measured run verifies exactly 10,485,760 successful results, zero model events and zero global events.
SDK and embedded Runtime execute in one Java 25 JVM; two independent sender clients each use one caller thread.

## Source and artifact identity

| Item | Identity used by E290-E299 |
| --- | --- |
| SDK source | `c6105e865dab98c0a896eded330b7faeac959ba5` |
| Runtime source | `93af156b6a3ee2ccb392d44971f487d9a18a2e02` |
| Benchmark class SHA-256 before heap identity logging | `e0f8f8b1defe6ed6bb7cccfa0b8abfae3c991f6bf30d64294c09a7ac90412c50` |
| Runtime entry class SHA-256 | `85876c16ed246b7f1f86c009b605122d95e3e5050a746fcb9a66b6f776996556` |
| Read/write message-store class SHA-256 | `dc465a89a6f1f514040761a05043539ce518b169e93835034f8ded080ed58a4a` |
| JDBC message-store class SHA-256 | `a40dfc02456ba619250e88176e6d598c52b9aa4b2e459a30a27cc7b857cbbee1` |
| Dependency classpath SHA-256 | `a3165817c255f6dbfe9f486a49531a8e7f493ab6e1150a35a9dfddad5802cea7` |
| Java | OpenJDK `25+36-3489`, AArch64 |
| Database | PostgreSQL 18.3 image `sha256:0e44620f2c3720714a21ef0761baee9326fcb32851ccf1278fa96d895e766e89` |
| Durability | `synchronous_commit=on`, `wal_sync_method=fdatasync` |
| Message payload | 32-byte benchmark payload; ordinary command/result metadata and transport remain present |
| Message-store cache | 65,536 entries and 64 MiB per store unless the row explicitly says otherwise |
| Message-store batching | legacy 4,096-message backlog, unbounded pending result jobs |

The later Runtime commit `9dda86b3` only makes subsequent runs print effective initial, maximum and committed heap;
it does not change the measured route.

## Run ledger and exact changes

| Run | Only intentional change | Profiling | PostgreSQL checkpoint policy | Throughput | p50 / p95 / p99 / max ms | Status |
| --- | --- | --- | --- | ---: | ---: | --- |
| E279 | Historical high reference, `-Xms8g -Xmx8g`, producer 16,384 | batch JFR | 1 GiB WAL / 5 min | **948,289/s** | profile artifact | diagnostic reference |
| E289 | Later clean low-state, producer 8,192; JVM heap flags not pinned | none | 1 GiB WAL / 5 min | 769,494/s | 77.181 / 107.848 / 130.760 / 182.502 | superseded low-state |
| E290 | Current classes, producer 16,384, no fixed heap | batch JFR | 1 GiB WAL / 5 min | 589,946/s | 81.511 / 243.524 / 364.567 / 882.656 | low-state attribution |
| E291 | E290 plus command cache count 131,072; byte cap remains 64 MiB | batch JFR | 1 GiB WAL / 5 min | 733,430/s | 77.162 / 110.161 / 128.911 / 246.084 | cache diagnostic only |
| E292 | No fixed heap, command cache 131,072; WAL raised so no checkpoint occurs | none | 16 GiB WAL / 5 min | 755,636/s | 75.679 / 105.895 / 117.862 / 143.933 | excludes checkpoint as root cause |
| E293 | Current production binary, default cache, producer 16,384, restore `-Xms8g -Xmx8g` | none | 16 GiB WAL / 5 min | **948,988/s** | 60.768 / 80.432 / 88.072 / 105.468 | high state restored |
| E294 | Same fixed heap, producer 8,192; timed checkpoint starts during run | none | 16 GiB WAL / 5 min | 851,775/s | 66.151 / 92.061 / 116.747 / 809.056 | excluded: checkpoint overlap |
| E295 | Clean checkpoint first, fixed heap, producer 16,384 | none | 16 GiB WAL / 30 min | **967,037/s** | 59.026 / 78.405 / 85.721 / 105.218 | clean bracket control 1 |
| E296 | Fixed heap, producer 8,192 | none | 16 GiB WAL / 30 min | **950,653/s** | 62.723 / 87.022 / 100.336 / 136.631 | clean driver comparison |
| E297 | Fixed heap, producer 16,384 | none | 16 GiB WAL / 30 min | **938,614/s** | 60.968 / 79.794 / 88.265 / 102.962 | clean bracket control 2 |
| E298 | Only `-Xms8g`; ergonomic maximum remains about 9 GiB | none | 16 GiB WAL / 30 min | **953,685/s** | 59.922 / 79.470 / 88.601 / 117.583 | causal split |
| E299 | Only `-Xmx8g`; initial heap remains about 576 MiB | none | 16 GiB WAL / 30 min | 758,911/s | 75.445 / 105.234 / 117.405 / 144.577 | causal split |

E295/E297 have a 952,719/s geometric mean. E296 differs by only -0.22%, so the earlier claimed general +2.53% for an
8,192 producer batch does not survive the restored high state. The 8,192 driver remains valid but is neutral, not a
performance checkpoint.

## Causal conclusion

The 948k -> 769k gap was not a production regression, JFR overhead, Maven versus direct Java, the rejected bounded
writer, or PostgreSQL checkpoints:

1. Direct Java with current target classes reproduced the low state, excluding the launcher.
2. E279 was fast with JFR while E289 was low without JFR, excluding accidental profiling as the explanation.
3. E290 showed 22.6 times more physical command-store scan input than E279. E291's larger count cap removed most of
   those reads and improved the profiled low state by 24.3%, proving a cache/backpressure amplifier but not the root.
4. E292 completed without a checkpoint and remained low, excluding forced checkpoints as the primary cause.
5. E293 restored only the historical fixed heap and returned to 948,988/s on the fully reverted production binary.
6. E298/E299 isolate the mechanism to the initial heap: `-Xms8g` retains the high state, `-Xmx8g` does not.

Without an explicit initial heap, this machine starts G1 at about 576 MiB and expands toward an ergonomic maximum near
9 GiB. The controlled split proves that this small initial heap causes the low state. The observed allocation burst,
physical-read increase and cache response are consistent with GC/heap-expansion pressure being amplified when the
command consumer falls outside its recent-message cache; that internal chain has not yet been independently timed at
every step. A fixed initial heap is a benchmark-environment requirement, not a Fluxzero production throughput
improvement.

## Cache interpretation and production guardrails

The count-only E291 probe is not a production recommendation. A count cap can accidentally fit one benchmark window
while being wrong for workloads containing tiny or very large commands/results. The byte cap is the representative
memory ownership boundary; in production it should eventually be derived from Runtime heap budget and remain subject
to adaptive memory-pressure trimming. A count cap may remain only as a defensive ceiling against millions of tiny
messages.

Command and result stores share the same ordered writer mechanics. Future message-appender work must therefore use a
payload-size matrix and validate both log types. Parallel serialization and inserts are allowed, but index ranges and
visible durable commit/publication must remain monotonically ordered and non-overlapping so trackers cannot advance
past a transaction that may later publish at a lower index. Numeric gaps are valid: indices also encode time, and a
rejected conditional reservation may deliberately leave unused positions.

## Process corrections and next experiment

- Canonical no-model launchers must use at least `-Xms8g`; the historical `-Xms8g -Xmx8g` pair remains the exact
  comparison identity.
- PostgreSQL benchmark runs use 16 GiB `max_wal_size`, 30-minute `checkpoint_timeout`, and one explicit checkpoint
  before a matched series. These settings remove measurement overlap and are not counted as product gains.
- Every run records source commits, dirty diff status, class hashes, JVM heap, Java version, database settings,
  profiling, cache entry/byte limits, payload size, request window, producer batch, throughput, latency and disposition.
- The abandoned E279 writer reconstruction lived only in `/private/tmp/fluxzero-runtime-e279-repro`; it was never run
  or accepted after E293 proved the production binary itself still reaches the high state.
- Next: isolate the generic ordered message appender with command/result-shaped metadata and small, normal and large
  payloads, then validate any mechanism on the intact durable no-model E2E route before production code is accepted.

E300-E315 execute that next step in
[`model-e2e-message-appender-anatomy.md`](model-e2e-message-appender-anatomy.md). They establish a fresh 962,888/s
clean-system pin, reject a count-only 8,192 backlog, and quantify both small-message transaction headroom and the
0.095-0.117 GiB/s incompressible payload boundary. No production candidate was accepted.
