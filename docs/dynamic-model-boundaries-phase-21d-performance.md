# Phase 21d — one-event end-to-end performance journal

This is the running evidence log for the 500–600k one-event SDK-to-runtime release gate. It deliberately distinguishes
commits, events and transport requests. A result is comparable only when its input, output, durability, cache mode,
batch shape and correctness checks below match.

## Benchmark definitions

| Name | Entry and exit | One commit contains | Included | Excluded |
| --- | --- | --- | --- | --- |
| Phase 21c bulk event gate | direct `JdbcModelCommitStore.commit` to durable completion | 100 ordered events targeting the same independently addressable model | model streams, state/sequence boundaries, commit identity/result, update tracking, one global publication and stream membership per event and one JDBC transaction/result per 100 events | SDK, WebSocket, command/result logs, handlers, search |
| Runtime one-event gate | direct `JdbcModelCommitStore.commit` to its `CommitModelsResult` | one event and one target | the same runtime correctness/storage contracts, but an independently completed result per event | SDK, WebSocket, command/result logs, handlers, search |
| Low-level WebSocket gate | SDK `EventStoreClient.commitModels` through the production WebSocket endpoint to `CommitModelsResult` | one event and one target | compact request/result wire path plus the complete runtime one-event gate | command publication/tracking, model loading, `@Apply`, ordinary command result |
| Full E2E gate | SDK command gateway through ordinary result completion | one command, automatic `@Apply`, one commit, one event and one target | command log, tracked consumer, automatic target resolution, ordinary `ModelRepository` load, model cache, commit WebSocket, runtime stores/global log, ordinary result log/tracking | synchronous search in the `searchable=false` profile only |
| E2E no-result control | SDK `sendAndForget(Guarantee.SENT)` through a local completion probe after the automatic handler future | one command, commit, event and target | command persistence/tracking, model load/cache, `@Apply` and complete model commit | ordinary result publication, result log/tracking and sender result future; this can never pass the release gate |
| Cache-disabled control | same model command flow with `disableAutomaticModelCaching()` | one command, commit, event and target | ordinary event-sourced reconstruction on every target load | model cache and model-update tracker; this is a cold diagnostic, not a production candidate |

## Retained reference results

| Profile | Result | Exact meaning |
| --- | ---: | --- |
| Phase 21c bulk | 1,018,715 events/s; 10,187 commits/s | 10.4 million events, 100 events per commit; not an independent one-event result |
| Runtime one-event | 718,439 commits/events/s | one independently completed runtime commit and result per event |
| Low-level WebSocket one-event | 539,986 and 538,773 commits/events/s | repeated 1,048,576-request runs; exact result, membership and global-event counts |
| Sustained cold SDK reconstruction | 977,714–1,051,170 models/events/s with adaptive cache | 32,768 independent one-event streams through real `Fluxzero.loadModels`; reads keep up with the retained runtime one-event writer |

The briefly observed 588,557/s result used 2,048 memberships per physical stream block. It was rejected: random
batched reads remained around 5,900 models/s. Physical blocks stay capped at 1,024 memberships.

## 2026-07-31 corrected E2E baseline

All runs used a clean schema, 65,536 model IDs, 262,144 warm-up updates, 1,048,576 measured updates, a 65,536
in-flight bound, 32-byte payloads, `searchable=false`, two event-sourcing sessions and exact post-run membership counts.
The result-callback experiment used a 16,384-item callback chunk. The recent-update-cache experiment was installed into
the benchmark classpath before the retained runs; two earlier 149,361/s and 142,725/s runs accidentally loaded the
previous runtime artifact from the local Maven repository and are discarded.

| Control | Throughput | Interpretation |
| --- | ---: | --- |
| Full E2E with ordinary results and legacy soft-reference cache | 150,772 commands/commits/events/s | current honest release-gate baseline; still fails |
| E2E no-result control, legacy cache | 178,299 commands/commits/events/s | result removal alone does not expose a fast commit path |
| E2E no-result control, adaptive hard-reference cache without memory-pressure trimming | 188,249 commands/commits/events/s | adaptive cache is the intended default, but this diagnostic still omits results |
| Cache-disabled no-result control | 19,454 commands/commits/events/s | every update reconstructs its event-sourced target from JDBC; confirms automatic caching is essential |
| Trivial tracked command → handler → ordinary result control | 189,069 commands/s | wave-based control with no model load/apply/commit; useful decomposition, not yet the sustained primary driver |
| Full E2E under JFR | 126,455 commands/commits/events/s | profiling overhead included; use only for attribution |

### Production-default repeat

Before the opaque-envelope experiment, the complete gate was repeated twice with the actual intended production
defaults: `fluxzero.defaults.version=2026.07.27` (adaptive model cache with JVM memory-pressure control), result-callback
chunks of 64, the 64-MiB recent-model-update cache, ordinary command results and no benchmark-only cache override. Both
runs used the same clean-schema, warm-up, dataset and correctness checks described above.

The exact sources were SDK commit `5b4635f79efa19b6970970179851533a4d4521c2` plus dirty-diff SHA-256
`bfdfa8130e0cd327f275ba7c9d338b00b27455fb67d1e4fe81034e68b34c7a86`, and runtime commit
`1c3e8a9b2f0b440608a87ba6170f8cca2a20a2b5` plus dirty-diff SHA-256
`e038d9bccee0ae991cc96f27d9026817a1fad154764cd6973c713a39dc58f6c5`. The exact artifacts were installed before
compiling and running the benchmark.

| Run | Throughput | Result latency p50/p95/p99/max |
| --- | ---: | ---: |
| Production-default repeat 1 | 144,926 commands/commits/events/s | 382.476 / 530.819 / 629.165 / 702.534 ms |
| Production-default repeat 2 | 154,579 commands/commits/events/s | 373.313 / 513.939 / 585.000 / 693.880 ms |

The mean is 149,753/s. This confirms that the production adaptive-cache configuration is currently equivalent to the
150,772/s legacy-cache baseline within run-to-run variance; it does not yet provide a throughput milestone.

## JFR attribution

The first bounded full-flow JFR showed a redundant hot path: the model-cache tracker selected newly written packed
`model_stream` rows and decompressed complete stream blocks solely to create `ModelUpdate` values. One sampled
allocation represented 141.2 MiB. A byte-bounded recent-update cache with durable fallback removed that tracker stack
from the following JFR. Correctness tests cover live tracking and restart fallback. The cache remains provisional until
an isolated repeated A/B proves its total throughput and memory effect.

After that removal, the following sampled costs remain:

- command tracking re-read/decompression included a 442.1-MiB weighted Zstd allocation sample;
- the asynchronous model-stream locator included a 122.2-MiB weighted stream-block decompression sample;
- WebSocket result reception included a 91.7-MiB weighted decompression sample;
- `String(byte[], Charset)` led CPU samples at 8.49%, and runtime `MessagePackSerializer.unpackString` added 3.71%;
- runtime command fetching reconstructed metadata in `JdbcMessageStore.LtsRow.convert`; one
  `Metadata.ofStrings`/`Map.copyOf` allocation sample represented 346.3 MiB, alongside map nodes, strings and byte
  copies;
- the SDK tracking codec separately creates immutable metadata maps after transport decode.

JFR allocation weights are sampled estimates, not exact allocated-byte totals. They prove the stacks are material but
do not by themselves predict throughput improvement. The next isolated spike must compare non-empty representative
metadata and prove that an opaque runtime envelope preserves every metadata/upcasting contract.

## Experiment ledger

