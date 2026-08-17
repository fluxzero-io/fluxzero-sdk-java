# SDK PR #286 Runtime-ingress integration gate

## Decision

SDK merge `190b82cc5f3` combines model-branch base `c4e46ee068d` with PR head `fedf55364ee`. The merge preserves
the model branch's binary transport, request-context restoration and asynchronous grouped-result preparation while
adopting bounded Runtime ingress and result completion. Runtime stayed fixed at `610a7060bda`; all database runs used
isolated database `codex_pr286_gate_20260817`, Java 25, an 8-GiB fixed heap and no JFR.

The accepted default completion concurrency is eight, not the PR head's Java-25 default of 32. Eight improves both the
isolated cheap-result route and matched full E2E routes over the exact model-branch parent. Wider concurrency remains
explicitly configurable for applications whose callbacks are actually slow.

The transport change is not literally `request(1) -> request(n)`: parent, PR head and merge all invoke
`WebSocket.request(1)`. The difference is when the next credit is granted. The parent requested the next frame after
the current callback, while the PR retains complete messages against message/byte limits and grants a new one only
when bounded decode/admission capacity permits. Decode workers and the client-wide result-completion dispatcher are
separate, so slow synchronous continuations cannot consume protocol-callback or decode capacity.

## Result-completion causal screen

The PR's comparison against released SDK 1.239 reported -45.7% for singleton results and +1.7%, +22.8% and +23.7% for
batches of 32, 128 and 1,024. That baseline was insufficient for this branch: its parent already contained a fast
64-result callback batcher. E823-E828 therefore use the exact branch parent. At limit 32, both pure PR head and the
conflict-resolved merge are slower for cheap 1,024-result batches; their matching results prove conflict resolution was
not the cause.

E829-E833 isolate concurrency. Cheap large batches over-parallelize above eight. E834-E839 show the opposite valid
workload: 32 helps singleton results and deliberately slow callbacks. Eight is the balanced default: it preserves eight
slow callbacks in parallel, improves singleton service over the parent, and avoids the large-batch collapse. A
wall-clock adaptive candidate briefly looked faster, but E847-E850 prove that scheduler/host delay can promote it to
the harmful 32-wide path; it was completely reverted.

