# No-apply durable-result route anatomy

Measured on 2026-08-02. This report records E209-E242, the intact command/result route with model application and event
publication deliberately absent. It is a diagnostic ceiling and **not** a qualifying model-throughput checkpoint.

## Question and exact route

The experiment answers how much aggregate capacity remains when commands still cross every ordinary command and result
boundary, but model loading, `@Apply`, model/event commit and global event publication do no work:

```text
SDK command construction and serialization
  -> WebSocket command publication
  -> Runtime durable command append
  -> command tracking and SDK payload deserialization
  -> explicit @HandleCommand void handle(UpdateModel command)
  -> ordinary durable result publication
  -> Runtime durable result append
  -> result tracking and WebSocket return
  -> request correlation, deserialization and caller-future completion
```

The explicit handler is registered in place of the benchmark's automatic model handler. Its typed `UpdateModel`
parameter preserves payload resolution/deserialization; returning `void` produces the normal result lifecycle. Every
run verifies the exact result count and separately queries storage for **zero model events and zero global events**.
Event publication is therefore absent by design, not accidentally optimized away.

## Identity and comparability

| Field | Value |
| --- | --- |
| Accepted production base | SDK production source `e94188b5876`; Runtime P3 `9d0bed30b643` |
| Effective repository heads during the latest diagnostics | SDK `600307977775`; Runtime `edb40774c2d2` (later commits are benchmark/diagnostic work, not a newer accepted production checkpoint) |
| No-apply/multi-client benchmark source SHA-256 | `a0976b25a6de68812776b8c9dad02c5e519198a22a008efa36bcdbeb2f01f442` |
| Batch-summary source SHA-256 | `af45392b6a0e35fccc4e403af8ceb7c036789ab2e7709145fe9e70223a1471ce` |
| SDK tracker trace-observer source SHA-256 | `0f1f63e20ae8f2a0ae23738a8fcad169c0a0de6ec63cfa5aab73095adac0435e` |
| Java | OpenJDK 25 for benchmark, SDK execution and embedded Runtime; SDK/common bytecode remains `--release 21` |
| Long hot workload | 10,485,760 warm-up commands, then 10,485,760 measured commands |
| Shared load controls | 65,536 total maximum in flight, 16 command consumers and the same total command count, independent of sender-client count |
| Profiling truth boundary | Only non-JFR observations estimate E2E throughput. Full request JFR supplies ordering/latency; corrected batch-only JFR supplies batch service/queue evidence. |
| Qualification | `canonical_comparable=false`: model apply, model/event durability and global event publication are absent |

## Correction: the old 0.61M/s ceiling was under-warmed

E209-E211 used only 65,536 warm-up commands. More warm-up and longer measured windows moved the same production route
well above those results, without a production-code change.

| Run(s) | Sender clients | Warm-up | Measured | Profiling | Throughput | Result latency p50 / p95 / p99 / max (ms) | Interpretation |
| --- | ---: | ---: | ---: | --- | ---: | --- | --- |
| E209-E211 | 1 | 65,536 | 1,048,576 | none | 618,009 / 631,205 / 588,077; geom. **612,162/s** | 48.979–56.199 / 81.448–92.815 / 93.980–116.879 / 107.101–134.205 | Invalid as a hot ceiling |
| E227 | 1 | 1,048,576 | 1,048,576 | none | **728,593/s** | 44.652 / 60.569 / 67.377 / 76.020 | Warm-up sensitivity remains |
| E228 | 1 | 10,485,760 | 1,048,576 | none | **767,519/s** | 41.148 / 61.513 / 70.705 / 79.946 | Reaches the hot band |
| E225-E226 | 1 | 1,048,576 | 10,485,760 | none | 775,052 / 779,922; geom. **777,483/s** | about 41.1 / 56.4–56.9 / 64.0–67.1 / 81.7–83.3 | Long measurement also amortizes remaining warm-up |
| E224 | 1 | 10,485,760 | 10,485,760 | async-profiler CPU | **779,071/s** | 41.038 / 56.214 / 64.324 / 88.088 | CPU sampling did not materially depress this route |