| Experiment | Outcome | Decision and reason |
| --- | --- | --- |
| Runtime packed one-event commits | 718,439/s | retained; preserves independent results, streams, heads, update tracking and global publication |
| Compact low-level WebSocket commit protocol | 539,986/s and 538,773/s | retained; this is the production request/result boundary below command handling |
| 2,048 memberships per stream block | 588,557/s create spike, reads about 5,900/s | rejected; write optics cannot destroy random-read locality |
| One event-sourcing session | about 152,271/s in the then-current E2E profile | rejected; worse than the two-session default |
| One command-consumer thread, older full-model profile | about 241,789/s in the then-current pre-correction profile | historical diagnostic only; superseded by the isolated tracked-command A/B below |
| 1-ms WebSocket request collection delay | about 150,024/s in the then-current pre-correction profile | rejected; latency reduced effective batching/throughput |
| Dedicated runtime JVM on the same machine | about 157,165/s in the then-current pre-correction profile | rejected as a local throughput optimization; CPU/JIT/GC separation lost more than it gained |
| Result callback chunks of 16,384 instead of 64 | 239,987/s to 266,073/s in the same pre-correction profile | promising but not yet a production default; modest gain and must retain latency/backpressure behavior |
| Disable automatic model caching | 19,454/s current control | rejected for production; proves the cache avoids a JDBC reconstruction per command |
| Adaptive model cache | 188,249/s versus 178,299/s no-result controls | retained direction; repeat with ordinary results and JVM memory-pressure control enabled |
| Recent committed-model update cache | removes cache-tracker stream decompression; restart fallback passes | provisional; retain only after repeated isolated throughput/memory A/B and slow-tracker eviction coverage |
| Opaque lazy metadata, first A/B | 183,200/s and 172,770/s; mean 177,985/s | +18.9% over the 149,753/s production-default mean, with lower latency; promising but below the independent 25% milestone, so profile and harden before retaining |
| Trivial tracked command, sixteen tracker threads | 236,364/s non-JFR; 239,895/s measured-only JFR | current command/result infrastructure ceiling before model work |
| Disable WebSocket compression on tracked-command control | 234,227/s | rejected; no throughput gain and therefore no justification for the bandwidth cost |
| Trivial tracked command, one tracker thread | 456,817/s | isolates overlapping segmented scans/decompression as the largest remaining command-path bottleneck; handler concurrency may not be sacrificed, so implement shared server-side scan/fan-out |
| Existing per-message `CachingMessageStore`, 65,536 entries | 530,056/s trivial tracked-command control | proves that a bounded hot window can eliminate repeated JDBC scans, but the skip-list representation allocates one node per message |
| First batch-native tracking cache | 22,934/s full E2E; large-cache run timed out | rejected implementation; linear lookup over accumulated batches made cache hits O(number of batches) |
| Batch-native tracking cache with binary batch lookup | 496,703/s tracked-command control; 242,226/s first full E2E run | promising: the complete model route improves about 33% over the approximately 182k/s pre-cache level; repeat, profile and verify reads/memory before retention |
| Repeated full E2E after adaptive-cache pressure | run stalled; follow-up JFR reported 21,578/s | invalid as throughput evidence; a captured thread dump proved a Java-level lock-order deadlock between `AdaptiveObjectCache` and `MemoryAwareCacheSupport` |
| Full E2E after deadlock fix, pressure trimming disabled diagnostically | 172,314/s | completed correctly, but disproves the first 242,226/s result as a stable milestone; this is a diagnostic only because production memory-pressure behavior remained disabled |
| Warm full E2E JFR after exact reactor install, pressure trimming disabled diagnostically | 214,365/s | valid attribution run with exact counts, not a release result; commit/result transport and its allocations dominate while apply preparation is small |
| Full E2E phase-timing diagnostic with one command-consumer thread | 178,286/s with timing enabled | diagnostic instrumentation lowers throughput; `prepareAndApply` averaged 3.428 us/command and `commitAndAfter` dominated wall time; its batch shape is not evidence for the sixteen-thread release profile |
| Sixteen-thread full E2E wire diagnostic | 181,853/s | 262,144 measured commands formed 24 conflict-free commit batches averaging 10,922.7 and reaching the 16,384 WebSocket maximum; small commit batches are not the current root cause |
| Serialized-metadata mutation overlay | paired in-process order `[copy, overlay, overlay, copy]`: 245,785 / 230,957 / 205,452 / 197,809/s | rejected and removed; throughput declined with elapsed run order, while the overlay was about 6% slower in one adjacent comparison and about 4% faster in the other, so it did not establish a reproducible gain |
| Exact-size tracking wire buffer through a sizing encode pass | 135,311/s versus 181,853/s in the comparable 262,144-update profile | rejected and removed; batches still averaged 14,563.6, so traversing and encoding every value twice cost materially more CPU than the removed growth/final copies saved |
| Fragment-list WebSocket reassembly with one final allocation | 64,646/s in the same 262,144-update profile | rejected and removed; all 16 commit batches held the full 16,384 requests, but p95 latency rose to 2.7 seconds, showing that retaining every callback fragment separately is much worse than the growing contiguous buffer |
| Production adaptive-cache GC-pressure sampling | 9,631/s before correction; 178,986/s short control and 193,827/s measured JFR after correction | retained correctness fix; sub-100-ms checks reset the sample baseline, so one young-GC pause caused a global 20% hot-cache trim despite healthy heap usage |
| Tracking-cache count 65,536 versus 262,144 | 235,388/s versus 235,057/s in paired 524,288-command runs | rejected larger default; exact diagnostics showed the same 16 command misses before the retained window, while roughly 440 misses requested data after the current cache head and cannot be fixed by retaining more history |
| Production-default 4,194,304-command endurance run | stalled after roughly 2.23 million commands; no throughput result accepted | exposed a real adaptive-cache recovery defect: sustained heap pressure evicted hot models and one prefetch collapsed 14,190 cold streams into a 127,710-event JDBC query; recording and thread dump retained for root-cause analysis |
| Physically bounded recent-message cache | 301,671/s over 4,194,304 complete commands, versus roughly 235k/s before the fix | retained in runtime commit `3f956315`; logically evicted batches had retained 4.40 GiB until late `ArrayList` compaction even though count/byte accounting said they were gone |
| Event-store session count, two/four/eight | 291,826 / 309,187 / 286,007 full E2E commands/s | four sessions gained only 5.9% and eight regressed while splitting transport batches further; session count is not the governing limit and the two-session default remains |
| WebSocket result callback chunk 64 versus 16,384 | 291,826 / 291,543 full E2E commands/s | rejected on the current pipeline; the formerly promising larger callback no longer improves throughput after the tracking-cache correction |
| Tracking-wire exact initial-capacity estimate | 290,935 full E2E commands/s | rejected and removed; a cheap byte-length pass avoided buffer growth but did not improve the adjacent 291,826/s baseline, so the extra codec complexity is not retained |
| Four-million no-result control after target-count-only cold cap | 36,039 commands/commits/events/s | rejected as a steady-state baseline but retained as defect evidence; adaptive eviction made every 1,024-target group retain roughly 65,000 deserialized events and caused repeated replay/cache-pressure amplification |
| Expanded direct-event replay without page-wide prepared-event retention | 213,902 commands/commits/events/s over 4,194,304 updates under JFR | retained candidate; the identical pressure profile is 5.9x faster and completes, while direct applies deserialize each event exactly once during application instead of retaining the whole page between classification and replay |
| Adaptive pressure from post-GC occupancy | 295,202 commands/commits/events/s over 4,194,304 no-result updates under JFR; 305,304/s without JFR | retained candidate; the adjacent direct-replay JFR was 213,902/s, so ignoring transient pre-young-GC Eden occupancy improves sustained throughput by 38% while GC-time and actual retained-heap pressure remain active |
| Reuse one serialized null payload per ordinary result batch | 299,435 full E2E commands/s over 1,048,576 updates versus the adjacent 291,826/s baseline | rejected and removed; automatic model commands do return null, but the 2.6% result is below the independent-complexity threshold and serialization of the four-byte null value is not a governing cost |
| One command-consumer thread after the tracking-cache and adaptive-cache corrections | 237,331 full E2E commands/s over 1,048,576 updates, with ordinary results and adaptive model caching | rejected; the larger per-fetch batches do not compensate for lost concurrent tracking/handling, so collapsing the production consumer to one reader is not a route to the target |
| Tracking `messageStoreBacklogSize` 4,096 → 32,768 | 148,116 full E2E commands/s over 1,048,576 updates | rejected; larger JDBC tracking-store waves nearly halved throughput and materially worsened tail latency, so the existing smaller production batch bound must remain |
| Repeated current production-default 1,048,576-update baseline | 209,183/s and 155,504/s | retained as instability evidence, not as a new baseline; identical runs formed respectively 199 batches averaging 5,269 and 162 averaging 6,473, with one-item tail batches and materially different latency |
| Four-million production-default JFR repeat | stopped after about 25,000 measured updates | exposed a cache-pressure policy defect: sustained allocation made GC time exceed 20% while post-GC heap remained about 30–50% occupied; the global trimmer evicted hot model entries and a 1,024-model reconstruction then spent tens of seconds in each global-event block lookup |
| GC-time trigger disabled diagnostically | 254,645/s over 4,194,304 full E2E updates under JFR | completed without cold-reconstruction collapse and proved the independent GC trigger caused the stall; not retained as configuration because adaptive GC pressure must keep working |
| Cache-unit and sparse-working-set pressure fix | 228,327/s over 4,194,304 full E2E updates under JFR | retained correctness fix; the production defaults completed with exact counts, while count- and byte-weighted caches no longer share an invalid numeric budget and GC time alone only trims a cache using at least 50% of its configured capacity |

The opaque-metadata A/B used SDK dirty-diff SHA-256
`64723f919696cb843a4fba614be6f89d84494f892fe0c76b01d520013cb0e972` and runtime dirty-diff SHA-256
`ffe96e9a32d92904345421cdeced750bd805a795ec01f213845953a242ca7926`. It kept tracking and model WebSocket version
numbers unchanged, wrote runtime MessagePack version 2, retained version-1 reads and selections, and passed 38 focused
common/protocol tests plus 137 runtime serializer/JDBC/model-commit tests. Result latency was
312.043 / 394.490 / 462.004 / 540.281 ms and 325.239 / 416.366 / 497.830 / 616.145 ms respectively. No commit is
accepted from this result alone: a production-default JFR, slow-path metadata tests and read-throughput A/B remain.

### Tracked-command ceiling and segmented-read amplification

The trivial tracked-command control was rerun after the opaque-metadata change. It still uses the production command
gateway, durable command log, normal SDK handler pipeline, durable result log, result tracking and one independently
correlated ordinary result per command, but deliberately performs no model load, apply or commit. With sixteen command
tracker threads it reached 239,895 commands/s under a measured-only JFR; a preceding non-JFR run reached 236,364/s.
Result latency under JFR was 141.924 / 269.952 / 366.309 / 399.999 ms at p50/p95/p99/max.

The JFR attributed the largest shared costs to runtime command-log reconstruction and forwarding: repeated MessagePack
string decoding, payload/metadata byte copies, tracking-wire re-encoding and block decompression. GC accounted for only
158 ms across fifteen pauses and is not the governing limit. Disabling WebSocket compression did not improve the same
control: it reached 234,227/s with 151.705 / 273.304 / 348.495 / 391.692-ms latency, so compression removal is rejected.