For E823-E842, `command_count` denotes completed result callbacks because this is the isolated result-dispatch route.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E823 | smoke | result completion, batch 1,024 | `c4e46ee068d` | parent P1 | 4,194,304 results | 5 passes | none | 3,366,981/s | n/a | false | exact parent service | diagnostic-only |
| E824 | smoke | result completion, batch 1,024 | `c4e46ee068d` | parent P2 | 4,194,304 results | 5 passes | none | 3,350,459/s | n/a | false | exact parent service | diagnostic-only |
| E825 | smoke | result completion, batch 1,024 | `c4e46ee068d` | pure PR head, limit 32, H1 | 4,194,304 results | 5 passes | none | 3,358,710/s geometric parent | 2,656,570/s | false | over-parallelized | diagnostic-only |
| E826 | smoke | result completion, batch 1,024 | `c4e46ee068d` | pure PR head, limit 32, H2 | 4,194,304 results | 5 passes | none | 3,358,710/s geometric parent | 2,821,861/s | false | pure-head confirmation | diagnostic-only |
| E827 | smoke | result completion, batch 1,024 | `c4e46ee068d` | merged candidate, limit 32, C1 | 4,194,304 results | 5 passes | none | 3,358,710/s geometric parent | 2,566,401/s | false | matches pure-head mechanism | diagnostic-only |
| E828 | smoke | result completion, batch 1,024 | `c4e46ee068d` | merged candidate, limit 32, C2 | 4,194,304 results | 5 passes | none | 3,358,710/s geometric parent | 2,836,302/s | false | reject default 32 | reverted |
| E829 | smoke | result completion, batch 1,024 | merged candidate | concurrency 1 | 2,097,152 results | 4 passes | none | n/a | 6,344,436/s | false | concurrency screen | diagnostic-only |
| E830 | smoke | result completion, batch 1,024 | merged candidate | concurrency 4 | 2,097,152 results | 4 passes | none | n/a | 9,400,991/s | false | fastest cheap-batch screen | diagnostic-only |
| E831 | smoke | result completion, batch 1,024 | merged candidate | concurrency 8 | 2,097,152 results | 4 passes | none | n/a | 5,553,769/s | false | balanced candidate | accepted |
| E832 | smoke | result completion, batch 1,024 | merged candidate | concurrency 16 | 2,097,152 results | 4 passes | none | n/a | 2,739,754/s | false | over-parallelized | diagnostic-only |
| E833 | smoke | result completion, batch 1,024 | merged candidate | concurrency 32 | 2,097,152 results | 4 passes | none | n/a | 2,301,995/s | false | over-parallelized | diagnostic-only |
| E834 | smoke | singleton result completion | merged candidate | concurrency 4 | 1,048,576 results | 3 passes | none | n/a | 393,931/s | false | trade-off screen | diagnostic-only |
| E835 | smoke | singleton result completion | merged candidate | concurrency 8 | 1,048,576 results | 3 passes | none | n/a | 421,138/s | false | balanced candidate | accepted |
| E836 | smoke | singleton result completion | merged candidate | concurrency 32 | 1,048,576 results | 3 passes | none | n/a | 511,083/s | false | wider helps only this route | diagnostic-only |
| E837 | smoke | batch 1,024, 250 us callback | merged candidate | concurrency 4 | 32,768 results | 2 passes | none | n/a | 11,753/s | false | slow-callback trade-off | diagnostic-only |
| E838 | smoke | batch 1,024, 250 us callback | merged candidate | concurrency 8 | 32,768 results | 2 passes | none | n/a | 23,379/s | false | eight callbacks remain parallel | accepted |
| E839 | smoke | batch 1,024, 250 us callback | merged candidate | concurrency 32 | 32,768 results | 2 passes | none | n/a | 96,856/s | false | explicit tuning useful | diagnostic-only |
| E840 | smoke | final result completion, batch 1,024 | `c4e46ee068d` | merge `190b82cc5f3`, default 8, C1 | 4,194,304 results | 5 passes | none | 3,358,710/s geometric parent | 4,648,340/s | false | final-source improvement | accepted |
| E841 | smoke | final result completion, batch 1,024 | `c4e46ee068d` | merge `190b82cc5f3`, default 8, C2 | 4,194,304 results | 5 passes | none | 3,358,710/s geometric parent | 5,662,082/s | false | final-source confirmation | accepted |
| E842 | smoke | final singleton result completion | `c4e46ee068d` | merge `190b82cc5f3`, default 8 | 2,097,152 results | 4 passes | none | approximately 375,500/s earlier parent | 426,857/s | false | improves singleton service | accepted |

