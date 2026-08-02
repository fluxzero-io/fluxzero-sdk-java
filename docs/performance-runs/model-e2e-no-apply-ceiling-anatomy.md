# No-apply durable-result route anatomy

Measured on 2026-08-02. This report explains the E209-E265 base anatomy and links the continuing E266-E315 audit of
the intact command/result route with model application and event publication deliberately absent. It is a diagnostic
ceiling and **not** a qualifying model-throughput checkpoint.

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
| Original no-apply/multi-client benchmark source SHA-256 through E242 | `a0976b25a6de68812776b8c9dad02c5e519198a22a008efa36bcdbeb2f01f442` |
| Caller-neutral benchmark source SHA-256 for E255-E262 | `d77578e9b3dd8375101889f5b9a13b07c90ea4246f8a643596bc30b2a86a09cc` |
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
| Verbose live-route result completion observation | 1,114,112 warm-up + measured results in 355 batches | 1.691M results/s | Request ID lookup, callback delivery and synchronous dependent futures while verbose route diagnostics are active | Result storage, Runtime tracking and WebSocket transport; superseded as a pure service ceiling by E280-E282 |

The sender chain explains why one synthetic caller should not define aggregate Runtime throughput. E280-E282 isolate
the completion graph without live-route feedback and supersede E234's verbose 1.691M/s estimate:

| Result batch | Request-handler lookup/callback/future | Full SDK deserialize/map/caller future | Plus E2E benchmark callback | Registration + full SDK completion loop |
| ---: | ---: | ---: | ---: | ---: |
| 2,048 | **13.399M/s** | **5.306M/s** | **4.793M/s** | 2.821M/s |
| 2,560 | **12.513M/s** | **5.276M/s** | **4.285M/s** | 2.990M/s |
| 8,192 | **12.716M/s** | **5.370M/s** | **4.797M/s** | 3.045M/s |

Each row completes 10,485,760 distinct logical requests after a 1,048,576-operation warm-up, with at most 65,536
requests retained at once. Every result starts as a fresh native envelope with `Void` JSON plus the 13-field default
application/client/tracker/invocation/correlation metadata shape. The full SDK scenario includes lazy data/metadata
materialization, deserialization, response `Message` construction, gateway callback removal and the public payload
future. The final scenario also performs the no-model E2E driver's timestamp write, in-flight permit release and latch
countdown. Result completion therefore has ample current headroom; it is not the shared ~0.95M/s no-model limiter and
does not justify production parallelism now.

E255-E258 replace the model-oriented benchmark scheduler with a simple no-apply feeder. On the same driver and a hot
10M/10M workload, two caller threads sharing one SDK client improve 700,322/s to 746,524/s (**+6.60%**). This proves
command offering is measurable route work. It does not establish aggregate Runtime capacity because both threads still
share one client's request map, queues, transport and result completion. The campaign therefore retains two views:

- one-client offering/completion capacity measures SDK client quality;
- multiple independent hot clients, under one global in-flight limit, measure aggregate Runtime capacity without one
  synthetic caller deciding the ceiling.

E283-E289 then instrument the actual no-model load generator without removing any route stage. Object-array creation
and attachment of all ten million driver callbacks are negligible. Most caller time is divided between waiting for
closed-loop capacity and the real SDK `CommandGateway.send` path. Doubling the global in-flight window to 131,072 is
not a fair many-client emulation: E284 collapses to 275,124/s with 440.030-ms p50 and 783.046-ms p95 latency.

Keeping 65,536 outstanding requests and replenishing in 8,192-command chunks beats 16,384 in two matched pairs:

| Pair | 16,384 producer batch | 8,192 producer batch | Change |
| --- | ---: | ---: | ---: |
| E283 / E285 | 753,180/s | **777,305/s** | **+3.20%** |
| E286 / E287 | 740,005/s | **753,736/s** | **+1.86%** |
| geometric mean | 746,563/s | **765,427/s** | **+2.53%** |

The smaller batch reduces summed permit wait from 11.440 to 10.225 seconds and 11.584 to 10.879 seconds. It raises
the union capacity while either SDK send-call is active from 0.857 to 0.946M/s and 0.840 to 0.899M/s. A 4,096 screen
does not clearly exceed 8,192 and doubles gateway-call count. E289 therefore accepts 8,192 only as the no-model
throughput-driver default and verifies the profiler-free exact route at 769,494/s in the current low state. This is
benchmark quality, not a production performance claim.

## Completion ordering contract

