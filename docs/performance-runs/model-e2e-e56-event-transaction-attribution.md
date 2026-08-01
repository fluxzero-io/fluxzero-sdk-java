# E56 event transaction critical-path attribution

E56 re-ranked the accepted P2 source pair without a production-code candidate:

- SDK `33e928c594aa24b15f8ab56dd8d1b559453b8539`;
- Runtime `ed9cb3419e0b61e49869886f81f742f1c8bf6a77`, clean diff;
- PostgreSQL 18.3 container `fluxzero-codex-s1-postgres` on port 64217;
- canonical 65,536-model, 65,536-warm-up and 1,048,576-measured-command boundary;
- latest SDK defaults `2026.07.27`, ordinary results and exact model/event/result checks.

## Matched dual-JFR control

The clean dual-JFR control reached 254,459 commands/s. Its p50/p95/p99/max latencies were
201.187/332.233/384.745/491.919 ms and its 144 measured dispatch batches averaged 7,281.8 commands. The Runtime and
client JFR SHA-256 values are `221dc90100f5f3e1ea6a5f7f56335b181962d95b80aa132b61afe568550bc1fa` and
`336cb728ecb4f9313c5d5d41ef00c756bc45dede1ffaef370755bb8380cf9624`. Benchmark and Runtime log SHA-256 values are
`850ff35c41cc0b65960983a7cb4cb55d74232ba591d77f19bbae6202580758bf` and
`1db92044f9d500bc39365e26c1d9dc65103a0535540bc2b0b40adf5ca9fb95cd`.

Every one of the 321 model-store transactions contained exactly one nested event-store transaction. Timestamp nesting
therefore separates queueing from actual transactional service rather than inferring it from aggregate thread time:

| Model route | Transactions / items | Model-store duration | Before event transaction | Event transaction | After event transaction |
| --- | ---: | ---: | ---: | ---: | ---: |
| Initial create | 167 / 65,536 | 0.863 s | 0.075 s | 0.756 s | 0.032 s |
| Packed update | 154 / 1,114,112 | 3.723 s | 0.331 s | **3.328 s** | 0.064 s |

The packed event writer waited only 0.051 s in its own executor queue and then consumed 3.327 s of service. Thus 89.4%
of packed model-store time was inside the co-located event transaction itself; adding another scheduling delay or
another upstream batch-size setting cannot address this control.

Nested phases account for 2.947 s of the 3.328-s packed event transaction:

| Packed event phase | Calls / items | Output rows | Aggregate service |
| --- | ---: | ---: | ---: |
| Direct LTS insert | 147 / 1,105,536 | 8,637 compact rows | 0.969 s |
| Staging and prior-tail flush | 154 / 1,114,112 | 8,320 new staging rows | 0.948 s |
| Co-located model SQL | 154 / 1,114,112 | n/a | 0.828 s |
| Commit | 154 / 1,114,112 | n/a | 0.198 s |
| Monitor | 154 / 1,114,112 | n/a | 0.001 s |

The 8,320 staging rows equal `transaction item count % 128` in every packed transaction. Zero-output stage events were
not necessarily empty: they can flush a tail retained by the preceding transaction. The existing layout repeatedly
stages a small remainder, then selects, deletes, recompresses and reinserts it before newer compact LTS rows can become
visible.

## Fresh PostgreSQL statement accounting

A separate non-profiled control reset `pg_stat_statements`, `pg_stat_io` and `pg_stat_wal` immediately before Runtime
startup. It reached 291,045/s with p50/p95/p99/max 173.370/286.569/329.079/399.528 ms and 150 measured dispatch batches
averaging 6,990.5 commands. Every exact check passed. Benchmark and Runtime log SHA-256 values are
`b67cde5e797e945c48b3d732b0402b69f4ff3d123ef342dc42b03d578388d285` and
`d07e29666557bb859ea94ee595102d418fe98f9f23219bfebbcfcaf7a7143d7e`.

Across setup, initial create, warm-up and measurement, the global event tables performed:

| Statement family | Calls | Rows | PostgreSQL execution |
| --- | ---: | ---: | ---: |
| Compact event LTS `INSERT` | 472 | 9,279 | 149.982 ms |
| Staging `INSERT` | 166 | 8,091 | 17.709 ms |
| Prior-tail `SELECT FOR UPDATE` | 158 | 8,002 | 20.932 ms |
| Prior-tail `DELETE` | 158 | 8,002 | 32.335 ms |

The roughly 221 ms of server execution is an order of magnitude below the corresponding aggregate JFR client/JDBC
phase time. The limiting demand is repeated protocol exchange, dynamic row-shape binding and data transfer, not
PostgreSQL computation. The full statement, I/O and WAL captures have SHA-256 values
`2f8b2290a654770004ab17b1360d7adce9b38d053c920dffeb66948df10fb2c6`,
`11c2662d5bcf8476850c5eb6bd25fada2c2ba5a3e58e348268b71a28dcada1e9` and
`70a5c01a056327d684ee4d70b58f8fd785eca270b6882be1fe858c92cb76a65b`.

## Decision

This is a diagnostic hit, not a code checkpoint. E31 already proved that merely making the underfilled model-event tail
direct removes about 95% of stage time but lets the opportunistic Runtime backlog drain into more transactions. E50
proved the same feedback for a fused event/model statement. The next candidate must therefore remove both compact-row
and staging-row multiplicity, not repeat either isolated optimization. The first bounded screen will use larger compact
LTS blocks plus a direct tail only for large co-located model transactions; ordinary low-rate command, event and result
logs retain the existing 128-message staging behavior. If that screen does not improve full E2E, the next architecture
must extend an already active ordered transaction with arrivals at a correctness-safe seal point rather than insert a
timer.
