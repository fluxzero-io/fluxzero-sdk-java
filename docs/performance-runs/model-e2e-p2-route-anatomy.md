# P2 full-route anatomy (E71 atomic refinement)

This is the durable route-anatomy report for accepted checkpoint P2. It refines E70's broad intervals into directly
adjacent trace boundaries and keeps fundamental and composite measurements separate. Future major performance
checkpoints use the same structure so that a throughput result, its latency anatomy and its capacity limits remain
comparable.

## Checkpoint and measurement identity

| Field | Value |
| --- | --- |
| Production basis | P2: SDK `e94188b5876`, Runtime `ed9cb3419e0b61e49869886f81f742f1c8bf6a77` |
| Accepted canonical throughput | candidate geometric mean 330,222 commands/s versus 275,049/s control; +20.06% |
| Timing run | E71-PROFILE, accepted P2 behavior plus JFR-only route markers |
| Route | stored/tracked command -> automatic model evaluation -> atomic model/event commit -> durable ordinary result -> tracked caller completion |
| Workload | 1,048,576 measured commands after 65,536 warm-up updates; 65,536 maximum in flight; latest defaults |
| Diagnostic throughput | 247,536 commands/s with JFR and a concurrently loaded host |
| Result latency p50/p95/p99/max | 205.703 / 333.964 / 507.244 / 577.464 ms |
| Correlation | 376 complete processing routes; 376/376 contain every required atomic marker; 244 also join through sender and command-storage boundaries |
| Benchmark log | `/private/tmp/model-e2e-e71-segmented-profile-20260802-0125.log`, SHA-256 `2ff88c5b70e588eacb67b04ec4a4a8b96d2bce3bdbccb21c27744c0c9dac6fa1` |
| Recording | `/private/tmp/model-e2e-e71-segmented-profile-20260802-0125.jfr`, SHA-256 `3a6139446b82a1b8cee320cae68e2d0e205b4b579e3fead610eae078fecf3e3b` |
| Generated summary | `/private/tmp/model-e2e-e71-segmented-profile-20260802-0125-summary-v2.txt`, SHA-256 `8c526b87514e2a7ca488478ca7a86b10ef1e600e0ebe752ed0446a1954009656` |
| Decision | diagnostic-only; this run explains P2 and neither replaces nor regresses its non-JFR canonical baseline |

The accepted 330,222/s P2 result remains the throughput anchor. E71 deliberately trades throughput for observability,
and the host was not isolated. Its 247,536/s is therefore not compared with the canonical result and cannot accept or
reject production code.

### Observer validation after E71

The adversarial review found two JFR-only allocation sites in the first E71 implementation: the full profile sampled
1,034.1 MiB below SDK `AbstractWebsocketClient.recordResultStages` and 728.2 MiB below Runtime
`WebsocketEndpoint.recordOutboundStage`. The trace semantics were correct, but that work could inflate absolute
profile latency. The observer was therefore changed to parse numeric `$traceId` values directly from opaque metadata
bytes, choose component names outside the message loop and construct events only for the deterministic 1-in-4,096
sample.

E72 repeated the complete 131,072-command route after that correction. All 32 expected samples again contained every
atomic marker. The two observer sites disappeared from the allocation ranking; in the directly comparable smoke they
had sampled 263.8 and 163.3 MiB. E72 sampled 5,774.7 MiB total versus E71-smoke's 6,008.6 MiB, although host speed and
batch shape make that total unsuitable as a production allocation claim. Its diagnostic throughput was 184,154/s,
and the same structural signals remained: C06 55.071 ms, M11 14.471 ms, M12 31.531 ms and R06 8.531 ms.

E72 log/JFR/summary SHA-256 values are respectively
`26de6fccd6446cd4213c0faee2ba71fb466db0df3a9cb885766f9b21a836f56b`,
`242596aaaf319e3a52ab5297d6939937f5626a2a9dcb1f1ef453243481db5d8f` and
`4fb75d5ec951590cad895be8298c77e9ff75674b78173cbf1badd2bce0beaa74`. E71 remains the full-workload distribution
table; E72 validates its boundaries and direction with the lower-allocation observer.

### E73 correction: C06 is predominantly demand residence

E73 retained the complete 1,048,576-command route and split C06 without removing or bypassing any stage. Exact model,
event and ordinary-result checks passed, as did all 375 fully staged processing traces. Its profiled throughput was
263,353/s; this is diagnostic-only and does not replace P2's canonical throughput anchor. The log, JFR and summary
SHA-256 values are respectively `575dc911850168afc03c04301bd0f8bfd117903864a473ddab6694867c486ecf`,
`ff1701f38150aaeb1726456a68f369a4dc5b74aa8c0d223af70056e8cff07e3a` and
`85229f5b46472014fdb7b01a9b27f754bc2ff9a2998a8c756cad1cf68b51628a`.

