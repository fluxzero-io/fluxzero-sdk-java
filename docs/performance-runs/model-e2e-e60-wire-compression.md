# E60 negotiated wire-compression diagnostic

E60 tested the largest independently switchable CPU cluster from E59 without changing production source. The accepted
P2 SDK and Runtime were run with the canonical full command-to-result workload and measured-phase JFR in both JVMs.
The only variable was the already-supported WebSocket compression negotiation: `LZ4` first and a fresh `ZSTD` control
immediately afterwards. Both runs negotiated `BINARY_V2`, used SDK defaults `2026.07.27`, processed 1,048,576 measured
commands and completed all exact result, event and model-state checks.

## Result

| Metric | LZ4 | ZSTD control | LZ4 delta |
| --- | ---: | ---: | ---: |
| Full command -> automatic `@Apply` -> model commit -> ordinary result | 265,448/s | 272,212/s | -2.48% |
| Measured command batches | 127 | 118 | +7.63% |
| Average command batch | 8,256.5 | 8,886.2 | -7.09% |
| Result latency p50 | 192.782 ms | 186.565 ms | +3.33% |
| Result latency p95 | 313.600 ms | 318.025 ms | -1.39% |
| Result latency p99 | 383.403 ms | 365.173 ms | +4.99% |
| Result latency max | 459.708 ms | 422.534 ms | +8.80% |
| SDK model-commit wire bytes | 46,096,652 | 25,466,290 | +81.01% |
| Runtime result wire bytes | 113,366,590 | 61,325,238 | +84.86% |

The pre-compression SDK ordinary-result envelopes were effectively identical: 712,944,558 bytes with LZ4 and
712,979,400 bytes with ZSTD. The wire-byte difference therefore comes from the negotiated codec rather than a changed
workload. The model-commit byte counter covers 1,036,097 LZ4 versus 1,038,648 ZSTD updates because a small part of the
measured phase can already be in the WebSocket pipeline when recording starts; the 81% ratio is too large for that
0.25% item-count difference to explain. Runtime result counters include the protocol responses emitted during the same
measured interval and show the same mechanism independently.

## Boundary and resource evidence

LZ4 did not buy a compensating CPU or storage advantage. Client JVM user plus system CPU averaged 37.21% with LZ4 and
38.20% with ZSTD, while Runtime averaged 18.71% and 16.33%. In particular system CPU rose on both sides. Normal JFR hot
methods confirmed that the requested codecs were active (`LZ4_compress_limitedOutput`/`LZ4_decompress_fast` versus
ZSTD native compression/decompression). GC pause time was comparable on the client (117 versus 122 ms) and higher on
the LZ4 Runtime (174 versus 152 ms).

The poorer compression also disturbed the downstream group shape. LZ4 needed 507 result-store appends versus 454 and
145 packed model transactions versus 134. Aggregate result-store duration was 8.116 versus 5.884 seconds; packed-model
duration was 3.484 versus 2.944 seconds. Those aggregate durations are not treated as isolated causal estimates because
the transaction counts and whole-machine load differed, but they rule out a hidden downstream win.

## Decision

Reject LZ4 for this route and retain ZSTD. This was a configuration-only diagnostic, so there is no production change
to revert. A negative equivalent pair is enough to close this candidate; unlike an acceptance claim it does not warrant
a balanced non-JFR confirmation campaign. Compression remains material CPU demand, but replacing negotiated ZSTD with
the available faster/lower-ratio codec increases the dominant model/result wire volume about 81-85% and does not improve
the full boundary. The next experiment must attack the structural serialized commit-then-result route rather than tune
another local compression method.

## Reproduction identity

- SDK production source: `e94188b5876` (later SDK commits are documentation only).
- Runtime source: `ed9cb3419e0b61e49869886f81f742f1c8bf6a77`.
- Launcher SHA-256: `39ef7a0ef1da9353aaf099ead0eb9d358454a23fe85ddee3bf50ea705f5a29b3`.
- LZ4 client log/JFR: `9e44334941a224cb11e0ad8aab8a05c1409e7f8e3617705bdd31002b6c4cce34` /
  `deb74c8712863cd65e3903485bf1a6d210593fbba7a0d1ad85bda758ab8a98dd`.
- LZ4 Runtime log/JFR: `9b21f105cd07960938670237bf5cdacd630e34ee284f64d652d1d863e6c362e7` /
  `d20c14c561a6a3559f70c2627a9c90e8742415104d2feacc50e0393bc88577bb`.
- ZSTD client log/JFR: `5f7e1ae9cfa9f996d3abc3517aaf81a7ff22a13ff58e4fedc86b9e9d948234d6` /
  `c9941e63a1596db9fc66f8756f68384235132ddef4f7d24e0bef63b91d26b538`.
- ZSTD Runtime log/JFR: `29219ac9cc03138cd7fb6eff63747fd5c495a2d4ace0dddc8cb88e8cbeded3f0` /
  `ba90db0279a7ee77fecf8ec4b10cb263ebd5c7fd88441aadd458497105b5cd39`.

The raw artifacts remain under `/private/tmp/model-e2e-e59-e60-{lz4,zstd}-jfr-*` on the measurement host.
