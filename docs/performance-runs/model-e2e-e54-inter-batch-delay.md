# E54 inter-batch collection delay

The accepted-control JFR at 254,459 commands/s contained exactly 65,536 warm-up plus 1,048,576 measured packed model
updates in 154 transactions. Their aggregate lane capacity was 0.299M/s. Size buckets exposed a large fixed cost:

| Packed transaction size | Transactions | Average size | Mean service | Aggregate capacity |
| --- | ---: | ---: | ---: | ---: |
| below 4k | 67 | 1,235 | 17.274 ms | 0.071M/s |
| 4–8k | 28 | 5,875 | 24.940 ms | 0.236M/s |
| 8–16k | 42 | 11,942 | 32.136 ms | 0.372M/s |
| at least 16k | 17 | 21,489 | 30.440 ms | 0.706M/s |

OLS over all transactions estimated 18.768 ms fixed cost plus 0.747 microseconds per command, although the low R² of
0.216 makes the fit directional rather than predictive. E54 therefore added an opt-in delay after a durable model batch
only when another batch was already queued. Durability, ordering and result completion were unchanged.

The first 1 ms pair screened at +5.43%, but the next three gains were -3.81%, -0.86% and +1.73%. Candidate/control
geometric means were 315,150/s and 313,388/s: +0.56%, exact paired-bootstrap 95% -2.46% to +3.82%. A separate 2 ms
screen reached +4.28% but also missed the acceptance threshold. SDK dispatch and Runtime transaction formation did not
move consistently with the delay, so the first pair was ordinary run variance rather than a stable group-commit gain.

The code was fully reverted. The result retains the useful causal finding—large packed transactions are much more
efficient—but rules out a fixed timer as the mechanism. The next candidate must merge on known reservation/arrival
boundaries or introduce a correctness-safe parallel durable lane. Exact runs and hashes are in
[`model-e2e-e54-inter-batch-delay.csv`](model-e2e-e54-inter-batch-delay.csv); the launcher SHA-256 was
`8de3e44b0babebca3ded3893df4b7561059cdfafc2ef7cb0cb7cac89d7a9503b` and the measured Runtime diff SHA-256 was
`a164a51449cfd8e500b742080c8d40d829dba0085750a741ce8f4edff9bc5678`.
