# Durable no-model E2E change log

This log isolates the durable command/result route used before event and model work is reintroduced. It records the
exact changes between the E279 high state and the later low state so a benchmark-environment regression cannot be
mistaken for a production regression again.

## Live scoreboard

| Campaign state | Current evidence |
| --- | --- |
| Accepted no-model production pin | **962,888 commands/s** (E315, profiler-free, full durable command/result route) |
| Fresh exact-weight pin | E386/E389 unbounded: **964,390 / 963,072 commands/s**; geometric mean **963,731/s** |
| Accepted cache-tail checkpoint | E449 old Runtime **830,972/s** -> E450 parked tail **912,280/s** on the same post-checkpoint host: **+9.78%** |
| Current no-model status | **912,280 commands/s** measured; projected healthy-host result is not a pin and stable >1M remains open |
| Best recent healthy batch-profile | E391: 943,926/s with 8,192-message capacity; local result writer improves 11.0%, but matched E2E is neutral |
| Current production checkpoint | SDK `bb8e1db2`: exact complete-envelope byte accounting plus Backlog `maxInFlightBatches`; Runtime remains intentionally unbounded and at the legacy 4,096-message backlog. The pin also requires command-cache count ceiling 1,048,576 with the unchanged 64-MiB byte ceiling |
| Current focus | E421-E424 reject target-aware cache indexing at −0.84% on the correct full canonical route. Production candidate code is removed; return to the measured result-writer/commit limiter on the clean pinned identity |
| Exit criterion before events/models return | Stable profiler-free no-model throughput **materially above 1.0M/s**; 1.5-2.0M/s remains the desired structural headroom before models return |

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

E338-E350 subsequently reject logical-job and row/byte-bounded transaction fusion: fewer commits do not compensate
for moving formerly parallel inserts onto larger single-connection transactions. E351 then proves that four result-log
commit lanes behind an ordered visibility frontier remove most writer queue residence without changing transaction
shape, but E2E stays flat. Enabling four independent lanes on both command and result logs in E353 permits eight
concurrent commits, increases commit duration and loses E2E. The diagnostic frontier remains uncommitted; a continuation
must share one global commit budget and add durable recovery rather than tune independent per-log lane counts.

E354-E356 close that continuation. One shared four-worker pool still loses 15.15% to its immediate legacy control as
both commit and insert duration rise; the trailing candidate is invalidated by a host/handler collapse and cannot rescue
an already negative mechanism. The publication-frontier source is rejected. The next candidate returns backpressure to
transaction-wave formation while allowing only serialization—not already-open transactions—to run ahead.

E357-E362 test that exact ordered storage-wave design and reject it. The implementation first serializes each logical
append, then gives an ordered asynchronous backlog every prepared append that is already ready. One backlog drain
coalesces only contiguous jobs up to 64 physical rows or 8 MiB per transaction, starts a bounded wave of those
transactions so their inserts overlap, retains the existing single ordered commit executor, and does not form the next
wave until every current transaction is durable. There is no collection delay or timer. A focused PostgreSQL test
delayed the first real commit, proved later append futures remained incomplete, then verified exact coalescing, order
and visibility after release; all 43 `JdbcMessageStoreTest` tests remained green.

E357 invalidated the first scalar admission approximation before it produced a measured result: nine prepared jobs
could fragment into five contiguous transactions despite a four-lane normalized row/byte budget. The guard fired and
the run was aborted. E358-E362 replaced that unsafe assumption with exact sequential subwaves. The mechanism then
worked, but complete E2E rejected it:

| Run | Shape | E2E | Result tx | Results/tx | Writer service | Commit service | Insert max | Ready at commit start |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| E358 | ordered wave, 4 lanes | 489,236/s | 208 | 5,041 | 0.600M/s | **1.455M/s** | 4 | 1.3 mean / 4 max |
| E359 | adjacent legacy control | 662,166/s | 430 | 2,439 | 0.710M/s | 0.778M/s | 8 | 7.3 mean / 14 max |
| E360 | ordered wave, 8 lanes | 617,198/s | 191 | 5,490 | **0.730M/s** | **1.901M/s** | 5 | 1.3 mean / 4 max |
| E361 | reverse legacy control | 626,000/s | 428 | 2,450 | 0.657M/s | 0.727M/s | 11 | 6.1 mean / 14 max |
| E362 | ordered wave, 16 lanes | 517,878/s | 200 | 5,243 | 0.609M/s | 1.680M/s | 4 | 1.3 mean / 3 max |

Wave 8 improves local writer service 6.9% over the geometric 0.683M/s of its two controls, but its complete-route
617,198/s is 4.1% below the controls' geometric 643,829/s. Four lanes lose much more; allowing sixteen lanes does not
create additional natural overlap and also loses. The writer demonstrably halves transaction count and nearly doubles
commit-only capacity, yet freezing work into durability waves removes the legacy pipeline's six-to-eight already
insert-ready jobs at the ordered commit boundary. That feedback loss matters more to the intact route than the commit
amortization saves. The entire diagnostic prototype is reverted; no Runtime production checkpoint is created.

This result corrects the broad API intuition. `Backlog.forOrderedAsyncConsumer` has the desired standalone semantics:
drain a batch, wait for its returned future, and only then drain what accumulated. `forAsyncConsumer` keeps draining
while earlier futures remain open and therefore needs an explicit downstream concurrency/byte bound; its current
prefix-based completion bookkeeping also assumes async batches complete in submission order. Nevertheless, replacing
the message writer with an ordered wave is not sufficient because PostgreSQL inserts on different connections imply
different transactions. Preserving their valuable insert-ahead overlap while reducing ordered publication cost remains
the architectural problem.

## E363-E365: current production is repinned after host interference

The low absolute E357-E362 controls did not become a new production baseline. Three exact 10,485,760-command,
profiler-free runs separated source identity from host state while retaining the E315 launch identity: Java 25,
`-Xms8g -Xmx8g`, two senders, 16,384-command producer batches, 65,536 maximum in flight and the complete durable
command/result route.

| Run | Runtime message-store bytecode | Host state | Throughput | p50 / p95 / p99 / max ms | Interpretation |
| --- | --- | --- | ---: | ---: | --- |
| E363 | current `d5abe0a7`, JFR observer disabled | `kernelmanager_helper` actively rebuilding kernel/driver cache | 782,755/s | 70.627 / 105.979 / 144.886 / 255.817 | excluded host-loaded repin |
| E364 | exact E315/P3 `JdbcMessageStore` bytecode | helper paused, but Docker/PostgreSQL writeback still elevated | 707,257/s | 66.713 / 205.375 / 335.419 / 468.584 | old bytecode does not restore high state |
| E365 | current `d5abe0a7`, JFR observer disabled | helper paused; writeback settled; thermal pressure nominal | **905,605/s** | **63.305 / 85.260 / 94.195 / 113.422** | fresh clean production repin |

The current and historical `FluxzeroRuntime` and `ReadWriteMessageStore` class hashes are identical. The only relevant
message-store bytecode difference is the later contiguous-writer-readiness JFR observer, which short-circuits when batch
JFR is disabled. E364 nevertheless ran the exact historical E315/P3 `JdbcMessageStore` class and was slower than both
adjacent current-class runs. E365 then recovered after the remaining virtualization/database writeback fell away.
Therefore neither the rejected wave prototype nor disabled JFR observer code caused the 600-700k readings.

E365 is a valid recovery pin, but it does not replace E315's **962,888/s** campaign high and is not yet the requested
stable 1.5-2.0M/s no-model state. Absolute comparisons remain host-qualified: a run overlapping kernel cache rebuilding,
Spotlight/FileProvider work or sustained post-run virtualization writeback is diagnostic-only.

## E366-E375: exact Backlog completion is checkpointed before bounding the Runtime

The legacy asynchronous Backlog completed every producer future up to a batch's end position. That was only correct
while consumer futures happened to complete in dispatch order. A later fast future could otherwise release an earlier
unrelated producer before its own consumer batch completed. The SDK checkpoint `5f4d2c76` replaces that prefix
assumption with one completion record per submitted collection and adds an explicit `maxInFlightBatches` overload.
One slot means the former ordered behavior. The old two-argument async factory deliberately remains unbounded, so none
of E366-E375 tests message-store backpressure yet.

Untracked values remain direct queue entries, avoiding a wrapper or completion future on the result/WebSocket hot path.
A completed consumer future releases its in-flight slot and schedules queued work before producer-future dependants
run, so an arbitrary user callback cannot retain Backlog capacity. Existing ordered call sites use
`forAsyncConsumer(..., 1)`; the old factory name remains a deprecated forwarding alias while Runtime callers migrate.

### Profiler-free route observations

