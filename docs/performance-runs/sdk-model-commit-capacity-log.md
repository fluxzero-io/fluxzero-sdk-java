# SDK-inclusive model-commit capacity log

## Live scoreboard

| Route | Current exact pin | Runtime store path | Store service capacity | Role in campaign |
| --- | ---: | --- | ---: | --- |
| Full command -> model -> event + result E2E | **359,508/s** matched geometric mean; best exact run **364,430/s** | `commit-packed-update` | **0.431M/s** in E531 | Sole acceptance gate for the 500k target |
| Low-level SDK `CommitModels` update round trip | **595,877/s** without JFR | `commit-packed-update` | **0.781M/s** in E488 | Runtime/wire upper-bound diagnostic |
| Direct SDK `assertAndApply(command)` | 80,074/s without JFR | `commit-general` | **0.108M/s** in E491 | Separate direct-API/idempotency diagnostic; not a proxy for tracked E2E |

Production is Runtime `87327b71` (`perf(modeling): compact derived stream locators`). The full model campaign remains
scoped to model work. Command and ordinary result paths remain present only in the canonical E2E acceptance route and
are not optimization targets.

## Route definitions

### Low-level SDK model-update round trip

This route retains:

1. SDK `EventStoreClient.commitModels`;
2. model wire encoding, CBOR, LZ4 and two SDK event-sourcing sessions;
3. the Runtime WebSocket endpoint;
4. the real `JdbcModelCommitStore`, global event log and PostgreSQL commit;
5. the durable model-stream locator gate;
6. `CommitModelsResult` encoding, WebSocket return and SDK result completion.

It deliberately starts from already formed `CommitModels` requests. It therefore excludes SDK model loading and
caching, assertions, interceptors, `@Apply`, transition planning and model-event serialization. It also excludes the
command log, command tracking and ordinary result log.

### Direct SDK model apply

This route calls the public instance extension point behind `Fluxzero.assertAndApply(command)`. It retains SDK model
loading/cache lookup, assertions, interceptors, `@Apply`, transition planning, event serialization, SDK commit
batching, the WebSocket/Runtime boundary and durable model plus global-event storage. Sixteen SDK worker threads keep
at most one update per model in flight, with 65,536 conflict-free model slots. It excludes command publication,
command tracking and the ordinary result log.

This direct API does **not** have a tracked source message index. `ModelCommitter` must therefore leave
`possibleDuplicate` unknown so a transport retry can still be recognized through durable commit receipts. Runtime
correctly routes these commits through the general idempotent path. The route is valuable, but it is not the packed
automatic-command route minus two cheap stages.

## E483-E488: qualify the low-level SDK boundary

E483 tried the older direct `JdbcModelCommitStoreBenchmark`. It failed at commit 65,536 because a second cycle reused a
stale read-state boundary. That benchmark is rejected as the campaign's main isolated route: it neither includes the
SDK transport boundary nor models repeated updates correctly.

E484 and E486 used `WebSocketModelCommitBenchmark`, but profiling exposed a benchmark-semantic error:
`expectedSequenceNumber` was always `-1`. The Runtime therefore measured `commit-initial`, not model updates. These
runs remain useful initial-create diagnostics but are not comparable to the full update route. E485 never started the
Runtime or touched the database because the destructive-benchmark opt-in was omitted.

E487/E488 maintain the exact expected sequence for every repeated model. Both verify exactly 1,048,576 durable
results, model memberships and globally published events.

| Run | Semantics | Profiling | Round-trip throughput | Store path | Store batches / mean size | Store mean | Store service capacity |
| --- | --- | --- | ---: | --- | ---: | ---: | ---: |
| E484 | initial create, misqualified as update | none | 566,957/s | `commit-initial` | not observed | not observed | not observed |
| E486 | initial create | profile JFR | 537,064/s | `commit-initial` | 257 / 4,080 | 5.661 ms | 0.721M/s |
| E487 | exact repeated update | none | **595,877/s** | expected packed update | not observed | not observed | not observed |
| E488 | exact repeated update | profile JFR | 580,456/s | `commit-packed-update` | 256 / 4,096 | 5.243 ms | **0.781M/s** |

E488's measured model batches have p50/p95/p99/max sizes of 3,904 / 6,934 / 7,399 / 8,071. Its caller window is
8,192, so the route cannot create the 20k-30k transactions seen in canonical E2E.