Consequently, 0.612M/s is not retained as a performance fact. The current hot single-client diagnostic band is about
0.768–0.780M/s.

## Client topology and aggregate Runtime capacity

One benchmark sender performs request preparation and submission serially on one caller thread. Real deployments can
have many clients. E235-E242 therefore use independent SDK clients, each with its own command/result WebSockets,
request map, result tracker and completion chain, while preserving one **global** 65,536-request in-flight limit.

| Run(s) | Clients | Warm-up / measured | Throughput | Result latency p50 / p95 / p99 / max (ms) | Decision |
| --- | ---: | ---: | ---: | --- | --- |
| E235 | 2 | 1M / 1M | 860,097/s | 64.977 / 85.246 / 92.990 / 105.158 | Short positive topology smoke |
| E236 | 4 | 1M / 1M | 790,865/s | 71.877 / 101.604 / 119.497 / 131.965 | Too little warm-up per client; not a ceiling |
| E237 | 8 | 1M / 1M | 451,431/s | 134.619 / 230.427 / 259.116 / 271.054 | Too little warm-up plus topology contention; not a ceiling |
| E238, E240, E241 | 2 | 10M / 10M | 973,617 / 930,168 / 904,559; geom. **935,683/s** | p50 58.657–62.760; p95 78.630–84.425; p99 87.580–127.427; max 108.799–369.640 | Current best aggregate topology; 7.63% spread means 973,617 is an observation, not a stable pin |
| E239 | 3 | 10M / 10M | **905,716/s** | 63.093 / 84.291 / 101.274 / 221.367 | Below the two-client geometric mean |
| E242 | 2 | 10M / 10M | **942,391/s** | 60.089 / 80.212 / 96.393 / 209.380 | Corrected low-overhead batch profile; lands inside the non-JFR two-client band |

The 20.3% jump from the hot single-client geometric mean (777,483/s) to the hot two-client geometric mean
(935,683/s) proves that a single benchmark caller materially limited the aggregate measurement. It does **not** prove
that every extra client helps: the shared Runtime and database converge on a different limiter, and excessive client
topology adds duplicate tracking work and contention.

## Fundamental route intervals

E229 captured 257 complete command/result routes after a 10M warm-up. RequestStage JFR is deliberately detailed and
reduced observed throughput to 352,389/s, so the following values explain dependency order and residence under that
profile; they are not hot service capacities. Each row is one direct marker-to-next-marker interval. Async branches can
overlap and the rows must not be summed into an E2E latency.

