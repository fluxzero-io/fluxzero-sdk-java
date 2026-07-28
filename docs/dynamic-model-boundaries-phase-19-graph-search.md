# Dynamic model boundaries — Phase 19 graph-search contract

Date: 2026-07-28

## Outcome

The provisional `Search.includeModelGraph()` modifier is replaced by the explicit
`Fluxzero.searchGraph(Root.class)` route.

- Graph results are `ObjectNode` values by default, because placed descendants are not fields of the root Java model.
- Explicit `SerializedDocument`, `Document` and typed `SearchHit` terminals remain available.
- AUTO reads a configured materialized projection and otherwise stitches live.
- `searchGraph(Root.class, true)` forces live composition.
- Constraints, sorting, path filtering and pagination have full-graph semantics in both routes.
- Forced-live composition applies the projection's path overrides, so it has the same public shape as the materialized
  view.
- Different child model types placed on the same canonical parent path form one deterministic model-ID-ordered list.

Live composition first discovers a bounded set of candidate roots using the root collection, explicit document IDs and
relationship constraints. It then loads and stitches the bounded graph before applying the full graph query. A query
that cannot fit within `maxModels` fails before graph traversal with guidance to narrow candidates or use a
materialized projection. This avoids unbounded accidental fan-out but intentionally means a selective constraint on a
descendant cannot make an otherwise unbounded live graph query cheap.

## Correctness finding

The full-view benchmark exposed a JDBC root-preparation bug before release. The loader attached the decoded search
summary to the outer immutable `SerializedDocument`, but an already captured lazy `Document` supplier still exposed
the old `null` summary during stitching. Full-text graph constraints could consequently produce false negatives.
Root preparation now reconstructs the serialized document with one consistent summary, and the JDBC contract verifies
both the transport value and `deserializeDocument().getSummary()`. Existing `MatchConstraint` and `@SearchExclude`
semantics remain unchanged.

## Measurements

All figures are local diagnostics on the retained PostgreSQL benchmark setup, not production capacity claims.

### Paired aggregate/model end to end

The 2,000-action, 48-leaf, concurrency-32 UNIFORM run was repeated for both model document modes.

| Profile | Aggregate | Model | Model / aggregate |
| --- | ---: | ---: | ---: |
| non-searchable mutation throughput | 1,007.2 actions/s | 1,029.6 actions/s | 1.022x |
| non-searchable WAL | 13.969 MB | 11.940 MB | 0.855x |
| non-searchable storage growth | 3.195 MB | 4.596 MB | 1.438x |
| non-searchable cold direct load | 24.556 ms | 14.719 ms | 0.599x |
| searchable mutation throughput | 951.5 actions/s | 943.0 actions/s | 0.991x |
| searchable WAL | 15.140 MB | 13.491 MB | 0.891x |
| searchable direct search p95 | 1.263 ms | 1.001 ms | — |
| searchable forced-live root search p95 | 0.805 ms | 11.817 ms | — |
| searchable materialized root search p95 | — | 0.802 ms | — |

The non-searchable model graph now writes current internal graph-component documents for explicit paths. This is real
write, WAL and storage cost for functionality that was previously absent; models without an explicit path still avoid
it. The single current run is not a clean historical code A/B because later phases also changed model persistence.
Within the current pair, models still wrote 14.5% less WAL than aggregates and loaded the direct leaf 1.67x faster.

One websocket ping reconnect occurred before the measured model mutation interval in the searchable run. Its results
are retained as diagnostic evidence rather than promoted to an absolute baseline.

### Live composition

The retained JDBC graph profile used 1,024 roots, 5 children per root, 5,120 total children, 50 iterations and explicit
one-byte child payload documents:

| Scenario | p50 | p95 | p99 |
| --- | ---: | ---: | ---: |
| prefiltered 1 root → 5 children | 2.452 ms | 3.108 ms | 3.424 ms |
| prefiltered 1,024 roots → 5,120 children | 50.557 ms | 60.818 ms | 72.860 ms |
| full-view selective result over all candidates | 48.956 ms | 50.754 ms | 73.070 ms |
| full-view broad result over all candidates | 47.227 ms | 49.171 ms | 50.790 ms |

The same 1,024/5,120 profile with 1-KiB child documents measured 103.967 / 111.280 / 116.489 ms p50/p95/p99 for broad
composition. Child-document loading took roughly 58–67 ms. This confirms that total source bytes, not only node count,
must remain an explicit live-composition bound.

## Verification

- Common stitch/search and JSON/CBOR protocol tests cover complete-view constraints, sorting, paging, path filtering,
  path overrides and same-path list composition.
- SDK fixture tests cover live fallback, materialized AUTO, forced live, default `ObjectNode`, descendant constraints,
  non-searchable graph children and projection path overrides.
- Runtime endpoint tests prove candidate queries do not consume graph constraints, graph constraints run after
  stitching, path overrides are honored and oversized candidate sets fail before traversal.

Full reactor and site/Javadoc results are recorded in the Phase 19 evidence entry in the main backlog.
