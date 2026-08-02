# P3 full-route anatomy (E75-E78)

P3 is the accepted large-co-located-tail checkpoint. It keeps the complete qualifying route intact and changes only
the physical representation of the underfilled tail of a sufficiently large co-located message-log transaction. Small
and isolated appends still use staging so low-rate logs retain tail coalescing.

## Checkpoint and measurement identity

| Field | Value |
| --- | --- |
| Accepted SDK production source | `e94188b5876`; JFR-only route-observer source `9f14c8c25d9` |
| Accepted Runtime source | `9d0bed30b643e9dfc6a99069a764d6ea69343d3a` |
| Route | stored/tracked command -> automatic `@Apply` -> atomic model/event commit -> durable ordinary result -> tracked caller completion |
| Workload | 1,048,576 measured updates after 65,536 warm-up updates; 65,536 models and maximum in flight; 16 consumers; fetch 65,536; payload 32 bytes |
| Defaults | `2026.07.27`, adaptive model cache, `ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH` |
| Host | Apple M4 Max, 36 GiB RAM, macOS Darwin 24.6.0, OpenJDK 25, `-Xms8g -Xmx8g` |
| Database | PostgreSQL 18.3 in Docker, host `localhost:64217`, fresh schema per invocation |
| Benchmark source | SHA-256 `1b85bb6ec7ce53d3e4565bf3f8dc07edef57228c2a41e1344bf7c97e3ea22feb` |
| Final Runtime class | `JdbcMessageStore.class` SHA-256 `a40dfc02456ba619250e88176e6d598c52b9aa4b2e459a30a27cc7b857cbbee1` |
| Benchmark class | SHA-256 `22a4d72e2e42edd90209ed8b04278c6b549bdaf3a10d9948a9ce7a48ea831b50` |
| Installed SDK/common jars | SHA-256 `9a1c06c081399afc10dda87a1ca08bf79ab2cbdf5bf8d726106257c7953d1210` / `698dd3e1d510adef322ab952fafe9980c97b11af5fc6e36351d6377b63e59410` |

E75 is the full JFR anatomy run. Its log, recording and summary SHA-256 values are
`66916aaeb549768370361259887a2634b96ba05583ccf2009afd199ae2f8fd31`,
`c6a58ff6836981f927e734e4247fdcfc9cce48ab0f97dac1dc50449ebcdf0013` and
`b8908ec82d88e715522de872ef77f8d2aee27c9da2de5139938e36c887085a12`. It captured 297 complete command routes and
297/297 routes contained every required processing marker. Its 270,497/s is profiling-only.

E76/E77 are the canonical matched acceptance runs. E78 verifies the final committed success path at 341,117/s; its
log SHA-256 is `4f8b5ec817f0e07265092577a91d4ee3e51d99dc49cda5ecd4cf7a9605e0882a`.

## Canonical acceptance

| Measurement | P2 control | P3 candidate | Change |
| --- | ---: | ---: | ---: |
| Throughput geometric mean | 328,161/s | **341,679/s** | **+4.12%** |
| Exact paired-bootstrap 95% interval | - | - | **+2.49% to +6.02%** |
| p50 geometric mean | 164.214 ms | **154.723 ms** | **-5.78%** |
| p95 geometric mean | 229.798 ms | **223.902 ms** | **-2.57%** |
| p99 geometric mean | 259.684 ms | 259.761 ms | +0.03% |
| max geometric mean | 295.123 ms | 296.119 ms | +0.34% |

All eight paired throughput changes were positive: +3.16%, +4.10%, +9.68%, +3.19%, +0.22%, +6.05%, +2.81% and
+4.00%. Every invocation used the complete ordinary-result route and passed the exact command, model-state,
relationship, event and result checks. The 16 immutable identities are in
[the confirmation CSV](model-e2e-e76-e77-large-colocated-tail-confirmation.csv).

## Mechanism and capacity

