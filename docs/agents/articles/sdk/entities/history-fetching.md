# Reconstructing Model history efficiently

An event-sourced Model loads from its cache, an applicable snapshot/checkpoint, then the required suffix of its own
stream. Independent children keep separate streams: loading one task does not replay its project's entire lifetime.

Snapshots trade extra writes/storage for shorter cold replay. Configure them only after measuring representative
histories. They are an optimization, not a replacement for the event history needed for arbitrary historical views.
A cache entry is likewise not durable historical storage.

For a cold standalone current read of a concrete snapshot-enabled Model, capable standard clients combine canonical
owner/head selection, an optional snapshot and the first event page in one Runtime request. A newer snapshot written
during the read may be missed; the selected seed and suffix still fit one read boundary. A missing snapshot starts
from sequence -1. An unreadable snapshot keeps the delete-and-replay policy at the already selected canonical identity
and boundary. Event pages retain their count/byte limits; the single snapshot's bytes are additional.

SDK and Runtime upgrades are independent: existing SDKs keep their unchanged operations against newer Runtimes.
The Runtime advertises `Fluxzero-Model-Reconstruction-Version: 1`. Missing capability, including a reconnect onto an
older peer before sending, uses the separate-read path with the original cache-publication fences. Custom
DocumentStore/SearchClient/EventStoreClient bindings keep that path unless they explicitly support the combined
semantics. Capability discovery occurs only after a cache miss. Batch, historical, prefix and already resolved Graph
reads keep their existing paths. Count SDK roundtrips separately from internal storage calls: the combined operation
still performs separate storage reads. A literal snapshot candidate is validated by the authoritative event read;
otherwise the canonical owner is selected before its snapshot and pinned suffix. An initial snapshot miss may be
reused when that same primary identity is confirmed. A logically deleted Model can use an earlier snapshot and replay
the deletion; erased/incomplete history cannot use a snapshot to restore availability.

Reconstruction pages events and can prefetch a bounded following page while applying the current page. Paging does
not change event order or application semantics. Avoid materializing an entire `revisions()` stream for a history
screen: select a boundary or bound the number of revisions presented.

Measure cold loads, warm loads, concurrent reconstruction and large payloads separately. Verify the same final state
with snapshots enabled and disabled, and after clearing caches. Include retained `STORE_ONLY` transitions and child
moves so optimized reconstruction is checked against both values and relationships.

## Retained Aggregate history: bound bytes as well as event count

Existing Aggregate applications remain supported during migration. Their WebSocket history fetches have a separate
byte limit; it does not configure independent Model streams or consumer tracking batches.

| Setting | Meaning |
| --- | --- |
| `fluxzero.eventsourcing.maxFetchBytes` | Maximum cumulative serialized event-payload bytes requested per Aggregate-history page |
| `FLUXZERO_EVENTSOURCING_MAX_FETCH_BYTES` | Conventional environment-variable form |
| `fluxzero.defaults.version >= 2026.09.10` | Uses 100 MiB when no explicit byte limit is configured |
| Explicit `0` | Count-only paging, also the compatibility default when no defaults opt-in applies |
| `WebSocketClient.ClientConfig.builder().aggregateHistoryMaxFetchBytes(bytes)` | Programmatic client-config alternative |

The event-count limit still applies. A page may contain one event larger than the byte budget so reconstruction can
make progress; this is not a strict heap-memory cap. A Runtime without byte-limit support ignores the optional cap
and retains count-based paging. Do not rely on this setting alone to handle arbitrarily large payloads.

`fluxzero.tracking.maxFetchBytes` instead controls consumer fetches. Choose the setting for the actual operation
rather than assuming one bounds every read. Prefer Models for new domain state; this section preserves the
configuration contract of retained Aggregate applications.
