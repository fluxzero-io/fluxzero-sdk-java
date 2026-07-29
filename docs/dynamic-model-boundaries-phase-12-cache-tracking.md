# Dynamic model boundaries — Phase 12 cache tracking

> Historical design record. Phase 21b removed SDK-owned materialization acknowledgements; the runtime now owns direct
> document/snapshot materialization and durable restart repair.

Date: 2026-07-27

> **Storage update (Phase 17):** the update cursor no longer scans permanently retained commit results. Full
> target-bearing receipts now have bounded time-partitioned retention; the compact durable commit core keeps exact
> historical boundaries and duplicate idempotency. A lagged tracker receives a backwards-compatible namespace-cache
> reset. The coherence and event-handler exactness contracts below are unchanged.

## Decision

Independent-model caches follow a dedicated durable model-update cursor. They do not infer model freshness from the
global event log: `STORE_ONLY`, document-only, relationship-only and hard-delete commits must be visible even when no
domain event is published.

One long poll returns committed commit substeps ordered by namespace-wide `stateIndex`. An update contains only the
commit/substep identity, nullable global `eventIndex`, and resulting target heads. Event, document and snapshot
payloads are never sent to SDKs that do not cache the affected model. Responses are bounded by item count, estimated
metadata bytes and wait duration; the oldest update is always allowed through so the cursor cannot deadlock.

The JDBC commit row stores its first and last state index. The existing hash partitioning by the stable 128 Fluxzero
segments remains unchanged; every physical commit partition gets a partial `(last_state_index, first_state_index)`
index. Sparse pending-materialization and pending-erasure indices maintain the safe document boundary without scanning
retained commits on every poll. This avoids a new row per target and avoids tailing the much larger `model_stream`
membership table.

## Coherence protocol

- The first cached current-model load performs one zero-wait position probe and starts one tracker for that repository
  namespace at the current durable update head, while retaining the possibly older safe materialization head
  separately. This deliberately skips irrelevant history because no cache entry predates the tracker. A direct
  document uses that probe instead of the previous model-head validation; an event-sourced first load pays this
  bootstrap once for the namespace. Later hot loads have no validation round trip.
- A remote newer target head fences an existing entry stale immediately. Its entity stays available internally as an
  event-replay base, but ordinary current loads either join the coalesced refresh or use the store path.
- At most one virtual refresh worker per namespace batches up to 1,024 stale cached IDs. Event-sourced models load only
  their missing suffix. Document-based models load their synchronously maintained direct document.
- An accepted local commit updates the cache and its fence immediately. Seeing the same durable update later is a
  no-op.
- An incomplete event history evicts an event-sourced replay base instead of fabricating state.
- A hard-delete fence contains no erased model IDs. Remote SDKs therefore clear the model-cache namespace; an SDK that
  initiated the deletion already evicts the exact target immediately. While external erasure is pending, direct
  document caching is disabled; completion advances the safe boundary and wakes the poll without requiring another
  update. The existing recreate-after-hard-delete rejection remains unchanged.
- Tracker failure disables the cache fast path until the durable cursor recovers. Timeout heartbeats do not invalidate
  anything. Shutdown cancels the open long poll and releases loads waiting on a refresh.

The runtime wakes namespace-local waiters immediately after commit or materialization completion. `AvailabilityCheck`
permits only one active runtime, and the event-sourcing endpoint/model store is memoized per namespace, so no periodic
database observer is needed. A newly active runtime reads the durable cursor when its namespace endpoint is recreated;
reconnect therefore cannot lose an update. Multi-active runtime coordination is intentionally deferred to the planned
request/result-log architecture instead of being approximated with polling.

Direct document/snapshot materialization and explicit erasure form a readiness barrier distinct from the processed
update cursor. A commit or privacy-safe hard-delete fence can already be durable while an external document store is
still applying its write. The runtime reports both the durable high-watermark and `materializedStateIndex`; a new
document cache never starts beyond the latter. Target updates are delivered and fenced immediately even when their
document write is pending; only affected document refreshes wait for the materialized head. Unrelated invalidations
and event-model loads therefore do not suffer head-of-line blocking. A hard-delete fence is likewise delivered
immediately to clear caches, while direct documents remain uncacheable until erasure completion advances the
materialized boundary. Without the two boundaries a fast refresh or post-delete reload could read an old document and
incorrectly fence it as current.

When the runtime owns the SearchStore, it clears the readiness intent itself after the fenced document/snapshot write.
In the supported split-store route, the SDK still performs its existing synchronous direct-document and snapshot
writes, then sends one idempotent `CompleteModelCommitMaterialization` acknowledgement. Command completion waits for
that acknowledgement. Thus the tracker cannot outrun a successful SDK-owned document write, without pretending that
the two databases share one transaction.

## Event-handler exactness

The current-cache tracker is not the historical truth for an event handler. A published model event carries its
persisted commit ID and substep. Injected targets and ancestors are reconstructed directly at that commit's exact
`stateIndex`; a tracker or cache already beyond the event therefore cannot leak later state. In the co-located JDBC
configuration, global event publication and model visibility commit atomically. Split event/model stores retain the
pre-existing documented partial-failure boundary and are not made transactionally atomic by this phase.

## Correctness evidence

Focused verification covers:

- ordered published and `STORE_ONLY` substeps with nullable event indices;
- count and byte response bounds with guaranteed cursor progress;
- local long-poll wake-up after commits, materialization completion and hard deletion, plus durable cursor recovery
  after runtime restart;