A decisive structural A/B changed only the command consumer from sixteen tracker threads to one. The otherwise identical
normal command/result control reached 456,817 commands/s with 115.846 / 161.773 / 179.608 / 183.402-ms latency. The
sixteen-thread route therefore loses roughly half its capacity before model work. Each tracker currently scans and
decompresses overlapping source ranges and then discards messages outside its claimed segment. The next retained design
candidate is a server-side shared scan per consumer cluster followed by segment fan-out to its waiting trackers. It must
preserve independent tracker lifecycle, positions, target/type filters, byte limits, ordering, long polling and failure
semantics; reducing handler concurrency is not an acceptable solution.

### Bounded batch-native tracking cache

The existing `CachingMessageStore` was first enlarged from its historical 100-message default. With its original
per-message skip-list representation and a 65,536-message window, the trivial sixteen-tracker command/result control
reached 530,056/s. This proves that the repeated JDBC read/decompression is avoidable with a bounded recent window, but
one skip-list node per retained message is not a responsible production representation for all message stores.

The first batch-native replacement retained immutable update batches but searched those batches linearly. It appeared
reasonable on small unit tests, yet the full E2E benchmark exposed the unbounded lookup cost: a 65,536-entry run fell to
22,934/s with roughly 41-second tail latency, and a 1,048,576-entry run timed out. That implementation is rejected and
must never be retried without an indexed lookup structure.

The corrected implementation keeps batches in index order and binary-searches both the batch and the message within it.
It retains count and byte/weight bounds, durable fallback on a miss, suffix replacement for overlapping updates and
whole/partial-prefix eviction. With the 65,536-entry, 64-MiB default it reached 496,703/s on the ordinary tracked-command
control (100.537 / 138.437 / 155.044 / 165.942-ms p50/p95/p99/max latency) and 242,226/s on the first complete automatic
model run (221.959 / 338.916 / 361.683 / 488.513 ms). The full result is not retained yet: it needs a repeated run, JFR,
slow-tracker eviction, restart fallback, memory and read-throughput verification.

The first repeat did not merely regress: it stopped making progress and eventually lost both event-store WebSocket
pings. A shorter measured JFR reproduced a seven-second interval with almost zero JVM CPU and no corresponding long GC
pause. A live thread dump then proved one Java-level deadlock. The model-prefetch backlog held the outer
`AdaptiveObjectCache` monitor and waited for its `MemoryAwareCacheSupport`; the JVM memory-pressure monitor held that
support monitor while invoking an eviction listener which called back into the outer cache. PostgreSQL was idle and all
sixteen command trackers were long-polling, confirming that neither JDBC nor WebSocket transport caused the stall.
These stalled runs are correctness evidence, not throughput measurements. Memory-pressure eviction notifications must
be delivered after releasing the internal cache lock, with a deterministic regression test, before the E2E repeat is
accepted.

That correctness fix is isolated in SDK commit `806d4dba308`: memory-pressure removal remains synchronized, but listener
delivery happens after releasing the internal support lock. The cache/support and model-cache-tracker suites pass 74
focused tests, including a deterministic reproduction of the former lock inversion. A subsequent full 1,048,576-command
run no longer deadlocked. Under the machine's then-current JVM memory-pressure signal it did, however, evict enough hot
models that one prefetch backlog began a very large cold event reconstruction. The run was stopped and is not throughput
evidence.

The same full route with only pressure-triggered trimming disabled—but the bounded adaptive cache, ordinary results and
all commit correctness checks still enabled—completed at 172,314 commands/commits/events/s. Latency was 301.133 /
489.110 / 678.623 / 852.814 ms at p50/p95/p99/max. This diagnostic shows two things: the first 242,226/s batch-cache run
was not a repeatable gain, and the next CPU profile must target the full warm model route rather than the already-fast
bare command/result control. Disabling adaptive pressure handling is not a release candidate; the final gate must pass
with production defaults and recover gracefully from real pressure.

After installing both exact Maven reactors, a second 1,048,576-command measured-only JFR completed at 214,365
commands/commits/events/s with 248.063 / 386.418 / 446.561 / 536.978-ms p50/p95/p99/max latency. The source identities
were SDK commit `273cda61c8cbfdffd5b9c16fbfac3b26d36811a3` plus tracked-diff SHA-256
`9e67cd33962a0b7fbf8400de07387e75d89549aed84ba48a1bb75d0d4154950e`, and runtime commit
`1c3e8a9b2f0b440608a87ba6170f8cca2a20a2b5` plus tracked-diff SHA-256
`f6faccca2f8d0e03c59642fa1349c3f52690b341883e9d5c7d0aec53cde97514`. The JFR is retained locally as
`/tmp/sdk-model-warm-route-1m.jfr`. It recorded approximately 36–37 GiB of allocation, 853 ms over 28 GC pauses and
material byte-array copying, Zstd, metadata wrapping/mutation, tracking-wire encoding and model-commit encoding costs.
Startup JFR instrumentation allocations are excluded from per-command conclusions.

An instrumented 262,144-command phase run then separated SDK work without changing the functional route. This run used
one command-consumer thread, not the sixteen threads in the release profile. Including seed and warm-up, model
preparation consumed 1,348.018 ms CPU for 393,216 commands, or 3.428 us/command, and post-commit SDK assembly averaged
0.490 us/command. Result matching and processing averaged 0.158 and 1.602 us/item respectively. The dominant
`commitAndAfter` phase received 128 result batches containing 367,325 items, an average of 2,869.7 items/batch. The
178,286/s total is not comparable as a baseline because detailed timers and logging were enabled. It supports the
narrow conclusion that target resolution, warm model loading and `@Apply` are not the largest sampled costs; its batch
count must not be projected onto the sixteen-thread route.

A subsequent wire-only diagnostic used the actual sixteen command-consumer threads, 65,536 model IDs, 65,536 warm-up
updates and 262,144 measured updates. It completed at 181,853/s with exact result, membership and global-event counts.
The measured updates formed 24 conflict-free commit batches: average 10,922.7, minimum 23 and maximum 16,384. There
were two event-sourcing sessions, but batches remained thousands of independent one-event commits per frame. The
production 16,384 request maximum and current collection policy therefore preserve substantial batching; increasing
batch delay or capacity is rejected as a response to the current bottleneck.

The next spike attempted to mutate serialized metadata through a compact base-plus-overlay representation, avoiding a
full map materialization for the SDK-owned correlation changes. Its paired order within one warmed JVM was deliberately
`[copy, overlay, overlay, copy]`, producing 245,785, 230,957, 205,452 and 197,809/s respectively for 524,288 exact
updates per variant. The continuing decline followed run order and shrinking effective batches; one adjacent comparison
favoured copying by about 6%, while the other favoured the overlay by about 4%. This is inconclusive and below the bar
for added metadata complexity. The overlay implementation and its benchmark switch were removed; 36 focused metadata
and wire tests pass after the removal. Two mutation-semantics tests remain as useful coverage for the already retained
lazy metadata format. The temporary spike source identity was unfortunately not snapshotted before removal, which is
an additional reason that it is ineligible for retention. Future spikes must record their diff hash before the first
measured run, not only before a retained commit.

The first post-journal spike replaced tracking-wire buffer growth with a sizing pass followed by one exact allocation.
Both passes invoked the same writer methods, making format drift impossible, and the focused wire/metadata tests passed.
Nevertheless, the otherwise equivalent 65,536-seed, 65,536-warm-up and 262,144-measured profile fell to 135,311/s,
with 335.604 / 889.469 / 922.463 / 929.653-ms latency. The 18 measured commit batches still averaged 14,563.6 items
and reached 16,384, so batching did not explain the loss. The second complete traversal is simply more expensive than
the copies it removes. The spike is fully removed and must not be retried as an exact two-pass encoder. The measured
common artifact SHA-256 was `5fc78d7b67397c42e34ce36466ccef5b3d4c15b9dbe55c8bc5e2b518c48f3107`; the shaded
runtime artifact was `4de8f3ddf82312e4673a7954daae0c1e0a7c966d97e8f8377d2d951ac9b4cbfc`.

A second copy-removal spike replaced both SDK and runtime `ByteArrayOutputStream` WebSocket reassembly with retained
fragment arrays and one exact final allocation. It preserved buffer positions and handled heap/direct, fragmented and
reused inputs in focused tests. In the same 262,144-update profile, however, throughput collapsed to 64,646/s and
latency reached 315.426 / 2,712.106 / 2,721.026 / 2,743.390 ms. Every measured commit batch still contained exactly
16,384 commits, ruling out batch fragmentation. The physical WebSocket implementations can deliver many callback
buffers within the configured logical frame/message; retaining each as a separate array/list entry is therefore much
more expensive than the current contiguous growth. The spike and tests are fully removed. Its SDK diff SHA-256 was
`ad8ca272b8f2bada8b5635f25102e17e8819bc73cb535fe7071d39f5d7704006`; its runtime diff SHA-256 was
`a3251edc7c6daf9781cce65e0a63005062f799c25a39d43af4204164868d473c`.

The first production-default adaptive-cache run after those rejections exposed a separate correctness defect rather
than another transport result. With 65,536 models, 131,072 warm-up updates and 524,288 measured updates, throughput
collapsed to 9,631/s and p95 result latency exceeded 19 seconds. A live thread dump retained as
`/tmp/sdk-model-production-stall-threads.txt` showed model prefetch reconstructing evicted streams while all command
trackers waited. The measured heap remained far below the configured 85% threshold. The GC-pressure controller was
resetting its sampling baseline on every sub-100-ms cache check, however, so a single roughly 213-ms young collection
could appear as nearly 100% GC time and evict 20% of the hot model cache.

