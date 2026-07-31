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
| One command-consumer thread | about 241,789/s in the then-current pre-correction profile | rejected; no improvement |
| 1-ms WebSocket request collection delay | about 150,024/s in the then-current pre-correction profile | rejected; latency reduced effective batching/throughput |
| Dedicated runtime JVM on the same machine | about 157,165/s in the then-current pre-correction profile | rejected as a local throughput optimization; CPU/JIT/GC separation lost more than it gained |
| Result callback chunks of 16,384 instead of 64 | 239,987/s to 266,073/s in the same pre-correction profile | promising but not yet a production default; modest gain and must retain latency/backpressure behavior |
| Disable automatic model caching | 19,454/s current control | rejected for production; proves the cache avoids a JDBC reconstruction per command |
| Adaptive model cache | 188,249/s versus 178,299/s no-result controls | retained direction; repeat with ordinary results and JVM memory-pressure control enabled |
| Recent committed-model update cache | removes cache-tracker stream decompression; restart fallback passes | provisional; retain only after repeated isolated throughput/memory A/B and slow-tracker eviction coverage |

## Measurement discipline

Before accepting or rejecting another optimization:

1. record SDK and runtime commit IDs plus dirty-diff identity;
2. install or use a reactor classpath containing those exact artifacts;
3. print the complete benchmark configuration and use a clean isolated schema;
4. retain the same warm-up, dataset, in-flight bound, cache mode and result semantics for the A/B;
5. repeat the measured run at least twice and verify exact command results, memberships and global events;
6. use JFR to select the next largest attributable production stack;
7. record both successful and rejected outcomes here before starting another experiment.

The next candidate is the existing runtime-backlog item for an opaque message envelope: the runtime should route and
store payload and metadata bytes without reconstructing domain metadata maps. This is a Fluxzero-wide protocol and
storage change, not a model-only shortcut. It is accepted only if ordinary commands, results, events, notifications,
schedules, custom topics, metadata interceptors, upcasting, retries and mixed-version compatibility remain correct.