- runtime-owned and SDK-owned direct-document materialization, acknowledgement and pending-erasure races, including
  initial tracker bootstrap;
- privacy-safe hard-delete fencing;
- stale fencing, retained replay bases, batched refresh and shutdown cancellation in the SDK;
- exact historical handler loads while the current store/cache has moved ahead;
- cache hits after local commits without per-load model-head checks.

Commands:

```text
runtime: ./mvnw -pl runtime -Dtest=JdbcModelCommitStoreTest test
         73 tests passed
SDK:     ./mvnw -pl common,sdk -am \
           -Dtest=WebSocketTransportCodecsTest,ModelCommitterTest,ModelCacheTrackerTest,InMemoryEventStoreModelCommitTest,DefaultModelRepositoryTest \
           -Dsurefire.failIfNoSpecifiedTests=false test
         101 tests passed (23 common, 78 SDK)
```

The final verification also passed:

```text
SDK:     ./mvnw -B install
         all nine modules, including test-server, proxy, annotation processing and Java/Kotlin downstream projects
SDK:     ./mvnw -B site -Pjavadoc
runtime: ./mvnw -B install
         all four modules; 625 runtime tests
```

A final regression-only review covered update/materialization ordering, tracker bootstrap and cancellation, remote
completion order, event-handler historical exactness, privacy-safe hard deletion, cache eviction/lifecycle, retained
commit lookup, and write/read hot-path allocation. It found no new row-per-target write, event/document payload
fan-out, or foreground validation round trip.

## Performance evidence

These are comparative local PostgreSQL 18 / Apple-silicon laptop diagnostics, not production hardware certification.
The retained benchmark code reports commit writes, memberships, physical bytes, WAL, commit lookup and update-cursor
throughput separately.

All density runs used 1 KiB events, synchronous global publication, no documents/relationships and a 256-update cursor
page. The 100-target outlier used 1,000 commits and concurrency 32; the other rows used 2,000 commits and concurrency
64.

| targets/commit | commits/s | memberships/s | physical/logical | WAL/logical | tracked updates/s | tracked heads/s | cursor batch p95 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 5,479 | 5,479 | 1.82x | 4.33x | 157,103 | 157,103 | 4.64 ms |
| 2 | 4,279 | 8,558 | 2.78x | 5.11x | 153,928 | 307,855 | 3.96 ms |
| 10 | 1,338 | 13,385 | 6.07x | 10.35x | 132,096 | 1,320,957 | 4.59 ms |
| 100 | 200 | 20,050 | 38.82x | 68.60x | 48,395 | 4,839,500 | 6.76 ms |

The fixed-cost amplification in the 100-target row is dominated by 100,000 independently addressable heads and
memberships for only 0.98 MiB of logical event data. The shared-payload representation prevents an additional 100x
event-payload duplication.

Retained-history runs:

| retained commits | payload | write commits/s | physical | WAL | tracked updates/s | cursor page | cursor p95 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100,000 | 128 B | 6,388 | 76.48 MiB | 106.78 MiB | 235,849 | 2,048 | 11.99 ms |
| 1,000,000 | 32 B | 6,010 | 701.11 MiB | 1,187.79 MiB | 134,594 | 5,000 | 51.76 ms |

The million-commit physical/WAL ratios (22.97x / 38.92x) must be read with the deliberately tiny 32-byte payload:
fixed commit, head, membership and index rows dominate. Absolute retained size and sustained production-hardware
measurements remain capacity-planning inputs, not reasons to duplicate payloads inline.

The SDK cache harness uses a 10,000-event model, 100,000 loads per hot sample and ten independent samples:

| operation | p50 | p95/max |
|---|---:|---:|
| local cache hit | 0.072 µs | 0.287 µs |
| remote update to refreshed value | 2.128 ms | 3.666 ms |
| refreshed cache hit | 2.500 µs | 12.459 µs |
| cold reconstruction | 27.393 ms | 84.542 ms |
| invalidated full reconstruction | 24.574 ms | 43.640 ms |

The previous Phase 11 model “warm leaf” result included a model-head validation round trip and was therefore not a
true cache-hit number. This phase removes that round trip while retaining durable invalidation and restart recovery
for the single-active-runtime topology.

## Operational visibility and remaining limits

Every websocket request/result already emits request metrics. `TrackModelUpdatesResult.Metric` adds update count,
target-head count, published count, returned cursor, durable runtime high-watermark and safe materialized high-watermark,
from which tracker and external-store lag are observable. Cache eviction metrics and explicit warnings cover eviction
and recovery failures. No event/document payload bytes are consumed by the tracker itself; refresh I/O remains visible
in the existing model-load/document-store metrics.

The normal command/query path intentionally observes the latest state known to its namespace tracker. Local runtime
commits wake that tracker immediately; reconnect resumes from its durable cursor. Event handlers do not use the
latest-known contract: they reconstruct on the persisted commit boundary as described above.

The split-store acknowledgement narrows but does not claim to remove the pre-existing cross-database crash window. If
an SDK process dies after its external document write but before acknowledgement, the durable commit remains fenced
and visible as materialization lag until the exact commit is safely repaired or administratively resolved. This is
fail-closed for cache correctness; horizontal request/result-log coordination remains explicitly outside this phase.

There is no idle database poll per model namespace. `fluxzero.maxModelUpdateWaiters` remains the overload bound for
concurrent long polls. Supporting multiple simultaneously active runtimes will require an explicit distributed
notification/log contract and is outside this phase.