Evidence SHA-256: E823 `a946b1ee7cd440927befea4395e3de69558ebbf41e089a082d72c3fa3dcc8699`, E824
`051afb74505e0d8528bf0db500aa586f2f8e8b1ffc4d571e5116263e7c73562d`, E825
`c39b65cafcc63a01503d785b60d3bd9c49f4b7a23b12b9221a05967d31fa5b0e`, E826
`6897f04bc9df4de10eb8f9c922522ae7933b0d186c1bdb9916751c62123a4733`, E827
`3758e93bc7becd1000231e0017b7da826696a99b08f4642cd9c4822b1f88eb47`, E828
`2e3402b538cfa7cbcc9a91830c1ec745cc9a997774565dd8a312fbdf41c3d0b8`, E829-E833 respectively
`f06ef0331d0a88259c7d8ac158e85af451655e12d2fb1fe1c6d0ec4de8128e40`,
`8278f0487f6cb1a9becec22dadcc5783636bf4c03d03a526334182284b185579`,
`da8bc261da76675a3bb78ff2a600a8d64899c7443bb04dc6ea621323c075518c`,
`2dfe5151433043941b7fdf9dc6f983b9009f484d5bdb373b8e7b610b23f19ec9`,
`61f9c3764a99f5b11fe8dea00168943ddb245397dda5c70a93aeda359de500f3`; E834-E839 respectively
`e636f7bc2534191312441cbb2bc1d996c78bb8097c7781baa3ff14b76d6e47b7`,
`ecad611ebad48c23899bd5fe24db477bda7487dad9cf7236410ca7aba82cb921`,
`b19d60d04e3a06a7b6c977d238a3d128f347427729f28c25ebefbec66cdd6aaf`,
`4ca790b032a6c5ca17444a869f5b89746b66da96aa1c71a04c7d8da696ba1d7d`,
`196243b6483fc26ccebb9a5a43eb14e0d306bad4067f539bc6b4ab4b2dfd2683`,
`bba90a827228f1bf95ec41cbeec288ee604c28b22c56b26a89065281c82bac27`; E840
`b1f2676b52f39569ad4081bf7def1285c48001848661793248f0be8601d97b6b`, E841
`b6d6c197f8403804cecad0e00fcdfe1f086f3df2350a586cf232b3d00be8bbec`, E842
`3467e2ff0ae868a20685dedf951ce1b4aa68ed6c098df438e6c039769ebb0c01`.

## Full E2E acceptance

The original default-32 full no-model pairs (E843-E846) are slightly negative in aggregate: approximately -1.6%.
The corresponding full model route (E855-E860) is neutral across the two stable pairs; E857-E858 are excluded because
the host collapsed. These observations agree with the isolated screen and reject 32 as the branch default.

The final default-8 no-model route is a balanced candidate-parent-parent-candidate sequence. Every run completed exactly
10,485,760 durable results and stored no model/global events. Candidate geometric mean is **615,908/s**, parent
**542,750/s**, or **+13.48%**. Absolute host capacity remained below the historical approximately 1M/s pin, so this is
a causal matched gain, not a replacement absolute pin.