The correction keeps heap-pressure detection immediate but evaluates GC pressure over one uninterrupted minimum
five-second window. Calls before that boundary no longer reset the baseline, and sampling is atomic across concurrent
cache users. Deterministic tests cover transient collections, sustained 20% GC pressure and the start of a new sample
window. The same production-default route then completed 262,144 measured updates at 178,986/s without JFR and 524,288
at 193,827/s under measured-only JFR. The latter latency was 271.816 / 374.850 / 440.380 / 584.619 ms and all 524,288
memberships and global events were verified. It saw six GC pauses totalling 496 ms, with a 123-ms maximum, without
losing the hot cache. Its exact sources were SDK commit `273cda61c8cbfdffd5b9c16fbfac3b26d36811a3` plus tracked-diff
SHA-256 `96f9363612a1d36c1b828705d06d673e2ca12b09a827ca781b0fb08699055a70`, and runtime commit
`1c3e8a9b2f0b440608a87ba6170f8cca2a20a2b5` plus tracked-diff SHA-256
`f6faccca2f8d0e03c59642fa1349c3f52690b341883e9d5c7d0aec53cde97514`. The recording is retained locally as
`/tmp/sdk-model-pressure-fix-524k.jfr`.

The earlier 233,897/s run with a 262,144-entry tracking-cache limit did not establish that a larger history window was
responsible for the improvement. A property-gated diagnostic counted every cache scan and classified its misses, after
which the instrumentation was removed. In two adjacent 524,288-command runs, the production 65,536-entry limit reached
235,388/s and the 262,144-entry limit reached 235,057/s. The command cache respectively reported 1,332 hits and 588
misses versus 1,211 hits and 599 misses. Both had exactly 16 misses whose cursor had fallen before the retained window;
436 and 447 misses instead requested an index after the current cache head. Result-cache classifications were likewise
equivalent. Retaining four times as many messages therefore consumes tens of MiB more per busy log without addressing
the actual misses. The larger default is rejected, while the existing bounded batch-native cache remains retained.
The measured sources after removing the diagnostics were SDK commit `6e32e815954f6afa2015dc53af532032fc6a3b65` plus
tracked-diff SHA-256 `ec40addf3b746b0ffb603145389568f438ca2573b6a6bb93baab4685bee319a4`, and runtime commit
`1c3e8a9b2f0b440608a87ba6170f8cca2a20a2b5` plus tracked-diff SHA-256
`f6faccca2f8d0e03c59642fa1349c3f52690b341883e9d5c7d0aec53cde97514`.

The next production-default endurance profile used 65,536 models, 524,288 warm-up updates and a planned 4,194,304
measured updates with 65,536 in flight, sixteen command consumers, ordinary durable results, automatic model caching,
one event per commit and `searchable=false`. It stopped progressing after roughly 2.23 million submitted commands and
is deliberately not reported as a throughput result. At the stall the durable logs contained approximately 2,293,760
commands, 2,162,944 global events and 2,228,157 results; the command/result distance was almost exactly the configured
in-flight bound. PostgreSQL showed no lock blocker and both JVM and database were otherwise nearly idle.

The captured thread dump and active query identified one model-event load containing 14,190 streams and a maximum of
127,710 events. The long measured JFR showed repeated young and old collections while retained heap grew into the
5--6-GiB range on an 8-GiB heap. Once genuine pressure crossed the production threshold, adaptive eviction removed hot
models; the next prefetch then combined thousands of cold reconstructions into one enormous interval-join query. This
is a production correctness and tail-latency defect, not permission to disable adaptive pressure handling. The recording
and dump are retained as `/tmp/sdk-model-production-4m-stalled.jfr` and
`/tmp/sdk-model-stalled-threads.json`. The next change must be selected from retained-object evidence: either remove the
unintended retention that creates pressure, or bound and schedule cold reconstruction so pressure recovery cannot turn
into a single load avalanche. Both may be necessary, but they must be measured and verified independently.

An exact 7.3-GiB HPROF capture then proved the primary retention owner. Eclipse MAT found 138.5 million live objects;
two `CachingMessageStore` instances retained 3.329 GiB and 1.075 GiB respectively. Their count and byte limits had
already logically evicted the old batches, but `evictOldest` only advanced an `ArrayList` prefix cursor. Every old
`CachedBatch` therefore remained strongly reachable until a much later structural compaction. The correction nulls a
fully evicted slot immediately and leaves periodic prefix compaction responsible only for shrinking the sparse backing
array. A deterministic test inspects that physical reference contract without relying on nondeterministic garbage
collection.

After the correction, the identical non-JFR endurance profile completed all 4,194,304 measured commands at 301,671
commands/commits/events per second, with 182.732 / 254.524 / 293.771 / 383.471-ms p50/p95/p99/max result latency. This
is roughly 28% above the adjacent 235k/s baseline and, more importantly, no longer collapses after two million commits.
The bounded batch cache, byte/count configuration and immediate physical release are committed independently in runtime
commit `3f956315`; 63 focused cache, JDBC-message-store and runtime-configuration tests pass.

The first long JFR repeat still exposed the second, already anticipated resilience problem. Profiling overhead created
enough sustained allocation pressure to evict a portion of the hot model cache. One command-prefetch then requested all
cold targets together; a live histogram showed 5.8 million decoded `ModelStreamBatchCodec.Entry` instances retained by
that single reconstruction, and the runtime spent its time in one packed-initial-event query. The recording is retained
as `/tmp/sdk-model-physical-eviction-fix-4m-stalled.jfr`. This does not invalidate the physical-retention fix; it proves
that cache-pressure recovery itself must be bounded. The next slice will cap cold prefetch/reconstruction work while
leaving the zero-load hot path and commit batching unchanged.

Cold command-prefetch is now capped at 1,024 independently loaded models per reconstruction. This is semantically safe
for the optimized path because it accepts only unrelated single-target commands: every command retains the exact
boundary of its own model load, while neither cross-model applies nor ancestor-dependent contexts enter this path. The
identical 4,194,304-command profile then completed under measured JFR at 300,175 commands/commits/events per second,
with 182.661 / 257.067 / 331.512 / 433.451-ms p50/p95/p99/max result latency. All memberships and global events were
verified. The recording is retained as `/tmp/sdk-model-bounded-prefetch-1024-4m.jfr`. The result deliberately claims no
hot-path throughput gain over the adjacent 301,671/s non-JFR endurance run; its value is that real adaptive eviction no
longer turns one tracking batch into an unbounded reconstruction wave. The 1,024 cap is retained as a resilience fix,
and the next JFR analysis returns to the steady-state 300k/s route.

The corresponding four-million-update no-result control exposed that a target-count cap alone is not an event-memory
cap. After adaptive eviction, each model had accumulated dozens of events; a 1,024-target reconstruction therefore
retained about 65,000 deserialized events and their prepared replay objects until the page completed. The run eventually
completed at only 36,039 commands/commits/events per second. Its JFR is retained as
`/tmp/sdk-model-no-results-4m.jfr`; this result must not be mistaken for the short warm-cache no-result ceiling.

The expanded reconstruction path now performs the same direct-plan classification already used by the compact path.
For an unambiguous direct `@Apply`, classification uses the serialized payload type without deserializing the event or
placing it in the page-wide prepared-event maps. Application then deserializes and replays that event exactly once.
Cross-model, ancestor-dependent, unknown and upcast-required events retain the general preparation path unchanged. The
identical no-result/JFR endurance profile subsequently completed at 213,902 commands/commits/events per second instead
of 36,039/s, a 5.9x improvement, with all 4,194,304 updates completed. Its recording is retained as
`/tmp/sdk-model-no-results-direct-replay-4m.jfr`. This removes the catastrophic replay retention but is not the final
throughput result: genuine adaptive eviction still causes repeated historical reconstruction, so the normal-result
route and a production-separated process profile remain required.

That recording also proved that the default heap threshold observed instantaneous allocated heap, not retained heap.
G1 repeatedly approached 85% immediately before an ordinary young collection and fell back to roughly 1--2 GiB
afterwards. Trimming hot model entries at the pre-collection peak converted short-lived transport allocations into
avoidable historical replay. The JVM-backed pressure controller now sums each heap pool's post-collection usage when
the collector exposes it, with the former instantaneous measurement retained only as a portability fallback. Sustained
GC-time pressure, explicit cache weight limits and the global oldest-entry policy remain unchanged. The exact
four-million no-result profile reached 305,304/s without JFR and 295,202/s under JFR, versus the adjacent 213,902/s
JFR result before this correction. The new recording is retained as `/tmp/sdk-model-post-gc-pressure-4m.jfr`; focused
cache tests cover multi-pool summation and the unsupported-collector fallback.

The next long ordinary-result repeat showed that retaining independent GC-time pressure was still unsafe. During the
measured JFR the first collections reduced a 5–6-GiB allocated heap to 0.7–2.7 GiB; later collections left 2.4–4.0 GiB,
well below the 85% retained-heap threshold. Their cumulative pause time nevertheless crossed the independent 20%
GC-time trigger. The coordinated trimmer then compared and summed caches whose weights have different units (model
entry counts and serialized tracking bytes), and the hot model working set disappeared. The JVM became mostly idle
while PostgreSQL served repeated 1,024-stream historical reconstructions; individual global-event block queries ran
for tens of seconds. The run was stopped and its JFR and thread dump are retained as
`/tmp/sdk-model-current-stall.jfr` and `/tmp/sdk-model-current-threads.txt`. Before another throughput optimization,
GC-time pressure must no longer evict a healthy hot working set merely because the application has a high transient
allocation rate; hard cache bounds and actual post-GC heap pressure must remain effective.

