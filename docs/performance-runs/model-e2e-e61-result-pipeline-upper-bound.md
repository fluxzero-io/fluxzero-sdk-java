# E61 ordinary-result pipeline upper bound and fusion design

E61 removed only ordinary command-result publication from the accepted P2 canonical route. It is not a qualifying
throughput run because callers deliberately did not receive results. It is a causal upper-bound diagnostic for the next
architectural candidate: model evaluation, event publication, model durability and exact final model/event checks stayed
enabled, while the SDK result mapper/interceptors/serializer, result WebSocket publication, Runtime result durability
and result tracking delivery were absent.

## Measured upper bound

| Metric | Ordinary results (E60 ZSTD control) | No ordinary results | Delta |
| --- | ---: | ---: | ---: |
| Full model route | 272,212/s | 405,700/s | +49.04% |
| Measured dispatch batches | 118 | 93 | -21.19% |
| Average dispatch batch | 8,886.2 | 11,275.0 | +26.88% |
| SDK command-tracker cumulative duration | 54.550 s | 37.215 s | -31.78% |
| SDK model-handler cumulative duration | 48.846 s | 34.413 s | -29.55% |
| Runtime packed-model transactions | 134 | 114 | -14.93% |
| Runtime packed-model cumulative duration | 2.944 s | 1.990 s | -32.39% |

The no-result run processed 1,048,576 measured commands under SDK defaults `2026.07.27`, negotiated `BINARY_V2` plus
ZSTD and completed the exact final event/model assertions. The missing result count and zero latency values are intended,
which is why 405,700/s is a mechanism ceiling rather than a checkpoint candidate. JFR observed no SDK result-gateway or
Runtime result-store work. Client JVM user plus system CPU averaged 36.79%, Runtime 13.23%, and whole-machine CPU averaged
78.41% during the client recording. Removing the result path also improved model transaction formation, so its cost is
not limited to its own 5.884 seconds of E60 result-store service.

## Proven serialization to remove

For automatic model handling the logical ordinary response in this workload is already known to be `null`. The current
route nevertheless completes in this order:

1. start and durably complete the model commit;
2. map, intercept and serialize the ordinary result in the SDK;
3. publish it over a second WebSocket request;
4. append it durably to the Runtime result log in a second transaction stream;
5. deliver it back over result tracking to complete the original command future.

E59 proved the first wait in command-consumer wall profiles; E60 and E61 now quantify the second half's wire, durable
storage, CPU and feedback cost. This is the largest unclosed architectural boundary, rather than another local codec,
cache or SQL microphase.

## E62 candidate contract

The candidate will prepare the known automatic handler result with the originating context while model evaluation is in
flight, attach its native envelope to the model-commit transport, and let a capable Runtime append accepted results in
the same transport-batch order before acknowledging the model commits. Successful Runtime storage suppresses the old SDK
result publication. A failed or unsupported fused append returns an explicit `not stored` outcome and publishes the
already-prepared result through the established gateway/error-handler path.

Compatibility is negotiated through a new transport capability. New SDKs talking to old runtimes retain `BINARY_V2`
and the existing result route; old SDKs send no attached result to a new Runtime. Non-WebSocket/custom event stores,
non-automatic handlers, custom response values and any request that cannot be prepared safely remain on the existing
path. The wire extension is additive and native-envelope based; existing persisted event, model and result formats do
not change.

The candidate is acceptable only if focused tests preserve:

- commit/result ordering and one response position per accepted command;
- conflict, duplicate, retry and partial-failure behavior;
- result suppression and result-preparation failure behavior;
- originating `ThreadLocalContext`, response mapping and all dispatch hooks;
- `awaitAsyncResults=true` completion on actual result durability;
- old SDK/new Runtime, new SDK/old Runtime and custom-client fallback;
- bounded batch memory and no unbounded retained result envelopes.

After those contracts pass, an equivalent dual-JFR run must prove that attached results are actually stored in fewer,
larger Runtime batches and that the SDK result gateway disappeared. Only a positive mechanism run proceeds to balanced
non-JFR confirmation; code is checkpointed only after a significant correct E2E gain over accepted P2.

## Reproduction identity

- SDK production source: `e94188b5876`; documentation HEAD at measurement time: `8043588dbafe`.
- Runtime source: `ed9cb3419e0b61e49869886f81f742f1c8bf6a77`.
- Launcher SHA-256: `d9933d9b6c7b3cc8639eae6bcbc552d675b63a72876715e1e439618364722f7c`.
- Harness log/client output: `219da7ed1cbd2b542fa2a09e1cae4b2271f0db78026045ecd6df4a56a2885d2b` /
  `d14398e192eee4fd5953f0295c66c6dbe789f0889fb3953738ac0e7fcbd18068`.
- Client/Runtime JFR: `154c23abaf4952ee977d9051151fcf21c85685699a1a6124edc20e16974e2842` /
  `81a8c3a42b5cc2aaa637dc9d39c03795714cd338370b55da4dcff85d162ce1b8`.
- Runtime log: `158283477e6e6cfaa97b98dbf6f58ef8d1e8356507093b4b4c72711f57f17b90`.

Raw artifacts remain under `/private/tmp/model-e2e-e59-e61-no-results-upper-*` on the measurement host.
