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