| Atomic measurement | E73 P2 | E75 P3 | Change | Meaning |
| --- | ---: | ---: | ---: | --- |
| Event staging mean | **8.382 ms** | 0.037 ms | -99.56% | Load/delete the prior staging tail and insert the new residual staging rows inside the atomic event/model transaction. |
| Staged event rows | **8,064** | 59 | -99.27% | Physical scalar staging rows produced for the fixed 1,048,576 events. |
| Event direct-LTS insert mean | 5.517 ms | 5.394 ms | -2.23% | Serialize/compress and insert compact event-log rows; this already starts before the ordered commit turn. |
| Co-located model SQL mean | 5.376 ms | **9.332 ms** | +73.59% | Model-stream blocks, head validation/state updates and related atomic model work in the same transaction. |
| Event commit mean | 1.285 ms | 2.256 ms | +75.56% | PostgreSQL commit for the combined event/model transaction. |
| Complete packed-model service mean | **24.555 ms** | **20.268 ms** | **-17.46%** | Serialized one-lane service from model-store start through durable completion. |
| Packed-model service capacity | 0.324M/s | **0.325M/s** | +0.31% | Items divided by summed serialized store service; smaller transactions offset much of the local stage removal. |
| Packed transactions | 132 | 159 | +20.45% | Natural atomic model/event transactions; no timer or artificial delay is used. |
| Average packed transaction | 7,944 | 6,595 | -16.98% | Commands/events per durable transaction. |
| Model queue p95 | **36.328 ms** | **32.611 ms** | -10.23% | Residence behind the one active durable model/event transaction. |
| Active durable model lanes | **1** | **1** | unchanged | No two model/event durability intervals overlap; this remains the next structural capacity target. |

The key result is not that every substage improved. Removing almost all staging work shortened complete service and
queue residence, but faster drainage also formed 20% more, 17% smaller transactions, shifting time into model SQL and
commit. The canonical +4.12% is therefore the acceptance fact; the local -99.56% staging number explains the mechanism
but does not substitute for it.

## Resource and storage shape

| Measurement | E73 P2 profile | E75 P3 profile | Interpretation |
| --- | ---: | ---: | --- |
| Sampled allocation | 31,872.2 MiB | 32,405.1 MiB | +1.67% on fixed work; consistent with 27 additional packed transactions, not a retained structure. |
| Max observed heap | 7,834.1 MiB | 7,992.0 MiB | +2.02% under the same fixed 8-GiB heap; both runs remain heap-pressure profiles. |
| GC count/time | 7 / 268.5 ms | 10 / 477.8 ms | Profile-level variation to retain in later reranks; canonical p99/max did not materially regress. |
| Event compact rows | 8,129 | 8,266 | 137 extra bounded rows for 1,048,576 events. |
| Event staging rows | 8,064 | 59 | 8,005 fewer scalar rows and no unbounded tail accumulation. |
| Event store queue maximum | 0 | 0 | The change does not add a queue. |
| New retained cache/buffer/executor | none | none | One boolean store-construction decision only; no per-message retained state. |

The operational trade-off is explicit: about one extra partial compact row per newly formed large transaction replaces
thousands of staging rows and their repeated read/delete boundary. The trigger requires both a co-located task and at
least one complete storage group. Low-rate ordinary appends and small co-located transactions therefore keep the old
staging/coalescing behavior.

## Fundamental route intervals

These are direct adjacent marker-to-marker boundaries. Async branches can overlap, so they are not additive. Marker
adjacency does not by itself imply active service: an interval may still represent residence behind earlier work on
the same ordered lane. E79 proves that this distinction matters for command tracking.
Definitions and inclusions are unchanged from the
[P2 route anatomy](model-e2e-p2-route-anatomy.md); this table supplies the P3 distributions. Rows whose mean exceeds
5 ms are bold because they are the first candidates to relate to capacity, not because latency alone proves a limiter.