All long runs use Java 25, embedded Runtime `d5abe0a7`, PostgreSQL 18.3, two sender clients, 16,384-command producer
batches, 65,536 caller requests in flight, 10,485,760 warm-up commands and 10,485,760 measured commands. Every run
verifies exactly 10,485,760 ordinary durable results.

| Run | Backlog implementation | `maxInFlightBatches` | Warm-up | Measured | p50 / p95 / p99 / max ms | Interpretation |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| E368 | legacy control | unbounded | 743,646/s | 912,243/s | 61.915 / 83.247 / 95.912 / 246.136 | healthy control |
| E369 | exact completion | unbounded | 653,941/s | 913,695/s | 62.260 / 82.219 / 90.508 / 115.074 | normal candidate state, neutral |
| E370 | exact completion | unbounded | **833,412/s** | **640,635/s** | 65.470 / 260.596 / 331.654 / 777.693 | measured phase crosses the route's bistable slow-state cliff; no profile captured |
| E371 | legacy control | unbounded | 768,642/s | 899,404/s | 63.258 / 85.456 / 98.831 / 119.208 | immediate reverse-control recovery |
| E372 | exact completion | unbounded | 785,402/s | **931,383/s** | **61.193 / 81.620 / 91.010 / 112.071** | candidate recovers; second healthy normal state |
| E375 | committed `5f4d2c76` | unbounded | 607,915/s | 885,307/s | 63.430 / 90.850 / 110.179 / 172.102 | post-verification checkpoint pin |

E370 cannot be discarded, but it also does not establish a Backlog mechanism: both adjacent candidate runs are healthy,
the reverse control recovers, and the route was already known to have a cache/database feedback cliff. A later bounded
screen must record the complete writer/read/cache shape so a recurrence can be attributed rather than inferred from
throughput alone.

### Matched command/result batch profile

E373/E374 use batch-only JFR over the same long measured workload. They compare the legacy data structure with the
pre-checkpoint exact-completion implementation; both retain unbounded message-store admission.

| Measure | E373 legacy control | E374 exact completion | Reading |
| --- | ---: | ---: | --- |
| E2E throughput | 912,362/s | 882,302/s | candidate profile is 3.30% lower |
| command transactions | 2,560 | 2,560 | identical |
| commands / transaction | 4,096.0 | 4,096.0 | identical full batches |
| command writer service | 1.226M/s | 1.199M/s | broad 2.2% service drift |
| result transactions | 4,330 | **4,217** | candidate makes 2.61% fewer commits |
| results / transaction | 2,421.7 | **2,486.5** | candidate batches are 2.68% larger |
| result commit mean | 2.161 ms | **2.101 ms** | commit amortization improves |
| result direct-insert mean | **2.840 ms** | 2.972 ms | candidate insert service is 4.65% slower in this profile |
| result writer service | 0.969M/s | 0.959M/s | effectively similar; no new hard writer collapse |
| result jobs ready at commit start | 4.45 mean / 16 max | 4.24 mean / 16 max | legacy insert-ahead shape retained |
| physical command scan | 436 calls / 827,392 messages | 409 / 860,160 | no material cache cliff |
| physical result scan | 1,442 calls / 3,293,989 messages | 1,542 / 3,229,406 | comparable physical-read volume |

The profile rejects transaction fragmentation as a consequence of exact completion: result transactions become larger,
not smaller, and commit cost falls. The final SDK reactor is green across common, SDK, test-server, proxy, annotation
processor, Java downstream and Kotlin downstream modules. This earns a correctness checkpoint, not a throughput
checkpoint. The next experiment changes only Runtime message-store admission and always records
`maxInFlightBatches`, logical batch size, physical transactions, insert/commit service, ready jobs and physical reads.

## E376-E383: first bounded Runtime admission curve and captured cache cliff

The Runtime candidate wires `ReadWriteMessageStore` to the checkpointed Backlog overload while preserving the old
default exactly: absent a property, `maxInFlightBatches` remains `Integer.MAX_VALUE`. The diagnostic property
`fluxzero.messageStoreMaxInFlightBatches` applies globally; appending `.command` or `.result` overrides one physical
message log. A separate `fluxzero.messageStoreMaxBatchWeight` property and per-log form use payload bytes as generic
Backlog weight, but every run in this section leaves that cap at `Long.MAX_VALUE`. The count-only path therefore does
not execute an extra `getBytes()` per message.

The production candidate diff is based on SDK `2e3128b983c` and Runtime `d5abe0a788a5`; its two tracked production-file
diff has SHA-256 `4448ff04a7bc3c587f18892b42a78ea2bed25f6f9f8ee3435c9261b4968b656c`.
`ReadWriteMessageStore.class` is `4c26216de6478ddbda9276e12fbd1bafbff686bf8e3d741405db5695552595eb`,
`JdbcMessageStore.class` is `733db7ac904b33ca8b018119d04344d75e35081b90fa89b25c0bae3ade820016`
and the benchmark class is `04c0c7d7c85fd344cbe46e8652ab3a39c022a01aa49d463d66f2d2ddfe8888d9`.
Java is OpenJDK 25 `25+36-3489`; PostgreSQL remains synchronous-commit 18.3 with `fdatasync`, 16 GiB WAL and a
30-minute checkpoint timeout. Every invocation uses batch-only JFR, 10,485,760 warm-up and measured commands, and
verifies exactly 10,485,760 durable ordinary results with zero model and global events.

### Full route and physical writer shape

Rates are million messages/s of active JDBC message-store service. Commit and insert values are mean milliseconds per
physical transaction. `ready` is the number of contiguous already-insert-ready storage jobs observed when the ordered
commit starts. The profile ceiling is diagnostic; it is not mixed with profiler-free acceptance throughput.

| Run | `maxInFlightBatches` | E2E/s | p50 / p95 ms | Command tx × rows/tx | Command writer | Result tx × rows/tx | Result writer | Result commit / insert ms | Result ready mean / max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| E376 | **unbounded** | 874,434 | 63.483 / 89.175 | 2,560 × 4,096.0 | 1.166M | 4,278 × 2,451.1 | 0.936M | 2.243 / 3.256 | 4.47 / 20 |
| E377 | **32** | 858,130 | 66.327 / 90.945 | 2,560 × 4,096.0 | 1.206M | 4,343 × 2,414.4 | 0.935M | 2.088 / 3.043 | 4.70 / 20 |
| E378 | **16** | **902,873** | **63.041 / 83.218** | 2,560 × 4,096.0 | 1.218M | 4,268 × 2,456.8 | 0.976M | 2.104 / **2.763** | 3.92 / 14 |
| E379 | **8** | 883,170 | 65.391 / 85.372 | 2,560 × 4,096.0 | **1.248M** | **3,970 × 2,641.2** | **0.999M** | **2.010** / 3.009 | 3.67 / 7 |
| E380 | **4** | 680,892 | 83.696 / 111.026 | 2,560 × 4,096.0 | 0.898M | **3,732 × 2,809.7** | 0.734M | 3.110 / 3.688 | 1.84 / 3 |
| E381 | **12** | 725,814 | 78.718 / 105.250 | 2,560 × 4,096.0 | 0.975M | 4,397 × 2,384.8 | 0.780M | 2.611 / 3.467 | 4.35 / 10 |
| E382 | **12** | 890,091 | 63.643 / 88.529 | 2,560 × 4,096.0 | **1.275M** | 4,209 × 2,491.3 | **0.997M** | **1.974** / 2.949 | 4.22 / 11 |
| E383 | **20** | 622,010 | 70.374 / 257.983 | 2,560 × 4,096.0 | **1.328M** | 4,339 × 2,416.6 | 0.927M | 1.767 / 2.704 | 4.06 / 16 |

The JFR-observed concurrent append futures confirm when the setting actually binds:

| Run | `maxInFlightBatches` | Command max active | Result max active | Command commit / insert mean ms |
| --- | ---: | ---: | ---: | ---: |
| E376 | unbounded | 9 | 22 | 2.355 / 3.366 |
| E377 | 32 | 8 | 23 | 2.220 / 2.956 |
| E378 | 16 | 8 | **16** | 2.155 / 2.808 |
| E379 | 8 | **8** | **8** | 2.174 / 2.834 |
| E380 | 4 | **4** | **4** | 3.105 / 3.702 |
| E381 | 12 | 8 | **12** | 2.728 / 3.596 |
| E382 | 12 | 8 | **12** | **2.088 / 2.825** |
| E383 | 20 | 9 | 18 | **1.955 / 2.692** |

E377 is effectively another unbounded observation because neither log reaches 32. E378 is the first informative bound:
it trims the result peak while retaining the command writer and most useful result insert-ahead. E379 makes 7.5% larger
result transactions than E377 and reaches the best local result-writer rate, but its lower overlap gives less complete
route throughput than 16. E380 is below the safe parallelism floor: it binds commands as well as results, slows both
their fixed-shape transactions and loses 24.6% versus E378.