The isolated control set only `gcTimeThresholdPercent=100` and then completed all 4,194,304 ordinary-result updates at
254,645/s under JFR. The retained implementation does not disable adaptive GC pressure. It gives cache weights an
explicit unit, so entry-count and byte-weighted caches no longer contribute to one meaningless total, and requires a
cache to occupy at least 50% of its own configured budget before GC time alone can trim that unit group. Actual post-GC
heap pressure and hard per-cache bounds remain unconditional. The same production-default JFR then completed all
4,194,304 updates at 228,327/s with exact command results, model memberships and global events. The difference between
the two complete runs is normal profiling/run variance; the accepted evidence is completion versus the reproducible
historical-reconstruction stall. Sixty-five common cache tests and twenty-four runtime in-memory-store tests pass.

A storage-layout inspection after 4.78 million commits found roughly 4,800 packed model-stream rows and 37,500 packed
global-event rows, but one derived `model_stream_locator` row per model membership. Because that unlogged locator is
rebuilt asynchronously, its row count alone does not prove that it limits foreground throughput. Two otherwise
identical 524,288-command E2E runs therefore compared the production locator with the locator worker disabled. The
production route reached 169,007/s and the locator-disabled route 158,209/s; both verified every model membership and
global event. Run-to-run batch formation still varied (5,699 versus 7,944 items on average), so the small negative delta
is not attributed to the locator. The locator remains a capacity/read-index design concern for billion-commit stores,
but replacing its B-tree with a GIN/array layout is rejected as the next throughput optimization until a separate
write/read/size benchmark demonstrates an overall win. The exact sources were SDK commit
`ad84ac29acfddf255dbcf40569f3af919e4d05ae` plus tracked-diff SHA-256
`354758490f32c5315fc1c2bd2e8103a1327f124a6c5ea2572a6f2c7abbb91997`, and runtime commit
`fcc4c25cdab5eef13239c1a4a5ccd667213574ec` plus tracked-diff SHA-256
`f84eedc570e4cae78a5fc6b5af3468141f7cff954f174489586593ab26b53a1a`.

The same 524,288-command profile also compared Java 25 collectors at an 8-GiB fixed heap. G1 completed two runs at
169,007/s and 222,606/s; Parallel GC completed at 186,905/s and 230,410/s; ZGC completed at 76,693/s. The adjacent
second pair differs by only 3.5% while ordinary run variance and commit-batch formation are larger. Parallel GC may be
a useful throughput-biased deployment option, but it is not a defensible SDK/runtime default because it does not
materially move the bottleneck and trades that small gain for stop-the-world tail risk. ZGC's concurrent CPU and memory
bandwidth cost actively competes with PostgreSQL and the allocation-heavy codecs in this single-host profile. G1
therefore remains the release baseline and the 500--600k/s requirement may not depend on a non-default collector.

The first separate-process benchmark result was invalidated after a longer repeat: the external runtime had already
opened namespace stores when the client-side benchmark dropped that namespace schema. Runtime workers then retained
handles to missing tables. The apparent 239,268/s result is discarded. With the schema reset before runtime startup and
client reset disabled, two valid process-separated hot runs reached 178,430/s and 164,250/s. A fresh handler SDK against
the retained history needed about 50 seconds to reconstruct the 65,536-model cache and warmed at only 2,604 commands/s;
after that it reached the second hot result. A later cold profile was stopped after a global-event interval query kept
one reconstruction page waiting for more than a minute. This is separate cold/failover read evidence, not a steady-state
write result. The external benchmark bootstrap must be made incapable of dropping an active runtime schema, and cold
reconstruction remains part of the read-keeps-up gate.

A valid clean process-separated dual-JFR profile reached 179,870/s for 524,288 exact command results, model memberships
and global events. The client/handler JVM used roughly 24% machine CPU during its measured interval while total machine
CPU was roughly 91%; its hottest sampled work was command/model wire encoding, metadata, JSON/CBOR serialization,
model assembly and futures. The separately recorded runtime JVM averaged much less CPU over the broader recording;
its hottest work was MessagePack/Zstd tracking serialization, model-commit wire decoding, packed result construction
and binary COPY. PostgreSQL therefore owns a material part of the remaining wall time. Table statistics show only
hundreds to thousands of packed command/event/result/model rows for 720,896 total model events, while the derived
locator owns about 720,896 unlogged rows; the locator-disabled A/B above already proved that those asynchronous rows do
not explain foreground throughput by themselves. The recordings are retained as
`/tmp/sdk-model-separated-hot-client-524k.jfr` and `/tmp/runtime-model-separated-hot-524k.jfr`.

With `pg_stat_statements` enabled on the isolated benchmark database, the production scalar/B-tree locator consumed
about 7.1 aggregate database CPU seconds across eight parallel COPY writers while command inserts used 1.16 seconds,
model-stream COPY 0.38 seconds and result inserts 0.28 seconds over the complete seed/warm-up/measured run. Disabling
the locator reached 259,716/s versus the adjacent 227,655/s production run, a useful 14% diagnostic but still not a
release option because cold reads require the locator to keep up. Replacing only its B-tree indexes with PostgreSQL hash
indexes reduced aggregate locator COPY time to 5.24 seconds, yet the complete route reached 220,971/s and did not beat
the adjacent B-tree run. The hash-index spike is therefore rejected pending contrary repeated read/write evidence.

The read gate against that hash-index spike reconstructed 720,896 events at 184,726 events/s before a cache clear threw
`ConcurrentModificationException`. An eviction listener could re-enter the same access-ordered support map while
`clear()` was still traversing it. SDK commit `c844b54074b` now snapshots and removes all entries under the cache lock,
then delivers notifications after releasing it. Focused adaptive/support cache tests pass, including deterministic
re-entry from a clear listener. This correctness fix is independent of the rejected hash-index experiment.

A PostgreSQL binary-COPY experiment for compact command, event and result tracking blocks exposed a dormant capability
check in the working tree: it compared the table's wrapped `DefaultDataSource` by identity with the
`DatabaseType.POSTGRES` enum, so the intended COPY path had never executed. Replacing that check with a delegated
dialect capability activated COPY and made the identical 524,288-command route materially worse at 159,032/s. The
SQL evidence explains why: each COPY call carried only about 10--30 already-packed rows, while the command, event and
result COPY statements consumed over four aggregate database CPU seconds. Their previous multi-row inserts are cheaper
at this granularity. Both the capability spike and the dormant tracking-COPY implementation are rejected and removed;
model-stream and locator COPY remain unaffected because their row volume and transaction shape are different.

Changing durable tracking-block compression from Zstd to LZ4 was also rejected. The allocation profile made compression
look attractive, but the identical complete route reached 234,341/s with LZ4 versus the adjacent 243,494/s Zstd/JFR
baseline. The collector pauses in that baseline totalled roughly 182 ms over a 2.15-second measured window, so even
eliminating them cannot supply the missing factor two. A locator-coalescing spike then reduced each partition from
roughly 73 indexed COPY calls to 30 by delaying materialization for 50 ms, but throughput remained 248,634/s and total
locator database work barely changed. Index maintenance per scalar membership, rather than COPY-call setup, owns that
cost. The delay and scheduling change are rejected: they add read-index lag without a material foreground gain.

That LZ4 result was challenged once after inspecting the implementation: Fluxzero's existing LZ4 enum uses
`highCompressor()`, which is not the latency-oriented LZ4 variant one would normally compare with Zstd level 1. A new
isolated spike changed only durable blocks to `fastCompressor()`. It still reached just 195,603/s on the complete route,
materially below both the 234,341/s high-compression LZ4 result and adjacent Zstd results. The fast variant and default
switch are fully removed. Durable Zstd level 1 remains decisively better for this highly repetitive message data.

Four command-tracking sessions plus four event-sourcing sessions reduced the complete route to 138,474/s by
fragmenting otherwise large batches; the production one-session command default remains correct. Persisting tracking
blocks without application compression was worse still: only 121,201/s, with 234.4 MiB command and 231.7 MiB event
payload bytes before PostgreSQL TOAST versus about 3.3 MiB each under the retained Zstd blocks. PostgreSQL reduced the
physical relations to roughly 24 and 22 MiB, but paid that compression and TOAST cost inside the database and retained
far more bytes. Moving compression layers is therefore not a shortcut. The remaining factor two requires eliminating
whole encode/decode passes or stored representations, not tuning collector, codec, COPY setup or session counts.

Changing the WebSocket frame compression did not expose hidden transport headroom either. LZ4 reached only 133,364/s
on the same complete route; disabling WebSocket compression reached 230,041/s versus the adjacent 243,494/s retained
Zstd/JFR baseline. The durable tracking blocks still use Zstd in every variant. LZ4 adds work without sufficient byte
reduction for these frames, while uncompressed transport moves more bytes and merely shifts work away from the codec.
Zstd remains the production transport default.

Finally, forcing result payload serialization to remain sequential reached 210,145/s, while an immediately adjacent
run with the retained parallel threshold reached 227,578/s. Both runs completed 524,288 ordinary command results,
memberships and global events. The sequential run formed 56 commit batches averaging 9,362 items; the retained run
formed 114 averaging 4,599, so absolute run variance is still substantial, but there is no evidence that the common
pool is the missing factor two. The parallel result-serialization path remains unchanged. A future result optimization
must remove serialization or allocation work rather than moving the same work back to one thread.

A compact persisted message-block spike then reused the transport codec's shared type, format, source and target
descriptor instead of writing those strings for every MessagePack message. The complete route reached 239,220/s,
whereas the immediately adjacent retained MessagePack/Zstd route reached 270,909/s. Zstd already removes most of the
physical repetition and the custom block traversal costs more CPU than the mature MessagePack implementation saves.
The codec spike is fully removed. Replacing one complete persistence representation with another is therefore rejected;
the next candidate must skip a representation transition or otherwise remove an entire unit of work.