## Why 0.781M/s does not contradict E459's 0.407M/s

Both profiles use the same packed Runtime update implementation, but feed it materially different work:

| Measurement | E459 full E2E | E488 low-level SDK update |
| --- | ---: | ---: |
| Store throughput during profiled run | 0.332M/s | 0.542M/s |
| Active ordered-store capacity | **0.407M/s** | **0.781M/s** |
| Transactions | 152 | 256 |
| Mean batch | 6,899 | 4,096 |
| p95 / max batch | **21,323 / 30,176** | **6,934 / 8,071** |
| Mean transaction storage | **16.937 ms** | **5.243 ms** |
| Model-store observed bytes | 1,334 MiB | 1,138 MiB |
| Global-event observed bytes | 362 MiB | 178 MiB |
| Stream-block insert mean | 3.744 ms | 1.091 ms |
| Co-located event work mean | 6.749 ms | 1.995 ms |
| State lock/read mean | 1.773 ms | 0.501 ms |
| State advance mean | 1.097 ms | 0.380 ms |

The low-level request contains a compact 32-byte event payload and stops at 8,192 open calls per wave. Canonical E2E
serializes the real `UpdateModel` event and the SDK commit feedback occasionally forms transactions above 30,000
updates. Larger batches reduce commit frequency, but these profiles show strongly nonlinear transaction cost at that
shape. This is a causal candidate to validate on the full E2E route; the isolated 0.781M/s number is not itself an
acceptance result.

## E489-E492: direct `assertAndApply` is a different durability contract

| Run | Type | Profiling | Updates | Throughput | Main observation |
| --- | --- | --- | ---: | ---: | --- |
| E489 | smoke | none | 131,072 | 151,619/s | Exact durable direct-call route works |
| E490 | qualification | none | 1,048,576 | 80,074/s | Long route is far below tracked E2E |
| E491 | profile | batch JFR | 1,048,576 | 106,820/s | 33 general transactions, mean 31,775 and 293.428 ms; store capacity 0.108M/s |
| E492 | causal diagnostic | none | 131,072 | 141,720/s | Every fast-path rejection is `possible-duplicate` |

E492 enabled the Runtime's existing packed-outcome counter. Across create, warm-up and measured phases all 262,144
candidates reported `possible-duplicate`; no shape, policy, read-set, cache-head or sequence rejection occurred.
Forcing `possibleDuplicate=false` would change retry/idempotency correctness and is forbidden. Improving the direct API
would require a separately designed idempotency mechanism and is not selected ahead of the canonical 500k target.

## E493-E507: transaction-count cap rejected after clean BAAB

E459 showed that canonical E2E occasionally formed packed model transactions above 30,000 jobs and that their
transaction cost grew nonlinearly. E493-E503 therefore screened the Runtime's existing, no-delay
`fluxzero.maxModelCommitBatchSize` override. This cap only limits how many already available jobs one backlog drain
takes; it introduces no timer or deliberate wait. The independent 512-MiB byte cap remained unchanged.

The first, chronologically separated comparisons looked promising:

| Setting | Non-JFR runs | Geometric mean | Apparent effect versus nearby 65k controls |
| --- | --- | ---: | ---: |
| 65,536 control | E497 344,128; E503 342,911/s | 343,519/s | control |
| 16,384 | E500 357,110; E502 351,765/s | 354,427/s | +3.18% |
| 8,192 | E495 356,989; E496 356,949/s | 356,969/s | +3.73% versus E494/E497 |
| 4,096 | E499 330,368/s | n/a | clearly worse |

The profiles confirmed that the cap worked mechanically. E501 at 16,384 had no transaction above 16,384 jobs,
mean 6,722 jobs, 16.872 ms mean active storage, 0.398M/s active store capacity and a 32.860 ms p95 queue wait.
E498 at 8,192 had mean 6,394 jobs, 17.935 ms active storage, 0.357M/s active store capacity and a 66.685 ms p95
queue wait. Neither profile reported durability or correctness failures. These measurements support the transaction
shape hypothesis, but do not by themselves prove E2E gain.

Before checkpointing, the proposed 16,384 default was rebuilt from Runtime `7ac06794` in a clean worktree with no
observer or benchmark-source changes. `JdbcModelCommitStoreTest` passed 99/99 and `./mvnw -B install` passed, after
which the exact full route was run as a tightly adjacent BAAB comparison on one binary:

