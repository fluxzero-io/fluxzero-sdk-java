# E55 ready model-commit chunk screening

E55 used the accepted P2 binaries and changed only the existing SDK property
`fluxzero.readyModelCommitBatchSize`. Full ready chunks are sent before handler-batch close and the bounded tail retains
the existing completion barrier, so this was a timer-free test of a known SDK reservation boundary.

Chunk 1,024 reached 302,721/s versus 304,438/s control (-0.56%). Chunk 4,096 reached 306,288/s versus 314,081/s
control (-2.48%) and regressed p95/p99 from 242.762/266.547 ms to 280.064/333.755 ms. SDK dispatch-batch counts and
averages did not move consistently in either pair. The larger logical chunk is fragmented by physical WebSocket intake
and the closed-loop completion flow before Runtime storage snapshots its queue, so it does not control the durable
transaction boundary.

No source change was made and the 256 P2 default remains. Exact runs and hashes are in
[`model-e2e-e55-ready-chunk-screening.csv`](model-e2e-e55-ready-chunk-screening.csv). The temporary launcher SHA-256 was
`ff198ae575e681dba961e14fd998b7607d0505521c7e856ad60ffe0b96b7c52c`; Runtime was clean with empty-diff SHA-256
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