| Atomic C06 measurement | n | mean ms | p50 ms | p95 ms | p99 ms | max ms | Exact meaning |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Correlated command update -> batch resolved | 374 | **41.621** | 38.448 | **86.780** | 129.027 | 177.187 | Residence from the command's store update until some concrete tracking batch contains it. This can include waiting for new SDK demand and is not local Runtime service. |
| Notification -> drain worker queue | 370 | 0.077 | 0.018 | 0.263 | 1.021 | 4.198 | Time from the first pending store notification until the notification-drain worker starts. |
| Notification-drain callback service | 370 | 0.017 | 0.001 | 0.004 | 0.038 | 5.231 | Time to snapshot waiting trackers and synchronously visit the notification-selected subset. Most snapshots contain no waiting tracker. |
| Selected tracker serial wait | 26 | 5.758 | 2.757 | 20.971 | 22.217 | 22.217 | Time a notification-selected tracker waits behind trackers visited earlier by the same drain. This is real, but rare. |
| Selected tracker resolution service | 26 | 1.159 | 0.379 | 3.953 | 8.008 | 8.008 | Position resolution, scan, filter, batch assembly and future completion for a notification-selected tracker. |
| All command message scans | 852 | 0.480 | 0.235 | 2.057 | 4.677 | 19.067 | Every command `scanBatch`, including the dominant immediate read-demand path; average source scan was 19,691 messages. |
| Command delegate-store scans | 76 | 2.876 | 2.108 | 8.668 | 19.037 | 19.037 | Cache misses that reached `JdbcMessageStore`; fetch-executor queue was only 0.017 ms mean / 0.048 ms p95. |

Only 26 notification-selected tracker resolutions were observed beside 852 total command scans. `DefaultTracker`
deliberately completes one ordered batch before issuing the next read; therefore most commands arrive while their
segment tracker is still processing the preceding batch. Their C06 clock continues until that tracker asks for and
receives its next batch. The 41.621-ms residence is primarily downstream backpressure reflected at the command log,
not 41.621 ms of notification, JDBC, filtering or batch-assembly service.

The scan work is amplified by segmented tracking—16,776,972 source-message visits for 1,048,576 commands—but consumed
about 0.409 seconds of aggregate measured scan service and supplied 41.034M source-message tests/s. It is useful future
headroom evidence, not the present 0.263M/s route boundary. Parallel notification fan-out could reduce the 26 selected
trackers' tail, but E73 rejects it as the next throughput candidate because it cannot plausibly explain the full-route
gap. The correct way to reduce C06 residence is to raise the capacity of the ordered downstream model pipeline that
delays new read demand.

### E74 direct-tail causal profile

E74 used the existing `fluxzero.messageStoreDirectTailRows=true` switch without removing any E2E work. All exact
checks and 332/332 fully staged traces passed. Relative to adjacent E73, event-log staging fell from 8.382 to 0.001 ms
mean and packed-model service from 24.555 to 19.431 ms. Profiled E2E moved from 263,353 to 269,039/s (+2.16%). The
benefit was partly offset because natural model batches became smaller (7,944 to 6,679), co-located model storage rose
from 5.376 to 8.248 ms and commit rose from 1.285 to 2.277 ms. This confirms a real avoidable stage cost, but also
confirms that eliminating one local phase is not enough to select the global forced mode as production behavior.

The next candidate is deliberately narrower: a sufficiently large co-located model/event transaction may persist its
underfilled tail directly in that same atomic transaction, while low-rate and ordinary isolated appends retain staged
tail coalescing. E74 log/JFR/summary SHA-256 values are
`11f9352cd027ff535d6f69dd1070848c11852d3f1f4b15e4cfdd82a2a6f04ea2`,
`b187a8710512a84cd58291dc4bed71f43949cf7a0461688a413064d61f2522d0` and
`71d02f9308eb37d592099dfb1a3daa226a656b5dd929551b7236ce0a34f34a72`.

## How to read this report

- Every time is milliseconds and describes one sampled command, not CPU service summed across the workload.
- A **fundamental interval** is a direct marker-to-marker boundary on one causal branch with no known route marker
  between its endpoints. It can still contain scheduler wait, queue wait, library work or database work.
- Fundamental does not mean exclusive. Where the route forks asynchronously, two fundamental intervals can overlap
  and must not be added blindly.
- A **composite interval** spans multiple fundamental boundaries or a whole asynchronous lifecycle. Its component
  column states the relevant fundamental IDs and any extra event-level boundary that prevents exact arithmetic.
- Latency identifies residence time. Throughput limitation additionally requires capacity, concurrency, queue and
  closed-loop evidence. A long interval can be harmless when many requests progress concurrently.
- `act` is the maximum number of overlapping recorded event intervals. It is not a thread count. In particular,
  `act=1` for the model store means that no two recorded durable packed-model transactions overlap.
- `svc M/s` is items divided by summed preparation plus storage duration. It approximates a capacity ceiling only for
  work that is actually serialized. `wall M/s` is the achieved rate over the recording window.

## Fundamental route intervals

### Command publication and tracking delivery

