# E70 complete-route timing trace

E70 validates low-overhead correlation across the accepted P2 production behavior. It is a JFR profile smoke, not a
canonical throughput comparison: 131,072 measured commands followed 32,768 warm-up updates, JFR was active, and host
load before the corrected run included WindowServer at 38.6% of one core plus Codex/ChatGPT activity. The run retained
the complete durable-result route and passed the benchmark's exact command, model, event and result checks.

The corrected run measured 170,765 commands/s with benchmark result-latency p50/p95/p99/max of
290.729/366.314/380.390/397.943 ms. Deterministic 1-in-4,096 tracing captured 33 complete processing routes; 32 could
also be joined back through the Runtime-assigned command index to sender registration. The raw benchmark log, JFR and
summary SHA-256 values are respectively
`8e582536b26fb26343437dc6b0327ad0ae9590a05510b45ed1a47a7951566a86`,
`b47bac4dc6d834e90c3feba00468e1c1470152e9127d9f56cd71ac1258e6c23f` and
`0907c463324074111851546f51eb4a398652c513902a06b4c4a11e847d3abecf`.

## How to read the numbers

- Every duration is in milliseconds. The rows are intervals on sampled commands, not additive service costs.
- Aggregate rows explicitly overlap their component rows and must not be summed with them.
- `svc M/s` is `items / sum(preparation + storage)`. It estimates serial service demand only when that work is truly
  serialized.
- `wall M/s` is items divided by the observed span from the first stage start to the last stage end. It describes the
  achieved stage rate in this run, not an isolated capacity ceiling.
- `act` is the maximum number of overlapping recorded intervals. Overlapping futures are not necessarily active CPU
  workers.
- Per-batch queue wait is recorded separately and may precede the event interval, so queue p95 can exceed total p95.

## Correlated lifecycle intervals