| Direct interval | Exact meaning | n | mean | p50 | p95 | p99 | max |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Sender registration + dispatch | Register request ID/callback/timeout, then hand the request batch to the physical sender | 256 | 2.947 | 2.857 | 3.599 | 5.022 | 13.374 |
| Sender dispatch -> command store start | SDK send handoff, compression/wire transit, Runtime decode and append admission | 256 | 3.531 | 2.767 | 5.188 | 13.944 | 79.661 |
| **Command durable store** | Runtime command append admission through PostgreSQL commit completion | 256 | **11.868** | 4.130 | 77.953 | 152.944 | 201.068 |
| Command durable -> notification submit | Durable command commit until tracking notification is submitted | 257 | 0.027 | 0.011 | 0.091 | 0.359 | 1.091 |
| Command notification -> strategy update | Notification dispatch until `DefaultTrackingStrategy.onUpdate` runs | 257 | 0.239 | 0.136 | 0.779 | 2.186 | 3.686 |
| Command strategy update -> batch resolved | Tracker availability/read request, scan/filter and MessageBatch assembly | 257 | 8.535 | 5.525 | 24.348 | 33.495 | 176.368 |
| Command batch resolved -> endpoint ready | Resolved batch handed to the Runtime consumer endpoint | 257 | 0.011 | 0.007 | 0.048 | 0.075 | 0.097 |
| Command endpoint ready -> response queued | Runtime consumer response admitted to WebSocket output | 257 | 0.024 | 0.008 | 0.094 | 0.140 | 0.157 |
| Command response queue | WebSocket output admission until encode/send worker starts | 257 | 0.370 | 0.189 | 1.352 | 2.328 | 3.404 |
| Command response encode | Runtime WebSocket command-batch encoding/compression | 257 | 0.856 | 0.358 | 2.066 | 23.965 | 23.986 |
| Command response socket send | Encoded command batch through socket-send completion | 257 | 0.091 | 0.049 | 0.342 | 0.743 | 1.375 |
| Command wire transit + SDK decode | Runtime send completion until SDK response context is restored | 257 | 0.868 | 0.303 | 4.093 | 7.841 | 9.900 |
| Command context restore -> preparation | SDK context restoration until generic response preparation starts | 257 | 0.065 | 0.034 | 0.192 | 0.772 | 1.386 |
| Command SDK response preparation | Decode/prepare the command tracking response before callback scheduling | 257 | 0.051 | 0.028 | 0.111 | 0.675 | 0.979 |
| Command preparation -> callback queue | Prepared response until callback is placed on its executor | 257 | 0.048 | 0.026 | 0.152 | 0.652 | 0.831 |
| Command SDK callback queue | Callback admission until callback execution begins | 257 | 0.056 | 0.036 | 0.168 | 0.554 | 0.643 |
| Command SDK callback execution | Generic WebSocket callback body | 257 | 0.071 | 0.040 | 0.223 | 0.770 | 0.835 |
| Command callback start -> read future | Tracking callback start until client read future completes | 257 | 0.038 | 0.024 | 0.125 | 0.232 | 0.521 |
| Command read future -> tracker delivery | Read-future completion until the SDK tracker invokes its processor | 257 | 0.120 | 0.034 | 0.521 | 1.230 | 1.334 |
| **Command consumer return -> position stored** | Handler/consumer has returned; synchronously persist this tracker's new position | 257 | **11.615** | 8.020 | 29.773 | 100.123 | 111.273 |
| Position stored -> next command read | Stored position until the same tracker starts its next read | 257 | 0.029 | 0.008 | 0.136 | 0.450 | 0.938 |
| Handler result barrier | No-op handler result ready until its publication barrier completes | 257 | 0.001 | 0.001 | 0.002 | 0.006 | 0.012 |
| Barrier -> result publication request | Completed barrier until result publication is requested | 257 | 0.009 | 0.007 | 0.018 | 0.039 | 0.068 |
| Result publication request -> worker | Result backlog admission until result worker starts | 257 | 0.975 | 0.974 | 1.965 | 2.661 | 6.756 |
| Result worker -> Runtime admission | Map/intercept/serialize/send until Runtime result append starts | 257 | 2.795 | 2.189 | 7.308 | 9.315 | 17.386 |
| **Result database durability** | Runtime result append admission through PostgreSQL commit completion | 257 | **12.399** | 6.497 | 27.966 | 116.643 | 166.534 |
| Result durable -> notification submit | Durable result commit until tracking notification submission | 257 | 0.265 | 0.260 | 0.429 | 0.727 | 1.184 |
| Result notification -> strategy update | Result notification dispatch until tracking strategy update | 257 | 0.664 | 0.413 | 2.474 | 3.863 | 5.416 |
| **Result strategy update -> batch resolved** | Result tracker request/availability, scan/filter and batch assembly | 257 | **32.423** | 29.593 | 71.966 | 80.224 | 80.274 |
| Result batch resolved -> endpoint ready | Resolved result batch until Runtime endpoint can respond | 257 | 4.676 | 5.154 | 7.329 | 7.688 | 7.700 |
| Result endpoint ready -> response queued | Runtime result response construction/admission to WebSocket output | 257 | 2.398 | 2.598 | 3.900 | 4.093 | 4.123 |
| Result response queue | WebSocket result response admission until encoding starts | 257 | 2.421 | 2.592 | 3.649 | 3.756 | 3.859 |
| Result response encode | Runtime result-batch encoding/compression | 257 | 9.614 | 10.018 | 14.110 | 24.213 | 24.420 |
| Result response socket send | Encoded result response through socket-send completion | 257 | 2.503 | 2.710 | 3.965 | 4.205 | 4.349 |
| Result wire transit + SDK decode | Runtime send completion until SDK response context restoration | 257 | 5.014 | 4.959 | 8.163 | 15.523 | 15.577 |
| Result context restore -> preparation | SDK context restoration until response preparation begins | 257 | 2.812 | 2.910 | 4.211 | 13.929 | 13.933 |
| Result SDK response preparation | Decode/prepare result tracking response | 257 | 2.604 | 2.867 | 4.006 | 4.204 | 4.210 |
| Result preparation -> callback queue | Prepared result response until callback scheduling | 257 | 2.745 | 2.853 | 4.226 | 13.199 | 13.303 |
| Result SDK callback queue | Result callback admission until execution | 257 | 2.571 | 2.863 | 3.868 | 4.596 | 4.648 |
| Result SDK callback execution | Generic WebSocket result callback body | 257 | 5.361 | 5.702 | 8.638 | 17.753 | 17.878 |
| Result callback start -> read future | Result tracking callback start until read future completes | 257 | 2.571 | 2.811 | 3.819 | 4.906 | 15.277 |
| Result read future -> tracker delivery | Result read-future completion until SDK tracker processor invocation | 257 | 4.046 | 3.643 | 7.712 | 16.376 | 16.907 |
| Result tracker handling | SDK result tracker processor work | 257 | 9.524 | 10.207 | 13.750 | 17.825 | 17.843 |
| **Result tracker -> request-handler receipt** | Correlate tracked results with outstanding request callbacks and deliver them | 257 | **23.934** | 21.825 | 48.900 | 77.676 | 85.227 |
| Request-handler result callback | Invoke the matched request's `ResponseCallback` | 257 | 0.005 | 0.004 | 0.013 | 0.021 | 0.035 |
| Result deserialization | Convert the ordinary response payload to the caller's result type | 257 | 0.001 | 0.001 | 0.002 | 0.011 | 0.020 |
| Deserialized result -> command future | Publish the decoded value to the command future | 257 | 0.001 | 0.001 | 0.002 | 0.008 | 0.016 |