### Tracking reads and position writes

These counters guard against mistaking a cache or position-feedback transition for message-writer improvement.

| Run | `maxInFlightBatches` | Physical command scans: calls / messages | Physical result scans: calls / messages | Command-position calls / updates | Result-position calls / updates |
| --- | ---: | ---: | ---: | ---: | ---: |
| E376 | unbounded | 436 / 716,800 | 1,479 / 3,294,992 | 2,957 / 16,000 | 3,229 / 4,779 |
| E377 | 32 | 405 / 765,952 | 1,545 / 3,156,086 | 2,887 / 14,941 | 3,340 / 4,744 |
| E378 | 16 | 389 / 774,144 | 1,475 / 3,164,649 | 3,119 / 15,804 | 3,326 / 4,823 |
| E379 | 8 | 357 / 712,704 | 1,518 / 2,757,550 | 2,708 / 14,229 | 3,174 / 4,367 |
| E380 | 4 | 350 / 585,728 | 1,409 / 3,059,845 | 3,125 / 15,741 | 3,122 / 4,417 |
| E381 | 12 | 423 / 864,256 | 1,512 / 3,333,494 | 3,123 / 16,143 | 3,360 / 4,956 |
| E382 | 12 | 433 / 749,568 | 1,566 / 2,980,409 | 2,861 / 14,875 | 3,326 / 4,634 |
| E383 | 20 | 708 / **33,386,496** | 2,760 / 2,744,467 | 2,363 / 12,054 | 4,186 / 5,506 |

E381 is explicitly excluded from the parameter curve. Although 12 cannot bind the command store's observed eight-batch
peak, command commit and insert service both degraded about 25% with the same 2,560 full transactions. After the run the
Docker VM still consumed roughly 39-65% host CPU while its individual containers reported idle, matching the previously
observed post-run virtualization/writeback state. Result production slowed and the mean result transaction consequently
shrunk instead of growing. The run is retained because it detects the collapse; it does not establish that 12 is worse
than 8 or 16. E382 repeats 12 after the VM settled: the unchanged command writer recovers from 0.975M to 1.275M/s,
the result writer from 0.780M to 0.997M/s and full E2E from 725,814/s to 890,091/s. That causally confirms E381 as a
host-state failure. The valid 12 observation falls between 8 and 16, so 16 remains the next acceptance candidate.

The current evidence selects 16 for a profiler-free matched bracket, not for a checkpoint yet. The host must first
return to its clean state. If the profiler-free bracket confirms a simple low-risk 3-4% gain, the campaign protocol
permits accepting it even below 5%; otherwise the property-gated Runtime candidate remains diagnostic and is reverted.

E383 is not a valid point on the admission curve. The configured limit of 20 did not bind: command and result append
peaks were only nine and eighteen. Both physical writers remained healthy and the fixed-shape command writer reached
1.328M/s, yet the complete route changed state about ten seconds into the measured phase. Physical command scan input
then rose from roughly 50-70 thousand messages per second to approximately five million per second and remained there.
The exact transition was:

| Wall-clock second | Command rows appended | Command rows delivered | Physical command rows scanned |
| --- | ---: | ---: | ---: |
| 17:04:31 | 905,216 | 907,923 | 65,536 |
| 17:04:32 | 933,888 | 932,980 | 61,440 |
| 17:04:33 | 356,352 | 313,888 | 1,224,704 |
| 17:04:34 | 294,912 | 307,592 | **4,931,584** |
| 17:04:35 | 319,488 | 308,344 | **4,931,584** |
| 17:04:36 | 307,200 | 310,915 | **4,976,640** |

All sixteen command trackers remained balanced before and after the transition. In the final healthy subsecond window,
short producer/consumer jitter created enough separation for tracker reads to leave the fixed 65,536-entry cached
range. The current event does not distinguish which edge was crossed: a tracker position can be older than the first
cached message, or the asynchronous cache monitor can temporarily trail the last durable message. At 17:04:33.406 the
first large delegate scan visited 65,536 JDBC rows to return 4,102 segment-matching commands. Later scans visited up to
131,072 rows each. Because every tracker scans the same ordered log and selects roughly one of sixteen segments, this
fallback multiplies physical database reads and makes the miss self-sustaining. E383 therefore captures the previously
inferred cache/database feedback mechanism directly, while the exact initiating cache edge remains to be instrumented;
its 622,010/s is neither a max-20 result nor evidence of writer regression.

The artifacts are `/private/tmp/model-e2e-e383-backlog-inflight20-profile.log` (SHA-256
`f4ab4bee6b80109a526ac7ccfb6c9ebd14d97a10717aaa51a5968fb39e86e333`), the JFR with SHA-256
`c80af301e9000a61928b4048a54bbe973f46adf4f734b5f9f6b93f5e1ef661e0`, and summary SHA-256
`12c8436a3c4cd242f6f80d81b6cf5481ae16a2a81aa07f3f27e8b5035d1593b0`. Exactly 10,485,760 durable ordinary
results were verified, with zero model and global events. The next comparison must keep the complete route intact but
give the command cache enough byte-bounded headroom to prevent a transient scheduling wobble from changing the storage
path. Only after that guardrail is stable is a profiler-free 16-versus-unbounded bracket meaningful.

## E384: the byte-bounded cache guardrail removes the feedback cliff

E384 repeats the same complete durable no-model route and the non-binding max-20 admission setting. Its only behavioral
configuration change from E383 is `fluxzero.messageStoreCacheSize.command=1048576`; the existing 64-MiB byte cap is
unchanged. Temporary JFR-only cache-edge instrumentation classifies every delegate fallback without changing cache
behavior. The run completed at 881,840/s with p50/p95/p99/max latency of
64.647/87.105/96.315/123.709 ms and again verified exactly 10,485,760 durable results.

| Measure | E383 default count cap | E384 byte cap effective | Reading |
| --- | ---: | ---: | --- |
| configured count ceiling | 65,536 | 1,048,576 | defensive count no longer binds |
| configured byte ceiling | 64 MiB | 64 MiB | unchanged memory budget |
| observed command-cache entries at fallback | not instrumented | 308,129 mean | byte cap is the effective bound |
| physical command scan | 708 / 33,386,496 | 412 / **704,512** | **97.9% fewer rows visited** |
| command writer service | **1.328M/s** | 1.191M/s | recovery is not faster command SQL |
| result writer service | 0.927M/s | 0.954M/s | normal full-route range |
| command/result max active append futures | 9 / 18 | 9 / 17 | max-20 still does not bind |
| E2E throughput | 622,010/s | 881,840/s | cache-cliff recovery, not admission evidence |

The cache events make the healthy fallback shape explicit: command had 413 `at-tail` events and result had 1,463;
neither store emitted `before-first`, `after-last`, `gap` or `empty`. `At-tail` means the requested last position is
still present but no newer cached message exists yet, so the delegate briefly checks for newly durable rows. This is
the ordinary low-volume JDBC race already present in healthy profiles. With the count ceiling out of the way, command
messages average about 90 payload bytes plus the cache's 128-byte ownership estimate, so 64 MiB retains roughly 308k
messages rather than a benchmark-shaped 65,536.

E384 proves the cache count cap is the cliff amplifier and that the existing byte budget is a representative guardrail
for this workload. It does not by itself select a new production default: the retained-weight calculation and a
payload-size matrix must still protect tiny messages, large messages and metadata-heavy envelopes. It does make the
next admission comparison valid: unbounded and max-16 can now be bracketed under the same byte-bounded cache policy
without a short scheduling wobble changing the physical read path.

Artifacts: log SHA-256 `0dc7fbe09820752e702ac069167bcb67cf2e5dc55ba4234c4da89d3e1b750bd4`, JFR SHA-256
`1789360b766fbed3486e09d284ce534f47800b59d9f48ac1c724fe0b25867a9b`, and summary SHA-256
`eb6599202361405c481eae2e74c32fe5c37a3184fe243b597c690b567e66778d`.

## E385-E389: exact envelope weights change the admission result

`SerializedMessage.getBytes()` previously returned only the payload length. That made every consumer byte limit,
message-store byte group and cache weight blind to fixed headers, routing strings and metadata. E385 changes no wire
format: it reports the already known native envelope length, or computes exactly the envelope that a materialized
message will produce. Dirty envelope-backed messages retain their payload and metadata slices while being sized. The
same complete durable route, 64-MiB command-cache budget and count ceiling of 1,048,576 remain in place.

