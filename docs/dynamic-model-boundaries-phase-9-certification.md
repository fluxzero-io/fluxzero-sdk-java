# Dynamic model boundaries — Phase 9 certification and rollout

## Decision

The complete implementation is **GO for merge and controlled rollout**. No local result exposes an architectural
reason to return to aggregate-sized consistency boundaries or to change the model-action/storage protocol.

This is deliberately not a claim that one laptop certified the production reference envelope. Unchanged Fluxzero
installations already demonstrate roughly 100 GB/min on their event paths, while the retained tests below ran on one
local PostgreSQL instance. A customer-scale deployment remains conditional on its normal hardware capacity,
replication, vacuum/bloat, backup/restore, and long-soak qualification.

The first release therefore has two separate gates:

1. code, correctness, migration, boundedness, and comparative local performance: passed;
2. absolute 100 GB/min capacity and steady-state operations on representative infrastructure: deployment gate.

## Environment and method

- macOS 15.7.4, arm64;
- Java 25;
- PostgreSQL 18.4 in Docker Desktop, `shared_buffers = 128MB`, no replica;
- 32 physical range partitions over Fluxzero's stable 128 ID segments;
- database schema dropped between profiles;
- explicit checkpoints and WAL-LSN differences for storage runs;
- diagnostic local results, not production capacity claims.

Phase 9 retained the Phase 0 cardinality and physical-layout dataset instead of needlessly recreating a billion-row
database. That dataset contains one million unique heads and one-entry streams and establishes the linear floor:

| Family | Approximate bytes/model | Linear one-billion floor |
|---|---:|---:|
| model head | 145 B | 145 GB |
| first 64-byte stream entry | 242 B | 242 GB |
| combined | 386 B | 386 GB |

Documents, temporal edges, replicas, backups, and WAL are additional. This is why heads, streams, payloads,
relationships, erasure fences, and deletion targets use the stable segment and bounded physical partitions. The
segment, rather than a PostgreSQL-native hash, also remains usable as a future shard-routing key.

## Complete model-action store/load profile

The final implementation was measured with 1-KiB event payloads, publication enabled, current plus historical batched
reconstruction, and the complete action schema. The Phase 5 paired result is included where it is directly comparable.

| Targets | Actions | Actions/s | Memberships/s | Write p99 | Physical/logical | WAL/logical | Current models/s | Historical models/s | Phase 5 actions/s |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 20,000 | 5,645 | 5,645 | 72.960 ms | 1.89× | 2.24× | 26,708 | 26,999 | 4,407 |
| 2 | 10,000 | 3,772 | 7,544 | 105.528 ms | 2.44× | 3.20× | 28,331 | 29,772 | 3,099 |
| 10 | 3,000 | 1,324 | 13,243 | 294.922 ms | 6.27× | 9.81× | 24,714 | 25,330 | 894 |
| 100 | 500 | 115 | 11,507 | 3,149.924 ms | 40.69× | 76.46× | 16,301 | 17,122 | 68 |

The final path was faster in every paired profile and kept physical amplification equal or slightly lower. The
100-target WAL ratio moved from 71.49× in the earlier small-denominator run to 76.46×; the absolute difference is
consistent with local WAL/checkpoint variance and is not accompanied by physical growth or a throughput regression.
Payload storage still crosses over to one shared payload row, so the full event payload is not multiplied by target
count.

### Concurrent reconstruction

Four virtual-thread readers continuously requested 128-model event batches while a second, disjoint-target write pass
updated existing models. The write comparison is the same executable with readers disabled/enabled.

| Targets | Baseline actions/s | Concurrent actions/s | Write delta | Reader models/s | Reader memberships/s | Maximum observed load batch |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 5,765 | 4,849 | -15.9% | 105,185 | 153,132 | 27.161 ms |
| 2 | 4,506 | 3,794 | -15.8% | 125,186 | 179,388 | 17.352 ms |
| 10 | 1,269 | 1,033 | -18.6% | 99,778 | 133,492 | 55.401 ms |
| 100 | 71 | 58 | -18.3% | 113,677 | 123,025 | 24.577 ms |

The concurrent write p99 values were 124.637, 121.681, 527.545, and 3,464.551 ms respectively. This is ordinary
database contention rather than per-model read amplification: reconstruction remained set-based and around
100k models/s throughout. The retained benchmark exposes `concurrentLoaders` and `concurrentActions` so deployments
can repeat this matrix on their own hardware.

## Graph and projection profile

The temporal graph implementation had already been exercised with a 1,024-deep chain, 10,000-wide root, and an
8,193-node/32,000-edge shared DAG. Current traversal remained one partition-pruned query per breadth level; the
pathological deep chain took roughly 0.8 s, while the wide descendants and ancestor lookup took approximately 116 ms
and 3.9 ms respectively. The shared DAG completed descendant traversal in approximately 280 ms. The deliberately
rejected alternative would duplicate opposite-segment routing columns permanently to optimize the rare chain.