| Direct interval | n | mean ms | p50 ms | p95 ms | p99 ms | max ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| sender registration + dispatch | 256 | 3.236 | 2.879 | 5.685 | 11.907 | 13.764 |
| sender dispatch -> command store start | 256 | 4.546 | 3.905 | 7.374 | 11.812 | 34.186 |
| **command durable store (database commit)** | 251 | **12.664** | 10.492 | 27.779 | 50.954 | 60.230 |
| command durable -> notification submit | 297 | 0.027 | 0.011 | 0.086 | 0.252 | 0.339 |
| command notification submit -> strategy update | 297 | 0.470 | 0.220 | 1.844 | 4.411 | 10.141 |
| **command strategy update -> batch resolved** | 297 | **40.782** | 38.433 | 104.584 | 153.244 | 169.913 |
| command batch resolved -> endpoint ready | 297 | 0.011 | 0.007 | 0.040 | 0.100 | 0.256 |
| command endpoint ready -> response queued | 297 | 0.045 | 0.033 | 0.134 | 0.192 | 0.464 |
| command response queue | 297 | 0.741 | 0.412 | 2.240 | 4.828 | 15.062 |
| command response encode | 297 | 0.912 | 0.609 | 2.411 | 7.722 | 8.678 |
| command response socket send | 297 | 0.129 | 0.075 | 0.329 | 1.389 | 2.904 |
| command wire transit + SDK decode | 297 | 1.464 | 0.926 | 4.642 | 11.168 | 11.210 |
| command context restore -> preparation | 297 | 0.082 | 0.049 | 0.195 | 0.858 | 2.568 |
| command SDK response preparation | 297 | 0.068 | 0.041 | 0.133 | 0.507 | 2.021 |
| command preparation -> callback queue | 297 | 0.061 | 0.040 | 0.133 | 0.410 | 1.446 |
| command SDK callback queue | 297 | 0.075 | 0.051 | 0.171 | 0.616 | 0.872 |
| command SDK callback execution | 297 | 0.114 | 0.055 | 0.162 | 1.428 | 5.111 |
| command callback start -> read future | 297 | 0.043 | 0.031 | 0.095 | 0.205 | 1.198 |
| command read future -> tracker delivery | 297 | 0.265 | 0.100 | 0.909 | 2.321 | 5.863 |
| command tracker delivery -> model commit registered | 297 | 3.525 | 2.236 | 12.118 | 18.820 | 27.336 |
| model commit registered -> evaluation start | 297 | 0.003 | 0.002 | 0.009 | 0.017 | 0.109 |
| model evaluation | 297 | 0.045 | 0.001 | 0.009 | 1.064 | 7.718 |
| model evaluation -> commit preparation | 297 | 0.002 | 0.001 | 0.006 | 0.021 | 0.030 |
| model commit preparation | 297 | 0.002 | 0.001 | 0.006 | 0.014 | 0.032 |
| prepared commit -> dispatch start | 297 | 0.001 | 0.000 | 0.002 | 0.006 | 0.011 |
| model commit synchronous dispatch | 297 | 0.000 | 0.000 | 0.002 | 0.005 | 0.007 |
| dispatched commit -> transport encoding | 297 | 1.172 | 0.667 | 3.768 | 10.118 | 11.670 |
| model request encode/compress | 297 | 0.276 | 0.160 | 1.025 | 2.233 | 2.557 |
| model request wire + Runtime decode | 297 | 0.384 | 0.241 | 1.357 | 3.520 | 4.247 |
| Runtime model intake preparation | 297 | 0.296 | 0.195 | 0.826 | 1.577 | 2.010 |
| **Runtime model-store queue** | 297 | **9.926** | 8.279 | 25.603 | 48.972 | 60.935 |
| **Runtime model-store durability** | 297 | **20.611** | 19.634 | 42.664 | 67.450 | 67.467 |
| Runtime post-durable job completion | 297 | 1.754 | 1.660 | 4.041 | 5.709 | 6.423 |
| model result eligibility -> response queue | 290 | 0.061 | 0.011 | 0.190 | 0.695 | 3.582 |
| model result response queue | 297 | 1.566 | 0.275 | 6.714 | 50.001 | 50.113 |
| model result encode | 297 | 0.974 | 0.262 | 1.849 | 49.846 | 49.865 |
| model result socket send | 297 | 0.137 | 0.048 | 0.178 | 0.667 | 21.039 |
| **model result wire transit + SDK decode** | 297 | **5.667** | 2.946 | 18.155 | 53.585 | 57.168 |
| model result context -> processor start | 297 | 0.075 | 0.052 | 0.209 | 0.253 | 2.358 |
| model processor entry | 297 | 0.513 | 0.238 | 2.110 | 4.138 | 4.722 |
| model result matching | 297 | 0.001 | 0.000 | 0.002 | 0.006 | 0.007 |
| matched result -> post-commit | 297 | 0.773 | 0.335 | 2.246 | 3.793 | 52.122 |
| SDK post-commit processing | 297 | 2.670 | 1.901 | 6.970 | 10.308 | 27.535 |
| post-commit -> result preparation complete | 297 | 0.195 | 0.109 | 0.760 | 0.969 | 1.371 |
| model preparation -> callback queue | 297 | 0.087 | 0.059 | 0.225 | 0.320 | 2.840 |
| model SDK callback queue | 297 | 0.627 | 0.387 | 2.107 | 3.973 | 5.090 |
| model SDK callback execution | 297 | 0.082 | 0.052 | 0.235 | 0.528 | 2.548 |
| **per-message execution -> handler batch tail** | 297 | **7.820** | 2.654 | 30.982 | 42.352 | 65.050 |
| model execution -> handler result future | 297 | 0.002 | 0.001 | 0.005 | 0.015 | 0.025 |
| handler result future -> result ready | 297 | 0.001 | 0.000 | 0.001 | 0.006 | 0.073 |
| handler result barrier | 297 | 0.001 | 0.000 | 0.002 | 0.005 | 0.023 |
| barrier -> result publication request | 297 | 0.021 | 0.004 | 0.017 | 0.090 | 4.126 |
| result publication request -> worker start | 297 | 1.355 | 1.072 | 3.221 | 4.887 | 7.840 |
| **result worker -> Runtime store admission** | 297 | **5.600** | 4.931 | 12.797 | 28.154 | 53.085 |
| **result database durability** | 297 | **18.722** | 15.843 | 43.352 | 54.703 | 72.948 |
| result durable -> notification submit | 297 | 0.317 | 0.359 | 0.596 | 1.656 | 2.377 |
| result notification submit -> strategy update | 297 | 0.920 | 0.627 | 2.358 | 4.071 | 30.075 |
| **result strategy update -> batch resolved** | 297 | **8.479** | 7.321 | 20.342 | 25.951 | 48.219 |
| result batch resolved -> endpoint ready | 297 | 0.628 | 0.634 | 1.195 | 1.589 | 2.037 |
| result endpoint ready -> response queued | 297 | 0.619 | 0.613 | 1.130 | 2.921 | 2.931 |
| result response queue | 297 | 0.903 | 0.807 | 2.096 | 5.070 | 5.072 |
| result response encode | 297 | 3.046 | 2.579 | 5.432 | 33.090 | 33.448 |
| result response socket send | 297 | 0.652 | 0.636 | 1.240 | 2.924 | 2.929 |
| result wire transit + SDK decode | 297 | 1.950 | 1.801 | 4.545 | 8.339 | 8.634 |
| result context restore -> preparation | 297 | 0.591 | 0.608 | 1.104 | 1.196 | 2.188 |
| result SDK response preparation | 297 | 0.567 | 0.591 | 1.075 | 1.161 | 1.190 |
| result preparation -> callback queue | 297 | 0.766 | 0.588 | 1.132 | 1.716 | 28.515 |
| result SDK callback queue | 297 | 0.591 | 0.592 | 1.088 | 2.049 | 2.334 |
| result SDK callback execution | 297 | 1.181 | 1.211 | 2.164 | 2.402 | 2.452 |
| result callback start -> read future | 297 | 0.589 | 0.602 | 1.101 | 1.272 | 1.367 |
| result read future -> tracker delivery | 297 | 1.014 | 0.928 | 2.085 | 3.149 | 3.245 |
| result tracker handling | 297 | 0.830 | 0.826 | 1.579 | 2.115 | 2.150 |
| **result tracker -> request-handler receipt** | 297 | **12.362** | 9.389 | 35.208 | 58.603 | 104.660 |
| request-handler result callback | 297 | 0.004 | 0.003 | 0.011 | 0.034 | 0.052 |
| result deserialization | 297 | 0.001 | 0.001 | 0.002 | 0.004 | 0.028 |
| deserialized result -> command future | 297 | 0.001 | 0.001 | 0.001 | 0.003 | 0.008 |