| Setting | Clean runs | Geometric mean | Matched effect |
| --- | --- | ---: | ---: |
| 16,384 candidate | E504 343,323; E507 354,537/s | **348,885/s** | **-0.12%** |
| 65,536 property control | E505 355,395; E506 343,304/s | **349,297/s** | control |

This clean BAAB result explains the earlier apparent improvement as time/host correlation rather than a causal
production gain. The default change was reverted and is not a checkpoint. The useful retained result is methodological:
transaction count materially changes store shape, but 16,384 does not raise canonical E2E on the clean production
binary. Future work must target the measured model-store work itself rather than tune this cap further.

## E508-E512: obtain a clean full-route profile before selecting code

Three profiler configurations were deliberately rejected before drawing a hotspot conclusion:

| Run | Throughput | Qualification | Decision |
| --- | ---: | --- | --- |
| E508 | 272,061/s | Internal JFR included request-stage trace metadata | Invalid for CPU/allocation ranking |
| E509 | 337,564/s | External recording missed the measured phase | Invalid timing window |
| E510 | 266,464/s | External `profile.jfc` still enabled Fluxzero custom events | Invalid clean-profile control |
| E511 | 335,997/s | External CPU/allocation profile with Fluxzero request/batch events disabled | Accepted diagnostic profile |
| E512 | 339,271/s | Clean non-JFR route plus PostgreSQL statement/table statistics | Accepted causal baseline |

E511 recorded 32,030.7 MiB allocations, seven garbage collections totalling 262.8 ms and a 7,385.7 MiB maximum
heap sample. Generic command/result work remained visible, but the campaign scope forbids optimizing those already
qualified routes. E512 instead exposed a model-specific multiplicative write: the derived stream locator wrote one
row for every model-hash membership. Across seed, warm-up and measurement this was about 1.18 million locator rows,
even though the authoritative model stream consisted of only about 1,361 stream blocks.

## E513-E519: prove locator headroom and reject timing-based coalescing

The existing diagnostic locator bypass preserved the complete command, model/event durability and result route but
omitted the derived lookup index. It is not production-correct; it is an upper-bound ablation used only to measure how
much full-route throughput is available in this one cost.

| Variant | Runs | Geometric mean | Effect versus matched control | Decision |
| --- | --- | ---: | ---: | --- |
| Locator enabled | E514 346,542; E515 349,382/s | **347,959/s** | control | retain |
| Locator bypassed | E513 375,923; E516 382,196/s | **379,047/s** | **+8.93%** | causal headroom only |
| 25 ms collection delay | E517 352,499/s | n/a | no convincing gain | reject |
| 50 ms collection delay | E518 350,182/s | n/a | no convincing gain | reject |
| 100 ms collection delay | E519 348,226/s | n/a | no gain | reject |

The delay series rules out “too many locator commits” as the primary mechanism. Waiting longer can coalesce work, but
does not remove the row and index amplification and violates the no-artificial-pause batching principle.

## E520-E524: isolate index maintenance from locator row materialization

| Variant | Runs | Geometric mean | Effect versus control | Decision |
| --- | --- | ---: | ---: | --- |
| Per-hash btree control | E521 353,063; E523 339,723/s | **346,329/s** | control | retain temporarily |
| Per-hash PostgreSQL hash index | E520 343,017; E522 341,222/s | **342,118/s** | **-1.22%** | reject |
| Per-hash heap rows, no lookup index | E524 359,843/s | n/a | diagnostic upper bound | no production use |

PostgreSQL used the hash index, so its loss is not a planner accident. E524 shows that both the 1.18 million physical
rows/COPY values and index maintenance matter. Merely changing the index implementation cannot remove the dominant
multiplicity.

## E525-E531: compact one stream-block membership set into one locator row

The accepted representation stores one `integer[]` of model hashes per stream block and hash partition, with a GIN
overlap lookup. It changes only the derived, rebuildable locator; authoritative model events, stream blocks, sequence
numbers and the global event log are unchanged.

