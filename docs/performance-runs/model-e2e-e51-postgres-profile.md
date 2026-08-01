# E51 PostgreSQL and Runtime CPU attribution

E51 profiles the accepted P2 source pair without a production-code candidate:

- SDK `fe3a8fc82a16a2d37a870892bc2159d042e1ff3e` plus the active E51 journal row;
- Runtime `ed9cb3419e0b61e49869886f81f742f1c8bf6a77`, clean diff;
- PostgreSQL 18.3 container `fluxzero-codex-s1-postgres` on port 64217;
- canonical 65,536-model, 65,536-warm-up, 1,048,576-measured-command configuration;
- latest SDK defaults `2026.07.27` and complete ordinary results/events/model checks.

## PostgreSQL statistics run

`pg_stat_statements`, shared I/O and WAL statistics were reset immediately before the Runtime started. The non-profiled
run reached 324,703/s with p50/p95/p99/max 167.215/235.128/262.756/294.791 ms and 124 measured dispatch batches
averaging 8,456.3 commands. Benchmark and Runtime log SHA-256 values are
`bb04da7dd4d6c32c953fcc108e184f4d4eeac4a31bbc15387d841f082c124e15` and
`d5adef7d300205b034a252ee443b5c06d9256da40e85aafb22ea129c8af6c612`. The launch-script SHA-256 at measurement was
`b87c65b675e20456a4e6f39e423e005ad544fac0efc506b7dba244e3d8254d66`.

The immediately captured statement delta was dominated by the eight physical locator partitions:

| Statement family | Calls | Rows | PostgreSQL execution | WAL |
| --- | ---: | ---: | ---: | ---: |
| Locator COPY, partition 0 | 345 | 149,580 | 0.952 s | 0 |
| Locator COPY, partition 1 | 343 | 147,689 | 0.922 s | 0 |
| Locator COPY, partition 2 | 343 | 143,982 | 0.911 s | 0 |
| Locator COPY, partition 3 | 342 | 147,420 | 0.955 s | 0 |
| Locator COPY, partition 4 | 344 | 148,122 | 0.938 s | 0 |
| Locator COPY, partition 5 | 344 | 148,244 | 0.907 s | 0 |
| Locator COPY, partition 6 | 345 | 150,174 | 0.928 s | 0 |
| Locator COPY, partition 7 | 345 | 144,432 | 0.900 s | 0 |
| **Locator COPY total** | **2,751** | **1,179,643** | **7.413 s** | **0** |
| Authoritative model-stream COPY | 348 | 1,361 | 0.351 s | 8.9 MiB |
| Command compact-row INSERT | 285 | 9,120 | 0.187 s | 24 MiB |
| Largest result compact-row INSERT shape | 144 | 4,608 | 0.156 s | 14 MiB |
| Event two-row INSERT shape | 144 | 288 | 0.006 s | 608 KiB |

The locator worker selects at most 384 authoritative blocks per page, but starts immediately after every visible model
commit. It therefore caught nearly every small foreground wave and issued one independent binary COPY/commit per
non-empty physical partition. The configured page limit was almost never allowed to fill. This is the same
service-time feedback E50 exposed at the foreground writer, now amplified eightfold in an asynchronous derived index.

## Runtime CPU run

A separate async-profiler 4.5 CPU run reached 278,969/s and retained 24,576 samples. Its benchmark/Runtime log SHA-256
values are `f67f45d7cf9b0229640bc75fda8035105ed26198723e81c72ff07d09182a16a6` and
`47514fe47ad46559eef923dfe92636b4a8ebb6e9f387becb81c194e549b9bff8`; the collapsed Runtime profile SHA-256 is
`0ac969597fbb5e789ef190b9d805acb1984b391fa529a145345500ae8733df8c`.

Only 133 samples (about 0.54%) contained `materializeInitialStreamLocator`; hash extraction and partitioning accounted
for still less. This rules out Java hash/decode tuning as the primary fix. The next candidate must eliminate eager
derived-index database cycles. It must preserve correct recent reads while the locator lags, force complete locator
materialization before destructive purge, retain restart rebuild behavior and bound the authoritative tail that a cold
read may inspect.