The SDK currently has both a global `responseProcessing` chain and a synchronized per-request `ResponseCallback`
processing chain. Only the latter protects a meaningful contract:

- chunks and intermediate responses for one request remain ordered;
- the final response for that request follows its earlier chunks;
- independent ordinary single-chunk requests may complete independently and in parallel.

There is no meaningful global submit-order contract between different request IDs: transport batching, handler duration
and storage already allow those futures to become ready in a different order. A caller needing submit-order observation
can await or consume its individual futures in that order. The global chain is therefore a legitimate later candidate,
but E280-E282 show 12.5-13.4M/s bare service and 5.276-5.370M/s including the complete caller graph. It is not the present
shared no-apply limiter and no production change has been made.

## Backlog-fragmentation diagnosis

E259-E262 use two independent clients and the caller-neutral driver on the exact durable command/result route. Raising
the existing global message-store backlog from 4,096 to 8,192 produces two matched hot improvements:

| Pair | 4,096 control | 8,192 diagnostic | Change | p50 latency control -> diagnostic |
| --- | ---: | ---: | ---: | ---: |
| E259 / E260 | 691,001/s | **808,488/s** | **+17.00%** | 84.786 -> 70.231 ms |
| E261 / E262 | 669,318/s | **805,019/s** | **+20.27%** | 87.776 -> 71.105 ms |
| geometric mean | 680,073/s | **806,752/s** | **+18.63%** | — |

All four runs verify 10,485,760 results and zero stored model/global events. These absolute values are not mixed with
the earlier E238-E242 driver identity. The matched delta proves that batch/transaction fragmentation is material on the
current no-model route. It does not yet prove which store benefits: `fluxzero.messageStoreBacklogSize` is global and can
alter command append, result append, their queues and closed-loop feedback. No production default changes from this
diagnosis. Matched batch-only profiles must first attribute transaction counts, average batches, writer service and
queue depth for both settings.

E263/E264 perform that matched batch-only attribution:

| Result-writer evidence | Backlog 4,096 | Backlog 8,192 | Change |
| --- | ---: | ---: | ---: |
| Transactions | 13,671 | **9,565** | **-30.03%** |
| Average messages/transaction | 767.0 | **1,096.3** | **+42.93%** |
| Active writer capacity | 0.671M/s | **0.786M/s** | **+17.14%** |
| Profiled E2E | 0.664M/s | **0.771M/s** | **+16.12%** |
| p95 writer queue | 67.987 ms | **55.104 ms** | **-18.95%** |
| Maximum concurrent outer result jobs | 65 | 64 | effectively unchanged |

The command-store transaction count also halves, but its active capacity remains about 1.7M/s and therefore does not
bound this route. At backlog 8,192, profiled E2E consumes about 98% of result-writer service. The outer Runtime backlog
drains asynchronously and creates up to 64-65 result jobs before the ordered commit executor reaches them; direct
inserts have already begun on job-owned transactions, so later result arrivals cannot join those queued jobs. This is
the precise fragmentation mechanism to address.

E265 closes larger global buffering: backlog 16,384 falls to 437,704/s and p95 latency rises to 313.994 ms. The next
diagnostic must instead limit result transaction admission to a small bounded preparation window (for example 2/4/8),
retain ordered commits and exact futures, and let not-yet-admitted messages accumulate into larger natural batches.

E266-E269 screen that bounded window on the intact route:

| Maximum pending result jobs | Throughput | p50 / p95 latency | Interpretation |
| ---: | ---: | ---: | --- |
| 1 | 862,259/s | 67.635 / 84.549 ms | No cross-job insert overlap |
| **2** | **962,092/s** | **58.811 / 79.273 ms** | **Best initial balance** |
| 4 | 950,291/s | 58.777 / 80.158 ms | Strong but admits more fixed transactions |
| 8 | 904,578/s | 62.653 / 85.252 ms | Fragmentation is returning |
| Unbounded recent diagnostic pin | 806,752/s geom. | about 70-71 ms p50 | Up to 64-65 fixed jobs |

The curve is causal: one job loses useful insert/commit overlap, while progressively more jobs commit arrivals into
separate future transactions too early. Two jobs allow one transaction to prepare/direct-insert while the preceding
transaction commits, and leave later arrivals mergeable in the existing bounded backlog. Exact result/event checks pass
at every point. The next run profiles N=2; the diagnostic switch is not yet an accepted production policy.