E385 profiles `maxInFlightBatches=16` after that correction. It reaches 923,628/s despite batch JFR and verifies all
10,485,760 results. The cache now retains 136,742 commands on average at an ordinary `at-tail` fallback. This is the
honest memory shape: about 363 native envelope bytes plus the cache's separate 128-byte Java ownership estimate per
command. All 326 command and 1,170 result fallbacks are `at-tail`; no `before-first`, `after-last`, `gap` or `empty`
event occurs. Physical command reads remain low at 326 scans / 618,496 visited rows.

The corrected message weights also change storage feedback. E385 forms 4,032 result transactions averaging 2,600.6
results, while the result admission peak reaches the configured 16. Commands remain at 2,560 full 4,096-message
transactions and peak at only eight in flight, so the limit binds only the result side.

The profiler-free ABBA bracket then rejects 16 for this route:

| Run | Order | `maxInFlightBatches` | Warm-up | Measured E2E | p50 / p95 / p99 / max |
| --- | ---: | ---: | ---: | ---: | --- |
| E386 | A1 | unbounded | 830,878/s | **964,390/s** | 62.329 / 81.122 / 90.422 / 135.976 ms |
| E387 | B1 | 16 | 833,759/s | 895,937/s | 66.515 / 92.133 / 115.803 / 212.637 ms |
| E388 | B2 | 16 | 851,153/s | 940,942/s | 63.012 / 87.357 / 112.578 / 165.680 ms |
| E389 | A2 | unbounded | 862,118/s | **963,072/s** | 62.369 / 82.841 / 98.205 / 164.093 ms |

The unbounded geometric mean is **963,731/s** versus 918,164/s for 16: **16 loses 4.73%**. The generic bounded-Backlog
API remains accepted and useful, but 16 is not selected as the Runtime message-store default. The next profile must use
the winning unbounded route and exact envelope weights; old payload-only admission results no longer select the next
production change.

Artifacts:

- E385 log/JFR/summary SHA-256: `e2b2c752561a1aef50ed24742d979c13e435dcfa94fc7df51def1331664226a8`,
  `4fb7d323ef2d3d430c585016fd3368bddbe8b02ace28c275e4994de850f7c515`,
  `74cbbffe693b67d3fae9095c8c0c6122473b3fc2926e65934b4dc7f4e4cf8801`;
- E386 log SHA-256: `78afd211b0c3d2cb2bb12421c0bd2077e4b17fdd612636442522e559be559b0b`;
- E387 log SHA-256: `64bba926a3e705b109a22017b0d7c8993e472ca7c8b4aa9eb5295e26af1b913f`;
- E388 log SHA-256: `eb707bd175e7b95722b40075509b20ccc1c4b4596604d5fb12453456ae900bd0`;
- E389 log SHA-256: `0c412664f45647075f6f0e802ed900caf2492e7d8a40f27ce1647a8ca36f7257`.

## E390-E396: larger natural batches improve the writer but not stable E2E

E390 profiles the profiler-free bracket's winning exact-weight, unbounded, 4,096-message route. Its 915,088/s profiled
E2E consumes nearly all of the 0.949M/s active result-writer capacity. The result log uses 4,134 transactions averaging
2,536.5 results; its serial commit service is 1.021M/s with a 2.484-ms mean commit. The command writer remains ahead at
1.149M/s. This confirms result transaction/commit amortization as the immediate local limiter.

E391 changes only the existing global `fluxzero.messageStoreBacklogSize` from 4,096 to 8,192. It waits nowhere and
retains parallel inserts. The local mechanism works exactly as predicted:

| Profile measure | E390: 4,096 | E391: 8,192 | Change |
| --- | ---: | ---: | ---: |
| command transactions | 2,560 | 1,280 | −50.0% |
| commands/transaction | 4,096 | 8,192 | +100.0% |
| command writer | 1.149M/s | **1.489M/s** | +29.6% |
| result transactions | 4,134 | **2,932** | −29.1% |
| results/transaction | 2,536.5 | **3,576.3** | +41.0% |
| result writer | 0.949M/s | **1.053M/s** | +11.0% |
| result commit capacity | 1.021M/s | **1.638M/s** | +60.4% |
| profiled E2E | 915,088/s | **943,926/s** | +3.15% |

E392 is excluded: macOS started `knowledgeconstructiond` at 97% CPU with about 175 MiB/s disk traffic during the run.
The process was paused before the valid bracket. The two profiler-free reverse pairs then disagree in sign:

| Pair | Control 4,096 | Candidate 8,192 | Delta |
| --- | ---: | ---: | ---: |
| E393/E394, candidate then control | 932,220/s | 889,180/s | −4.62% |
| E395/E396, control then candidate | 901,059/s | 950,788/s | +5.52% |
| geometric mean | 916,507/s | 919,468/s | **+0.32%** |

Every valid run verifies exactly 10,485,760 durable results and zero model/global events. The complete profiler-free
route therefore rejects 8,192 as a throughput checkpoint despite its clear local writer improvement. Larger natural
batches merely move the limiter and alter closed-loop timing; another batch-size, fusion or admission candidate is not
selected. The next measurement targets the fixed synchronous PostgreSQL commit cost itself.

Artifacts:

- E390 log/JFR/summary SHA-256: `03835cf1c667737d82a9d8634be192bc97e795594c19e43bdcb8c7476f7eb9d7`,
  `399c8c691e7f925044fd4903181eeb062f43eef5ae0567e385653ed5715bef8a`,
  `ae1a159c523b81b3e1f87973ff3b2fabae41ddcdb9ba8eadf0588e11f29e114f`;
- E391 log/JFR/summary SHA-256: `aaade752c3c94197ff092aaf97b98a85a677feb5b0c6c91430139ea5d71aa3cc`,
  `432f645f730ca276006a27c7c34c180b535fbfd3594bcb921182be595915950f`,
  `ed2a319b83c2d1ed654040ea737b21ca1806ac6d20d1d438a94feabe2857d569`;
- excluded E392 log SHA-256: `ca131422f42b1ef528267f64b91b38dcc3113184d3559dc404ae298509f2aa83`;
- E393-E396 log SHA-256: `0cb62716ecc334795499dd1cc71fa8c696141ccd75fe3a62cd2c50677067e058`,
  `327aba4ae516a9bdc2b4c086178145b3e9f911b81d07ad12218b5a4e3f7238c3`,
  `1b8ed4ea8745d88a3875701f8a3801b745706208e2b28ad19701d5aa61599045`,
  `9c876d9200ef9dcb35bfe37a010958cea610f4ef01878e745e99d562ab91a526`.

## E397: measured-phase PostgreSQL statistics split physical WAL work from commit latency

E397 runs the unchanged exact-weight, unbounded, 4,096-message route with the existing external measurement barrier and
without JFR. After the full 10,485,760-command warm-up, the barrier pauses before measured work, PostgreSQL's database,
I/O and WAL statistic counters are reset, and only then is the complete 10,485,760-command/result phase released. The
run verifies every durable result and zero model/global events at 901,089/s, with p50/p95/p99/max latency of
67.006/87.964/97.754/134.514 ms. Resetting counters changes no stored data, durability setting or production path.

PostgreSQL 18 already had `track_io_timing=on`, `track_wal_io_timing=on`, `synchronous_commit=on` and
`wal_sync_method=fdatasync`. Its measured-phase deltas are:

| Server measure | Count or total | Mean / interpretation |
| --- | ---: | ---: |
| committed transactions | 16,864 | includes message, position, read and lifecycle transactions |
| WAL records / bytes | 700,932 / 401,731,794 | no WAL-buffer-full event |
| client-backend WAL writes | 10,035 / 504.366 ms | **0.050 ms per write** |
| client-backend WAL syncs | 9,961 / 6,674.372 ms | **0.670 ms per physical sync** |
| commits per client WAL sync | 1.69 | PostgreSQL already performs material group commit |
| client relation write + extend time | 192.576 + 500.432 ms | smaller than WAL sync total |

The E390 JFR result commit interval averages 2.484 ms. E397 proves this cannot be described as a 2.484-ms physical
disk sync: WAL write itself is about 0.050 ms and the backend that performs an `fdatasync` averages 0.670 ms. A follower
can wait for another backend's group commit without owning the recorded sync, and command/result/position transactions
overlap. The server totals therefore must not be directly subtracted from the Java interval. The next diagnostic samples
`pg_stat_activity` during the same measured phase and classifies wait events by active SQL kind before any commit-policy,
batching or durability candidate is built.

Artifacts: log SHA-256 `6006a6e7924352a126c168524e3d905d77fbd93d476fe0aa8be371da4b62af81`; measured-phase
PostgreSQL snapshot SHA-256 `c4d228993680d952b7e06707925b2d48e3afc89c1dffba1d6e42c5b106373347`.

## E398: wait sampling confirms insert-ahead residency rather than long physical writes