| Run | Route/variant | Throughput | Additional result | Decision |
| --- | --- | ---: | --- | --- |
| E525 | Full E2E compact locator | 354,651/s | matched candidate A | accept after pair |
| E526 | Full E2E compact locator | **364,430/s** | matched candidate B | accept after pair |
| E527 | Full E2E per-hash control | 343,236/s | matched control B | control |
| E528 | Compact locator plus large read validation | 369,693/s writes | hot 2.196M models/s; cold run later hit the existing event-join/spill path | write-valid, read run incomplete |
| E529 | Compact locator, matched small read route | 115,780/s writes | hot 795,903; cold 48,157 models/s | read-valid |
| E530 | Per-hash control, matched small read route | 108,328/s writes | hot 623,934; cold 47,520 models/s | control |
| E531 | Full E2E compact locator, batch JFR | 358,453/s | active ordered store capacity **0.431M/s** | accepted profile |

Using E523/E527 as the matched controls gives 341,475/s geometric mean. E525/E526 give **359,508/s**, a causal
**+5.28% full-route improvement**. Physical locator rows fell to 10,841 for 1,179,647 model-hash memberships: about
109 times fewer heap/COPY rows. The matched small read route shows no lookup regression: sustained cold reconstruction
rose 47,520 -> 48,157 models/s (+1.34%), while hot lookup also improved. After E531 every GIN index reported zero
pending-list pages and tuples, so the write gain is not deferred maintenance hidden in `fastupdate`.

Startup migration recognizes both the immediately preceding per-hash/btree schema and the older array/GIN schema.
It transactionally truncates and rebuilds only this unlogged derived locator; it never mutates authoritative history.
The existing stream-tail fallback remains available while the locator catches up. Focused migration tests and the
complete Runtime reactor (681 Runtime tests plus benchmark module) pass.

### E531 fundamental active store phases

All values are milliseconds per packed model/event transaction. The store total is composite; the rows below it are
fundamental non-queue phases inside that transaction. Percentiles are per transaction and therefore must not be added
across columns.

| Segment | Exact meaning | Mean | p50 | p95 | p99 | max |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Packed model-store total | Ordered active transaction work after queue admission | **16.891** | 15.835 | **29.817** | 53.159 | 63.595 |
| Co-located global-event write | Insert and commit the globally visible event rows in the same durability operation | **6.952** | 5.168 | **15.685** | 46.749 | 57.410 |
| Insert model stream blocks | Persist per-model event-stream blocks/envelopes | **4.018** | 2.843 | **9.004** | 43.048 | 51.315 |
| Lock current model state | Read and lock the durable state heads used for conflict validation | 1.692 | 1.019 | 4.360 | 9.563 | 25.969 |
| Advance model state | Persist new state indexes, sequences and current heads | 1.143 | 0.766 | 3.782 | 6.376 | 8.061 |
| Ensure model types | Validate/cache the model-type registry rows | 0.005 | 0.005 | 0.009 | 0.026 | 0.031 |
| Unattributed active residual | Locator plus remaining transaction administration not separately instrumented in E531 | **3.081** | n/a | n/a | n/a | n/a |

The separate admission queue wait averaged 12.178 ms (p95 26.733 ms); it is not active service time and is excluded
from the 16.891 ms total. The next profile must split the 3.081 ms residual before choosing production code. The two
largest already named model-specific phases are the co-located global-event write and model stream-block insert, but
their size alone does not yet prove the next mechanism.

## Current decision

1. Runtime `87327b71` is the new accepted production checkpoint; full E2E matched gain is +5.28%.
2. Use E531 and its 0.431M/s active store capacity as the new model-route profile baseline.
3. Keep the low-level SDK update route as a fast secondary physical/wire check, never as the 500k acceptance gate.
4. Keep direct `assertAndApply` as a separate public-API/idempotency observation, not a packed-route proxy.
5. Split the E531 residual and remeasure the named fundamental phases before implementing the next model-store change.
6. Accept production work only through matched full command -> model -> event + result runs with correctness and read
   validation proportional to the affected path.

## Evidence

- E483 log: `/private/tmp/model-e2e-e483-integrated-model-store-qualification.log`, SHA-256
  `0c019327a486eded08179c903d65502b831167f03b3525775cb8d24a57f0ac69`.
- E484 log: `/private/tmp/model-e2e-e484-sdk-model-commit-only-qualification.log`, SHA-256
  `6c04859f124d08ace85f8dbc962c437f056e38a843d060ad4d32d86d97816074`.
- E485 launch-failure log: `/private/tmp/model-e2e-e485-sdk-model-commit-only-profile.log`, SHA-256
  `40f5ae714df16849b11ba99de63f219b87db2f9d729f6d740b72b2a71542d07d`.