| ID | Direct interval | Exact included work | n | mean | p50 | p95 | p99 | max |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| C01 | Sender registration + dispatch | Register request ID, callback and timeout in the SDK sender, then hand its request batch to the physical sender. | 256 | 3.633 | 2.992 | 6.439 | 10.183 | 47.230 |
| C02 | Sender dispatch -> command-store start | SDK wire batching/encode/compress/write, network handoff, Runtime decode and admission until the Runtime command append starts. | 255 | 4.514 | 3.769 | 7.092 | 29.272 | 31.352 |
| C03 | Command durable store | Runtime command-store start until the actual database transaction commit has completed. This is the durable JDBC boundary, not outer-future completion. | 244 | 10.473 | 8.968 | 20.592 | 35.388 | 41.516 |
| C04 | Command durable -> notification submit | Database commit completion until the command store submits its tracking notification to the monitor executor. | 376 | 0.129 | 0.037 | 0.329 | 2.834 | 4.447 |
| C05 | Command notification submit -> strategy update | Monitor-executor queue/handoff until `DefaultTrackingStrategy.onUpdate` begins processing the committed range. | 376 | 0.407 | 0.192 | 1.563 | 3.953 | 8.391 |
| C06 | Command strategy update -> batch resolved | From `onUpdate` entry until the waiting tracking request has a concrete `MessageBatch`: notification-drain scheduling, tracker wake-up/position handling, store re-read and `scanBatch`, filtering/byte limits and batch assembly. It excludes command JDBC and outbound WebSocket work. | 376 | **40.641** | 36.646 | **93.997** | 120.524 | 154.876 |
| C07 | Command batch resolved -> endpoint ready | Resolved `MessageBatch` until the consumer endpoint has produced its response value. | 376 | 0.087 | 0.020 | 0.239 | 2.071 | 2.369 |
| C08 | Command endpoint ready -> response queued | Endpoint completion until the command-tracking response is admitted to Runtime WebSocket output. | 376 | 0.106 | 0.056 | 0.339 | 1.140 | 1.842 |
| C09 | Command response queue | Runtime WebSocket output admission until its sender starts encoding this response. | 376 | 0.574 | 0.276 | 2.303 | 4.274 | 5.410 |
| C10 | Command response encode | Runtime response encode/compress start until bytes are ready for socket send. | 376 | 0.708 | 0.543 | 1.862 | 3.161 | 7.970 |
| C11 | Command response socket send | Encoded response ready until the Runtime WebSocket send future completes. | 376 | 0.140 | 0.089 | 0.405 | 1.388 | 1.486 |
| C12 | Command wire transit + SDK decode | Runtime send completion until the SDK has received/decoded the response and restored its captured context. Includes transport scheduling on both sides. | 376 | 1.273 | 0.520 | 3.612 | 27.623 | 28.423 |
| C13 | Command context restore -> preparation | Restored SDK context until generic response preparation begins. | 376 | 0.103 | 0.052 | 0.319 | 1.353 | 2.407 |
| C14 | Command SDK response preparation | Generic response mapping/preparation under the restored context. | 376 | 0.088 | 0.047 | 0.301 | 1.088 | 2.126 |
| C15 | Command preparation -> callback queue | Prepared response until its callback chunk has been submitted. | 376 | 0.080 | 0.047 | 0.180 | 0.906 | 1.512 |
| C16 | Command SDK callback queue | Callback submission until execution begins on the callback executor. | 376 | 0.101 | 0.058 | 0.326 | 0.880 | 1.993 |
| C17 | Command SDK callback execution | Callback executor entry through delivery into the tracking read-result callback chain. | 376 | 0.171 | 0.074 | 0.710 | 2.309 | 5.712 |
| C18 | Command callback start -> read future | Callback entry until the tracking client's read future completes. This is a branch inside C17 and overlaps it. | 376 | 0.074 | 0.036 | 0.244 | 0.871 | 2.049 |
| C19 | Command read future -> tracker delivery | Completed tracking read future until the SDK command tracker invokes the command handler. | 376 | 0.218 | 0.069 | 0.792 | 2.577 | 7.220 |
| C20 | Command tracker -> model commit registered | Handler delivery through argument/context resolution and automatic model handling until the command joins model-commit coordination. | 376 | 4.050 | 2.364 | 14.754 | 41.660 | 46.626 |

The previously reported `command-store complete -> tracker delivery` interval was composite. E71 shows precisely why
it is interesting: only 0.536 ms on average is spent from durable commit through notification delivery (C04+C05), and
the WebSocket/SDK delivery tail C07-C19 is individually small. C06 owns 40.641 ms of the 44.728-ms corrected composite
mean. That boundary is where Runtime tracking converts a committed-range notification plus a pending long-poll into a
resolved batch; it is not command serialization, command SQL or raw socket latency.

### Model evaluation, atomic model/event commit and acknowledgement