All times are milliseconds. The apparently broad result-side queue/encode/callback values are amplified by the
per-request JFR observer and by batched callbacks; they identify ordering and residence, not a production service
ceiling. E242's low-overhead batch measurements below supersede them for capacity selection.

## Composite route intervals

These rows deliberately span multiple direct intervals. They are useful lifecycle views but overlap each other and
must never be added.

| Composite interval | Constituent work | n | mean | p50 | p95 | p99 | max |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Command outer append lifetime | Store admission + JDBC durability + monitor submission + outer-future completion | 256 | 11.931 | 4.191 | 77.979 | 153.042 | 201.103 |
| Command durable -> tracker delivery | Notification + tracking resolution + Runtime response + SDK decode/callback/scheduling | 257 | 11.399 | 8.482 | 30.042 | 46.861 | 178.439 |
| Legacy command store-complete -> tracker | Compatibility marker overlapping notification because outer completion is not DB commit | 256 | 11.339 | 8.255 | 29.628 | 46.829 | 178.372 |
| Result gateway publication lifetime | Mapping/interception/serialization + append admission + awaited transport/store future | 257 | 1.115 | 0.784 | 2.658 | 5.538 | 14.542 |
| Result outer durable store | Runtime admission + JDBC durability + monitor submission + outer completion | 257 | 12.940 | 6.943 | 28.169 | 117.066 | 167.062 |
| **Result durable -> tracker delivery** | Notification + tracking resolution + Runtime response + SDK decode/callback/scheduling | 257 | **77.326** | 76.136 | 123.757 | 134.279 | 144.552 |
| Result tracker -> request handler | Tracker handling + request-correlation delivery | 257 | 23.934 | 21.825 | 48.900 | 77.676 | 85.227 |
| Command tracker handling | Entire no-op handler and result-publication work observed by command tracker | 257 | 12.782 | 9.223 | 31.916 | 102.320 | 113.272 |
| Consumer cycle: position store | Automatic tracker-position persistence after consumer return | 257 | 11.615 | 8.020 | 29.773 | 100.123 | 111.273 |
| Consumer cycle: next-read scheduling | Interceptor unwind + flow regulator + loop handoff before next read | 257 | 0.029 | 0.008 | 0.136 | 0.450 | 0.938 |
| Complete command-consumer cycle | Handler/result publication + position persistence + next read | 257 | 12.812 | 9.228 | 31.935 | 102.329 | 113.281 |
| Full processing route | Command tracker delivery through durable result return and caller completion | 257 | 117.905 | 118.228 | 175.395 | 202.511 | 208.575 |
| **Full captured route** | Request registration through caller command-future completion | 256 | **147.781** | 137.431 | 264.596 | 357.092 | 375.508 |
| Captured durable route | Command store start through result tracker completion | 256 | 126.898 | 115.745 | 248.801 | 340.989 | 372.856 |

