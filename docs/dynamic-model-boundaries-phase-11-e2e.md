# Dynamic model boundaries — Phase 11 aggregate/model end-to-end comparison

## Method

`AggregateModelE2eBenchmark` runs a production SDK client over websocket against an embedded production runtime and a
real PostgreSQL database. It builds the same domain twice:

- one aggregate root with three direct `@Member` collections; a primary member has leaf and detail collections;
- independent `@Model` roots, primaries, leaves, details, secondary items, and tertiary items linked through
  `@ParentId`.

Both sides receive the same command/event payload classes and padding. The operation matrix covers root, direct child,
grandchild, cross-branch two-target, move, logical delete, and recreate. The cross-branch command is verified to update
both values. The aggregate must load its root and manually traverse both branches; the model action discovers and
commits its two returned targets automatically.

The sustained workload mutates leaves. Cold readers use new SDK clients. Event counts are read from the physical
aggregate stream or model membership tables, not estimated. Search profiles compare the synchronous aggregate root
document, synchronous direct model document, relation-backed `includeModelGraph`, and the materialized graph document.

### Environment

- Apple Silicon arm64 laptop, Darwin 24.6.0;
- OpenJDK 25;
- PostgreSQL 18.3 in Docker Desktop, no replica;
- one runtime and one PostgreSQL instance;
- schema dropped between every representation/profile;
- explicit checkpoint before the measured mutation interval;
- 1-KiB repeated payload padding, 4 roots, 3 primary branches/root, 4 leaves/primary, 2 details/primary, 3 secondary
  and 3 tertiary items/root;
- 200 warm-up and 2,000 measured actions, concurrency 32, 50 search samples;
- three runs for the principal uniform 2,000-action profiles; tables report their median;
- retained 100,000- and 1,000,000-action schemas for long-history load and physical-layout diagnostics;
- process/thread allocation from `com.sun.management.ThreadMXBean`; the allocation profile uses a fixed platform-thread
  executor so completed task allocations remain observable;
- local diagnostic evidence, not an absolute production-capacity certification.

## Uniform target results

### Event-sourced, not searchable

| Metric | Aggregate | Model |
|---|---:|---:|
| mutation throughput | 1,382 actions/s | 1,791 actions/s |
| mutation p50 | 14.344 ms | 16.122 ms |
| mutation p95 | 23.499 ms | 19.558 ms |
| mutation p99 | 26.265 ms | 65.206 ms |
| cold direct leaf load | 21.925 ms | 10.363 ms |
| warm direct leaf load | 0.056 ms | 3.905 ms |
| cold whole-root load | 17.262 ms | 34.869 ms |
| stored events needed by selected leaf | 595 | 53 |
| measured WAL | 13.967 MB | 7.768 MB |
| measured total storage growth | 3.211 MB | 2.621 MB |

The independent model is 29.5% faster for writes and 2.1 times faster for a cold direct leaf load. Whole-root loading
is about twice as slow at this small history because it must traverse and combine separate streams. Warm aggregate
loads retain their expected advantage because the complete root is one cache entry; warm model `loadGraph` still
performs graph work.

The physical compressed event-payload bytes were about 37 KiB for aggregate block compression and 603 KiB for
individually compressed model events. This is a real small-event storage disadvantage of fragmented streams even
though total model storage and WAL were lower. Payload, membership, head, relationship, and action overhead must
therefore all remain part of capacity testing.

### Searchable with asynchronous graph projection

| Metric | Aggregate | Model |
|---|---:|---:|
| mutation throughput | 1,386 actions/s | 1,405 actions/s |
| mutation p50 / p95 / p99 | 14.780 / 23.928 / 26.799 ms | 20.726 / 25.622 / 71.107 ms |
| cold direct leaf load | 20.940 ms | 10.417 ms |
| cold whole-root load | 17.978 ms | 31.125 ms |
| direct document search p50 | 0.669 ms | 0.494 ms |
| relation-backed root search p50 | 0.489 ms | 6.849 ms |
| materialized graph search p50 | — | 0.470 ms |
| direct document bytes | 876 B root | 216 B leaf |
| action-result / graph-visible | synchronous root | 6.227 / 28.516 ms |
| measured WAL | 15.327 MB | 14.412 MB |
| measured total storage growth | 3.834 MB | 4.547 MB |