| ID | Direct interval | Exact included work | n | mean | p50 | p95 | p99 | max |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| M01 | Commit registered -> evaluation start | Coordination registration until automatic `@Apply`/transition evaluation starts. | 376 | 0.002 | 0.001 | 0.008 | 0.014 | 0.019 |
| M02 | Model evaluation | Automatic command-to-model transition evaluation. | 376 | 0.006 | 0.001 | 0.004 | 0.009 | 1.039 |
| M03 | Evaluation -> commit preparation | Evaluation completion until `CommitModels` envelope preparation starts. | 376 | 0.002 | 0.001 | 0.005 | 0.015 | 0.023 |
| M04 | Model commit preparation | Build model targets, updates, events, relationships and the commit request. | 376 | 0.002 | 0.001 | 0.007 | 0.013 | 0.072 |
| M05 | Prepared commit -> dispatch start | Prepared request waiting for synchronous transport dispatch to begin. | 376 | 0.001 | 0.000 | 0.002 | 0.009 | 0.025 |
| M06 | Model commit synchronous dispatch | SDK dispatch call until the request future is registered with transport batching. | 376 | 0.001 | 0.000 | 0.002 | 0.012 | 0.056 |
| M07 | Dispatched commit -> transport encoding | Registered transport request until its physical WebSocket batch starts encoding; mainly ready-batch formation/scheduling. | 376 | 0.880 | 0.617 | 3.123 | 4.968 | 7.244 |
| M08 | Model request encode/compress | Physical model batch encoding/compression until bytes are ready. | 376 | 0.230 | 0.127 | 0.697 | 1.950 | 3.001 |
| M09 | Model request wire + Runtime decode | SDK encoded bytes ready until the Runtime endpoint has decoded `CommitModels`. | 376 | 0.290 | 0.170 | 0.984 | 2.410 | 4.358 |
| M10 | Runtime model intake preparation | Runtime decoded request until its packed model-store job is enqueued. | 376 | 0.250 | 0.142 | 0.681 | 2.139 | 5.307 |
| M11 | Runtime model-store queue | Enqueue until the ordered packed model/event transaction starts. This is backlog residence, not database execution. | 376 | **10.040** | 7.359 | **26.502** | 45.880 | 60.850 |
| M12 | Runtime model-store durability | Packed transaction start until event-log and model state/stream/relationship work has committed durably and atomically. | 376 | **22.673** | 19.586 | **57.628** | 67.504 | 71.458 |
| M13 | Runtime post-durable job completion | Durable commit until the per-request job/result is completed and becomes eligible for output. | 376 | 1.765 | 1.439 | 4.782 | 8.205 | 8.956 |
| M14 | Model result eligibility -> response queue | Eligible result until Runtime WebSocket output admission. Nine samples could not be joined at this optional eligibility marker. | 367 | 0.025 | 0.009 | 0.094 | 0.314 | 0.795 |
| M15 | Model result response queue | Runtime output admission until model-result encoding starts. | 376 | 0.947 | 0.265 | 5.774 | 7.378 | 13.346 |
| M16 | Model result encode | `CommitModelsResult` encode/compress until bytes are ready. | 376 | 0.476 | 0.202 | 1.213 | 6.175 | 6.195 |
| M17 | Model result socket send | Encoded response ready until Runtime WebSocket send completion. | 376 | 0.066 | 0.041 | 0.176 | 0.306 | 0.698 |
| M18 | Model result wire transit + SDK decode | Runtime send completion until the SDK has decoded the model result and restored context. | 376 | 4.375 | 2.093 | 13.326 | 31.317 | 60.019 |
| M19 | Model result context -> processor start | Restored response context until generic preparation hands control to model-result processing. | 376 | 0.069 | 0.039 | 0.232 | 0.293 | 0.294 |
| M20 | Model processor entry | Generic SDK response preparation/model callback entry until `ModelCommitter` receives the commit response. | 376 | 0.580 | 0.200 | 2.466 | 3.417 | 4.871 |
| M21 | Model result matching | Match returned commit entries to their registered local commits. | 376 | 0.001 | 0.000 | 0.002 | 0.005 | 0.008 |
| M22 | Matched result -> post-commit | Matched response until SDK cache/proof post-commit processing starts. | 376 | 0.630 | 0.218 | 2.459 | 3.264 | 4.379 |
| M23 | SDK post-commit processing | Apply cache/proof/version updates and complete the post-commit future. | 376 | 3.149 | 1.398 | 10.052 | 39.528 | 40.209 |
| M24 | Post-commit -> response preparation complete | Post-commit completion until generic model-response preparation finishes; a response-processing branch, not the handler barrier. | 376 | 0.207 | 0.081 | 0.890 | 1.126 | 1.503 |
| M25 | Model preparation -> callback queue | Completed model-response preparation until callback work is submitted. | 376 | 0.091 | 0.046 | 0.284 | 0.401 | 1.747 |
| M26 | Model SDK callback queue | Callback submission until executor start. | 376 | 0.603 | 0.287 | 2.106 | 3.255 | 5.206 |
| M27 | Model SDK callback execution | Callback entry until this individual command's model execution is marked complete. | 376 | 0.068 | 0.052 | 0.173 | 0.430 | 0.857 |
| M28 | Per-message execution -> handler batch tail | This command is complete but waits for the slowest command in the same handler batch before the batch-level marker closes. | 376 | 7.988 | 1.944 | 34.719 | 57.554 | 87.759 |
| M29 | Model execution -> handler result future | Per-message model completion until the handler's result future completes. | 376 | 0.001 | 0.001 | 0.003 | 0.012 | 0.021 |
| M30 | Handler result future -> result ready | Completed handler future until the ordinary result becomes eligible for its publication barrier. | 376 | 0.000 | 0.000 | 0.001 | 0.004 | 0.006 |
| M31 | Handler result barrier | Await the required commit/result precondition under `ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH`. | 376 | 0.001 | 0.000 | 0.001 | 0.004 | 0.004 |
| M32 | Barrier -> result publication request | Barrier completion until ordinary-result publication is requested. | 376 | 0.011 | 0.004 | 0.016 | 0.022 | 2.004 |