E398 keeps the E397 route and measurement barrier but samples `pg_stat_activity` from a separate PostgreSQL session
approximately every 3.65 ms. The sampler itself and a pulsing Docker VM reduce the complete route to 839,540/s, so the
throughput is diagnostic-only; all 10,485,760 results and zero model/global events still verify. PostgreSQL's independent
I/O counters remain close to E397: client WAL writes average 0.056 ms and 9,968 physical syncs average 0.704 ms.

The 4,105 wait samples reveal the state between parallel inserts and ordered commits:

| Observed last SQL / state / wait | Mean backends | Active-backend sample share | Meaning |
| --- | ---: | ---: | --- |
| result insert / idle in transaction / `ClientRead` | **6.509** | **54.24%** | result insert is complete; server waits for Java to issue its ordered commit |
| command insert / idle in transaction / `ClientRead` | **1.846** | **15.38%** | same insert-ahead residency for commands |
| result position / active / `ClientRead` | 0.958 | 7.99% | connection is predominantly waiting on client/protocol progress, not storage I/O |
| autovacuum / `VacuumDelay` | 0.883 | 7.36% | throttled maintenance observed during this loaded diagnostic |
| all sampled `IO:WalSync` states | about 0.234 | about 1.95% | short sync events are undersampled at this cadence but are not the dominant residency |

`pg_stat_activity.query` retains the last insert text while these transactions await client work, so this sampler cannot
label the later protocol commit as a separate SQL kind. That limitation does not affect the main result: parallel inserts
are already prepared well ahead of ordered publication, and most retained connection time is deliberate Java-side
insert-ahead feedback rather than PostgreSQL actively writing. E398 therefore does not select fewer insert lanes,
another transaction-fusion attempt or an asynchronous durability policy.

The intact route now points back to its closed-loop driver as the next causal check. With a global 65,536 request window,
E397's 901,089/s implies 72.7-ms average residence and E398's 839,540/s implies 78.1 ms, both inside their measured latency
distributions. E399 enables the existing low-overhead offering counters without changing the route to quantify how much
of the measured interval is spent waiting for that global window before modeling independent real clients.

Artifacts: log/wait samples/PostgreSQL snapshot SHA-256
`1291e03b375395fb2f4b3517f7607ab5e66e896ae5f3e720dc9f2fea3c8a0888`,
`2d4e801bdb82a56faf8a53deeb8526f40e9ba5919b969f0f2f1f4fd4a5cb8a52`,
`b9e969d672f8e851a6da7739c04a155f0402d71fa52da809c53f9eb68ac3cc4d`.

## E399: the global request window and two-caller offering capacity both bind

E399 enables only the benchmark's existing low-overhead `offeringDiagnostics` counters on the intact no-model route.
The Docker VM is already near one host core before the run, so its 909,339/s is not used as a baseline; every exact
result/event count still verifies. The measured 10,485,760-command phase lasts about 11.53 seconds and reports:

| Driver segment | Summed time / capacity | Interpretation |
| --- | ---: | --- |
| two complete producer workers | 22.923 s | approximately two workers times route wall duration |
| wait for one global 65,536 permit window | **9.383 s** | **40.9% of summed worker lifetime** cannot offer more work |
| construct command arrays | 0.129 s | negligible |
| SDK send calls | 13.305 s; 0.788M/s summed service | real mapping/serialization/registration/WebSocket offering path |
| union with at least one SDK send active | capacity **1.107M/s** | two callers provide only 21.7% offering headroom over E2E |
| attach driver completion callbacks | 0.105 s | negligible |

This is not evidence for one larger synchronized global wave: E284 already showed that a single 131,072-request wave
destroys feedback and latency. It does prove the current multi-client benchmark is not modeling independent clients:
both SDK clients share one semaphore, so their combined open requests can never exceed 65,536. It also proves merely
removing that semaphore is insufficient for a 1.5-2M target because two callerthreads themselves offer only 1.107M/s.

The next benchmark-only diagnostic therefore preserves the complete durable route and adds an opt-in per-client window.
It first uses four independent callers whose windows still sum to 65,536, separating caller parallelism from queue depth;
only then may it raise total open requests. The existing canonical global-window mode remains unchanged, and any larger
window must retain exact byte-bounded cache/fallback evidence rather than tune a count cap to this workload.

Artifacts: log/host snapshot SHA-256 `b887a2bdb5aeabf445614c20c82c8becf0c2d324b450e76ca5567cfce6f0ded1`,
`6f000fbc9f702d560379b683176a5bda4f74717e2f5e348c0af203cc03aa6349`.

## E400-E403: independent-client diagnostics expose target-filter scan amplification

An opt-in benchmark-only mode gives every actual sender client its own request semaphore while preserving the canonical
global-window default. Threads belonging to one client share that client's semaphore. E400 first keeps total admission
fixed at 65,536 by running four clients with 16,384 permits each. This is deliberately not a larger-window candidate:
it separates client ownership from total queue depth.

| Run | Clients / callerthreads | Request window | Warm-up | Measured E2E | Offering union | Reading |
| --- | ---: | --- | ---: | ---: | ---: | --- |
| E399 | 2 / 2 | 65,536 global | 771,535/s | 909,339/s | 1.107M/s | prior intact diagnostic |
| E400 | 4 / 4 | 16,384 per client; 65,536 total | 590,822/s | 504,442/s | 1.133M/s | hard partitions cannot lend temporarily unused permits; p99 grows to 357 ms |
| E401 | 4 / 4 | 65,536 global | 309,237/s | 716,435/s | 0.945M/s | four clients remain substantially slower even without partitioned admission |
| E402 | 2 / 4 | 65,536 global | 316,550/s | 804,287/s | 1.049M/s | concurrent calls on the same two gateways add contention instead of transport capacity |

All three new runs verify exactly 10,485,760 ordinary durable results and zero model/global events. Their changing and
thermally loaded host state prevents a throughput ranking against E399, but the mechanism conclusions do not depend on
that ranking: splitting one fixed window is harmful, adding clients adds route work, and adding callerthreads does not
create another independent gateway transport lane. The per-client benchmark switch remains diagnostic-only and the
canonical benchmark default remains unchanged.

E403 then adds JFR-only accounting around every cache scan. The host is explicitly non-qualifying: even its profiler-free
warm-up reaches only 318,907/s, Java receives about 3.5 cores while Docker's backend/VM use another 3.5, and the measured
batch-JFR phase reaches 294,390/s. Its scan counts are nevertheless exact and internally correlated:

| Result-store source | Scanned envelopes | Returned target matches | Scan/return ratio |
| --- | ---: | ---: | ---: |
| Runtime result cache | **20,203,902** | **10,097,278** | **2.001×** |
| JDBC result fallback | 767,618 | 388,482 | 1.976× |
| Combined | **20,971,520** | **10,485,760** | **2.000×** |

The two SDK result trackers jointly receive exactly the expected 10,485,760 results, but target filtering examines
20,971,520 envelopes to do so. This is not an inference from CPU samples: it is the `MessageStoreBatch.scannedSize()`
versus returned-message count of every cache and fallback scan. `DefaultTrackingStrategy` currently gives an opaque
predicate to the store, so each target-filtered tracker advances through the shared global log and tests every result.
Adding real clients therefore multiplies Runtime target-filter scan work linearly even though each result belongs to
only one client. That explains why four clients are not a fair way to remove the benchmark driver's two-caller ceiling
and selects target-aware result scanning/routing as the next production mechanism to design. Any implementation must
preserve global scan progress, null/tracker/client target semantics, byte limits, filtering, ordering and long-poll
behavior; a target-only happy-path cache is insufficient.

Post-analysis found an additional benchmark-identity error: E403 omitted
`fluxzero.messageStoreCacheSize.command=1048576`, which was part of the E386/E389 exact-weight pin. Its command cache
fallback events contain exactly 65,536 entries, proving the legacy count ceiling—not the 64-MiB byte ceiling—was active.
The result target-amplification counts remain exact and useful, but E403 cannot select a production optimization for the
healthy pinned route. This omission continues through E416 and is corrected at E417.

Artifacts:

- E400 log SHA-256: `d5ff5c17ecd9cdffb89d6e02ea811681344c500ee0446fa58d2855eb62178422`;
- E401 log SHA-256: `8bb4dfcdb386ee932f534a0a592bf31a8aca2b9c6e0e52ec683afea8ab83807d`;
- E402 log SHA-256: `a4a33410a755c988ffa741e65f517cb958104c587fc1e357c0b5809b6912fe27`;
- excluded E403 log/JFR/summary SHA-256:
  `eb80bd769c48d93f81b9df041c788904518b9c6dde3a03970d2e33bbaeee7dcf`,
  `e298cfa5315814e3d5d59cb84e078a049977b5b2c07bec2c2f79639372d5e26e`,
  `609877e7539d1910380f1b5d1d59703378f04e1b28c4fddc181cb00ee4c536f0`.