- E486 log/JFR/summary SHA-256: `eddc5fc32bc3652bd2aea85c80e52358f4678fa9d650330bb8469a696bf67aa1`,
  `1dc2c3b17e446ec61370052f6a8dd382f19d215bd13cdb8de780d464b7757fea`,
  `c00079791800678e7bbe7a1ccaaba624394fcaeb83b6d0cf09869d1ea26ec8ef`.
- E487 log: `/private/tmp/model-e2e-e487-sdk-model-update-only-qualification.log`, SHA-256
  `b24ff8f4a0c356904db7a04325fa0e25af84edcb8ac03249bb2991726b5f5f12`.
- E488 log/JFR/summary SHA-256: `97fe6c163508766540481be1501b4654a995f6c4825843b1870a24ed7a037bc1`,
  `102e0628894ac8b29e2d45fb0f795574882225cacf5d3758e7fc04777ad12465`,
  `89fc6890704b99aee01a4380bcee56dbc634b885c0a95c6c19e76422918b314d`.
- E489/E490 logs SHA-256: `4382f36323eb281cc5b60a2c0d8b5707da936e4b52e490809ea18d855341a865`,
  `5d05d9574caf3b4da375fe44c0d50654db6af819f1e12e883a50d039c4f562ac`.
- E491 log/JFR/summary SHA-256: `86e7c6a1b41e4fbf9dcae4a25b6c3b40cb5efb6b0b0ae8bef5850c6983d1f2cc`,
  `35315e0e66d722f2f65cecfe6faf05d3eacea65be930eb34e07f6da591e718eb`,
  `1aa6ca48d0aae84169c4933c24ec3e2827cbf412703acf011355c90a2336a9f5`.
- E492 log: `/private/tmp/model-e2e-e492-sdk-assert-apply-packed-diagnostic.log`, SHA-256
  `d0657567d0bd4226a246e56dbdc36be266d786e1c83de34e6895b6f1612e77d5`.
- E493-E497 log SHA-256: `4e4fc7b19387844eaca026095a5a4735b5e0c0be2b8604e4ee32ed73eda4c601`,
  `cf1b1596b5f884090e0c948636205a95bc03fc289b60b01f87424343325c117a`,
  `3f289ac9ce8e0f3b6d79c2f067ca1020d089d6805bfc35e4bda8553528ad3ee3`,
  `a019e7b5dbd307cc16c9df16ee3d0d57a782dcc108d2f933ca03e9f2cf43f6f6`,
  `37d6cfae23fa2483ae0372b8c8139c1db142c8636dd4444ac28f8abbc6a2633d`.
- E498 log/JFR/summary SHA-256: `6429526f0b4736e88aba77ad424678ad6a8c2265d730965ddc09229c2482aa73`,
  `3d41384c8f1178643d237d9c6e32cd151aba58ae48616be4902822302a14db07`,
  `8a0e0073d1c8a4f88b20e21c6a71e4ac3280bfe0a4a8c564be7b89972f55c8d5`.
- E499/E500 log SHA-256: `35f21e414a8bcdf59224fdb17383ef0b4b470debd64ff71f17f4a4b6a681e57c`,
  `166f570d55bea5896a83a2ed3052de5c028318b1005be1d22fc490292c0e1eea`.
- E501 log/JFR/summary SHA-256: `a5d59d5839c9407b79c69af5562fd781a5c62dcdcb1a086062e8e2d14f28347e`,
  `ca2a2922a6af1629fd00af9d4f0417c3fe322e148b39ae3fc4998923a6a5beed`,
  `116b22dd17596f57fdd9004a0e4d287cbaeb48024325bea160b6e7f60f7c5882`.
- E502/E503 log SHA-256: `a90a8940a1b18d06091582c63f366ba8ccd3530eb88b3d6566686acafa655f0e`,
  `5c91a28e855d63871461836843c4cf29d3048bfd54ecbf27f00d18998067039b`.
- E504-E507 clean log SHA-256: `0295b2d23e694976059ea05e42f62b220eaaecdfede24601a49cab1f473db592`,
  `65fdd781ff5bb9c6ac328e5cd29f9c6634859095b331f359069bf87e2c3d6d6c`,
  `d9e8d4f1971314405f8cb2ccfa4890c141f592599bf3898c2809c21ddc6dd266`,
  `dda0ea3421eff57f9e09191d69b12bf11859fd74207df3d6891dce90b3fdf62c`.