| Segment | Exact boundary and meaning | n | mean | p50 | p95 | p99 | max |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Sender registration + dispatch | Request ID, callback and timeout are registered -> request batch handed to the sender | 32 | 3.857 | 3.573 | 6.181 | 6.389 | 6.389 |
| Sender dispatch -> command-store start | Sender handoff -> Runtime command append begins; combines command wire encode/compress/write, Runtime decode and append admission | 32 | 5.302 | 5.026 | 7.472 | 7.660 | 7.660 |
| Command durable store | Runtime command append begins -> durable append completes | 32 | 12.723 | 8.168 | 45.492 | 47.229 | 47.229 |
| Command store -> tracker delivery | Durable command completion -> command is delivered to the SDK tracker/handler client | 32 | 71.298 | 68.879 | 137.358 | 187.784 | 187.784 |
| Tracker delivery -> commit registration | SDK command delivery -> command joins model-commit coordination | 33 | 12.325 | 8.246 | 44.942 | 48.488 | 48.488 |
| Registration -> evaluation start | Commit registered -> automatic model evaluation begins | 33 | 0.006 | 0.004 | 0.013 | 0.027 | 0.027 |
| Model evaluation | Automatic `@Apply`/transition evaluation start -> complete | 33 | 0.003 | 0.002 | 0.007 | 0.013 | 0.013 |
| Evaluation -> preparation | Evaluation complete -> commit-envelope preparation begins | 33 | 0.004 | 0.001 | 0.016 | 0.018 | 0.018 |
| Commit preparation | Build model targets, events, relationships and `CommitModels` request | 33 | 0.003 | 0.002 | 0.007 | 0.008 | 0.008 |
| Prepared -> dispatch start | Prepared request -> synchronous transport-dispatch call starts | 33 | 0.002 | 0.001 | 0.004 | 0.013 | 0.013 |
| Synchronous dispatch | Dispatch call starts -> request future has been registered with the transport batch | 33 | 0.001 | 0.001 | 0.003 | 0.003 | 0.003 |
| Dispatch -> transport encoding | Request registered -> its physical WebSocket batch starts encoding; predominantly ready-batch formation/wait | 33 | 1.691 | 0.932 | 4.889 | 9.993 | 9.993 |
| Model encode/compress | Physical model batch encoding/compression start -> encoded bytes ready | 33 | 0.373 | 0.179 | 2.590 | 2.594 | 2.594 |
| Model write/decode | Encoded bytes ready -> Runtime endpoint has decoded the commit request | 33 | 0.513 | 0.411 | 1.540 | 1.559 | 1.559 |
| Runtime intake preparation | Decoded Runtime request -> model-store job enqueued | 33 | 0.441 | 0.347 | 1.230 | 1.375 | 1.375 |
| Runtime model-store queue | Job enqueued -> packed durable transaction starts | 33 | 18.833 | 17.523 | 54.244 | 54.659 | 54.659 |
| Runtime model durability | Packed transaction starts -> database-backed event/model work is durable | 33 | 33.669 | 28.185 | 77.411 | 77.413 | 77.413 |
| Runtime post-durable completion | Durability -> per-job result is completed and becomes eligible for WebSocket output | 33 | 9.491 | 6.254 | 67.050 | 67.089 | 67.089 |
| Model result transport | Runtime job completion -> SDK decoded-result batch enters its model-result processor | 33 | 25.547 | 26.949 | 82.683 | 86.511 | 86.511 |
| SDK result receipt -> post-commit | Result processor entry -> cache/proof post-commit callback starts | 33 | 3.956 | 1.043 | 7.491 | 60.409 | 60.409 |
| SDK post-commit processing | Post-commit callback start -> cache/proof update future completes | 33 | 6.375 | 2.439 | 18.071 | 18.096 | 18.096 |
| Post-commit -> handler commit complete | Post-commit done -> handler's commit-completion boundary closes | 33 | 27.567 | 18.800 | 59.589 | 94.295 | 94.295 |
| Commit registered -> commit complete | Aggregate model-commit lifetime; overlaps the detailed model rows above | 33 | 128.474 | 135.415 | 165.465 | 173.240 | 173.240 |
| Commit registered -> result preparation | Aggregate delay until the async result worker begins ordinary-result publication; overlaps commit completion | 33 | 106.082 | 114.143 | 160.044 | 165.094 | 165.094 |
| Result preparation + transport handoff | Result worker begins mapping/interception/serialization -> SDK append future completes its transport handoff | 33 | 3.947 | 3.252 | 10.628 | 10.700 | 10.700 |
| Result durable store | Runtime durable result append starts -> completes | 33 | 15.084 | 13.803 | 29.273 | 29.654 | 29.654 |
| Result store -> tracker delivery | Durable result completion -> sender's result tracker receives it | 33 | 6.547 | 4.863 | 14.173 | 17.960 | 17.960 |
| Result tracker handling | Result tracker delivery -> tracker batch handler completes | 33 | 0.604 | 0.518 | 1.677 | 1.678 | 1.678 |
| Result tracker -> request handler | Tracker delivery -> original request callback receives the correlated result | 33 | 15.024 | 9.996 | 36.057 | 36.129 | 36.129 |
| Request-handler callback | Correlated result received -> existing request callback completes | 33 | 0.008 | 0.007 | 0.018 | 0.023 | 0.023 |
| Result deserialization | Result deserialization start -> typed message available | 33 | 0.002 | 0.001 | 0.004 | 0.016 | 0.016 |
| Deserialized result -> command future | Typed result available -> existing tracked command future completes | 33 | 0.001 | 0.001 | 0.003 | 0.006 | 0.006 |
| Command tracker handling | Tracker delivery -> command handler batch completes; overlaps the entire commit/result route | 33 | 151.313 | 167.378 | 193.713 | 196.869 | 196.869 |
| Processing route | Command tracker delivery -> caller command future completes | 33 | 164.676 | 163.417 | 254.336 | 269.685 | 269.685 |
| Full captured route | Sender request registration -> caller command future completes | 32 | 257.995 | 282.796 | 337.667 | 339.883 | 339.883 |
| Durable captured route | Runtime command-store start -> result tracker handling completes | 32 | 234.177 | 251.235 | 308.292 | 313.968 | 313.968 |

## Primary stage capacity and queue context

`qmax` is queued items, `act` is maximum overlapping event intervals and `MiB` is the sum of each event's recorded
byte field. A dash means that the event did not populate a meaningful service-phase field.