The subsequent production-default endurance/JFR completed 4,194,304 measured commands at 190,851/s under profiling,
with 261.888 / 530.955 / 803.576 / 1,379.010-ms p50/p95/p99/max result latency. It formed 383 conflict-free commit
batches averaging 10,951.2 and verified the complete result, membership and event counts. Across the 22-second
recording, no isolated business/model method dominates. The combined representation pipeline does: Zstd compression
and decompression account for 13.79% of sampled allocation, byte-array copies/ranges for 12.94%, MessagePack buffers
for 5.97%, tracking-wire writer allocation plus message decoding for 3.37%, followed by string construction/encoding,
message construction, futures and collections. The leading CPU methods likewise span tracking-wire string encoding,
runtime message filtering, maps, futures, payload decoding and model-result correlation. The recording is retained as
`/tmp/sdk-model-structural-4m.jfr`.

This rules out another local collector, buffer-size or small object-cache tweak as the route to 500--600k/s. The next
structural slice is S1's opaque, batch-native message path: keep payload and metadata bytes encoded while the runtime
indexes, persists, caches and forwards a tracking batch, and materialize application messages only at the SDK handler
boundary. It must preserve legacy decoding, all filters and tracking semantics, bounded caches, exact indices and cold
read throughput; a write-only raw-byte shortcut is not acceptable.

The first opaque-message spike deliberately retained each decoded `Append` payload as a lazy indexed block through the
tracking store and cache. A clean 524,288-command run reached only 218,431/s, below the adjacent current-code range of
roughly 227--271k/s. The transport envelope contains many small `Append` requests; creating offsets and lazy state for
each small request, then flattening those lists in the runtime backlog, adds work and ultimately materializes the same
messages. A laptop-suspend run that reported 15,872/s is explicitly discarded and is not evidence. Per-`Append` lazy
blocks are therefore rejected. Any retained opaque path must preserve one homogeneous request batch through endpoint
dispatch and store append, instead of adding laziness below a boundary that has already fragmented the batch.

An isolated `ProducerEndpoint` batch-dispatch spike then kept eager message decoding but submitted a homogeneous
transport batch as one endpoint task and one message-store append. After correcting the generic batch helper so
fire-and-forget commands do not receive unsolicited `VoidResult`s, two exact runs reached 245,441/s and 246,939/s.
Latency improved to about 196-ms p50 and 423-ms p99, but throughput remains inside the adjacent production range and
far below the target. The store backlog was already coalescing the individual appends; removing endpoint task setup
alone does not remove the dominant representation work. The first invalid form, which did send unsolicited results,
reached only 140,474/s and emitted missing-request warnings, so it is explicitly excluded. Batch dispatch is useful
only as the carrier for a whole-batch opaque representation, not as a standalone performance claim.

The first whole-request opaque carrier initially reached 178,409/s; a 1,048,576-command JFR run reached 243,859/s but
showed that `CachingMessageStore.enqueueUpdate` immediately flattened the block through its per-message backlog. A
batch-native cache-update queue removed that accidental boundary. Two subsequent non-JFR runs still reached only
221,418/s and 189,882/s, and the follow-up JFR reached 206,273/s. The new recording
(`/tmp/sdk-model-opaque-cache-batch.jfr`) shows the remaining materialization at the actual tracking read: the runtime
iterates every cached or cold command through `MessageStoreBatch.scan` and constructs complete messages merely to
evaluate the standard segment/type/target predicate. The selected messages are then encoded again for `ReadResult`.
The cache-backlog correction is necessary but not itself a throughput win. The opaque slice is incomplete until
standard tracking filters operate on compact headers and a selected block is forwarded without a full runtime
decode/encode cycle; custom predicates must retain an eager compatibility fallback.

The completed raw-data/metadata carrier removes those runtime payload copies and preserves the existing
`SerializedMessage` predicate contract; changed messages fall back to the regular encoder. After eliminating an
accidental per-message scan-record allocation, two 1,048,576-command runs reached 290,222/s and 298,699/s, while a JFR
run reached 281,013/s. An adjacent diagnostic switch that disabled only opaque decode/forwarding in the same binaries
reached 274,543/s and 292,606/s. The roughly four-percent mean improvement is real but much smaller than the first JFR
delta and is not the missing factor two. The retained recording (`/tmp/sdk-model-opaque-lazy-raw.jfr`) confirms that
full payload decoding now occurs at the SDK handler boundary; runtime filtering uses lazy data/metadata and raw
response forwarding. This remains a provisional S1 candidate pending larger-payload, cold-read and compatibility
gates, not yet an independently accepted throughput milestone.

Handler-consumer count is not the hidden limiter. With the same opaque candidate and 1,048,576 measured commands,
32 consumers reached 291,916/s, effectively equal to 16, while 64 consumers collapsed to 178,821/s and raised p99
latency to 1.369 seconds. Additional consumers fragment work and add contention; the production 16-consumer baseline
remains the correct gate and the 500--600k/s target may not depend on overprovisioning handler threads.

The final larger-payload gate rejected this S1 implementation. With 1-KiB command payloads and 524,288 measured
commands, opaque forwarding reached 170,246/s and the same binaries with opaque forwarding disabled reached
170,158/s. Tail latency improved, but throughput did not. The block implementation therefore adds too much scanner,
offset and persistence complexity for its measured value and is removed rather than retained speculatively. S1 remains
a valid backlog objective, but a future implementation must start from a native fixed-header envelope instead of
layering offset tables over the existing per-message wire representation.

After removing the complete lazy block/offset implementation, two dedicated-JVM runs of the ordinary 1,048,576-command
profile reached 290,746/s and 308,234/s. Both retained automatic model caching, one event per command and the normal
stored command/result tracking flow, and verified exact model-membership and global-event counts. The cleanup therefore
restores the recent production-default band without losing the compact tracking transport or the independently useful
`Metadata`-as-`Data` foundation. A 210,819/s run made through Maven's in-process exec goal is excluded from the baseline:
it shared Maven's JVM and reactor lifecycle instead of using the benchmark's dedicated 8-GiB process. Future S1 work
must be measured from this eager, dedicated-JVM baseline and may not reintroduce per-message offset state.

A clean measured-only JFR repeat completed at 285,025/s and is retained as
`/tmp/sdk-model-clean-envelope-baseline.jfr`. No isolated business or model method owns the missing factor two. The
largest sampled CPU sites were string/header encoding, concurrent-map lookups, committed-model assembly, context
capture, futures and tracking scans. Allocation was dominated by byte-array ranges/copies (17.88% combined), Zstd
compression/decompression (10.00%), MessagePack buffers, Jackson serialization, tracking/model-commit writers and
UTF-8 strings. This independently confirms the four-million-command structural profile: the remaining cost is a chain
of complete representations, not one local `@Apply`, cache or collector hotspot.

PostgreSQL statement accounting on the same current code showed that command-log persistence and the asynchronous
model-stream locator remain material database consumers. In one noisy 1,048,576-command diagnostic, command inserts
used about 2.08 aggregate seconds, model-stream COPY about 0.59 seconds and the eight locator COPY writers about 13.4
aggregate seconds. Increasing the generic message-store backlog from 4,096 to 65,536 roughly halved command-store call
count, but the adjacent complete run reached only 247,086/s and did not beat the 290--308k/s clean band. This repeats
and strengthens the earlier 32,768-backlog rejection: fewer SQL calls do not compensate for delayed or more variable
pipeline waves, so a larger production default is not retained.

Detailed timing output deliberately invalidated its own 119,405/s throughput result through heavy console logging, but
is still useful for decomposition. Warm model preparation and applies generally consumed only about 2--20 ms per wave;
the commit-and-post-commit boundary usually consumed roughly 60--150 ms. Ordinary result mapping, serialization and
enqueueing were mostly a few milliseconds and the asynchronous append future completed almost immediately. A separate
pre-serialized trivial-command control reached 7.27M/s for serialization and 3.13M/s for the initial append, but only
194,776/s through generic tracked consumption. That control intentionally bypasses automatic model batching and is not
a release result. Together these diagnostics reject result serialization and generic backlog sizing as the next local
micro-optimization; the next candidate must remove a complete runtime/SDK representation boundary.

The first fixed-header persistence experiment deliberately tested that conclusion rather than merely making payloads
lazy. Tracking transport decoded into mutable fixed-header entries; JDBC command/result persistence reused those entries
without a field-by-field MessagePack encode, and cache/read transport could copy compatible entries without decoding
payload or metadata. Focused Unicode, mutation, concatenation and legacy-MessagePack tests passed. The complete
1,048,576-command gate nevertheless collapsed to 119,973/s, with 151 commit waves averaging only 6,944 items. The
implementation removed one representation pass but added a second complete block assembler and copy boundary around
the retained persistence format; that extra work also delayed and fragmented model commit formation. It is fully
removed. Its source-set SHA-256 was `718c009a7f5f5614475b7b3a2423c1f957cfea8b122f48670a78b8c6c7fc76f9`.
Future S1 work may not add a parallel encoded-message block next to MessagePack. A viable native envelope must make one
existing representation patchable/forwardable end to end, or fuse a boundary so that no replacement encode pass is
introduced.