This split invalidates one tempting reading of E70. `post-commit -> handler commit complete` looked like a large
per-command result barrier. E71 shows that the actual per-message route through the result barrier is only 0.970 ms
on average (M24-M27+M29-M31 as measured by its composite marker). Most of the broader 8.957-ms interval is M28: waiting
for other commands in the same handler batch. M28 matters for batch completion and feedback, but is not work required
to make this individual command's result safe.

### Ordinary result publication, durability and caller completion

| ID | Direct interval | Exact included work | n | mean | p50 | p95 | p99 | max |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| R01 | Publication request -> worker start | Result backlog residence from publication request until the SDK result worker starts. | 376 | 1.371 | 0.963 | 3.739 | 7.491 | 43.419 |
| R02 | Result worker -> Runtime store admission | Response mapping, interception, serialization, SDK append handoff, WebSocket batching/encode/write, Runtime decode and admission to the result store. | 376 | 5.458 | 3.905 | 11.967 | 51.631 | 57.540 |
| R03 | Result database durability | Runtime result-store admission until its actual database transaction commit completes. | 376 | **14.285** | 10.319 | **35.027** | 52.280 | 62.965 |
| R04 | Result durable -> notification submit | Durable result commit until its tracking notification is submitted. | 376 | 0.380 | 0.341 | 0.878 | 1.762 | 4.670 |
| R05 | Result notification submit -> strategy update | Monitor-executor queue/handoff until result `DefaultTrackingStrategy.onUpdate` begins. | 376 | 0.693 | 0.628 | 1.762 | 3.207 | 7.349 |
| R06 | Result strategy update -> batch resolved | Result tracking notification/long-poll resolution, store scan/filter and `MessageBatch` assembly. | 376 | 8.833 | 6.908 | 25.416 | 40.220 | 101.328 |
| R07 | Result batch resolved -> endpoint ready | Resolved result batch until consumer endpoint response completion. | 376 | 0.903 | 0.547 | 2.399 | 4.729 | 4.803 |
| R08 | Result endpoint ready -> response queued | Endpoint completion until Runtime WebSocket output admission. | 376 | 0.844 | 0.535 | 2.256 | 4.389 | 4.611 |
| R09 | Result response queue | Runtime output admission until encoding begins. | 376 | 1.040 | 0.787 | 2.958 | 6.054 | 6.094 |
| R10 | Result response encode | Encode/compress the tracking `ReadResult` containing ordinary results. | 376 | 2.901 | 1.965 | 8.192 | 9.901 | 10.083 |
| R11 | Result response socket send | Encoded response ready until Runtime WebSocket send completion. | 376 | 0.818 | 0.556 | 2.235 | 2.984 | 3.508 |
| R12 | Result wire transit + SDK decode | Runtime send completion until SDK result decode and context restoration. | 376 | 1.987 | 1.298 | 6.041 | 9.846 | 31.605 |
| R13 | Result context restore -> preparation | Restored SDK context until generic response preparation starts. | 376 | 0.833 | 0.530 | 2.240 | 3.333 | 5.846 |
| R14 | Result SDK response preparation | Generic response preparation under restored per-response context. | 376 | 0.837 | 0.516 | 2.218 | 2.903 | 5.472 |
| R15 | Result preparation -> callback queue | Prepared response until callback work is submitted. | 376 | 0.792 | 0.538 | 2.227 | 2.372 | 2.634 |
| R16 | Result SDK callback queue | Callback submission until executor start. | 376 | 0.848 | 0.561 | 2.297 | 3.621 | 4.849 |
| R17 | Result SDK callback execution | Callback executor entry through result read-callback work. The 60-ms p99/max tail reflects a small number of scheduler stalls. | 376 | 2.834 | 1.094 | 4.584 | 60.010 | 60.066 |
| R18 | Result callback start -> read future | Callback entry until tracking read future completion. This is a branch inside R17 and overlaps it. | 376 | 1.017 | 0.547 | 2.414 | 4.112 | 56.403 |
| R19 | Result read future -> tracker delivery | Read-future completion until the SDK result tracker receives the batch. | 376 | 2.058 | 0.824 | 3.362 | 57.447 | 57.616 |
| R20 | Result tracker handling | Result tracker delivery through its batch handler completion. | 376 | 0.863 | 0.580 | 2.216 | 3.086 | 8.522 |
| R21 | Result tracker -> request-handler receipt | Correlate tracked result to the original request ID and schedule/deliver it to the request handler. | 376 | **14.554** | 9.902 | **43.233** | 46.534 | 47.537 |
| R22 | Request-handler result callback | Original correlated request callback execution. | 376 | 0.004 | 0.003 | 0.012 | 0.020 | 0.045 |
| R23 | Result deserialization | Deserialize the ordinary result payload to the caller's expected type. | 376 | 0.001 | 0.001 | 0.001 | 0.003 | 0.007 |
| R24 | Deserialized result -> command future | Typed result availability until the caller's tracked command future completes. | 376 | 0.001 | 0.001 | 0.001 | 0.004 | 0.015 |

## Composite route intervals