ASYNC keeps model mutation throughput effectively equal to aggregates while preserving synchronous direct-leaf
search. A current relation-backed root query is materially slower than fetching one aggregate document, as expected.
The runtime-maintained graph document restores sub-millisecond root search and is independently rebuildable.

### Searchable with AWAIT result completion

| Metric | Aggregate | Model |
|---|---:|---:|
| mutation throughput | 1,392 actions/s | 392 actions/s |
| mutation p50 / p95 / p99 | 14.973 / 23.730 / 27.005 ms | 77.193 / 96.917 / 141.180 ms |
| final result / query-visible | synchronous root | 22.913 / 41.478 ms |
| immediate query after result contains update | yes | yes |
| materialized graph search p50 | — | 0.460 ms |
| measured median WAL | 15.169 MB | 15.288 MB |

AWAIT is intentionally expensive here: every leaf result waits for a full root projection. It is a per-operation
correctness/latency choice for callers that immediately query the root, not the recommended default for sustained leaf
traffic. The authoritative commit remains durable if projection later fails.

## Hot-root and conflict interpretation

`HOT` cycles over the leaves of the first root; it does not mean one permanently selected leaf. At concurrency 12,
there is at most one writer per leaf:

| Metric | Aggregate | Model |
|---|---:|---:|
| mutation throughput | 1,278 actions/s | 1,351 actions/s |
| cold direct leaf load | 53.225 ms / 2,235 events | 18.525 ms / 190 memberships |
| cold whole-root load | 50.387 ms | 60.235 ms |
| WAL | 14.047 MB | 7.786 MB |

At concurrency 32 over only 12 leaves, multiple actions write the same model concurrently. ACCEPT correctly appends the
original events but must rebase derived state; model throughput then drops to 361 actions/s. This is a contention
diagnostic, not the independent-storage baseline. Applications for which same-entity ordering matters can use the
normal single-writer routing pattern. The benchmark prints an explicit warning when a HOT configuration includes such
collisions.

The ZIPF distribution remains available to measure mixed contention rather than silently blending it into the uniform
storage comparison.

## Long histories

The 100,000-action uniform profile used 1,000 warm-up actions and the same 48 leaves:

| Metric | Aggregate | Model |
|---|---:|---:|
| mutation throughput | 1,674 actions/s | 2,004 actions/s |
| mutation p50 / p95 / p99 | 11.336 / 19.515 / 23.066 ms | 15.411 / 17.130 / 19.284 ms |
| cold direct leaf load | 388.965 ms | 84.256 ms |
| memberships applied for selected leaf | 25,295 aggregate events | 2,111 model memberships |
| cold whole-root load | 401.460 ms | 368.054 ms |
| WAL | 699.617 MB | 317.103 MB |
| total storage growth | 143.942 MB | 91.054 MB |
| GC collections / time | 199 / 563 ms | 24 / 126 ms |

At this history depth, the model is 19.7% faster to write, 4.6 times faster for the direct leaf, slightly faster for the
complete root, writes 54.7% less WAL, and grows storage by 36.7% less. This is the crossover the refactor is intended to
create: short independent streams remove unrelated replay without sacrificing reconstruction of the complete tree.

The 1,000,000-action uniform profile used 1,000 warm-up actions and retained both schemas after the write run:

| Metric | Aggregate | Model |
|---|---:|---:|
| mutation throughput | 1,642 actions/s | 1,843 actions/s |
| mutation p50 / p95 / p99 | 11.429 / 19.826 / 23.134 ms | 16.689 / 19.265 / 21.627 ms |
| cold direct leaf load | 4,230 ms | 1,036 ms |
| warm direct leaf load | 0.170 ms | 2.752 ms |
| cold whole-root load | 3,616 ms | 1,297 ms |
| warm whole-root load | 0.044 ms | 113.787 ms |
| selected direct replay | 250,295 aggregate events | 20,861 model memberships |
| measured WAL | 7,478,892,496 B | 3,607,430,728 B |
| compressed event payload bytes | 61,900,185 B | 302,694,405 B |
| GC collections / time during writes | 1,385 / 5,210 ms | 84 / 784 ms |
| retained schema after index hardening | 416,980,992 B | 811,778,048 B |