## Composite route intervals

Composite intervals span multiple fundamental boundaries or async lifecycles. The component column prevents them from
being summed again as if they were independent work.

| Composite interval | Included fundamental route | n | mean ms | p50 ms | p95 ms | p99 ms | max ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| **command outer append lifetime** | store admission + JDBC durability + monitor submission + outer-future completion | 256 | **12.656** | 10.535 | 27.875 | 50.990 | 60.277 |
| **command durable -> tracker delivery** | notification submit + tracking update/scan + Runtime response + SDK decode/callback/scheduling | 297 | **45.176** | 41.455 | 111.066 | 157.507 | 177.318 |
| **legacy command store-complete -> tracker delivery** | compatibility marker; overlaps tracking notification because outer completion is not the DB commit | 251 | **46.771** | 45.768 | 106.813 | 143.496 | 176.834 |
| **model commit registered -> handler batch complete** | evaluation + request transport + Runtime queue/durability + result transport/post-commit + batch tail | 297 | **55.715** | 53.447 | 114.005 | 130.135 | 134.897 |
| **model result transport** | Runtime response queue + encode/send + wire/decode + SDK processor entry | 297 | **8.990** | 4.496 | 24.308 | 84.420 | 85.069 |
| post-commit -> per-message execution | completion-chain propagation for this command | 297 | 0.990 | 0.691 | 2.864 | 4.890 | 5.782 |
| **post-commit -> handler batch complete** | per-message completion + wait for the slowest command in the handler batch | 297 | **8.811** | 4.341 | 32.371 | 45.453 | 65.902 |
| post-commit -> result barrier complete | per-message execution + handler future propagation + result-publication barrier | 297 | 0.994 | 0.693 | 2.866 | 4.892 | 5.784 |
| **commit registered -> result worker** | model commit route + result barrier + result-backlog wait | 297 | **49.274** | 45.288 | 92.538 | 123.864 | 128.867 |
| result gateway publication lifetime | mapping/interception/serialization + append admission + awaited transport/store future | 302 | 2.169 | 1.704 | 5.493 | 7.407 | 50.355 |
| **result outer durable store** | Runtime append admission + JDBC durability + monitor submission + outer completion | 297 | **19.331** | 16.652 | 44.089 | 55.203 | 73.130 |
| **result durable -> tracker delivery** | notification + tracking resolution + Runtime response + SDK decode/callback/scheduling | 297 | **21.633** | 21.230 | 43.891 | 54.630 | 63.965 |
| **result tracker -> request handler** | tracker handler + request correlation delivery | 297 | **12.362** | 9.389 | 35.208 | 58.603 | 104.660 |
| **command tracker handling** | entire automatic model commit and result-publication work observed by the command tracker | 297 | **68.393** | 66.142 | 124.531 | 150.215 | 156.241 |
| **full processing route: command tracker delivery -> command future** | model processing + result publication/storage/tracking + caller completion | 297 | **111.118** | 108.528 | 189.616 | 227.006 | 261.348 |
| **full captured route: request registered -> command future** | sender + command durability/delivery + processing + durable result return | 251 | **189.057** | 183.003 | 292.116 | 355.075 | 387.709 |
| **captured durable route: command store start -> result tracker complete** | command append + delivery + model/event commit + result append/delivery/handling | 251 | **170.456** | 163.208 | 271.719 | 331.703 | 371.671 |