## Low-overhead service and queue evidence

E242 records only batch events and explicitly disables per-request RequestStage events. Its 942,391/s throughput is in
the middle of the hot two-client control band, making its batch capacities relevant to that topology. `svcM/s` divides
items by summed active duration; it is a serial-lane capacity only where `actMax=1`. `wallM/s` is observed items over
route wall time. Concurrent rows with `actMax>1` must not be treated as serial ceilings.

| Stage | Batches | Avg batch | actMax | svcM/s | wallM/s | Mean active ms | p95 total ms | p95 queue ms | p95 storage ms | Interpretation |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Runtime serial command JDBC store | 2,560 | 4,096.0 | 1 | 1.342 | 0.945 | 3.051 | 7.900 | 10.672 | 4.344 | Shared but with 29.7% active headroom at this route rate |
| **Runtime serial result JDBC store** | **4,484** | **2,338.5** | **1** | **1.018** | **0.945** | **2.297** | **6.652** | **20.754** | **Route consumes 92.6% of active service capacity; queue max 15** |
| Runtime command commit phase | 2,560 | 4,096.0 | 1 | 2.152 | 0.946 | 1.903 | 4.330 | 0 | 4.329 | Commit alone is not the command-store limiter |
| Runtime result commit phase | 4,484 | 2,338.5 | 1 | 1.280 | 0.946 | 1.828 | 4.187 | 0 | 4.187 | Significant part of serial result store, but not all of it |
| Runtime direct command LTS insert | 2,560 | 4,096.0 | 4 | 1.549 | 0.945 | 2.644 | 5.674 | 0 | 5.673 | Parallel work; 1.549M is summed service, not a serial cap |
| Runtime direct result LTS insert | 4,484 | 2,336.9 | 9 | 0.904 | 0.945 | 2.585 | 5.593 | 0 | 5.592 | Parallel work; apparent svc rate is not a route ceiling |
| Command tracking strategy scan | 15,528 | 10,804.5 | 2 | 37.807 | 15.104 | 0.286 | 0.479 | 0 | 0.479 | Scan work is not limiting |
| Result tracking strategy scan | 5,220 | 4,017.5 | 2 | 1.494 | 1.891 | 2.689 | 10.154 | 0 | 10.154 | Two trackers each scan/filter the global result log; 20,971,520 scanned items for 10,485,760 results |
| SDK result gateway publish | 4,819 | 2,175.9 | 1 | 2.507 | 0.945 | 0.868 | 2.029 | 2.502 | Result creation/publication service has ample active headroom |
| SDK command tracker handle | 15,271 | 686.6 | 16 | 0.107 | 0.944 | 6.436 | 14.122 | Summed per-lane service; aggregate concurrency sustains the route |
| SDK result tracker handle | 3,567 | 2,939.7 | 2 | 199.195 | 0.946 | 0.015 | 0.035 | Tracker handler body is trivial in the batch profile |

The result store's 10.784 ms mean queue time, 20.754 ms p95 queue time and maximum queued batch count of 15 are the
strongest route-wide limiter evidence presently available. The matching single-client batch profile ran at 759,170/s
with result-store service capacity 0.933M/s, only about 81% utilized. Adding the second real client removes much of the
SDK edge and drives the same shared serial writer toward saturation. This triangulation is stronger than a CPU hotspot
or microbenchmark, but still requires an intact full-model-route validation before any production change can be
accepted.

