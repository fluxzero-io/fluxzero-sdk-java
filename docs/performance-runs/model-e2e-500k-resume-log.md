# Full-model 500k campaign resume log

## Live scoreboard

| Item | Current state |
| --- | --- |
| Scope | Optimize only the model path. Command and result paths remain intact as E2E context and regression sensors, but are no longer optimization targets. |
| No-model transition gate | E452/E453: **1,012,685 / 1,010,338 commands/s**, profiler-free and fully durable |
| Current full-model production pin | E454: **350,218 commands/s**, profiler-free, exact results + model events + global events |
| Three-run clean control geometric mean | E454/E456/E458: **347,691 commands/s** |
| Target | At least **500,000 commands/s** on the same complete model/event/result route |
| First measured model limiter | One ordered packed model/event transaction: **0.407M models/s** active service in E459 |
| Current code status | Production remains Runtime `7ac06794`; detailed model JFR instrumentation is diagnostic-only and uncommitted |

## Canonical identity after the no-model transition gate

The full-model route uses Java 25, an embedded Runtime, an 8-GiB fixed heap, the latest SDK defaults
`2026.07.27`, one sender, 65,536 models, a 65,536-command caller window, 65,536 warm-up updates and 1,048,576
measured updates. Every qualifying run requires exactly 1,048,576 ordinary results, stored model events and global
events.

Message-store settings deliberately return to the ordinary full-model identity: a 4,096-message backlog, unbounded
command/result storage batches, 65,536-entry command and result cache count ceilings, 64-MiB byte ceilings and
authoritative cache-tail parking. The no-model route's 1,048,576-entry command count ceiling is not inherited. The
model commit store itself still uses an ordered asynchronous Backlog and therefore admits one model batch at a time.

`maxInFlightBatches=16` is not a production default. Exact-envelope E386-E389 rejected it for the no-model route at
-4.73% against unbounded admission. `sdkModelCommit.maxInFlight=65536` is a different setting: it is the caller request
window and remains identical in both routes.

## E454-E459: clean pin and detailed model segmentation

E454 and the reverse controls use a detached, independently compiled Runtime worktree at committed `7ac06794`. This
ensures the uncommitted model observer cannot affect the production pin. E455/E457 run the same complete route with
the observer bytecode present but JFR disabled. E459 enables batch JFR only for the measured phase.

| Run | Runtime shape | Profiling | Throughput | p50 / p95 / p99 / max |
| --- | --- | --- | ---: | --- |
| E454 | clean committed control | none | **350,218/s** | 151.636 / 224.207 / 245.425 / 266.680 ms |
| E455 | first detailed observer | none | 340,888/s | 157.979 / 221.733 / 241.835 / 267.280 ms |
| E456 | clean reverse control | none | **348,905/s** | 151.514 / 216.129 / 247.795 / 286.589 ms |
| E457 | call-site-gated observer | none | 340,894/s | 159.673 / 229.970 / 260.952 / 304.175 ms |
| E458 | second clean reverse control | none | **343,981/s** | 153.435 / 227.226 / 253.914 / 286.047 ms |
| E459 | call-site-gated observer | batch JFR | 329,426/s | 158.667 / 237.194 / 284.609 / 343.573 ms |

The clean controls have a geometric mean of **347,691/s**. E455/E457 average geometrically to 340,891/s, 1.96%
lower, but the moving clean controls narrow the direct E457/E458 difference to 0.90%. The observer is therefore valid
for diagnostic profiling but is not accepted as zero-overhead production code.

E459 verifies the full 1,048,576/1,048,576/1,048,576 result/model/global-event contract. Its fundamental model
storage segments are:

| Segment | Batches × items | Mean | p95 | Active service capacity | Exact meaning |
| --- | ---: | ---: | ---: | ---: | --- |
| Ordered packed model/event transaction | 152 × 1,048,576 | **17.019 ms** | 35.204 ms | **0.407M/s** | Complete Runtime transaction lifetime after a model batch is admitted; one active transaction. |
| Packed fast-path validation | 152 × 1,048,576 | 1.238 ms | 3.552 ms | 5.573M/s | Validate unique commits/targets and cached predecessor heads before storage. |
| Packed stream preparation | 152 × 1,048,576 | 1.288 ms | 4.438 ms | 5.356M/s | Build state-indexed compact model entries, blocks and write index. |
| Model state row lock/read | 152 × 1,048,576 | **1.773 ms** | 4.932 ms | 3.890M/s | Lock and read the authoritative state head and erasure/graph flags. |
| Model stream-block insert | 152 × 1,048,576 | **3.744 ms** | 10.075 ms | 1.843M/s | Insert the compact per-model stream representation inside the atomic event transaction. |
| Model state-head update | 152 × 1,048,576 | **1.097 ms** | 3.311 ms | 6.288M/s | Advance the authoritative model state index in that same transaction. |
| Co-located event-log task | 152 × 1,048,576 | **6.749 ms** | 15.658 ms | 1.022M/s | Model SQL executed through the event-log transaction callback; overlaps the encompassing transaction. |
| Event direct LTS insert | 149 × 1,048,471 | **4.967 ms** | 13.417 ms | 1.417M/s | Insert packed global-event log blocks in the same durability boundary. |
| Event transaction commit | 152 × 1,048,576 | **1.837 ms** | 4.578 ms | 3.755M/s | PostgreSQL commit of model plus global-event work. |

Nested and overlapping rows must not be added blindly. The actionable fixed-cost pair is the state lock/read plus
state-head update: 2.870 ms per transaction and two PostgreSQL round trips around one already ordered state row. The
first candidate will combine their validation and advance into one conditional state mutation inside the same
transaction. It must retain one transaction, full atomic rollback, erasure/graph flags, read-state validation,
cross-Runtime predecessor validation, ordered visibility and completion only after the real commit.

The asynchronous model-stream locator remains substantial background work (roughly 20.6 ms across its four sequential
phases per page), but E52/E53 already proved that reducing its aggregate SQL cost did not improve canonical E2E and
could worsen foreground contention. It is not selected ahead of the directly limiting ordered model transaction.

Artifacts:

- E454 log SHA-256: `17e2b18e930f7308faa220b5a4c47d02b29f1c89c7188a59de750f34258d64bd`;
- E455 log SHA-256: `e3b33e021313be8fa88494f9b242cee60ab0dbf1cd7b1680a83be23f3e32a203`;
- E456 log SHA-256: `bca51cec228121be2a1dcb726d5779b7a9fd8488b384079a054db323321ef399`;
- E457 log SHA-256: `bb5c52982165669fc50a68411c30988b0c78fa7943ffb5fc8e41a314cb9b52f7`;
- E458 log SHA-256: `d835ecea815c38a372b249bf9913cf78c474b29fb66a877323e78b5009179d7e`;
- E459 log/JFR/summary SHA-256: `b0f7e9ecaa3226ac21ecec678c9c53f1f538a1cd86e6b1eff2d1546885833e70`,
  `178b4233729570a9af4a3ea9e24cf8656eab1ac4a0e2183e4e18012d0ed68c7d`,
  `5dc0b0787746dced2a9601b74c2b80b6ec471e30ababc28bc8390b2de9e6b443`.