E270 confirms N=2 with low-overhead batch evidence: 3,551 result transactions average 2,952.9 messages, active writer
capacity is 1.003M/s, p95 writer queue is 7.372 ms and profiled E2E is 955,648/s. Direct inserts overlap at exactly two.
E271/E272 test acquiring admission before batch construction. This grows average batches further to 4,723 and local
writer capacity to 1.095M/s, but intact E2E is neutral-lower at 937,677/s unprofiled and 949,030/s profiled. The broader
Common change is therefore rejected. E273 separately rejects two callers per SDK client at 917,758/s; benchmark command
offering is no longer the route limiter once result admission is bounded.

E274 restores the 4,096 production batch cap while retaining N=2. Throughput is 693,426/s and p95 latency 236.642 ms,
so admission alone is neutral and operationally worse. The validated mechanism needs both boundaries: 8,192 preserves
natural command chunks and creates large result waves; N=2 keeps those waves mergeable instead of opening dozens of
future result transactions. The production candidate must scope 8,192 to command/result tracking stores and N=2 to the
result store, with explicit overrides and all unrelated stores unchanged.

E275-E278 implement and test that narrow pair, then reject it on same-binary E2E evidence. Legacy controls run at
960,003 and 958,405/s; candidate runs at 957,002 and 911,763/s. Geometric means are 959,204 versus 934,109/s
(**-2.62%**). E279 profiles the favorable legacy state at 948,289/s: 4,235 result transactions average 2,476 messages,
writer service is 1.063M/s, route utilization is 89.5% and p95 writer queue is 19.843 ms. The unbounded writer retains
more useful direct-insert overlap than N=2. All production candidate code is reverted. Result batch fragmentation is a
valid explanation for low-state collapse, but a hard pending-job cap is not the correct optimization.

## Rejected mechanism and current conclusion

E213-E220 made the SDK result worker await the real `STORED` acknowledgement instead of append admission. Four balanced
pairs were about 1.3% slower geometrically and moved p50 result latency from roughly 50–52 ms to 67–73 ms. E221 showed
that it enlarged result-store batches and raised physical result-store service capacity, but its changed feedback held
results longer and did not improve intact E2E throughput. The candidate was reverted.

Current evidence says:

1. The no-apply durable-result route is not capped at 0.61M/s; that conclusion came from inadequate warm-up.
2. E290-E299 identify the later 0.59-0.77M/s band as an unpinned initial-heap low state, not a production regression.
   With `-Xms8g`, the fully reverted production route returns to a 0.953M/s clean geometric pin and reaches 0.967M/s.
3. At that topology the strongest measured shared limiter remains the ordered result JDBC store. E279 measured
   **1.063M results/s active service**, **89.5% utilization** and p95 queue **19.843 ms**.
4. This route still omits model/event work. It establishes base-route headroom and a causal target, not evidence that
   the complete P3 model route is near 1M/s.
5. The current campaign remains on no-model command/result E2E until it is well above 1M/s. Screen bounded result
   transaction admission, improve command offering and completion where route-relevant, then add one durable event.
   Return to model commits only after that lower-bound route has ample headroom.

## Immutable evidence

Every invocation is recorded as E209-E335 in
[`model-e2e-run-registry.csv`](model-e2e-run-registry.csv). Principal artifacts are:

- E224 async-profiler log/collapsed stacks: `0bcf2161...` / `8467cf40...`;
- E229 detailed trace log/JFR/summary: `4b2ef67f...` / `eba49d8e...` / `453a9309...`;
- E232 corrected single-client batch log/JFR/summary: `9147dec7...` / `78b6f857...` / `ea3f46fc...`;
- E238/E240/E241 two-client long controls: `1f45123b...` / `dfbdbe14...` / `55bbf15c...`;
- E242 two-client batch log/JFR/summary: `76c5fe66...` / `ae05a611...` / `98b11672...`.
- E280/E281/E282 completion isolation logs: `682471ee...` / `96b18f84...` / `0e4a3674...`.
- E283-E289 offering diagnostics and clean default verification are registered with full hashes below.
- E290-E299 heap/cache/database regression audit: [`model-e2e-no-model-change-log.md`](model-e2e-no-model-change-log.md).
- E300-E315 generic appender and payload audit: [`model-e2e-message-appender-anatomy.md`](model-e2e-message-appender-anatomy.md).
- E316-E335 PostgreSQL and position durability audit:
  [`model-e2e-position-durability-anatomy.md`](model-e2e-position-durability-anatomy.md).

Full SHA-256 values and all paired/rejected run artifacts remain in the registry.
