# E57 co-located large event blocks

E57 tested E56's physical-row diagnosis without changing ordinary low-rate message logs. An opt-in Runtime candidate
used direct compact rows of at most 1,024 messages only when a co-located transaction already contained at least 1,024
events. Existing staging was migrated first in the same transaction; smaller and non-co-located appends retained the
accepted 128-message staging path. Stored bytes and the reader format were unchanged. All 40 focused
`JdbcMessageStoreTest` tests passed, including a new mixed staged/direct/ordinary-tail case.

Candidate and control used the same dirty Runtime classfiles. The only difference was
`fluxzero.coLocatedMessageStoreGroupSize=1024`; the control left the property absent. Both used SDK
`95b64fa36afb8140c97193f817c5554d3c1d50ac`, Runtime
`ed9cb3419e0b61e49869886f81f742f1c8bf6a77`, Runtime diff SHA-256
`3ece697e2e694d1ec58e9f3ac857bc285c591869ecbdb7d31d0e5686b0c97ca2`, dual JFR, latest defaults `2026.07.27` and
the full canonical exact-check boundary.

| Measurement | Control | Candidate | Change |
| --- | ---: | ---: | ---: |
| Full E2E | 274,520/s | 247,367/s | **-9.89%** |
| p50 / p95 / p99 / max | 189.847 / 287.672 / 335.035 / 381.135 ms | 201.208 / 323.909 / 374.689 / 412.058 ms | all worse |
| Packed transactions | 148 | 192 | +29.7% |
| Average packed transaction | 7,528 | 5,803 | -22.9% |
| Direct compact output rows | 9,131 | 1,752 | -80.8% |
| Direct-insert phase | 0.966 s | 1.162 s | +20.3% |
| Staging phase | 1.071 s | 0.380 s | -64.5% |
| Co-located model SQL | 1.241 s | 1.726 s | +39.1% |
| Event transaction commit | 0.327 s | 0.515 s | +57.5% |

The candidate removed the intended physical work: compact output rows fell fivefold and staging service fell by
0.691 s. It nevertheless completed each transaction sooner and drained the opportunistic Runtime backlog. The 44 extra
packed transactions then paid more direct-insert calls, model SQL and commits; complete packed service worsened from
3.571 to 3.992 s. This reproduces E31/E32 and E50 with a stronger row reduction and closes physical block sizing as an
isolated solution.

Candidate/control Runtime JFR SHA-256 values are
`13735d21967518d941f131180beccf99bc9bb71a4d9e795dfe3fcb38df093ae4` and
`80234cff429c5aa844f9c8b6406e9ee81b3b35af317f68140b874918a0f1109c`; client JFR values are
`b54c9c6dae45357177d461b999ea2ef783500894eaf84ca55f031feccd026afd` and
`ebde96b5398879d770d1fe4c542d2c16cdc7b6be4b3ca1c21814a1c5781c9c82`. Benchmark log values are
`86dde487da93129bf2ade6d82d0181998f7ea2e504db184b94f632fefd4c6128` and
`0eb2b40dc4dd01336c73bbac9935b823984cee9033edf05357f6f58326a60803`; Runtime log values are
`8228edd86319de7935d27e4f112692d969b4541339b3b5345e399f946c0952e6` and
`37e26f050d6e0f0cef956df0c261aabd6f7bc782ef54d74d8ab29fb7cc071287`.

The candidate was fully reverted. The next implementation must change transaction formation itself: while one ordered
packed transaction performs productive preparation or event insertion, it should admit compatible jobs that have
already arrived before an explicit seal point. Their event indices, model state range, completion futures and atomic
visibility must join that same transaction. No fixed collection delay or policy relaxation is justified.