## E404-E412: target-aware cache indexing under the wrong command-cache baseline

E403 selects a cache-level target hint because two result trackers examine almost exactly two envelopes for every
result delivered. The candidate wraps the unchanged tracking predicate with the eligible tracker/client targets. A
supporting cache may use those targets to preselect candidates, but the original predicate remains the final authority;
global `scannedSize`, last scanned index, byte boundaries, order, untargeted-message behavior and cache fallback all
remain unchanged. Tests compare the hinted and generic scans over multiple cache batches, inclusive/exclusive starts,
row and byte limits, null targets and deliberate hash collisions.

E404 is an old-predicate control on an already loaded host. Like E403, it and every run through E416 accidentally use
the legacy 65,536 command-cache count ceiling instead of the pinned 1,048,576 defensive ceiling plus 64-MiB byte bound.
E405 indexed materialized `String` targets and was stopped
during warm-up: it eagerly materialized envelope-backed targets across the cache and is rejected by construction. The
next version hashes encoded target bytes without materialization and stores sorted hash/position pairs. Its internally
matched, still non-qualifying bracket is directionally positive:

| Run | Variant | Warm-up | Measured E2E | p50 / p95 / p99 / max ms |
| --- | --- | ---: | ---: | ---: |
| E406 | full target hash + sorted positions | 314,799/s | **877,975/s** | 68.449 / 92.114 / 117.274 / 187.733 |
| E407 | exact old generic predicate | 319,881/s | 764,503/s | 76.039 / 109.422 / 183.101 / 342.133 |
| E408 | full target hash + sorted positions | 316,975/s | **855,971/s** | 69.674 / 98.630 / 118.021 / 153.234 |
| E409 | exact old generic predicate | 794,372/s | 716,749/s | 83.817 / 111.277 / 125.563 / 163.290 |

The candidate geometric mean is 866,903/s versus 740,247/s for the control, a 17.11% relative difference. That is not
accepted as a production claim: all four runs are below the fresh 963,731/s pin, use the wrong command-cache count
ceiling and show the host/route changing state. E410's batch profile also shows that the full-hash/sort implementation consumes *more* cache service
than the generic scan: approximately 3.87 versus 3.12 aggregate result-cache seconds per 10M measured results.

E411 therefore replaces the full hash with encoded length plus the first eight UTF-8 bytes and replaces sorting with a
primitive hash-to-linked-position index. Exact target equality still resolves collisions. The uninterrupted full run
remains in its slow warm-up state and reaches only 319,790/s after a 345,271/s warm-up. E412 repeats the same binary with
a full 10M warm-up, then starts batch JFR before a short 2M measured phase. That barrier allows the route to recover and
the measured phase reaches 791,940/s. It is diagnostic-only: the barrier means it cannot contradict E411 or establish
stable uninterrupted throughput.

The E412 profile does show that the cheaper index is the best local implementation so far:

| Cache service, normalized to 10M results | Generic E403 | Full hash/sort E410 | Prefix/linked E412 |
| --- | ---: | ---: | ---: |
| result-cache scan service | ~3.12 s | ~3.87 s | **~2.69 s** |
| command-cache scan service | ~0.46 s | ~2.37 s | ~0.49 s |

This is only about 0.4 aggregate CPU-seconds of result-cache headroom per 10M results, so it cannot by itself justify a
large E2E throughput claim. Before any production checkpoint, the prefix/linked version requires an uninterrupted clean
host bracket against the exact old predicate. During E411/E412 Docker's VM retained roughly 50-85% host CPU after the
benchmark database had accumulated about 147 GB of block writes, so another qualifying run is deferred until that
external work is idle. The candidate also underwent an adversarial contract review: the cache hint must never replace
`Tracker.canHandle`, because a message explicitly targeting `trackerId` intentionally bypasses normal segment
filtering. A focused regression test now fixes that invariant before further measurement.

Artifacts:

- E404/E405 log SHA-256: `ae8a581349dacd536e302681b2f9c6aaeb6d40daeee299182a0ef4a05054a732`,
  `70b604e19aabdd2d3bbdd641b03dc37aff3448935e32b486aa70cf7ab41d4030`;
- E406-E409 log SHA-256: `296eb469418e9daf869681a8f322a328a391797107fc35ca080f30f3e46e3ef9`,
  `1620aafc2eece8d22ee8e8c2f126c8e789105eca5b589caa112f408ee2891e30`,
  `cddbbd0bda9513968dafe3a943dce99d7d39ce357d9b8fb4817a0f30e14177ee`,
  `4c73da85f1bce7eae3ab41d01ebe3d3fccbd6ec47da35f45306ed9c73351d368`;
- E410 log/JFR/summary SHA-256: `a8359f6c44ecf2eb1a8493c18fc06b8f529330f9a6d3b1a7ad193397b266c71f`,
  `73f4b5c129b39c8f21098058a5eb0b8d32030f657d6c871fe20444be82c9d2b8`,
  `b481ef8e15d501efccfac8d5eb96761f994cb04ff120f3442e6444441888f750`;
- E411 log SHA-256: `0c9b51dd60882ba7d82f544ac97648463893c560124b39d0f917d53e42737719`;
- E412 log/JFR/summary SHA-256: `7b97153aad7138ca3401c76fbe911b07706ab14cb9b718bf75a56532d2c37eef`,
  `c35f584c458b19c1ad57f5671f440cdce3f4920ecb1340a0b0cef196eeef7378`,
  `dac13aa210c5776b019edb573052f1d5813ec34752097b47e12ac6ee3a28f3da`.

## E413-E420: four-client screens expose the baseline error and leave a +6.09% clean-host hypothesis

E413-E416 use four real sender clients and a shared global 65,536 request window. They are full durable command/result
E2E runs but deliberately short: 2,097,152 warm-up and measured commands. They still omit the pinned command-cache
count property. The target-aware candidate strongly mitigates that unhealthy cache feedback:

| Wrong-baseline pair | Generic control | Target-aware candidate | Relative |
| --- | ---: | ---: | ---: |
| E413/E414 | 508,628/s | 688,753/s | +35.42% |
| E416/E415 | 613,439/s | 670,386/s | +9.28% |
| geometric mean | 558,581/s | 679,507/s | **+21.65%** |

This is not the desired claim. It says the target index can soften a command-cache cliff while serving four result
trackers; the accepted byte-bounded command-cache policy is specifically meant to prevent that cliff. E417 therefore
restores only `fluxzero.messageStoreCacheSize.command=1048576`; the 64-MiB byte ceiling remains unchanged. The pulsing
Docker VM still excludes absolute throughput, and the corrected ABBA screen is mixed:

| Correct-baseline pair | Generic control | Target-aware candidate | Relative |
| --- | ---: | ---: | ---: |
| E417/E418 | 811,492/s | 782,150/s | −3.62% |
| E420/E419 | 705,183/s | 823,452/s | +16.77% |
| geometric mean | 756,472/s | 802,535/s | **+6.09%** |

Every run verifies exactly 2,097,152 durable ordinary results and zero model/global events. The changing pair signs
mean +6.09% is a hypothesis, not a checkpoint. No further target-index implementation work is selected before the
full-size canonical bracket in E421-E424.

Artifacts:

- E413-E416 logs: `bbe98b79f2a94318a600cc5985297166bd10b65a5b129a36f34b0b1ccfb308b2`,
  `f388abef5f837f5950a8fc040384b45dc9956afb63430fbf5ae532db0b69c701`,
  `330974fa39f0289a625d06bc8d36e6001cabb0ce832b613ef4dbbc7c93a65995`,
  `d6aee9fc2e3ea6c69fbfaa84c8773495b42817246e8f9a092508a19bd8591d4d`;
- E417-E420 logs: `6508ccc7a751f6f5910a0fec2874de866587723356bc8cde3de14e736f584b99`,
  `850ac8870c9c7a45beeada2858a786e41a7fc3d463350fbf99ebbacb736e810e`,
  `5598bf057dd2d7ea5e4bc84412f10a5762f8a3d12e2c2c9fc53327714e0d20cd`,
  `e1b18e943f3c596da398af0bff13ad843a7a174c54895d99082d499eb5c392dd`.

## E421-E424: correct canonical bracket rejects target-aware cache indexing

The benchmark now prints its effective message-store identity before startup, including backlog size, command/result
cache count and byte ceilings, and per-store `maxInFlightBatches`. This makes the E403-E416 omission directly visible
in every future log. E421-E424 then use the exact pinned identity: two sender clients, global 65,536 request window,
10,485,760 warm-up and measured commands, command-cache count ceiling 1,048,576, unchanged 64-MiB byte ceiling,
4,096-message backlog and unbounded in-flight storage batches.