## Correctness and failure-path verification

The final source passed 42 `JdbcMessageStoreTest` tests, the combined 140 message/model-store tests and the complete
672-test Runtime reactor. Tests cover direct-tail ordering, exact index reads, low-rate staging, opt-out behavior,
conditional accept/reject, transient insert and unknown-commit retry, shutdown, atomic model rollback and all existing
model-store contracts.

Adversarial review found a latent head-layout issue made relevant by the new default: when a large conditional
transaction planned to flush existing staging and then rejected, PostgreSQL rolled the flush back while the in-memory
head retained the predicted post-flush state. Both new tests failed before the hardening (four stale staging rows
sequentially and three under an already reserved successor). The final implementation restores the prior staging head
with CAS when no successor exists. If a successor was already planned from the post-flush layout, it compacts only the
old, already durable staging rows before the single commit executor advances. Rejected messages are never persisted.

## Decision and next limiter

P3 is accepted and committed. It is a simple bounded storage-shape rule with a reproducible complete-route gain and no
wire, stored-format, ordering, atomicity, durability, retry, policy or SDK-default change.

### E79 correction: the 40-ms command-tracking interval is downstream residence

E79 added a JFR-only origin marker to every sampled resolved batch. Of 365 fully correlated command routes, **359
(98.4%)** were resolved by a later client request after that consumer finished its preceding ordered batch; only **6
(1.6%)** were resolved by the storage notification that first announced the command. C06 averaged **43.147 ms**, while
the 910 actual command scans averaged only **0.682 ms**. The notification worker itself averaged 0.216 ms and normally
had no tracker to wake because all 16 consumer lanes were busy.

Consequently C06 is not an independently optimizable 43-ms tracking service stage. It is a residence gauge for
downstream consumer capacity. Scan SQL or parallel notification fan-out would attack at most the 1.6% notification
minority; increasing handler/model/result capacity should reduce C06 automatically. The E79 log/JFR/summary SHA-256
values are `7b7189f96f2d60751656cf7dd95205c9da62863346b0c0d5a6d36116e8fbd768`,
`39735991b3ab60385101e37fe4a6c85c256b3c35e84f560c98575625f4656c44` and
`47eefd265968a18e3f80f2655eafcd91a4a5f1db84fda4e8fd8d557425b96d02`.

The remaining hard capacity signal is the same one the user selected: one active model/event durability lane. P3
reduced its queue p95 but did not materially raise its item service ceiling because smaller natural transactions moved
work into model SQL and commit. The next experiment must therefore preserve natural batches while allowing preparation
and direct inserts to overlap across bounded jobs, with state/index conflicts and final commits remaining strictly
ordered. It requires a causal prototype and complete-route proof; a sleep-based collection window or globally
unordered commit is already rejected by prior experiments.