| Stage | What it measures | batches | items | avg batch | qmax | act | svc M/s | wall M/s | svc mean ms | total p95 | queue p95 | prep p95 | store p95 | pub p95 | cb p95 | MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SDK command serialize | Application commands -> serialized command messages | 17 | 131,072 | 7,710.1 | 0 | 1 | — | 0.274 | — | 4.585 | 0 | 0 | 0 | 0 | 0 | — |
| Runtime command append | Outer queued command append future | 33 | 131,072 | 3,971.9 | 4,096 | 4 | 0.327 | 0.276 | 12.164 | 45.576 | 0 | 0 | 44.923 | 0 | 0 | 10.998 |
| Runtime command JDBC | Serialized JDBC command writer | 33 | 131,072 | 3,971.9 | 2 | 1 | 0.717 | 0.276 | 5.542 | 21.803 | 21.904 | 17.259 | 4.538 | 0 | 0 | 10.998 |
| SDK command tracker | Command tracker batch including handler callback | 85 | 131,072 | 1,542.0 | 0 | 16 | 0.143 | 0.184 | 10.767 | 186.994 | 0 | 0 | 27.783 | 0 | 174.650 | 10.998 |
| SDK model handler | Handler lifetime awaiting after-handler commit completion | 85 | 131,072 | 1,542.0 | 0 | 16 | 0.014 | 0.190 | 109.457 | 173.442 | 0 | 0 | 173.250 | 0 | 0 | — |
| SDK model WebSocket send | All-model physical request batches; mixed batches account for the omitted 499 items | 605 | 130,573 | 215.8 | 0 | 2 | — | 0.240 | — | 0.618 | 0 | 0 | 0 | 0 | 0 | 2.721 |
| Runtime model intake | Complete decoded `CommitModels` intake stream | 1,104 | 131,072 | 118.7 | 0 | 3 | — | 0.241 | — | 0.020 | 0 | 0 | 0 | 0 | 0 | — |
| Runtime packed model store | Single durable model/event transaction lane | 16 | 131,072 | 8,192.0 | 0 | 1 | 0.308 | 0.207 | 26.634 | 77.635 | 54.664 | 0 | 77.381 | 0 | 0 | 132.559 |
| Runtime event JDBC | Co-located durable global-event append | 16 | 131,072 | 8,192.0 | 0 | 1 | 0.362 | 0.208 | 22.611 | 73.957 | 1.755 | 64.329 | 36.688 | 0 | 0 | 10.998 |
| Runtime model-result WebSocket | Pure `CommitModelsResult` response batches; mixed response batches are separate | 80 | 129,911 | 1,623.9 | 0 | 2 | 2.292 | 0.206 | 0.708 | 0.777 | 6.000 | 2.525 | 0 | 0.084 | 0 | 0.570 |
| SDK result publish | Result mapping/serialization plus append handoff | 74 | 131,072 | 1,771.2 | 7 | 1 | 1.408 | 0.196 | 1.258 | 5.574 | 7.244 | 4.190 | 0 | 0.218 | 0 | 84.991 |
| Runtime result append | Outer async result-append futures; overlap is seven-deep | 62 | 131,072 | 2,114.1 | 9,867 | 7 | 0.146 | 0.193 | 14.451 | 31.250 | 0 | 0 | 31.093 | 0 | 0 | 0.500 |
| Runtime result JDBC | Serialized durable result writer | 62 | 131,072 | 2,114.1 | 5 | 1 | 0.405 | 0.194 | 5.221 | 15.742 | 23.506 | 8.527 | 10.071 | 0 | 0 | 0.500 |
| SDK result tracker | Sender result tracker batch handling | 48 | 131,072 | 2,730.7 | 0 | 1 | 135.033 | 0.192 | 0.020 | 3.201 | 0 | 0 | 0.029 | 0 | 0.014 | 0.500 |
| SDK result decode | WebSocket result-envelope decode | 204 | 131,306 | 643.7 | 0 | 3 | 1.002 | 0.184 | 0.642 | 1.847 | 0 | 1.847 | 0 | 0 | 0 | 7.085 |
| SDK result callback | Parallel callback chunks after decode | 2,208 | 131,306 | 59.5 | 0 | 235 | — | 0.184 | — | 8.632 | 7.852 | 0 | 0 | 0 | 0.664 | — |

