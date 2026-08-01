# E58 active model/event transaction extension

E58 implemented the transaction-formation mechanism selected by E56/E57. An ordered packed-model transaction could
non-blockingly admit one group of compatible jobs that had already reached the Runtime model backlog while the initial
events were being serialized and inserted. The safe seal required the original transaction still to own the global
event-log head. Late jobs retained their own completion and batch-reservation ownership; event indices and the complete
model state-index range were assigned before one co-located model write and one commit. No timer, policy change, wire
change, relaxed durability or extra transaction was introduced.

The mechanism was correct. Eight `BacklogTest` tests, all 41 `JdbcMessageStoreTest` tests and all 99
`JdbcModelCommitStoreTest` tests passed. Focused tests covered shared success/failure completion, ordered initial plus
late persistence, and atomic rollback of both parts. Every canonical run used defaults `2026.07.27`, recreated the
schema and completed the exact result/event/model checks for 1,048,576 measured commands.

## Mechanism result

An equivalent dual-JFR pair proved that extension happened and reduced the targeted service demand:

| Measurement | Control | Candidate | Change |
| --- | ---: | ---: | ---: |
| Full E2E | 261,660/s | 274,009/s | +4.72% |
| Packed transactions | 155 | 119 | -23.2% |
| Average packed transaction | 7,188 | 9,362 | +30.3% |
| Largest packed transaction | 27,425 | 35,049 | +27.8% |
| Complete packed storage | 3.711 s | 3.044 s | -18.0% |
| Event staging | 1.102 s | 0.607 s | -44.9% |
| Co-located model SQL | 1.267 s | 1.049 s | -17.2% |
| Event commit | 0.320 s | 0.252 s | -21.3% |

The candidate Runtime/client JFR SHA-256 values are
`fd13c4a1c3e394875147d9da2c00686b13fe4ff322b8c3f7267c2b0fadd41a83` and
`736f6ca8711325faf2320c2ac9076cf57427d96536c92541f15f35989c27d674`; control values are
`7acde7d030364281977d55d16f1946b0206c0ae9a0e738cbd43848e86b00ff0e` and
`1d39c12251fdf7d9a3d3c44ba31fc77c8f2e1b6981a7dd6f4c311e51fa7aa167`. Candidate/control benchmark-log hashes are
`67ce81fb5123afc660132f81b1105950e9d3d46b3f408eb54b67dc6d774b830a` and
`526c55981fb5be799e3848d589a679b87cb1a0ecb92b2ba76f82dbe5f03aec09`; Runtime-log hashes are
`d47fec384e14d1fb8e7c573cf923ac8ecc3348aa708e4e6767c6f03cef3c97db` and
`b60b63760f35cacce66ef023c315d45f33637f9a020a327e8c9333f6789f8622`.

Moving the seal later, after initial staging, was also tested once under dual JFR. It retained exactly 119 packed
transactions, reduced neither service nor E2E throughput and was reverted before screening.

## E2E screening decision

The balanced non-JFR order was `A B B A A B B A`. Control and candidate geometric means were respectively
319,158/s and 318,936/s: **-0.07%**. Paired changes were -1.78%, +4.36%, +2.41% and -5.00%; the exact paired-bootstrap
95% interval was -3.41% to +3.38%. Candidate p95/p99/max geometric means improved 4.89%/7.70%/9.93%, but p50 worsened
3.11%. The complete identities are in
[`model-e2e-e58-active-transaction-extension.csv`](model-e2e-e58-active-transaction-extension.csv).

E58 is therefore rejected and fully reverted. It successfully removed a material fraction of the previously dominant
Runtime transaction service, but that work was not the limiting E2E throughput demand once P2 was active. The latest
equivalent JFR's top CPU samples include Runtime native-message decode/serialization and
`JdbcModelCommitStore.commitAll`, plus SDK adaptive-cache maintenance, metadata encoding and native tracking-wire
writes. Their inclusive demand still needs to be measured before selecting a candidate. The next experiment must start
from that full-route rerank rather than retain transaction-extension complexity for a neutral throughput result.