| Full canonical run | Variant | Warm-up | Measured E2E | p50 / p95 / p99 / max ms |
| --- | --- | ---: | ---: | ---: |
| E421 | generic control A1 | 787,986/s | **921,689/s** | 64.083 / 83.961 / 156.396 / 242.664 |
| E422 | target index B1 | 833,535/s | 899,919/s | 66.326 / 90.598 / 106.453 / 148.042 |
| E423 | target index B2 | 826,071/s | 908,906/s | 66.433 / 86.726 / 96.401 / 131.451 |
| E424 | generic control A2 | 831,005/s | **902,459/s** | 65.781 / 88.873 / 111.266 / 224.445 |

The generic-control geometric mean is **912,023/s** versus 904,401/s for the target index: **−0.84%**. Every run
verifies exactly 10,485,760 durable ordinary results and zero model/global events. The lower absolute level than the
963,731/s pin is consistent across the bracket and coincides with the already recorded pulsing Docker VM; it does not
change the matched decision.

The target-amplification observation remains architecturally valid for very large tracker counts, but the tested
cache index adds complexity and a target-mutation/ownership assumption without improving the current qualifying route.
All target-aware production/API code and its feature-toggle properties are therefore removed. JFR cache-scan/fallback
accounting remains because it diagnosed both the amplification and the wrong cache baseline without changing normal
behavior. The next optimization returns to the correct pinned route's measured hard limiter: durable result-store
service and its commit amortization.

Artifacts: E421-E424 log SHA-256
`baa8a60c26b3d3dc55abe5ab3c1eed856948e75243cbff727822c0721a26a641`,
`e69d472c7b9d6e731592d58921bb6f6de176d11042beb4dc2b8d052f8135b565`,
`4b225a3f1f37c5e066c46686dcffe9dbb98def993a9dc6490a36167330deafde`,
`7064d46ecd49bb4547fc74c2de9424055bba457f7c2990ba5f3025b88a9c3089`.

## E425-E430: direct cache-monitor delivery is locally cheaper but not an E2E checkpoint

E425 profiles the restored generic-cache control on the exact canonical identity. It reaches 804,923/s under batch
JFR. The durable result writer is again the limiting storage service: 0.855M messages/s over 4,077 transactions with
2,571.9 results per transaction. Its mean store/commit times are 3.010/2.712 ms. The command writer reaches 1.028M/s
over 2,560 full 4,096-message transactions. Result tracking performs 1,219 cache-tail fallbacks; those JDBC reads scan
3,108,330 rows to return 1,535,762 results and consume about 10.9 aggregate thread-seconds.

The narrow E426 diagnostic invokes `CachingMessageStore.onUpdate` directly from the already ordered delegate monitor,
instead of putting the same update through the cache's additional notification backlog. It preserves the full durable
route and exact result count. E426 reaches 906,795/s under JFR and reduces result JDBC input by 8.3% to 2,851,494 rows,
but every writer service also becomes roughly 10% faster. That broad shift indicates a changing host/PostgreSQL state,
not a cache-hop effect of that magnitude. A full profiler-free ABBA bracket therefore decides the candidate:

| Run | Cache monitor delivery | Warm-up | Measured E2E | p50 / p95 / p99 / max ms |
| --- | --- | ---: | ---: | ---: |
| E427 | direct A1 | 831,621/s | 851,172/s | 69.215 / 93.146 / 185.805 / 262.920 |
| E428 | backlog B1 | 731,564/s | 807,744/s | 73.095 / 97.447 / 205.526 / 284.099 |
| E429 | backlog B2 | 776,920/s | 904,466/s | 65.237 / 86.453 / 200.158 / 254.987 |
| E430 | direct A2 | 805,377/s | 871,861/s | 68.523 / 90.944 / 132.746 / 188.511 |

The direct geometric mean is 861,454/s versus 854,738/s for the backlog control: **+0.79%**. All four runs verify
exactly 10,485,760 durable ordinary results and zero model/global events. The change removes an asynchronous boundary
whose independent ordering and failure behavior would require a broader contract proof, while the observed E2E delta
is smaller than the within-bracket host variation. It is therefore rejected and fully removed. The useful causal
finding is narrower: cache notification lag contributes some fallback work, but it is not the >1M/s breakthrough. The
next experiment stays anchored on E425's result writer and transaction-size measurements.

Artifacts:

- E425 log/JFR/summary SHA-256: `70d5fae689cb32dc77c97a3e9ab5d3d44dce4d497e369deaa16df1c447b3bf86`,
  `f4b0a1a6f83c7ff2cad808e0de50158dd5432ac71707ccd490e6c0f1ec09e070`,
  `93cfb366ca156687e37804b9bd533d3db97710003a3b4a0a7e1ee722f429e3b5`;
- E426 log/JFR/summary SHA-256: `411cb829833a34cf380fd1192752d56412cc4126e97c565e9b9f508b906978cc`,
  `718ddbbf7c6185a6a94e8c85f5e2e7f4e44e41c625ee3c2634ec68c08f9cecab`,
  `9201b3d1ac7e22194faf42de9d47598342001e257a241c2899156c58f0096ce7`;
- E427-E430 log SHA-256: `ce83d47db8c8ab4d671127276366ba9f242819c448361331525e17132f2a77f9`,
  `6f9899a10d5528e6b511cdf8330275dd3f961f40604f90949c0a01c6af8b798f`,
  `7eb7f1c870508db544899f7704496047a26a84d35b03e406de7a2956b3b78807`,
  `eeb0a572b88f31b76d621f194622958b455bf3f25aab49dd3ebe1b0f9d3491ed`.

## E431-E435: result-only 8,192-message batches isolate and reject transaction sizing

E431 adds a diagnostic table-specific maximum batch size so only the result store uses 8,192 messages; commands remain
at 4,096. There is still no timer or artificial wait. Under batch JFR the local mechanism is unambiguous:

| Profile measure | E425 control 4,096 | E431 result-only 8,192 | Change |
| --- | ---: | ---: | ---: |
| command transactions / mean rows | 2,560 / 4,096.0 | 2,560 / 4,096.0 | unchanged |
| result transactions | 4,077 | **3,106** | −23.8% |
| results/transaction | 2,571.9 | **3,376.0** | +31.3% |
| result writer | 0.855M/s | **0.932M/s** | +9.0% |
| result commit capacity | 0.949M/s | **1.329M/s** | +40.0% |
| profiled E2E | 804,923/s | **834,686/s** | +3.70% |

This removes the command-side confounder from E390/E391, but the full profiler-free route remains the decision maker:

| Run | Result max batch | Warm-up | Measured E2E | p50 / p95 / p99 / max ms |
| --- | ---: | ---: | ---: | ---: |
| E432 | 4,096 A1 | 721,512/s | **838,912/s** | 71.511 / 95.266 / 115.211 / 152.347 |
| E433 | 8,192 B1 | 680,641/s | 785,588/s | 74.634 / 109.098 / 164.540 / 270.777 |
| E434 | 8,192 B2 | 697,682/s | 803,073/s | 73.237 / 106.068 / 171.496 / 242.404 |
| E435 | 4,096 A2 | 698,475/s | 758,522/s | 79.201 / 105.008 / 125.243 / 169.015 |

The control geometric mean is 797,705/s versus 794,282/s for result-only 8,192: **−0.43%**. Every run verifies
exactly 10,485,760 durable ordinary results and zero model/global events. Thus larger result transactions improve local
writer service but do not raise the closed-loop route; transaction sizing is not the currently selected E2E lever. The
temporary per-table batch-size property and code are removed. Existing independently justified per-table weight and
in-flight limits remain unaffected.

Artifacts:

- E431 log/JFR/summary SHA-256: `a49de1eb55f815e291cf23d78bee66b588c93da9eb6bab67467b9df9a2aba13a`,
  `787f845697f92899749c255110032077b15cbc16b210ccd37946458add034218`,
  `521127bed6f444769f2fa6c0d11debb7fa4fe2f5a2b2ab4c10da3fe7984ebbe5`;
- E432-E435 log SHA-256: `d80992579a52b7471755a4b7f04cc7b5c61c3701f4c7aad508af506e0b724d0e`,
  `b3e946075e38fe50f4cf28ccab6441e8b484e9e981cf1642978a896c97338229`,
  `4635eab47f840235a553390505973f228764ef929d24dde6ca8d44782ad400bf`,
  `f01a55fec0ee71fbf9475a82b3fd9846dbb3e22ae7eda51c976b940965043ef4`.

## E436-E439: exact cache-tail parking removes physical polling reads