The final model sequence brackets one candidate with two controls. Every run completed exactly 1,048,576 durable
results, stored model events and global events and verified 8,192 final states. Candidate was **177,574/s** versus a
**141,119/s** geometric control interpolation, or **+25.83%**. This is a matched loaded-host gain; the clean P5
**425,606/s** remains the absolute release pin.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E843 | canonical | command -> durable result, no model | `c4e46ee068d` | parent P1 | 10,485,760 | 10,485,760 | none | 517,621/s | n/a | true | original-default control | diagnostic-only |
| E844 | canonical | command -> durable result, no model | `c4e46ee068d` | PR merge, default 32, C1 | 10,485,760 | 10,485,760 | none | 517,621/s preceding | 476,507/s | true | pair 1 | diagnostic-only |
| E845 | canonical | command -> durable result, no model | `c4e46ee068d` | PR merge, default 32, C2 | 10,485,760 | 10,485,760 | none | 527,859/s following | 555,244/s | true | reverse pair | diagnostic-only |
| E846 | canonical | command -> durable result, no model | `c4e46ee068d` | parent P2 | 10,485,760 | 10,485,760 | none | 527,859/s | n/a | true | aggregate -1.6% | diagnostic-only |
| E847 | canonical | command -> durable result, no model | `c4e46ee068d` | adaptive candidate | 10,485,760 | 10,485,760 | none | 594,769/s following | 666,402/s | false | apparent gain; require reverse | reverted |
| E848 | canonical | command -> durable result, no model | `c4e46ee068d` | parent adaptive P3 | 10,485,760 | 10,485,760 | none | 594,769/s | n/a | false | moving-host control | diagnostic-only |
| E849 | canonical | command -> durable result, no model | `c4e46ee068d` | parent adaptive P4 | 10,485,760 | 10,485,760 | none | 686,354/s | n/a | false | reverse control | diagnostic-only |
| E850 | canonical | command -> durable result, no model | `c4e46ee068d` | adaptive candidate | 10,485,760 | 10,485,760 | none | 686,354/s preceding | 491,954/s | false | warmup collapsed; adaptation unsafe | reverted |
| E851 | canonical | command -> durable result, no model | `c4e46ee068d` | merge `190b82cc5f3`, default 8, C1 | 10,485,760 | 10,485,760 | none | 624,896/s following | 689,500/s | true | +10.34% pair 1 | accepted |
| E852 | canonical | command -> durable result, no model | `c4e46ee068d` | parent P1 | 10,485,760 | 10,485,760 | none | 624,896/s | n/a | true | adjacent control | diagnostic-only |
| E853 | canonical | command -> durable result, no model | `c4e46ee068d` | parent P2 | 10,485,760 | 10,485,760 | none | 471,403/s | n/a | true | reverse control | diagnostic-only |
| E854 | canonical | command -> durable result, no model | `c4e46ee068d` | merge `190b82cc5f3`, default 8, C2 | 10,485,760 | 10,485,760 | none | 471,403/s preceding | 550,170/s | true | +16.71%; geometric +13.48% | accepted |
| E855 | smoke | full command -> model -> event + result, 8 CPUs | `c4e46ee068d` | parent P1 | 1,048,576 | 262,144 | none | 146,470/s | n/a | false | default-32 control | diagnostic-only |
| E856 | smoke | full command -> model -> event + result, 8 CPUs | `c4e46ee068d` | PR merge, default 32, C1 | 1,048,576 | 262,144 | none | 146,470/s preceding | 149,098/s | false | stable pair +1.79% | diagnostic-only |
| E857 | smoke | full command -> model -> event + result, 8 CPUs | `c4e46ee068d` | PR merge, default 32, C2 | 1,048,576 | 262,144 | none | 124,336/s following | 93,799/s | false | host collapse; exclude | diagnostic-only |
| E858 | smoke | full command -> model -> event + result, 8 CPUs | `c4e46ee068d` | parent P2 | 1,048,576 | 262,144 | none | 124,336/s | n/a | false | host collapse; exclude | diagnostic-only |
| E859 | smoke | full command -> model -> event + result, 8 CPUs | `c4e46ee068d` | PR merge, default 32, C3 | 1,048,576 | 262,144 | none | 168,831/s following | 164,140/s | false | stable reverse -2.78% | diagnostic-only |
| E860 | smoke | full command -> model -> event + result, 8 CPUs | `c4e46ee068d` | parent P3 | 1,048,576 | 262,144 | none | 168,831/s | n/a | false | stable aggregate -0.52% | diagnostic-only |
| E861 | smoke | full command -> model -> event + result, 8 CPUs | `c4e46ee068d` | parent bracket P1 | 1,048,576 | 262,144 | none | 133,890/s | n/a | false | final default-8 control | diagnostic-only |
| E862 | smoke | full command -> model -> event + result, 8 CPUs | `c4e46ee068d` | merge `190b82cc5f3`, default 8 | 1,048,576 | 262,144 | none | 141,119/s interpolated | 177,574/s | false | exact bracket +25.83% | accepted |
| E863 | smoke | full command -> model -> event + result, 8 CPUs | `c4e46ee068d` | parent bracket P2 | 1,048,576 | 262,144 | none | 148,738/s | n/a | false | final default-8 control | diagnostic-only |

