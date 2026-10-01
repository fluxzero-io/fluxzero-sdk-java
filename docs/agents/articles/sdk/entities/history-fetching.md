# Reconstructing Model history efficiently

An event-sourced Model loads from its cache, an applicable snapshot/checkpoint, then the required suffix of its own
stream. Independent children keep separate streams: loading one task does not replay its project's entire lifetime.

Snapshots trade extra writes/storage for shorter cold replay. Configure them only after measuring representative
histories. They are an optimization, not a replacement for the event history needed for arbitrary historical views.
A cache entry is likewise not durable historical storage.

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