E436 revisits the benchmark request window after exact envelope weights removed the old count-cache cliff. Both cache
count ceilings are raised defensively to 1,048,576 while their exact 64-MiB byte bounds remain unchanged; the request
window increases from 65,536 to 98,304. Against the immediately preceding E435 low-state control it is throughput
neutral (760,184 versus 758,522/s), while p50 rises from 79.201 to 111.460 ms. The extra requests only add queueing and
are rejected as a service-capacity mechanism.

The E425 profile showed 1,219 result-cache tail fallbacks and 3.11M physically scanned result rows. A cache miss at the
tail was ambiguous: it could mean either that no newer durable row exists or that the asynchronous cache monitor has
not processed it yet. E437 adds an exact committed-index bound to `JdbcMessageStore`, materializes cache updates on the
commit thread and treats a covered empty tail as authoritative. This cuts result fallbacks to 96 and physical result
rows to 0.40M, but moves cache work onto the commit lane and creates more eager tracker scans. Profile E2E reaches
818,066/s versus E425's 804,923/s; this direct form is diagnostic-only.

E439 keeps the original asynchronous cache worker. At an empty cached tail, a JDBC-backed delegate with an exact
committed bound now parks the existing long-poll request until that same worker publishes its monitor update. It neither
polls JDBC nor sends an empty response. The resulting profile is the clean mechanism proof:

| Profile measure | E425 control | E439 parked cache tail | Change |
| --- | ---: | ---: | ---: |
| physical command scans | 297 / 585,728 rows | **0 / 0** | eliminated |
| physical result scans | 1,219 / 3,108,330 rows | **0 / 0** | eliminated |
| result tracking scan service | 1.340M scanned/s | **3.684M/s** | +174.9% |
| result writer | 0.855M/s | **0.893M/s** | +4.4% |
| profiled E2E | 804,923/s | **842,745/s** | **+4.70%** |

E438 combines the first direct-cache form with a global 8,192-message backlog but starts in a new system slow state:
warm-up is only 534,138/s, command preparation becomes 2–4× slower and measured E2E is 406,959/s. It is excluded from
all candidate comparisons. E440-E443 then run a full candidate-control-control-candidate bracket through the same low
host state. Absolute values do not replace the 963,731/s healthy pin, but both adjacent causal comparisons have the
same large sign:

| Pair | Legacy tail JDBC fallback | Parked cache tail | Delta |
| --- | ---: | ---: | ---: |
| E440/E441 | 498,424/s | **664,270/s** | **+33.27%** |
| E442/E443 | 664,030/s | **815,156/s** | **+22.76%** |
| geometric mean | 575,299/s | **735,856/s** | **+27.91%** |

Every run verifies exactly 10,485,760 durable ordinary results and zero model/global events. The result is supported by
the exact profile mechanism rather than absolute host throughput: parked tail reads eliminate all physical tracking
polls and preserve the asynchronous cache worker. Tail parking therefore becomes the default for delegates that expose
an exact committed bound; `fluxzero.cachingMessageStoreAuthoritativeTail=false` retains the legacy fallback as an
operational escape hatch. Stores without that bound remain unchanged.

Artifacts:

- E436 log SHA-256: `fd7833563907a9181d4f733b62415d2cbf4498e0a265db0004c427891618238f`;
- E437 log/JFR/summary SHA-256: `6c72126d02bf142f160691403f7212d3f7e6c9175859f8632af89a703c8df019`,
  `1bd100329b66d11e21766a8f027cb232ed5061c16bdc573a5caab906eb000385`,
  `8e39b8ea54f1ca34c6a0e8db9a1eb57e77b9f44e637c41ce4eba844c82361ca8`;
- excluded E438 log/JFR/summary SHA-256:
  `76e1abd9a4e0b3b41f3c4e8ccb4f62cbfacd6faacd7aa313a115b79c3eb59222`,
  `0c0c908d480503cf6f950b30c1cecbdac2c595cde82a11a978d0e87a59265db5`,
  `300c7bfa3f66337f255c18d865dc749bf1e2ee3fa21c6e603a0288724b7d452c`;
- E439 log/JFR/summary SHA-256: `32edcb942b67ec47d2b9e54a8088e156c914cd7817a4f1061f4a4a813c43ac6e`,
  `70f2288de7aa8766793c05d70cd8d83a6339cf392b65f6fb5fd12e276417e6ec`,
  `4bdb2f9144911e4bf511ebc4d62a0c8ba4c29adb0241e813f860c4c3bea7cc9e`;
- low-host E440-E443 log SHA-256:
  `ff93add96ece33db085dd50e6c9f650fd5d81cd0a1f01f633d2c6f37590b22f9`,
  `7f317d84e831199c0dc08fad4edcc32955c85e2d71aa33032ae61bff7ea246cc`,
  `88d8c1f131c84a69d86c5705c67b0fa49e67c092a68159f56826ab65760c33ea`,
  `c3542f0d3c9fbd1202e193e0afc16ba8563bb94ed8bbb43d8c1bebf1b25238c5`.

## E444-E450: historical replay separates host loss from production behavior

The low absolute E440-E443 band could not establish whether the machine had slowed down or later diagnostic code had
regressed the E386/E389 control. E444-E450 therefore keep the exact E386 identity: Java 25, embedded Runtime, two
sender clients, an 8-GiB fixed heap, a global 65,536-request window, 32-byte payloads, a 4,096-message backlog,
unbounded in-flight storage batches, a 64-MiB command-cache byte cap and 10,485,760 warm-up plus measured commands.
Every run again verifies exactly 10,485,760 durable ordinary results and zero model/global events.

The host first changes materially without a code change. E444's current legacy-tail control reaches only 591,429/s;
after the machine settles, E446 reaches 835,372/s with the same binary and configuration. E445 and E447 bracket that
moving state and are retained as diagnostic-only rather than used as a precise candidate estimate:

| Run | Runtime / tail mode | Warm-up | Measured E2E | p50 / p95 / p99 / max |
| --- | --- | ---: | ---: | --- |
| E444 | current, legacy JDBC tail fallback | 637,337/s | 591,429/s | 88.231 / 180.325 / 254.477 / 516.033 ms |
| E445 | current, parked tail | 747,012/s | 875,064/s | 68.572 / 91.590 / 104.659 / 135.500 ms |
| E446 | current, legacy JDBC tail fallback | 756,437/s | 835,372/s | 70.685 / 102.135 / 125.138 / 177.704 ms |
| E447 | current, parked tail | 806,866/s | 783,368/s | 75.115 / 105.578 / 137.827 / 286.960 ms |

To distinguish host loss from a Runtime regression, E448/E449 compile committed Runtime `d5abe0a7` in an isolated
worktree while keeping the current SDK and benchmark driver. This is the pre-candidate implementation used by the
E386/E389 route. It also falls to 621,899/s before a forced checkpoint. After the dedicated benchmark PostgreSQL
container finishes that checkpoint, E449's old Runtime reaches 830,972/s, only **0.53% below** E446's 835,372/s
current legacy control. There is therefore no material Runtime-code regression hidden between the historical pin and
the current control; the old implementation itself loses about 13.8% versus its 963,731/s healthy-host geometric mean.

E450 immediately follows E449 in the same post-checkpoint state with only cache-tail parking enabled:

| Matched replay | Old committed Runtime | Current parked tail | Change |
| --- | ---: | ---: | ---: |
| E449 -> E450 | 830,972/s | **912,280/s** | **+9.78%** |

E450 records p50/p95/p99/max latency of 65.914/86.404/98.003/121.042 ms. It is 1.02% below the earlier E421
400-series high of 921,689/s despite the replay control being well below E386/E389. Applying the measured factor to
the healthy control would imply about 1.058M/s, but that value is explicitly a projection, not a qualifying result.
The stable measured >1M no-model gate remains open.

Artifacts:

- E444-E447 log SHA-256: `8f074ac0eeed72d6b046728ba17c9f9d564f98d1df83f2b4833716cf9aeefc38`,
  `a23a9ca819047f4e794cd24899f026c04850c84ce19892d0be727e4ffeecf85e`,
  `75b8b80c8fa516c2a24d06cfa21c5c13bcc097aa5a0911c5d6bcea26751fa6a2`,
  `daa7be3b649ded2deb89b0f3863d781d22b5bb878aa639a56c09c50fa4cdac56`;
- E448/E449 old-Runtime replay log SHA-256:
  `2710bcbe5dcda1ecd6502bd02f5fd1dad9c90b43ad8a607045bde474667d81f2`,
  `543311d87122010755e4d1df4a37f7197a59cb2e3b37bb2ef54782571438cbd5`;
- E450 log SHA-256: `24ef4cf6678498ddbbb871453910438a148aec1837428364a5df916179313493`.