- E508 log/JFR/summary SHA-256: `47ee9e723ee1376851530081c7c46f3418e0916fbee98391698f06253f7e2da2`,
  `7c43b8f99234c02a5f746463a19c8cb7dd13f5e08b5c91da68386ebfbdbaf3ff`,
  `004bcf04a3b4a4bf5cf7c4ce64353c16ca356b48775e50a08399ccc56fddff70`.
- E509 log/JFR/summary SHA-256: `8e9ffa90a9fad5b72a84b1484c68cf2e716e1860d23df627ebed0b51f31ea02d`,
  `4050fdd45333aa688a98d2064936b79076f895b4c4dc5811235bd2f662fe871c`,
  `78149b27d6bc10633f5e94f91614a103d3341815a9bc98271b78f8ec418c4abc`.
- E510 log/JFR/summary SHA-256: `fe740e71450e5a5b08a15d2de2a7072c0aad6dba3c9e743a633bcf5ffa3beb0c`,
  `48b246ea92c68c01735269bc7115705e03485be30e617d1ac85a8af9a8a0f5ca`,
  `b3c4e71a95b54a9cbce4ab2f4e3d3d0437f6bf420e6687ec66ca588dc7d49902`.
- E511 log/JFR/summary SHA-256: `34859a9395a4d92bd3da43320dc5b8b07f6275cf581a2f7b299de82f08546b4c`,
  `dcb9d9ffd136bf140e973dd63dfea762a51b7abfa339dd020953b9af78003ea9`,
  `4c0e18c1393f1cfc05c50a684de455c0bb8588c9b28d6af5e5f71ea591121ba0`.
- E512-E519 log SHA-256: `616959fc25055ebccbba5555ce04cd67399bc872e460fe8b7adce73c79949c6b`,
  `051565d01da5a5e164e95df58070778150c8733c8977a908bcd06601eb89d7c6`,
  `a47cdb5aa3ad82ada81eef4c2c187545faa2639816e5f6a2e50f68fe1ab41283`,
  `b800d8cc70a260e02d37ba3cac8508f22162940cf7442afa326efb879dc9373d`,
  `644fef72b7c9a934714c3d11e34b7628f2fa23a21b7354d6be2dc957881dc04b`,
  `5d400307d4c327154f7df3e0872b290fd8fa25dc01a17f330a19ae30b9dbdd24`,
  `78ad851ac59f7fa8eb46ded6bc3c7e3b716fbef0fa73f895d9157746a48933b7`,
  `394b7c19c0ddad1d939e4fa09a93c24b6a67f2ad9e4533b8431eee5dfc04b845`.
- E520-E527 log SHA-256: `1e666110f64bc0652871c20ce4310c26b864af9b1cde41e6437bec270d6b658c`,
  `652b2217a272213fec5e7884dce36d5abfc429a003aa90b8745af3592a412002`,
  `d1a0d132d055c8d47d20ec4a73b3034608e49b6c6d2d426c4f089acb690a9d42`,
  `474df847a7f9d5468b1b5bc8dd98d9aa6576a3b89f3a1d7fd859df4f2ecec1b3`,
  `a30df0639f940eb93149471138ab82b4d29091a4300d38a4ba81ed5aed41fd7d`,
  `a669de4913eb04e5d0e655cea9a50c6fdea3b2a8f9f41731e0c92922adef9a52`,
  `10ce90f5c49b45982619ce76c0c703efdaeed0b6f944e84b51b94a41289dbf1a`,
  `8fabdbf8715d1fb8606c2792b19e7cc4d29b1146302d4c76f4b137ac7b134d11`.
- E528-E530 log SHA-256: `3a65b26d5e20462f672869bcb7bcd2fa7df9cee9f7c1d09bfdfc7d2f82202bd4`,
  `627875b3c2acbb82c437ef3da5c2560d3237c0a3333c1c26445d6f21189c09be`,
  `5d082695e3c6dd7897435da1a6521d953648480fa3ed05f5348d39fcc2980045`.
- E531 log/JFR/summary SHA-256: `5a863492fc6f322accbec758d659f10fda264580f1783aeaca1cf318a6441946`,
  `f1a0a910b8e58c853257633fbdc1a5e2d7bce69f68c0abb38c50b33cdf94aee7`,
  `0980b2ec6d59d446c5e948304d14b97c8317005c09c9a3e7a32914277acb0f61`.
