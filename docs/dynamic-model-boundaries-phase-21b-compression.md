# Phase 21b production-code compression

This report records the deletion audit, implemented ownership corrections and paired measurements against the
immutable Phase 21 baselines:

- runtime `552ead1e`;
- SDK `e5d30518003`.

The budgets count all hand-maintained production source under `src/main`. Formatting, generated equivalents, moving
SQL to resources, deleting public documentation and moving implementation across modules do not count as design
compression.

## Ownership inventory

After the accepted consolidations, the production deltas against `origin/main` are:

| Repository area | Net lines | Retained responsibility |
|---|---:|---|
| Runtime model JDBC | +6,236 | atomic actions, heads, streams, shared payloads, temporal relations, hard erasure, update feed and graph projection |
| Runtime search JDBC | +1,153 | synchronous fenced documents, snapshots, graph documents and irreversible erasure |
| Runtime model core | +873 | bounded graph/lineage algorithms, backpressure and store contract |
| Runtime remaining integration | +835 | endpoints, shared cycle validation, metrics and legacy-neutral wiring |
| **Runtime total** | **+9,097** | |
| SDK model wire API | +3,343 | action, stream, relation, deletion, projection and tracking protocol |
| SDK graph search/common | +1,046 | graph constraints, stitching and relation-aware search |
| SDK modeling implementation | +5,850 | metadata, target/action planning, automatic handlers, conflict evaluation and commit preparation |
| SDK repositories and event clients | +6,191 | current/historical reconstruction, snapshots, cache tracking and fixture/local semantics |
| SDK remaining integration | +2,017 | configuration, search clients, handlers and test-server endpoints |
| **SDK total** | **+18,447** | |

The direct immutable-commit comparison removes 895 runtime and 1,361 SDK production lines. Because `origin/main`
advanced by 64 SDK lines after the Phase 21 checkpoint, subtracting today's branch totals from the historical +19,872
checkpoint would misleadingly report 1,425. The requested ceilings still require another 2,097 runtime and 4,447 SDK
lines against current `origin/main`. Those lines cannot be obtained by deleting another owner of the same state: they
now belong to retained wire contracts or to the sole implementation of accepted behavior.

## Accepted deletions

- Removed the runtime-only `ModelStreamRequest`/`ModelStreamEntry` protocol and its second batch stream loader. Runtime
  tests now exercise the public `GetModelEvents` path used by real clients.
- Consolidated temporal child relation queries around one set-based implementation.
- Gave `ModelErasureKeys` ownership of the shared HMAC tokenizer used by model and search stores.
- Reduced the update log to one time-ordered action result plus a compact lifecycle marker. Ordinary target JSON and
  deletion state are no longer duplicated in the feed.
- Removed the unused update-log index while retaining range partitioning and direct ordered reads.
- Unified SDK action/rebase loading, handler target planning, parameter merging, apply evaluation and repository
  reconstruction around shared plans and helpers.
- Removed the second, SDK-driven direct-materialization protocol and its seven wire types. A successful action result
  now has one meaning: the runtime has completed direct documents and snapshots. Split-store failures remain durable
  in the action row and the runtime drains them after retry or restart.
- Removed `materializationApplied` from action results, duplicate pending booleans and SDK-side snapshot writes. The
  durable projection itself is the pending marker and the runtime is the only production owner of applying and
  clearing it.
- Moved ordered relation-cycle validation into `common`, so JDBC, `LocalClient`, synchronous/asynchronous fixtures and
  the test server enforce the same pre-mutation rule without two validators.
- Merged model parameter resolution, action target planning and post-commit cache publication paths, and made
  cache-tracker shutdown atomically cancel the outstanding long poll.
- Added a no-relationship fast return before temporal graph loading and descriptor reconciliation.

These are ownership deletions, not formatting compression. No SQL was moved to resources, no Javadocs or tests were
removed for the budget and no implementation was hidden in generated code.

## Rejected spikes

### Use the hash-partitioned action table as the update tracker

Removing the time-ordered update feed improved commit throughput by roughly 16–17%, reduced physical amplification by
roughly 6% and WAL by roughly 10%. It also made every tracker catch-up merge all eight hash partitions and reduced
tracker throughput by 3–7%. A covering result index was not viable: PostgreSQL rejected large multi-target results
because index tuples exceed 2,704 bytes. The spike was discarded.

### Join action updates and lifecycle deletions during every tracker poll

Keeping hard deletion only in its lifecycle table removed a small duplicate marker, but the ordered `UNION ALL` query
reduced tracker catch-up from the 136–142k updates/s baseline range to 93,452 updates/s. The spike was discarded. A
compact lifecycle marker in the existing update feed restored one ordered read without creating a second authority.

## Paired performance checkpoint

The longer identical PostgreSQL/JVM run used 20,000 warm actions, 20,000 one-target 1-KiB actions, concurrency 256,
20,000 current loads in batches of 128 and two load iterations.

| Measurement | Phase 21 | Phase 21b | Change |
|---|---:|---:|---:|
| Commit throughput | 8,906 actions/s | 9,131 actions/s | +2.5% |
| Commit p50 | 22.870 ms | 22.487 ms | -1.7% |
| Physical storage | 36.34 MiB | 36.34 MiB | unchanged |
| WAL | 46.44 MiB | 44.72 MiB | -3.7% |
| Action-boundary lookup | 6,680/s | 6,763/s | +1.2% |
| Update tracking | 156,343/s | 196,859/s | +25.9% |
| Current model load | 25,620/s | 25,572/s | -0.2% |

The final isolated confirmation built both immutable commits in detached worktrees and alternated their execution
order against the same PostgreSQL instance:

- two 20,000-action core runs per version averaged 6,428 actions/s for Phase 21 and 6,424 actions/s for the final code;
  current event loads averaged 25,842 and 25,871 models/s respectively;
- physical bytes and WAL were unchanged within 0.1%; action-boundary lookup was within 0.6%;
- update tracking improved from approximately 317k to 385k updates/s;
- direct 1-KiB document plus 1-KiB snapshot materialization was repeated in both orders. Throughput differed by less
  than 1%, p50 and p99 had no stable regression, physical amplification differed by at most 0.4% and WAL remained
  within run-to-run noise. The initially higher current p95 reversed when execution order was reversed, so it was not
  attributable to the implementation.

The complete SDK reactor, including test server, proxy, annotation processor and Java/Kotlin downstream projects,
passes. The complete runtime reactor passes 612 tests. Site/Javadocs completes, `git diff --check` is clean, and
focused cycle, materialization-restart and cache-tracker tests pass.

## Gate conclusion

No remaining deletion of the required size is merely duplicate ownership:

- deleting the SDK in-memory action implementation removes `LocalClient`, synchronous/asynchronous `TestFixture` and
  test-server parity rather than reusing another dependency-safe implementation;
- deleting runtime update or lifecycle state breaks measured tracking, restart recovery or erasure;
- replacing manual compact persistence with generic serialization may save hundreds, not thousands, while increasing
  bytes, allocations and compatibility risk;
- moving wire types, SQL or algorithms to another module only relocates the same maintained code.

The implementation is now materially smaller and has one owner for each direct write, snapshot, relation validation
and cache update. It is also at least as fast on the measured paths. It does **not** meet the requested +7,000/+14,000
ceilings: runtime remains +9,097 and SDK +18,447. Closing that numerical gap now requires removing accepted
capabilities, hiding maintained implementation or replacing set-based/hot-path code with denser generic machinery.
Those would violate “as simple as possible, but not too simple”. The line-budget checkboxes therefore remain open
rather than turning a useful forcing function into a dishonest completion claim.
