# Durable no-model E2E change log

This log isolates the durable command/result route used before event and model work is reintroduced. It records the
exact changes between the E279 high state and the later low state so a benchmark-environment regression cannot be
mistaken for a production regression again.

## Live scoreboard

| Campaign state | Current evidence |
| --- | --- |
| Accepted no-model production pin | **962,888 commands/s** (E315, profiler-free, full durable command/result route) |
| Fresh current-production repin | **905,605 commands/s** (E365, profiler-free; 10,485,760 exact results, p50/p95 63.305/85.260 ms) |
| Best recent healthy batch-profile | 900,736 commands/s (E333, diagnostic async-position candidate) |
| Current production candidate | SDK `5f4d2c76`: exact per-submission Backlog completion plus optional `maxInFlightBatches`; Runtime remains intentionally unbounded for the checkpoint pin |
| Current focus | Screen Runtime message-store `maxInFlightBatches=4/8/16/32` with command/result transaction shape and cliff diagnostics recorded for every profile |
| Exit criterion before events/models return | Stable profiler-free no-model throughput **at least 1.5M/s**, with **2.0M/s** as the structural-headroom target |

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