These rows are useful lifecycle views, but they are not additional costs. “Components” names the fundamental route or
the extra event-level marker used by the aggregate. Async branches and batching mean the published aggregate is the
authoritative number; summing component means is only an approximation.

| Composite interval | Components / exact scope | n | mean | p50 | p95 | p99 | max |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Command outer append lifetime | Runtime append admission + C03-C04 + outer-future completion; admission and final completion have event-level markers rather than per-command IDs. | 256 | 10.887 | 9.183 | 21.216 | 35.533 | 42.806 |
| Command durable -> tracker delivery | C04-C19. | 376 | **44.728** | 39.660 | **101.002** | 137.661 | 163.274 |
| Legacy command store-complete -> tracker | Compatibility marker spanning outer store completion to C19; overlaps C04 because outer completion is not the database-commit marker. | 244 | 47.106 | 42.632 | 102.263 | 141.107 | 156.461 |
| Commit registered -> handler batch complete | M01-M28 across model request, durability, acknowledgement/post-commit, callback and batch-tail closure. | 376 | 55.414 | 51.667 | 114.431 | 160.816 | 199.429 |
| Model result transport | M14-M20: eligible Runtime result through output queue/encode/send, wire/decode and SDK processor entry. | 376 | 6.537 | 3.132 | 21.273 | 45.022 | 62.318 |
| Post-commit -> per-message execution | M24-M27 across async preparation and callback branches; authoritative composite, not the arithmetic sum of their overlapping means. | 376 | 0.968 | 0.584 | 2.813 | 4.005 | 6.233 |
| Post-commit -> handler batch complete | Post-commit-to-per-message composite + M28. | 376 | 8.957 | 3.654 | 36.788 | 57.994 | 87.910 |
| Post-commit -> result barrier complete | M24-M27 + M29-M31; individual-command completion and publication-safety barrier, excluding M28 batch-tail wait. | 376 | **0.970** | 0.586 | 2.814 | 4.007 | 6.236 |
| Commit registered -> result worker | M01-M27 + M29-M32 + R01; branches overlap around generic response completion. | 376 | 48.810 | 44.883 | 110.345 | 154.231 | 165.580 |
| Result gateway publication lifetime | SDK gateway event spanning mapping/interception/serialization, append admission and its configured awaited publication future; overlaps R01-R03 and is not their sum. | 388 | 1.796 | 1.310 | 4.286 | 11.393 | 50.532 |
| Result outer durable store | Runtime outer append admission + R03-R04 + outer-future completion. | 376 | 15.007 | 11.295 | 36.089 | 52.698 | 63.563 |
| Result durable -> tracker delivery | R04-R20. | 376 | 24.783 | 19.788 | 65.058 | 115.898 | 138.590 |
| Result tracker -> request handler | R20-R21: tracker handling plus request correlation/delivery. | 376 | 14.554 | 9.902 | 43.233 | 46.534 | 47.537 |
| Command tracker handling | C20 plus model-handler work through batch completion; the tracker observes the handler lifecycle, not the caller's later result future. | 376 | 72.636 | 68.210 | 133.261 | 201.115 | 218.143 |
| Full processing route | C20 + M01-M32 + R01-R24, with asynchronous overlaps: command tracker delivery until caller future completion. | 376 | 111.943 | 106.540 | 212.419 | 378.076 | 401.805 |
| Full captured route | C01-C20 + M01-M32 + R01-R24, with branches/overlap: request registration until caller future completion. | 244 | **207.141** | 193.483 | **327.568** | 483.881 | 514.571 |
| Captured durable route | Command store start/C03 onward through model/event durability, result durability and R20; excludes final caller-delivery tail. | 244 | 186.197 | 173.901 | 303.056 | 450.348 | 474.549 |

## Capacity, batching and concurrency

This table answers a different question from the per-command latency table: how much work each batched stage completed
and whether it had serialized headroom at E71's observed 0.248-0.254M/s route rate.

