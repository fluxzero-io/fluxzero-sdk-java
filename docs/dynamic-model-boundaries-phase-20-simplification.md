# Dynamic model boundaries — Phase 20 production-code simplification

## Scope and baseline

Phase 20 started at SDK commit `6e6c81d609025e4037d555547825cffbb23ea207` and runtime commit
`5c67e333cf1281e6e8e827e41e0fc2122c5b0a1c`. The strict `origin/main...HEAD` entry count was:

| Repository | Production source | Test source |
|---|---:|---:|
| SDK | +20,071 / -86 | +15,311 / -11 |
| Runtime | +16,249 / -12 | +14,452 / -11 |

The largest production additions were `DefaultModelRepository` (+2,520), `InMemoryEventStore` (+2,271),
`ModelActionHandlerRegistry` (+1,342) and `ModelActionCommitter` (+1,150) in the SDK, and
`JdbcModelActionStore` (+7,007), `JdbcModelGraphProjectionStore` (+2,200), `JdbcSearchStore` (+1,526) and
`InMemoryModelActionStore` (+1,387) in the runtime.

## Absolute Phase 20 delta

The phase changed substantially more code than its net line count suggests:

| Repository/source | Added | Deleted | Net | Absolute lines touched |
|---|---:|---:|---:|---:|
| SDK/common production | 453 | 0 | +453 | 453 |
| SDK module production | 80 | 646 | -566 | 726 |
| **SDK production total** | **533** | **646** | **-113** | **1,179** |
| SDK/common tests | 209 | 0 | +209 | 209 |
| SDK module tests | 178 | 0 | +178 | 178 |
| Runtime production | 56 | 461 | -405 | 517 |
| **Combined production** | **589** | **1,107** | **-518** | **1,696** |
| **Combined phase including tests** | **976** | **1,107** | **-131** | **2,083** |

Against `origin/main`, SDK production moved from +20,071/-86 to +19,977/-105. That is why the visible branch
balance changes by only 113 lines even though 1,179 SDK production lines were edited: the shared 422-line validator
replaces two larger implementations, including one in the other repository.

## Simplifications retained

- Independent-model wire validation now lives with the protocol in `common`. The SDK in-memory store and runtime use
  the same rules; the runtime keeps a small compatibility facade for its internal stream request.
- Direct document and snapshot extraction is owned by `MaterializeModelAction.from(...)` instead of being copied in
  `ModelActionCommitter` and `InMemoryEventStore`.
- Ordinary, relation-aware model and graph search use one synchronous and one asynchronous pagination algorithm.
  Request factories preserve each distinct wire action and its relation/composition options.
- Runtime graph and ancestor reads share one action/state-boundary resolver.
- Runtime hard-deletion paths share one rows-to-deletion-batch conversion.

Fresh CPD reports confirm that the materialization, pagination and deletion-batch duplicates are gone. The remaining
small graph-result assembly duplicates follow different graph traversals and were not abstracted merely to reduce a
line count.

## Simplifications deliberately rejected

- Moving the duplicated model-head transition from the JDBC and in-memory planners into `ModelHead.advance(...)`
  initially reduced more code, but a same-machine A/B exposed a roughly 4% short-run write regression. The abstraction
  was removed before commit. CPD therefore still reports 23 duplicated planning lines; this is an intentional hot-path
  optimization.
- `JdbcModelActionStore` and `JdbcModelGraphProjectionStore` remained large because splitting their transaction-local
  SQL into cosmetic collaborators would only move code. A subsequent whole-runtime architecture review found a deeper
  issue that this local simplification phase did not address: receipts, materialization recovery, cache tracking,
  projection signals/tasks and model-specific search lifecycle form parallel infrastructure instead of composing the
  runtime's existing durable logs and tracked consumers. Phase 21 therefore supersedes the earlier conclusion that no
  further structural reduction was warranted; it requires shared ownership and deleted state machines, not cosmetic
  class splitting.
- Existing compiled reflection plans and `ReflectionUtils.TypeMetadata` ownership were retained. No parallel
  class-keyed reflection cache was introduced.

## Preserved contracts

The refactor changes no public request shape, persisted table or index, state/event ordering, transaction boundary,
publication guarantee, retry/idempotency result, graph placement, deletion lifecycle or aggregate path. Local,
test-fixture and production runtime validation now converge rather than drift. Pagination still emits the exact
requested page sizes and cursor sequence for ordinary, model-relation and graph searches, synchronously and
asynchronously.

## Performance gate

The baseline was built from detached worktrees and installed separately before each measurement. The final runtime was
then installed and measured with the same JVM, PostgreSQL instance and benchmark parameters.

### Single-target action/store and load

Twenty thousand measured 1-KiB actions, 3,000 warm-up actions, concurrency 128:

| Metric | Baseline | Final | Change |
|---|---:|---:|---:|
| write throughput, mean of two | 4,478 actions/s | 4,421 actions/s | -1.3% |
| current load, mean of two | 26,090 models/s | 27,397 models/s | +5.0% |
| physical amplification | 1.94x | 1.93x | unchanged |
| WAL amplification | 3.05x | 3.02x | unchanged within run variance |

The write difference is below the observed run-to-run variation and there is no detectable regression.

### Ten-target action with relationships

Five thousand measured actions, ten 1-KiB targets per action, concurrency 128. Three normal runs averaged 441 baseline
versus 449 final actions/s. A ten-iteration load profile then measured 18,962 baseline versus 18,922 final models/s
(-0.2%). Physical amplification remained 9.42x and WAL remained within PostgreSQL checkpoint variance.

The end-to-end aggregate/model harness also completed its root, child, grandchild, cross-branch, move, delete and
recreate matrix. In the retained non-searchable run, the model used 66.9% of aggregate allocation and 83.5% of
aggregate WAL. Phase 19 remains the evidence for unchanged graph stitching, split-store projection and contention
paths; Phase 20 did not alter their SQL or execution model.

## Verification

- Focused common validator/materialization, SDK pagination, in-memory model-action and committer suites passed.
- The complete nine-module SDK reactor passed, including test-server, proxy, annotation processing and Java/Kotlin
  downstream projects.
- The complete four-module runtime reactor passed, including the full PostgreSQL integration suite.
- `./mvnw -B site -Pjavadoc` completed successfully.
- Fresh 80-token CPD reports and `git diff --check` passed in both repositories.
- A separate final-diff review covered null/malformed wire inputs, cursor termination, mutable request templates,
  materialization ownership, historical boundaries, deletion batches, allocation and mixed SDK/runtime compatibility.