Evidence SHA-256: E843-E846 respectively `a7fa7b98c9cdff4eb2fab5f5b3f57359eddb8d9c9aea8a48d42bb013885a9cac`,
`fcfeaed6f62bb7978b12f0d5719d89ce7dcbba38028dd3b8288215319dee4e41`,
`db4f069af80645a64146f14712f5f39957b346908cd198aa65b581da32a18e3c`,
`e9583f442eb4216dbfbfa81195222a6c81025cb119b2936274cdbb114f9c1274`; E847-E850 respectively
`7e5eaa9d4dc1f5035b78799b06117386ce5c9d579df460070bb1faf2a780c564`,
`2787dab38a4d7cd6bd93a56c045a4aa9328b5912cee64682c4c2dcd2dbbd800c`,
`51fd7861c215353d931624477cb5c72344b19a865b6ac90e2f56ef5134ea188c`,
`d960c4ac1152950cf68cbc7fb46613bf38de5fa188755f30beebe6afc32327ae`; E851-E854 respectively
`85387b7e5b43b678cf185747470006bc4ca013f41925c884dabc771d014d8121`,
`ee468ffd35891f740104fcbd38a19e61d3fdb63a4ce8401153ef91df8eff47a9`,
`8a299c6451a8d0ec29293bb395da9548f8f04496e9896ad3b42063d1bf123ed8`,
`4e97b48d6fed489d71a66d178d33c1a4e7f1509191a3ef7b5e779df24c00e323`; E855-E860 respectively
`c9b0848742d4dc4c71859da546258359d4a157c3798f90b8efb8ff1b44705fad`,
`704701f778a34776d0a728d45cb08af1cb492d29fd479c84117cbb0bb191def9`,
`c47b1f672418ed80df004e82cee948cb0e02c6f158f5aca1c33152a615de10d0`,
`3e10782188cd743b6c550f31addad0b066ac5cf1abe7e0ecddc0d5bf1c8484f7`,
`b5e4ba63d91d0462b469352d50cb6e0ae58d9881d5fda5def4f56bc899ffa8f4`,
`9a9cdb7b4645fc496cfedc36690612dd3cfdc9d66dd5ab472834b826fec834bd`; E861-E863 respectively
`f2866fd0cc984d34fc7feea93a554ba164e417e1c0dc6ee505871ae7f44e5469`,
`aafb597324d81f2e03898c5870eda711ee573c7a8dc4bfc66a4f5b5636062378`,
`e25a78a39c3b1a60000be43379897c4fe1e2dc02b7255efdf7ea20ebab8edb68`.

## Current-source direct 8 versus 32 audit

E864-E871 were added after the acceptance gate to test the completion limit directly on the same production binary,
instead of inferring its effect from comparisons against the pre-PR parent. Both routes use the full compressed
WebSocket decode and bounded result-dispatch path with a trivial functional callback. The sequence alternates
8-32-32-8. The host was not canonical (`mds` used approximately 55% CPU and the Docker VM approximately 15%), so only
the large within-sequence ratios are used.