| Stage | Meaning | batches | items | avg batch | qmax | act | svc M/s | wall M/s | service mean | total p95 | queue p95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SDK command serialization | Application command batches to serialized messages. | 141 | 1,043,152 | 7,398.2 | 0 | 1 | — | 0.255 | — | 4.418 | 0 |
| Runtime command outer append | Queued public append lifetime; futures overlap. | 376 | 1,048,576 | 2,788.8 | 4,096 | 12 | 0.302 | 0.249 | 9.222 | 22.437 | 0 |
| Runtime command JDBC | Serialized durable command writer. | 376 | 1,048,576 | 2,788.8 | 10 | **1** | **0.686** | 0.249 | 4.064 | 12.134 | 18.313 |
| SDK command tracker | Command tracker batches including handler callback. | 770 | 1,048,576 | 1,361.8 | 0 | 16 | 0.138* | 0.249 | 9.856 | 129.903 | 0 |
| SDK model handler | Handler lifetime awaiting configured commit completion; many batches overlap. | 770 | 1,048,576 | 1,361.8 | 0 | 16 | 0.021* | 0.249 | 66.098 | 119.012 | 0 |
| SDK model WebSocket requests | Physical model request batches observed after the JFR barrier. | 5,901 | 1,040,994 | 176.4 | 0 | 2 | — | 0.248 | — | 0.482 | 0 |
| Runtime model intake | Decoded `CommitModels` frames before durable backlog merging. | 13,483 | 1,048,576 | 77.8 | 0 | 5 | — | 0.250 | — | 0.012 | 0 |
| Runtime packed model store | Atomic event/model transactions after backlog merging. | **143** | 1,048,576 | **7,332.7** | **130 / 39.1 MiB** | **1** | **0.295** | 0.250 | 24.896 | 53.467 | **41.168** |
| Runtime event JDBC | Event-log half co-located in the same packed transaction. | 143 | 1,048,576 | 7,332.7 | 0 | **1** | **0.330** | 0.250 | 22.191 | 48.610 | 1.539 |
| Runtime model-result WebSocket | Pure `CommitModelsResult` batches; mixed output is separate. | 711 | 1,032,782 | 1,452.6 | 0 | 2 | **6.835** | 0.247 | 0.213 | 0.515 | 5.000 |
| SDK result publication | Mapping/serialization plus append handoff. | 612 | 1,048,576 | 1,713.4 | 25 | **1** | **1.505** | 0.251 | 1.138 | 3.409 | 3.836 |
| Runtime result outer append | Public result append lifetime; futures overlap deeply. | 533 | 1,048,576 | 1,967.3 | 14,177 | 15 | 0.102* | 0.251 | 19.207 | 41.712 | 0 |
| Runtime result JDBC | Serialized durable result writer. | 533 | 1,048,576 | 1,967.3 | 13 | **1** | **0.374** | 0.251 | 5.265 | 15.400 | **36.075** |
| SDK result tracker | Result tracker batch handling. | 225 | 1,048,576 | 4,660.3 | 0 | **1** | 169.492 | 0.254 | 0.027 | 3.947 | 0 |
| SDK result decode | WebSocket result-envelope decode. | 1,889 | 1,050,484 | 556.1 | 0 | 3 | **1.120** | 0.249 | 0.496 | 2.469 | 0 |
| SDK result callbacks | Parallel callback chunks after decode. | 17,874 | 1,050,484 | 58.8 | 0 | 184 | — | 0.249 | — | 2.580 | 2.237 |

`*` These service rates divide work that intentionally overlaps or waits for downstream futures. They are not serial
capacity ceilings. The bold JDBC/model-store rates are much more useful for capacity reasoning.

### Nested durable-store phases

| Phase | batches | items | avg batch | act | svc M/s | wall M/s | mean service | p95 | p99 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Command direct LTS insert | 359 | 1,048,081 | 2,919.4 | 4 | 0.789 | 0.249 | 3.699 | 13.007 | 16.745 | 19.136 |
| Command database commit | 376 | 1,048,576 | 2,788.8 | 1 | 1.431 | 0.249 | 1.949 | 5.388 | 9.296 | 27.775 |
| Event staging/prior-tail work | 143 | 1,048,576 | 7,332.7 | 1 | 0.947 | 0.250 | 7.746 | 21.705 | 33.154 | 60.093 |
| Event direct LTS insert | 139 | 1,039,616 | 7,479.3 | 1 | 1.266 | 0.250 | 5.907 | 16.793 | 26.743 | 32.877 |
| Event co-located model task | 143 | 1,048,576 | 7,332.7 | 1 | 1.318 | 0.250 | 5.565 | 10.581 | 34.419 | 43.996 |
| Event database commit | 143 | 1,048,576 | 7,332.7 | 1 | 6.059 | 0.251 | 1.211 | 3.471 | 4.569 | 7.408 |
| Model ensure types | 143 | 1,048,576 | 7,332.7 | 1 | 1,194.392 | 0.250 | 0.006 | 0.011 | 0.062 | 0.070 |
| Model stream-block insert | 143 | 1,048,576 | 7,332.7 | 1 | 2.166 | 0.250 | 3.386 | 6.695 | 30.277 | 41.605 |
| Model state lock | 143 | 1,048,576 | 7,332.7 | 1 | 6.409 | 0.250 | 1.144 | 3.272 | 6.354 | 8.820 |
| Model state update | 143 | 1,048,576 | 7,332.7 | 1 | 8.450 | 0.251 | 0.868 | 2.138 | 3.208 | 10.614 |
| Result staging/prior-tail work | 533 | 1,048,576 | 1,967.3 | 1 | 1.992 | 0.252 | 0.988 | 5.746 | 11.253 | 33.467 |
| Result direct LTS insert | 529 | 1,043,881 | 1,973.3 | 8 | 0.452 | 0.252 | 4.370 | 11.340 | 22.473 | 51.272 |
| Result database commit | 533 | 1,048,576 | 1,967.3 | 1 | 0.817 | 0.252 | 2.407 | 6.882 | 11.427 | 28.418 |

## What “one active lane” means

For the packed model store, `act=1` means the 143 recorded intervals from transaction start through durable completion
never overlap. `JdbcModelCommitStore` consumes the next ordered backlog batch only after the previous batch future has
crossed its durability/publication boundary. It is therefore one **inter-batch durability lane**.

It does not mean:

- one Java thread performs every preparation step;
- PostgreSQL can use only one core;
- only one command is in flight (the average durable batch contains 7,332.7 commands);
- no inserts can run concurrently inside or ahead of the ordered commit boundary.