The final 5,000-model/edge search and composition profile, with 256-byte event and direct-document payloads, measured:

| Operation | Selective p50/p95/p99 | Broad p50/p95/p99 |
|---|---:|---:|
| related graph search | 1.582 / 2.041 / 2.855 ms | 72.200 / 76.862 / 88.866 ms |
| stitched current graph | 2.076 / 2.547 / 2.853 ms | 50.942 / 65.495 / 80.252 ms |

Broad search was dominated by the ordinary target-document query, not relationship traversal. Broad composition p95
split into approximately 14.500 ms traversal, 4.652 ms document-location lookup, 22.161 ms document loading, and
19.349 ms stitching.

The final 2,000-root asynchronous projection materialized about 1,054 roots/s with the conservative 10,000-model
bound and 7,752 roots/s for a leaf profile. This is consistent with the earlier 1,110/7,491 roots/s run. Registration
is now propagated through the existing locked model-state row, so an already-running sibling instance starts writing
durable signals on its next commit without polling or an extra hot-path query.

A special co-located recursive search compiler is not a first-release requirement. The retained staged plan is
set-based, bounded, works for both co-located and split stores, and is already below the document-query cost in broad
profiles. Compiling a second execution engine now would add behavior and pagination divergence without evidence that
it removes the bottleneck. It remains an evidence-driven future optimization.

## Failure, recovery, and privacy

The executable contracts cover:

- all-or-nothing JDBC event/action publication when co-located;
- one published event across transient retry with an external event store;
- a publish-first external event becoming observable before its action result: boundary reconstruction fails closed
  with “action boundary is not visible” and succeeds only after the durable action commit;
- action idempotency, unknown/repeated result delivery, restart materialization, and document/snapshot write fences;
- bounded coordinator count/bytes and request refusal rather than unbounded accumulation;
- graph-projector failure, durable retry, rebuild, duplicate/late delivery, moves, and deletes;
- 1,024-target resumable hard-delete batches, including a 100,000-model wide cascade;
- protected detached lineage, inclusive shared-DAG descendant erasure, direct and graph-document tombstones, and exact
  ID-reuse rejection;
- cache/snapshot fencing and current/historical reconstruction boundaries.

Split databases intentionally do not claim XA. The core action durably retains compressed materialization intent;
document/snapshot/search application is idempotent and fenced, so failure resumes without re-running user assertions
or duplicating the original event.

For split model and search databases, configure the same Base64-encoded 256-bit key through
`fluxzero.modelErasureKey`, or use the exact namespace-specific
`fluxzero.modelErasureKey.<schema>` property. Configure it before the first model/search use. Existing databases retain
their generated key; a configured mismatch fails startup explicitly. Key replacement/rotation requires a deliberate
data migration and is not silently supported.

## Compatibility and rollout

- Existing aggregate classes, protocols, repository paths, caches, publications, searches, deletes, and fixtures stay
  on their original paths. Model metadata discovery is not added to the aggregate hot path.
- A new SDK talking to an old runtime continues to use legacy flows unchanged. A model operation uses its distinct
  request type and receives the runtime's existing clear unsupported-request error; no new capability handshake is
  required.
- An old SDK talking to a new runtime emits only existing request types and follows the unchanged handlers and storage
  paths.
- The model tables and columns are additive and lazily initialized. There is no automatic aggregate-to-model data
  migration in the first release.
- During a rolling upgrade, enable model traffic only after every serving runtime understands model actions. Direct
  aggregate traffic may continue throughout.
- Rolling back runtime binaries remains safe for legacy traffic, but an old runtime cannot serve new model actions.
  Preserve the additive model tables, restore the new runtime, and resume durable materialization/erasure work.
- Direct model documents remain synchronously searchable when commit succeeds. Whole-graph documents remain opt-in,
  asynchronous, rebuildable projections with an exposed high-watermark.
- Compact action results are a reconstruction correctness dependency in the first release and must not yet be purged
  independently. Retention/archival requires a separately proven replacement index.

## Remaining deployment gates

These are operational qualifications, not unfinished model semantics:

- repeat the retained harness on representative production hardware or a planned multi-segment deployment and
  demonstrate the required 100 GB/min read/write envelope with headroom;
- run a production-duration mixed-traffic soak including checkpoints, autovacuum, bloat, replica lag, failover, and
  overload recovery;
- qualify backup/restore time and correctness for model streams, graph relations, action results, projection queues,
  erasure state, and externally managed key recovery;
- validate customer-specific payload, hot-key, Zipf, graph-degree, retention, and search distributions.

Those results determine deployment sizing and rollout rate. They do not require changing the API or storage design
unless they reveal a material regression against the explicit budgets above.