## Sender and completion service diagnostics

E233 and E234 add logging around active SDK service only. Logging makes their route throughput non-canonical, but the
summed active durations separate client-local work from shared Runtime work.

| Client-local service | Items / batches | Active service capacity | What it includes | What it excludes |
| --- | --- | ---: | --- | --- |
| Single-client sender chain | 1,048,576 commands | **0.954M commands/s** | Interception, serialization, request finalization, callback/timeout registration and physical send call | Waiting for command/result completion and all Runtime work |
| Prepared sender prefix | 1,048,576 commands | 1.665M commands/s | Interception, serialization and final envelope/routing preparation | Request registration and send |
| Registered/sent suffix | 1,048,576 commands | 2.234M commands/s | Request registration, timeout setup and send | Earlier command preparation |
| Single-client result completion chain | 1,114,112 warm-up + measured results in 355 batches | **1.691M results/s** | Request ID lookup, callback delivery and future completion on the SDK sender | Result storage, Runtime tracking and WebSocket transport |

The sender chain explains why one synthetic caller should not define aggregate Runtime throughput. The completion chain
is relevant to single-client 1M/s headroom, but at 1.691M/s it is not the current two-client 0.94M/s shared limiter.

## Completion ordering contract

The SDK currently has both a global `responseProcessing` chain and a synchronized per-request `ResponseCallback`
processing chain. Only the latter protects a meaningful contract:

- chunks and intermediate responses for one request remain ordered;
- the final response for that request follows its earlier chunks;
- independent ordinary single-chunk requests may complete independently and in parallel.

There is no meaningful global submit-order contract between different request IDs: transport batching, handler duration
and storage already allow those futures to become ready in a different order. A caller needing submit-order observation
can await or consume its individual futures in that order. The global chain is therefore a legitimate later candidate,
but E234 shows it is not the present shared no-apply limiter and no production change has been made.

## Rejected mechanism and current conclusion

E213-E220 made the SDK result worker await the real `STORED` acknowledgement instead of append admission. Four balanced
pairs were about 1.3% slower geometrically and moved p50 result latency from roughly 50–52 ms to 67–73 ms. E221 showed
that it enlarged result-store batches and raised physical result-store service capacity, but its changed feedback held
results longer and did not improve intact E2E throughput. The candidate was reverted.

Current evidence says:

1. The no-apply durable-result route is not capped at 0.61M/s; that conclusion came from inadequate warm-up.
2. A single sender client is an artificial aggregate constraint; two real clients sustain a geometric **0.936M/s** and
   have reached **0.974M/s** once, with all ordinary command/result durability and completion intact.
3. At that topology the strongest current shared limiter is the serial result JDBC store at **1.018M results/s active
   service**, **92.6% utilization**, p95 queue **20.754 ms** and queue maximum 15.
4. This route still omits model/event work. It establishes base-route headroom and a causal target, not evidence that
   the complete P3 model route is near 1M/s.
5. The next step is measurement: reproduce the result-writer saturation on the full model route with a fair multi-client
   driver and partition its serial preparation, parallel insert and ordered commit costs. Only then should a
   correctness-preserving production mechanism be designed.

## Immutable evidence

Every invocation is recorded as E209-E242 in
[`model-e2e-run-registry.csv`](model-e2e-run-registry.csv). Principal artifacts are:

- E224 async-profiler log/collapsed stacks: `0bcf2161...` / `8467cf40...`;
- E229 detailed trace log/JFR/summary: `4b2ef67f...` / `eba49d8e...` / `453a9309...`;
- E232 corrected single-client batch log/JFR/summary: `9147dec7...` / `78b6f857...` / `ea3f46fc...`;
- E238/E240/E241 two-client long controls: `1f45123b...` / `dfbdbe14...` / `55bbf15c...`;
- E242 two-client batch log/JFR/summary: `76c5fe66...` / `ae05a611...` / `98b11672...`.

Full SHA-256 values and all paired/rejected run artifacts remain in the registry.
