# SDK-inclusive model-commit capacity log

## Live scoreboard

| Route | Current exact pin | Runtime store path | Store service capacity | Role in campaign |
| --- | ---: | --- | ---: | --- |
| Full command -> model -> event + result E2E | **347,691/s** clean-control geometric mean | `commit-packed-update` | **0.407M/s** in E459 | Sole acceptance gate for the 500k target |
| Low-level SDK `CommitModels` update round trip | **595,877/s** without JFR | `commit-packed-update` | **0.781M/s** in E488 | Runtime/wire upper-bound diagnostic |
| Direct SDK `assertAndApply(command)` | 80,074/s without JFR | `commit-general` | **0.108M/s** in E491 | Separate direct-API/idempotency diagnostic; not a proxy for tracked E2E |

Production remains Runtime `7ac06794`. The code introduced for E483-E492 is benchmark and observer infrastructure
only; it is not an accepted production optimization. The full model campaign remains scoped to model work. Command and
ordinary result paths remain present only in the canonical E2E acceptance route and are not optimization targets.

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

## Current decision

1. Use the low-level SDK update route as a fast secondary check for physical Runtime/wire changes.
2. Keep direct `assertAndApply` as a separate public-API capacity observation, never as the packed E2E baseline.
3. Select and accept production work only through matched full command -> model -> event + result runs.
4. Investigate the full route's 20k-30k packed transactions and larger real event envelopes before another production
   implementation. The question is whether a no-delay size/weight boundary can preserve natural batching while
   avoiding the measured nonlinear store cost; no fixed setting is accepted without full E2E proof.

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