The capacity evidence is strong: at a 0.250M/s observed route rate, the lane supplied only 0.295M/s measured service
capacity and accumulated 130 queued jobs / 39.1 MiB with a 41.168-ms queue p95. Unlike a long but highly concurrent
latency interval, this is both serialized and close to saturation. At unchanged per-item service it needs roughly
1.7x capacity for 0.5M/s and 3.4x for 1M/s.

## Parallel inserts with ordered commits

The generic JDBC message logs already use a relevant pattern:

1. reserve monotonically ordered index ranges under a short serialized boundary;
2. serialize and perform eligible direct LTS inserts on bounded parallel workers/connections;
3. pass completed storage jobs to one ordered commit executor;
4. publish monitor notifications and complete futures only after the corresponding ordered durable commit.

Adapting that pattern to model/event storage is promising, but cannot be a mechanical copy. One model commit
transaction atomically combines event-log rows, model stream/state/relationship writes, conflict checks and the
visible state index. Its retry/idempotency behavior and unknown-commit outcome must remain one contract.

The structurally interesting design is therefore:

- preserve or enlarge the existing natural 7k-10k packed batches;
- reserve their ordered state/index ranges before expensive I/O;
- prepare and, where PostgreSQL semantics allow it, execute independent insert/update work in multiple uncommitted
  transactions or conflict-disjoint lanes;
- hand durable commit/publication through a strict order baton;
- expose no result, state index, tracking notification or retry decision before that batch owns the baton and commits;
- bound every prepared transaction by count, bytes, connection count and shutdown/cancellation rules.

This differs from rejected E42. E42 let four packed transactions consume the queue independently. It fragmented 119
large transactions into 393 transactions averaging 2,668 models, multiplying fixed SQL and commit work. A new test
must first preserve transaction formation and overlap only work behind an ordered commit/publication boundary. The
message-log architecture proves that parallel inserts plus serial commits is viable in this codebase; it does not yet
prove that PostgreSQL locks, conflict resolution and atomic model/event visibility permit the same split unchanged.

## Current route-wide reading

1. **Command tracking residence is a backpressure symptom, not the largest local service cost.** E73 split C06's
   41.621 ms into a 0.077-ms notification-worker queue, a 0.480-ms average command scan and a rare 26-event
   notification-selected path. Most commands wait until their SDK tracker completes its preceding ordered batch and
   asks for more work. Do not optimize the broad C06 number independently of complete-route capacity.
2. **The model/event boundary is the clearest capacity smoking gun.** It has one durability lane, 0.295M/s service
   capacity at 0.250M/s observed load, a 130-job queue and 41.168-ms queue p95. Parallel preparation/insertion with an
   ordered commit baton is now the leading structural hypothesis, subject to an intact-route proof that batch sizes do
   not collapse.
3. **The result writer is a later capacity boundary, not unlimited headroom.** Its serialized JDBC rate is 0.374M/s.
   E64 proved result durability alone was not the limiter around 0.2-0.3M/s, but a route above 0.5M/s must also make this
   boundary parallel or faster.
4. **Serialization and WebSocket transport are not currently the largest waits.** Model request encode+wire averages
   0.520 ms, model-result transport 6.537 ms composite, and most command-response transport stages are sub-millisecond.
   Result response encode (2.901-ms mean, 8.192-ms p95) and allocation remain secondary future targets.
5. **The old post-commit suspicion is narrowed sharply.** Per-message post-commit-to-result-barrier is 0.970 ms; the
   larger batch-completion interval primarily waits for sibling commands. Optimize batch feedback only if capacity
   evidence links that tail to route throughput.
6. **0.5M/s is credible; 1M/s requires more than one fix.** The current data contains avoidable tracking residence and
   a nearly saturated serial model/event lane. Removing those constraints can plausibly cross 0.5M/s. Reaching 1M/s
   additionally requires scaling result durability and maintaining latency below the 65.5-ms average implied by the
   fixed 65,536 in-flight bound.

## Next evidence, before implementation

E73 completed the requested C06 split and excluded notification scheduling, scan service and delegate-read queueing as
the primary route limiter. The model-store experiment must now be specified as a batch-preserving pipeline, with
explicit measurements for transaction count/size, number of prepared transactions, insert overlap, commit order,
conflict/retry/idempotency outcomes, queue bytes and result publication order. The first implementation is justified
only when the trace or a minimal intact-route mechanism shows available E2E capacity without repeating E42's batch
fragmentation.

## Required format for later major checkpoints

Every later “super checkpoint” report must contain:

1. immutable SDK/Runtime source identity, canonical control/candidate results and artifact hashes;
2. one full qualifying-route profile on the accepted code, clearly separated from canonical throughput;
3. a fundamental marker-to-marker table with n/mean/p50/p95/p99/max and exact semantic definitions;
4. a separate composite table that names its fundamental components and overlaps;
5. stage capacity, batching, queue, bytes and concurrency tables;
6. explicit corrections to earlier broad interpretations;
7. accepted/rejected code state, remaining uncertainty and the next causal evidence target.

The machine-readable run remains in
[`model-e2e-run-registry.csv`](model-e2e-run-registry.csv); this report is the human-readable P2 anatomy checkpoint.