The immediate follow-up tested that stricter alternative: tracking transport used the runtime's actual MessagePack
version directly, with a fixed-width patchable index and lazy payload/metadata slices. Compatible command and result
entries were forwarded into JDBC persistence and back into tracking transport without a second format or field encode.
The focused wire and persistence tests passed, but the identical complete gate reached only 123,132/s. It formed 153
commit waves averaging 6,853 items and tail latency rose to 1.68 seconds. Creating/scanning the self-contained
MessagePack carrier before handler dispatch still delays the command waves enough to dominate the saved runtime work;
raw reuse after that point cannot recover the lost concurrency. This candidate is also fully removed. Its source-set
SHA-256 was `5acccbc837d28b8e8446e0edf64cd931965c96fecac1a44ddb8877205f6502ac`.
The next investigation must therefore preserve the current fast transport producer and batch timing. In particular,
it may not replace the existing shared-descriptor tracking encoder merely to make JDBC persistence reusable.

### Quiet-machine eager-baseline repeat

After closing IntelliJ and the active browsers and minimizing the Codex UI, the machine had 16 GiB unused memory,
84% system memory-pressure headroom and about 91--92% idle CPU before measurement. Two fresh-schema runs then used a
dedicated fixed 8-GiB JVM with the current eager transport/persistence path, 65,536 model IDs, 65,536 warm-up updates,
1,048,576 measured updates, a 65,536 in-flight bound, sixteen command-consumer threads, 32-byte payloads,
`searchable=false`, two event-sourcing sessions, production adaptive model caching and ordinary stored/tracked command
results. Both completed exact command-result, model-membership and global-event checks:

| Run | Throughput | Result latency p50/p95/p99/max | Measured commit batches |
| --- | ---: | --- | --- |
| Quiet eager baseline 1 | 304,569 commands/commits/events/s | 169.179 / 261.073 / 353.257 / 388.528 ms | 152; average 6,898.5, min 1, max 16,384 |
| Quiet eager baseline 2 | 307,911 commands/commits/events/s | 171.012 / 250.846 / 283.400 / 319.515 ms | 112; average 9,362.3, min 11, max 16,384 |

The results differ by only 1.1% and reproduce the earlier 290,746--308,234/s clean-envelope band near its upper end.
They are therefore the current production-default baseline for the next S1 A/B; the release route remains roughly a
factor two below the required 500--600k/s.

The measured-only JFR of the identical route reached 299,273 commands/commits/events per second with exact counts,
176.245 / 268.376 / 327.105 / 358.700-ms latency and 123 commit batches averaging 8,525.0 items (min 1, max 16,384).
The recording is retained as `/private/tmp/sdk-model-quiet-eager-baseline.jfr` with SHA-256
`93731b569e7c16fc9957a9de5ed32869a2969a629a811e77b3958b834f4befe6`. Six young GCs totalled about 228 ms of pause
time while reducing 4.9--5.5-GiB pre-GC heaps to 0.6--1.3 GiB; there was no allocation stall or old collection.

The JFR again attributes no missing factor two to one business method. The leading combined representation costs are
tracking-writer growth/final copies (about 5.3% of sampled allocation), runtime MessagePack buffers plus final copies
(about 4.8%), Zstd compression/decompression across transport and persistence (about 10.3%), tracking payload/metadata
and string reconstruction, model-commit writer copies/strings, and the surrounding maps/futures. The largest single
Fluxzero CPU owners likewise span tracking/model-commit string encoding, transport encode/decode, metadata, tracking
scan, model assembly and cache lookups. The next experiment must therefore fuse or remove a complete tracking
representation transition while preserving the current shared-descriptor producer and batch timing; another local
collector or cache tweak is not justified by this profile.

The exact pre-run source identity was SDK commit `c844b54074b5c201ff5056165a9110b2d8d1e4ca` plus dirty-diff SHA-256
`a0f57c80115394f7cbd4bb76141ee73b858bc8f61fd1f06e30d285e2b0ead781`, and runtime commit
`fcc4c25cdab5eef13239c1a4a5ccd667213574ec` plus dirty-diff SHA-256
`ee4200e732a9f1e8db4f5be9581995df39a8188382f7c90c735697b65c32f8dd`. Installed artifact SHA-256 values were
`a96a7bb6d903422485fca26d73f2151ef487c0d3f4e9eb3a25c7b5668de1ed4d` for `common`,
`2927805b9958822184c1ef8a387be3b583af38f3e82c19ad5e71aee53f2db9aa` for `sdk`,
`84d4a66c895c3b90006573984ec4be075afc8b39c6c930148b0c4b3cbe54cf5d` for the shaded runtime and
`6aca8b7ed3aec7c3ae8bc697d1048123efb59fc1e8fbe860656117fdb7bc93b7` for the benchmark driver class.

A same-binary diagnostic then configured only the command and result tracking logs as ephemeral, leaving automatic
`@Apply`, adaptive model caching, model commits, stored model events, the durable global event log, ordinary tracked
results and all exact checks in place. It reached only 220,181 commands/commits/events per second, with
183.844 / 678.108 / 897.706 / 930.554-ms latency and 106 commit batches averaging 9,892.2 items (min 1, max 16,384).
This is rejected as an optimization and is not a release result. Removing the durable MessagePack/Zstd/JDBC boundary
also removes its pacing/cache execution shape and substitutes the in-memory store's long-lived scan structure; the
result is materially slower despite substantial commit batches. Durable tracking persistence therefore cannot simply
be bypassed or replaced with the existing ephemeral path. A viable S1 change must preserve the durable store and its
batch timing while fusing work inside one of its existing representation transitions.

A subsequent fusion spike made the default MessagePack serializer write directly into a Zstd level-1 output stream for
direct LTS batches. The outer Fluxzero compression header retained the exact uncompressed size, decompression produced
byte-for-byte the same MessagePack representation, custom serializers kept the original materialized fallback, and 39
focused serializer/JDBC-store tests passed. The complete production-default route nevertheless reached only 292,575/s,
with 181.663 / 270.729 / 322.855 / 340.822-ms latency and 168 batches averaging 6,241.5 items (min 2, max 16,384), versus
the adjacent quiet 304,569 and 307,911/s baselines. The per-write streaming overhead outweighs removal of the temporary
uncompressed block on this route. The implementation and its test are fully removed and must not be retried without a
different compression API or evidence that eliminates that call overhead. The measured shaded runtime artifact had
SHA-256 `03174c7082490b570e9a5bd97e7045ea07f39dc68d9bf65d6a86118e73de30e3`; the retained log is
`/private/tmp/sdk-model-streaming-zstd-run1.log` with SHA-256
`3bf3d7bec57068f239c90ab7af462f85df369984ac126f18bd0ff0bcf32c4544`.

An exact model-commit writer-sizing spike then accumulated the request size inside the descriptor scan that already
visits every commit, avoiding a second batch pass and all writer growth/final copies. Six codec tests, including Unicode,
heterogeneous descriptors and the event-id/commit-id form, passed with an invariant that rejected even a one-byte size
mismatch. A same-binary switch measured 301,347/s with exact sizing versus 295,718/s with the original growing writer:
only 1.9%, within route variability and far below the independent-complexity threshold for roughly one hundred lines of
wire-size bookkeeping. The implementation and temporary switch are fully removed. The measured common artifact had
SHA-256 `ec9ba03f48ba39f1387ade5939d4c714f856ca454d904ad6ae28ae0b3c7dae75`; the exact/control logs are retained as
`/private/tmp/sdk-model-exact-model-writer-run2.log` and
`/private/tmp/sdk-model-exact-model-writer-control.log`.

Direct 32-hex formatting of process-unique technical UUIDs was also rejected. It removed the temporary dashed UUID
string and dash-replacement pass while preserving UUID-v4/variant bits, uniqueness and the compact public shape; the two
focused UUID tests passed. The complete route reached 294,814/s versus the immediately adjacent old-ID control at
295,718/s, so the conspicuous UUID/replace allocation is not a throughput limiter. The formatter is fully removed. The
measured SDK artifact had SHA-256 `2246294bb8746292c5b084e2e6c9af31cc322bfe1716a7680502fa887b0d76d9`; its log is
`/private/tmp/sdk-model-compact-technical-id-run1.log`.

The current normal command/handler/stored-result control then reached 513,411/s with 91.301 / 167.534 / 187.424 /
196.149-ms result latency. Combining that independently measured tracking cost with the retained 718,439/s runtime
one-event model-commit cost predicts `1 / (1/513411 + 1/718439) = 299,431/s`. That almost exactly matches the recent
294,814--301,347/s complete runs. This is strong evidence that the release route is at the additive CPU cost of two
already-optimized complete subsystems rather than blocked by an isolated method, cache or batch-size defect. Reaching
500--600k/s on the same hardware now requires eliminating or fusing a full tracking/result or model-commit boundary;
making either existing half modestly faster cannot close the gap. The control log is retained as
`/private/tmp/sdk-tracked-command-current-control.log` with SHA-256
`db062cbd7c6bda3103674b32cc5852819c3f1fee1ac098fabe585506daa664c2`.

### Canonical per-message native envelope

The retained S1 candidate now uses one self-contained per-message envelope from the SDK boundary through tracking
transport, runtime storage/cache and response forwarding. Its 72-byte fixed header places total length, segment,
request ID, global index, timestamp, data revision, field lengths and original revision before the variable fields;
type, format, source, target, message ID, payload and metadata follow as opaque bytes. Runtime-owned fixed fields can be
patched in place. Payload and metadata remain lazy `Data<byte[]>` slices, and the variable string headers are decoded
only when an existing `SerializedMessage` getter or tracking filter needs them. The final carrier stores those string
offsets and lengths as primitive fields: an intermediate implementation allocated five `StringSlice` records per
decoded message, which represented about 648 MB of sampled allocation in one full run.

