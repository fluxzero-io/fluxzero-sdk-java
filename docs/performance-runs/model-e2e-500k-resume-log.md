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
| Current code status | Production remains Runtime `7ac06794`; fused state advance is rejected and reverted; detailed model JFR instrumentation is diagnostic-only and uncommitted |

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

## E460-E464: fused state validation/advance rejected

The first candidate replaced the state-row `SELECT FOR UPDATE` plus later `UPDATE` with one conditional
`UPDATE ... RETURNING` inside the existing packed model/event transaction. A same-binary property selected the old or
new path, so the ABBA comparison changed neither the observer nor any other route component. All five runs retained the
exact 1,048,576 results, model events and global events.

| Run | Path | Profiling | Throughput | p50 / p95 / p99 / max |
| --- | --- | --- | ---: | --- |
| E460 | control A1: separate lock/read + update | none | 353,281/s | 153.625 / 226.068 / 250.816 / 287.798 ms |
| E461 | candidate B1: conditional state advance | none | 357,320/s | 147.860 / 213.802 / 238.665 / 268.949 ms |
| E462 | candidate B2: conditional state advance | none | 341,129/s | 156.607 / 243.887 / 268.310 / 325.450 ms |
| E463 | control A2: separate lock/read + update | none | 342,779/s | 152.405 / 235.174 / 286.017 / 304.202 ms |
| E464 | candidate profile | batch JFR | 338,626/s | 160.657 / 227.408 / 260.223 / 317.078 ms |

The control geometric mean is 347,990/s and the candidate geometric mean is 349,131/s: only **+0.33%**. The profile
does prove the local mechanism. Conditional advance takes 2.070 ms versus 2.870 ms for the former lock/read plus update,
a 28% local reduction, and measured ordered-store capacity rises from 0.407M/s to 0.429M/s. PostgreSQL work shifts,
however: stream insertion rises 3.744 -> 4.184 ms and direct global-event insertion 4.967 -> 5.843 ms. The complete
transaction consequently rises 17.019 -> 17.425 ms despite fewer and larger transactions.

That is insufficient route benefit for a higher-risk authoritative-state protocol change. The candidate is rejected
and fully removed; Runtime production source remains `7ac06794`. The next investigation must target physical work in
the already ordered model/event transaction and must first account for prior exclusions: denser 4,096-membership
stream blocks were already rejected in E142-E145, and earlier preinsert/multilane variants damaged complete-route
batching and PostgreSQL contention.

Artifacts:

- E460 control log SHA-256: `b6f571edf4905754d1198bb33f346b066dac16a7b5e51db80d9cab4f20cbecdc`;
- E461 candidate log SHA-256: `a12466af16073285b49d0f04d7ebb5d6c9a239338468617693a412a3978228c2`;
- E462 candidate log SHA-256: `d49b6375651fd836ac6775cd606822c912a33cf580026d409541e781010d3ded`;
- E463 control log SHA-256: `c2d44cac75650be86455b77185501020b45c78c3d34a68907ffbf4410293f2cf`;
- E464 profile log/JFR/summary SHA-256: `16ace38d54bc5ffb6723cf59e9c76cb3cee6ef57f2e1bac72032adc0037f5c02`,
  `8a6a7975b3f7a6ae0805c81fccafef2d240603469c696716cb700eb4160fdbc8`,
  `84ff077763e2c713d17e35175a085a2cf98a0513bf4412252277fa4cb98ac9a7`.