The first retained model-tree load exposed two separate problems. A current `loadGraph` pinned an explicit boundary and
therefore neither populated nor reused the current model cache; fixing that changed its second load from 19.7 seconds
to 200 milliseconds. The cold path still reconstructed graph streams serially. It now reconstructs at one already
pinned graph boundary in at most eight independent batches and merges in deterministic graph order. That changed the
same cold load from 20.6 seconds to 1.30 seconds and its warm load to 114 milliseconds, without snapshots or a
materialized graph document. The aggregate loads above and the final model loads were remeasured against the exact
retained histories after both corrections.

The model writes 12.2% more actions per second, uses 51.8% of the WAL, loads the requested cold leaf 4.1 times faster,
and reconstructs the complete cold tree 2.8 times faster. Its warm `loadGraph` remains much slower than the single
aggregate cache lookup because it still enumerates and validates the current relational graph. Normal repeated
whole-root queries should use the sub-millisecond materialized graph document; `loadGraph` remains the exact
event-sourced graph route.

The physical storage result is intentionally not hidden. The retained model schema is 1.95 times the aggregate schema
for this unusually compressible repeated 1-KiB payload. Per-event compression produced 302.7 MB of model payload while
aggregate block compression produced 61.9 MB. Model action idempotency also retained 207 MB. An unused model-stream
`action_id` index accounted for another 73 MiB; the implementation removed it and replaced it with a 256-KiB partial
shared-payload ownership index before the retained schema total above was recorded. Shared compressed blocks remain
rejected because the Phase 0 measurements showed that they make the important direct-event load path an order of
magnitude slower. WAL, allocation, and read latency all favor models, but storage capacity planning must include this
row/individual-compression trade-off.

## Allocation profile

A separate isolated 20,000-action profile used the same shape, 1,000 warm-up actions, concurrency 32, event sourcing,
and no search:

| Metric | Aggregate | Model |
|---|---:|---:|
| throughput | 1,593 actions/s | 1,947 actions/s |
| measured allocation | 418,846 B/action | 183,385 B/action |
| GC collections / time | 47 / 107 ms | 22 / 46 ms |
| WAL | 134,637,784 B | 83,514,480 B |
| storage growth | 32,677,888 B | 20,832,256 B |
| cold direct / whole-root | 98.648 / 88.036 ms | 31.506 / 56.212 ms |

The model path used 43.8% of aggregate allocation, 62.0% of its WAL, and 63.8% of its physical growth in this medium
history. Allocation includes the complete in-process SDK, websocket, runtime, JDBC, and benchmark caller work during
the measured mutation interval; it is not a per-method micro-allocation claim.

## Interpretation and remaining capacity gate

The paired result supports the design:

- independent models win on targeted loads as histories grow;
- bounded parallel reconstruction lets cold full-tree event sourcing use independent streams instead of serializing
  their transport/replay cost;
- synchronous direct search remains intact;
- ASYNC materialization restores very fast root search without putting search in the authoritative transaction;
- AWAIT works and is deliberately visible in result latency;
- fragmented small streams and durable action idempotency pay more physical storage than aggregate block compression
  for highly compressible payloads, even while using materially less WAL and allocation;
- same-model concurrent ACCEPT rebases are measurable but are not the normal independent-target path.

This laptop does not certify Fluxzero's existing 100 GB/min production envelope. The retained JDBC-core benchmarks,
hash-segment partitioning, bounded batching, and this end-to-end comparison support the implementation design.
Production rollout still requires representative hardware, replication, autovacuum/bloat, backup/restore, failover,
retention/archival for durable action metadata, and long mixed-traffic soaks. The local result is a merge gate, not a
100-GB/min certification.