## Nested durable-store phases

| Stage | batches | items | avg batch | act | svc M/s | wall M/s | mean service ms | total p95 | store p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Command direct LTS insert | 33 | 131,072 | 3,971.9 | 4 | 0.643 | 0.279 | 6.180 | 17.216 | 17.216 |
| Command database commit | 33 | 131,072 | 3,971.9 | 1 | 1.881 | 0.278 | 2.111 | 4.467 | 4.467 |
| Event staging/prior-tail work | 16 | 131,072 | 8,192.0 | 1 | 1.327 | 0.210 | 6.174 | 31.828 | 31.828 |
| Event direct LTS insert | 16 | 130,176 | 8,136.0 | 1 | 0.901 | 0.210 | 9.030 | 61.399 | 61.399 |
| Event co-located task | 16 | 131,072 | 8,192.0 | 1 | 1.779 | 0.209 | 4.605 | 12.447 | 12.447 |
| Event database commit | 16 | 131,072 | 8,192.0 | 1 | 8.250 | 0.209 | 0.993 | 2.079 | 2.078 |
| Model ensure types | 16 | 131,072 | 8,192.0 | 1 | 1,838.549 | 0.210 | 0.004 | 0.009 | 0.008 |
| Model stream-block insert | 16 | 131,072 | 8,192.0 | 1 | 3.364 | 0.210 | 2.435 | 8.703 | 8.703 |
| Model state lock | 16 | 131,072 | 8,192.0 | 1 | 8.404 | 0.211 | 0.975 | 2.722 | 2.722 |
| Model state update | 16 | 131,072 | 8,192.0 | 1 | 12.371 | 0.210 | 0.662 | 1.428 | 1.428 |
| Result staging/prior-tail work | 62 | 131,072 | 2,114.1 | 1 | 2.042 | 0.195 | 1.035 | 4.432 | 4.432 |
| Result direct LTS insert | 62 | 130,088 | 2,098.2 | 4 | 0.420 | 0.194 | 4.994 | 15.031 | 15.030 |
| Result database commit | 62 | 131,072 | 2,114.1 | 1 | 0.874 | 0.195 | 2.419 | 8.135 | 8.135 |

## Profile-wide context

The recording contained 156 Java execution samples and 34 native samples, too few for a stable CPU ranking. Allocation
sampling estimated 5,521.2 MiB, one 59.5-ms GC and a 5,790.9-MiB maximum used heap. The largest sampled allocation
sites were ZSTD decompress/compress (693.9/392.6 MiB), JFR benchmark recording infrastructure (421.4 MiB), tracking
wire writers (406.8 MiB), `SerializedMessage.encode` (346.1 MiB), model-commit wire writers (205.2 MiB),
`SerializedMessage.decode` (180.8 MiB) and model commit preparation/serialization (122.4/119.6 MiB). These are
supporting structural clues, not candidate-selection evidence by themselves.

## Initial reading, not yet candidate selection

1. The largest granular waits are command-store completion -> tracker delivery, Runtime model-store queue/durability,
   model-result transport, SDK post-commit -> handler completion and result tracker -> request-handler delivery.
2. Runtime packed model/event durability is the clearest serialized stage: one active interval, 0.308M/s service demand,
   0.207M/s observed wall rate and a 54.664-ms queue p95. It is plausible route pressure, but prior local SQL and batch
   improvements proved that reducing this stage alone can change batch feedback and remain E2E-neutral.
3. Result JDBC has more serial headroom at 0.405M/s. The outer result append's misleading 0.146M/s service number comes
   from seven overlapping futures; its 0.193M/s wall rate kept pace with this run. This agrees with E64: result
   durability alone is not the current limiter.
4. The 1,104 Runtime model-intake batches average only 118.7 commits while 16 durable transactions average 8,192.
   Transport latency itself is small, but repeated framing, compression and wire allocation remain plausible CPU and
   batching-feedback costs. Any candidate must preserve the intact route and demonstrate canonical E2E relevance.
5. Command storage has ample local JDBC service rate, but the following delivery interval dominates its critical-path
   latency. That points toward tracking delivery cadence, scheduling or closed-loop batch feedback rather than command
   SQL itself; latency alone still does not prove throughput limitation.