For 1,024-result messages, limit 8 has a geometric mean of **5,304,432 results/s** versus **2,363,448 results/s** for
limit 32: 8 is **2.24x** or **124.44%** faster. For singleton messages, limit 32 has a geometric mean of **515,042
results/s** versus **420,624 results/s** for limit 8: 32 is **22.45%** faster. This confirms a real workload trade-off,
not a universally superior limit. It also narrows the E2E claim: the earlier +13.48% and +25.83% are matched gains of
the complete accepted PR integration over its parent, but cannot be attributed exclusively to 8 because the full E2E
gate did not alternate 8 and 32 directly on one binary.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E864 | smoke | current-source result decode/completion, batch 1,024 | `190b82cc5f3`, limit 8 | 8, A1 | 4,194,304 results | 5 full passes | none | 5,048,600/s | n/a | false | direct parameter control | diagnostic-only |
| E865 | smoke | current-source result decode/completion, batch 1,024 | `190b82cc5f3`, limit 8 | 32, B1 | 4,194,304 results | 5 full passes | none | 5,048,600/s preceding | 2,202,433/s | false | 32 over-parallelizes cheap batch | diagnostic-only |
| E866 | smoke | current-source result decode/completion, batch 1,024 | `190b82cc5f3`, limit 8 | 32, B2 | 4,194,304 results | 5 full passes | none | 5,573,229/s following | 2,536,234/s | false | reverse confirmation | diagnostic-only |
| E867 | smoke | current-source result decode/completion, batch 1,024 | `190b82cc5f3`, limit 8 | 8, A2 | 4,194,304 results | 5 full passes | none | 5,573,229/s | n/a | false | geometric advantage 124.44% | diagnostic-only |
| E868 | smoke | current-source singleton result decode/completion | `190b82cc5f3`, limit 8 | 8, A1 | 1,048,576 results | 3 full passes | none | 420,334/s | n/a | false | direct parameter control | diagnostic-only |
| E869 | smoke | current-source singleton result decode/completion | `190b82cc5f3`, limit 8 | 32, B1 | 1,048,576 results | 3 full passes | none | 420,334/s preceding | 509,923/s | false | 32 helps singleton scheduling | diagnostic-only |
| E870 | smoke | current-source singleton result decode/completion | `190b82cc5f3`, limit 8 | 32, B2 | 1,048,576 results | 3 full passes | none | 420,916/s following | 520,213/s | false | reverse confirmation | diagnostic-only |
| E871 | smoke | current-source singleton result decode/completion | `190b82cc5f3`, limit 8 | 8, A2 | 1,048,576 results | 3 full passes | none | 420,916/s | n/a | false | geometric disadvantage 22.45% | diagnostic-only |

E872-E875 screen limit 16 on the same source and loaded host. Singleton service is a genuine midpoint: geometric
**471,839/s**, 12.18% above 8 and 8.39% below 32. Its two batch measurements vary from 2.51M/s to 6.44M/s; the
geometric 4.02M/s is 24.26% below 8, but that spread is too wide for a causal rank. Limit 16 therefore remains an
unproven compromise, not a better default. The opposing batch/singleton curves also expose that one setting currently
bounds both admitted result groups and active callbacks inside large result batches; a deterministic separation of
those concerns deserves evaluation before selecting a wider fixed default.

| run | run_type | route | accepted_base | candidate | command_count | warmup_count | profiling | control_throughput | candidate_throughput | canonical_comparable | decision | code_status |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| E872 | smoke | current-source result decode/completion, batch 1,024 | `190b82cc5f3`, limits 8/32 | 16, C1 | 4,194,304 results | 5 full passes | none | 5,304,432/s limit 8 geometric | 2,507,556/s | false | unstable midpoint screen | diagnostic-only |
| E873 | smoke | current-source result decode/completion, batch 1,024 | `190b82cc5f3`, limits 8/32 | 16, C2 | 4,194,304 results | 5 full passes | none | 5,304,432/s limit 8 geometric | 6,436,762/s | false | variance rejects causal rank | diagnostic-only |
| E874 | smoke | current-source singleton result decode/completion | `190b82cc5f3`, limits 8/32 | 16, C1 | 1,048,576 results | 3 full passes | none | 420,624/s limit 8 geometric | 466,983/s | false | midpoint screen | diagnostic-only |
| E875 | smoke | current-source singleton result decode/completion | `190b82cc5f3`, limits 8/32 | 16, C2 | 1,048,576 results | 3 full passes | none | 515,042/s limit 32 geometric | 476,746/s | false | singleton midpoint confirmed | diagnostic-only |

## Correctness and compatibility gate

- Focused WebSocket/configuration/result-dispatch tests: 101 green before the final simplification and 94 green after.
- Full `./mvnw -B install`: all nine modules green, including test-server, proxy, annotation processor and Java/Kotlin
  downstream projects.
- One first-pass failure in an unchanged timeout-cancellation test reproduced as green in isolation and the full rerun;
  no production or test code was changed for that pre-existing queue-observation race.
- All accepted full-route runs completed exact result/event counts and final model-state verification.
- The adaptive candidate and Java-25 default 32 are absent from merge `190b82cc5f3`.