`BINARY_V2` transports complete native envelopes for tracking appends/results and the common model-commit request.
The SDK builds envelopes on the publishing or model-handler thread before the existing batch sender, so the sender
continues to copy already-prepared bytes. The runtime assigns indices before canonicalization, persists concatenated
envelopes, scans fixed indices directly for exact model reads and forwards reusable envelopes without decoding
payload, metadata or strings. Cache entries own independent per-message arrays, preventing a small retained suffix
from pinning a complete websocket/JDBC batch. Legacy MessagePack v0/v1/v2 rows remain readable and are normalized only
when old staging rows are compacted with new data. Clients and runtimes that do not negotiate `BINARY_V2` use the
existing compact `BINARY`, CBOR or JSON boundary and are converted to native messages on ingress.

The first complete form reached only 277,038 and 282,606 commands/s. A forced `BINARY` control in the same binaries
reached 292,262/s and a JFR showed repeated growth/copying of the native tracking writer: its initial capacity still
assumed payload plus 64 bytes while every envelope contains at least 72 fixed bytes plus headers. Exact capacity for
already-native append/read-result batches removed that growth and restored a 296,467/s run. Exact sizing of the
model-commit writer reduced allocation but did not improve throughput and repeated an earlier rejected bookkeeping
spike, so it was removed. Batch-local string interning was also rejected after 291,984 and 295,900/s runs showed no
repeatable gain. Lazy string headers were retained for the architecture; replacing their five slice objects with
primitive offsets was the material final allocation correction.

Two final production-default runs completed exact command-result, membership and global-event checks:

| Run | Throughput | Result latency p50/p95/p99/max | Measured commit batches |
| --- | ---: | --- | --- |
| Native envelope final 1 | 312,341 commands/commits/events/s | 165.545 / 245.902 / 278.016 / 312.065 ms | 161; average 6,512.9, min 1, max 16,384 |
| Native envelope final 2 | 300,411 commands/commits/events/s | 169.698 / 267.379 / 301.474 / 328.671 ms | 142; average 7,384.3, min 1, max 16,384 |

Their 306,376/s mean is effectively equal to the quiet eager baseline's 306,240/s mean; the native representation no
longer regresses the 32-byte single-pipeline gate, but it does not move that route toward 500--600k/s by itself. The
logs are `/private/tmp/sdk-model-native-envelope-primitive-slices-run1.log` and `run2.log`, with SHA-256
`c9d42b3ee8c2253a2e8d34f56766d89e1756c4a899349cd056baa7c1ecdf0e3c` and
`eb0fa59559617b3034fa7c1c8da4d4de7d15a2c3af29d4d5d83869b3d8ee5624` respectively.

The fan-out gate preloaded one event log and delivered it to sixteen independent tracker-only consumers. Runs were
interleaved by transport, with three measurements per cell:

| Payload | `BINARY` deliveries/s | `BINARY_V2` deliveries/s | Median change |
| --- | --- | --- | ---: |
| 32 bytes | 1,438,869 / 1,457,874 / 1,445,813 | 1,497,965 / 1,496,362 / 1,485,235 | +3.5% |
| 2 KiB | 883,383 / 880,416 / 873,813 | 976,327 / 995,798 / 980,894 | +11.4% |

A 262,144-message JDBC storage/read gate with 32-byte payloads wrote 879,677 messages/s, decoded mostly-cold batches
through `JdbcMessageStore` at 4,228,129 messages/s and completed the normal tracking strategy at 2,788,765 messages/s.
It stored 262,144 messages in 2,048 compressed rows and preserved all counts and indices. The log is
`/private/tmp/message-store-native-cold-32b.log`, SHA-256
`a74c8e4c39e41f67f2cb5f03442b12df5c6836fcbb501eeab9bf10da41f12e32`.

The functional matrix passed 41 focused common protocol/carrier tests, 87 SDK websocket/model-commit tests and 156
runtime serializer, cache, JDBC, legacy-staging and model-commit tests; a subsequent cleanup subset added 57 passing
runtime tests. The measured source identity was SDK base `c844b54074b5c201ff5056165a9110b2d8d1e4ca` plus dirty-diff
SHA-256 `e9d177908fa0341ea82516e83eda9166a8b77ab0f97a9d8843582a7abc9ac4a7`, and runtime base
`fcc4c25cdab5eef13239c1a4a5ccd667213574ec` plus dirty-diff SHA-256
`5a90c5c2f67b506409fe9cf193dca1497cb08786713ed18294c560c5b60bf253`. The measured installed common artifact was
`ed7c4964326783b8963c423847b9815454fa36de80e1b9b5e83610291688f930`.

One compatibility decision remains before commit. New runtimes read historical MessagePack and old clients are
converted at the transport boundary, but an old runtime binary cannot read a native row written by a new runtime to a
shared database. A rolling deployment therefore requires either coordinated replacement of all runtime instances
before native writes begin, or an explicit legacy-write activation gate. This persisted-format constraint must be
resolved and documented operationally; client negotiation alone does not solve mixed-runtime database readers.

### Read-cache keys and deferred compact metadata

The next profiling cycle retained two independently testable SDK reductions. `RepositoryCache` now reuses lookup-only
namespace keys from a reference-clearing, reentrancy-safe thread-local stack; keys used for insert, compute, removal or
eviction publication remain stable objects. In the adjacent measured JFR, sampled `RepositoryCache.CacheKey`
allocation fell from one estimated 12,522,704-byte sample to zero and execution samples containing
`RepositoryCache` fell from 11/733 to 3/807. The focused test deliberately re-enters the same repository lookup from
the delegate and verifies that nested lookups cannot mutate the outer key.

The resulting JFR then identified metadata preparation as the larger remaining SDK cluster. The compact metadata
builder previously encoded immediately, after which correlation, recursive-publication depth and request timeout
could each cause another map or binary representation transition. The builder now publishes an immutable normalized
string-array representation using copy-on-write when the builder itself is reused. Direct `get` and `containsKey`
operations read that representation, normalized string additions remain compact, and the existing binary format is
encoded once when `toData()` reaches the transport boundary. Inspection before that boundary retains the original
wire order. Materialized map values, including their existing `String` identity behavior, and all public APIs remain
unchanged.

The production-default gate again used 65,536 model IDs, 65,536 warm-up updates, 1,048,576 measured updates, a 65,536
in-flight bound, sixteen command-consumer threads, 32-byte payloads, `searchable=false`, two event-sourcing sessions,
defaults version `2026.07.27`, adaptive model caching and ordinary stored/tracked command results. Every run completed
the exact command-result, model-membership and global-event checks. Two final non-JFR runs reached 214,950/s and
241,147/s with 145 and 137 commit batches respectively; their batch-size-sensitive spread is not treated as a clean
throughput delta. The final measured-only JFR reached 216,372 commands/commits/events per second with
259.013 / 320.966 / 338.486 / 356.913-ms p50/p95/p99/max result latency and 118 commit batches averaging 8,886.2
items (min 1, max 16,384).

The attribution change is unambiguous even though absolute throughput remains noisy. Against the immediately
preceding read-key JFR, `DefaultRequestHandler.prepareRequest` metadata `HashMap` allocation fell from five samples
estimating 289,590,104 bytes to zero. Execution samples in metadata binary lookup/UTF-8/encode stacks fell from 73/807
to 55/753, while total sampled allocation fell from 39,725,492,992 to 39,060,208,624 bytes. No wire-format, result,
membership or event-count change was accepted to obtain that reduction. The final recording and log are
`/private/tmp/sdk-model-compact-metadata-final.jfr` and `.log`, SHA-256
`9f6e8ecd364350802781dd43ef4461aa9aaae119c0efa0c27af7a8fdce15ee40` and
`bcdcaa65c2128ca2599d732429fbad840edd7232059b578214aa78cc90b7403c`. The two non-JFR logs have SHA-256
`bb9fe4fe4bbed565fa61a0cb2433b92151b25bb8ecd382db7d8c4de790bc0c70` and
`4809703b434c6667ae3b5cf9990fbfa15629726c8b2604eb1090ccd1c3997608`.

The measured source identity was SDK base `5569281e981f857d0c24455d9a7200e251dd4149` plus the four measured
implementation/test-file dirty-diff SHA-256
`e601844796c918584cf83210bfca432aa331622c262b3aeb80e2a8b422772849`, before this performance note and a
subsequent Javadoc-only wording correction, and clean runtime commit
`49f76b92ddcf82bb0430c532c08143c672d84893`. The wording correction does not alter class output. Installed artifact
SHA-256 values were
`f75c2a2ff21dd9fbcca43d3651d90f484a78345a44d48398568d2047669daec6` for `common`,
`92179cf7c078407e07e201d6021d430a0b21fa52cd2706b47630201f3fb3d2d9` for `sdk`,
`2e16b310c105401b1af44edd4585dc0b1d804abc047e7ac44b66ccee2b8efd09` for the runtime and
`b6aacd128b916a37547dc28d21c2c5562648cf7bf7dcc7e25fd53c17f925da18` for the benchmark driver class.

## Measurement discipline

Before accepting or rejecting another optimization:

1. record SDK and runtime commit IDs plus dirty-diff identity;
2. install or use a reactor classpath containing those exact artifacts;
3. print the complete benchmark configuration and use a clean isolated schema;
4. retain the same warm-up, dataset, in-flight bound, cache mode and result semantics for the A/B;
5. repeat the measured run at least twice and verify exact command results, memberships and global events;
6. use JFR to select the next largest attributable production stack;
7. record both successful and rejected outcomes here before starting another experiment.

Batch formation and wire byte/count limits are now eliminated as the primary cause. The opaque message-envelope and
batch-native tracking-cache candidates remain provisional: neither may be retained from a synthetic control or a single
spike. The next implementation change must remove a measured copy or decode operation without adding a second complete
encode pass, then repeat the production-default gate and the read/correctness matrix.
