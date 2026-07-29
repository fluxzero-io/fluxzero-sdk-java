# Phase 15 — Contention-aware model-commit performance

Date: 2026-07-27

## Correction

The Phase 14 paired-tree table used the benchmark's `ZIPF` selector. With 48 leaves, concurrency 32 and 2,000 commits,
that selector sends 13.8% of all writes to one leaf, 48.6% to the first aggregate root, and contains repeated writes to
the same leaf in all 63 concurrency batches. There are 683 duplicate target slots and as many as nine concurrent
writes to one leaf in a batch.

That is a useful contention profile, but it is not the conflict-free mutation baseline. The Phase 14 conclusion that
small model mutations were intrinsically about 2.5 times slower than aggregate mutations was therefore wrong. This
phase separates the two workload contracts and removes two avoidable contention multipliers.

## Changes

### Runtime conflict-free waves

The JDBC model-commit backlog previously had an all-or-nothing optimistic batch decision. One later commit that read a
model written by an earlier commit made every independent commit in that intake batch fall back to individual,
sequential JDBC commits.

The runtime now divides consecutive `ACCEPT` commits into ordered conflict-free waves:

- an commit that reads a model written earlier moves behind that write;
- independent commits stay in the earliest batchable wave;
- write order for the same target remains stable, including write-only targets;
- `FAIL` and `RETRY` commits remain individual ordering barriers;
- a failed optimistic wave still falls back through the existing authoritative individual path.

This changes batching only. Runtime conflict detection, state-index assignment, event ordering, atomic publication,
relationships, documents and commit idempotency retain their existing contracts.

### SDK local single-writer fast path

Commands handled in one SDK could previously evaluate the same cached model concurrently. The first commit succeeded;
the others reached the runtime with the same read boundary and needed one or more `ACCEPT` rebase round trips.

Default `ACCEPT` model commits whose read sets overlap are now locally ordered. A waiter re-evaluates assertions,
interceptors and applies against the cache updated by its predecessor before committing. Disjoint model IDs remain
fully parallel, and multi-model acquisition is atomic for the complete read set, so local commits cannot deadlock.
Strict `FAIL`/`RETRY` commits retain their existing runtime-observable conflict path.

The coordinator is an optimization, not an authority:

- the runtime still detects writers in other SDK processes or runtimes;
- a failed predecessor releases its model IDs and the waiter evaluates current state normally;
- only the contended re-evaluation is offloaded from the websocket callback thread, with the complete request context
  restored;
- the ordinary uncontended path retains its inline evaluation and one commit request.

## Retained measurements

These are local PostgreSQL comparisons, not production-capacity claims. Both representations use the same four-root
tree, 1-KiB updates and concurrency 32.

### Conflict-free 10,000-commit baseline

| metric | aggregate | independent models | model/aggregate |
| --- | ---: | ---: | ---: |
| event-only throughput | 1,089.0 commits/s | 1,493.9 commits/s | **1.372×** |
| p50 / p95 / p99 | 17.284 / 38.419 / 47.706 ms | 18.567 / 28.870 / 41.861 ms | lower model tail |
| cold / hot leaf load | 70.360 / 0.073 ms | 30.161 / 0.011 ms | lower model latency |
| cold whole-root load | 68.332 ms | 52.077 ms | 0.762× latency |
| WAL | 75.25 MB | 30.86 MB | 0.410× |
| caller/runtime allocation | 4.24 GB | 2.12 GB | 0.499× |

A separate 2,000-commit direct-search run measured 1,093.6 aggregate versus 1,153.3 model commits/s
(`1.055×`). Direct model search was 0.399/0.856/5.186 ms p50/p95/p99, versus
0.577/1.668/18.900 ms for the aggregate document.

### Skewed 10,000-commit contention profile

The deterministic `ZIPF` profile touches all 48 leaves, but sends 14.5% of writes to one leaf. Every one of its 313
concurrency batches contains same-leaf writes, with 3,459 duplicate target slots and up to ten writes to one leaf.

| metric | aggregate | independent models | model/aggregate |
| --- | ---: | ---: | ---: |
| event-only throughput | 1,059.8 commits/s | 818.7 commits/s | **0.773×** |
| p50 / p95 / p99 | 18.257 / 39.471 / 44.585 ms | 18.461 / 36.000 / 50.611 ms | comparable latency |
| cold / hot leaf load | 127.512 / 0.066 ms | 82.290 / 0.011 ms | lower model latency |
| cold whole-root load | 121.403 ms | 96.541 ms | 0.795× latency |
| WAL | 74.46 MB | 63.22 MB | 0.849× |
| allocation | 4.74 GB | 2.75 GB | 0.580× |

Before local coordination, the same short 2,000-commit event-only profile measured 300.3 model commits/s versus
1,132.9 aggregate commits/s (`0.265×`). Conflict-free runtime waves alone raised the model result to 374.1 commits/s.
With both changes it reached 842.1 commits/s versus 1,077.1 (`0.782×`). The longer result above confirms that this is
stable rather than a warm-up artifact.

The remaining 23% event-only gap is specifically same-model serialization, not an independent-model write tax.
Searchable skew additionally keeps the synchronous direct-document guarantee inside that hot-key queue: the retained
2,000-commit run measured 553.6 model versus 949.9 aggregate commits/s (`0.583×`). Removing that last gap would require
safe pipelining or coalescing of several functional state transitions for one model, not weakening direct-search
visibility or silently applying stale derived documents.

## Benchmark contract

`AggregateModelE2eBenchmark` now prints the deterministic workload shape before starting: distinct leaves, hottest
leaf and aggregate-root shares, concurrency batches with repeated leaf writes, duplicate slots and maximum same-leaf
fan-in. Future reports must publish at least:

1. a conflict-free `UNIFORM` baseline;
2. a separately labelled skew/hot-key profile;
3. searchable and event-only results when synchronous documents are relevant.

The benchmark compose file also uses PostgreSQL 18's supported `/var/lib/postgresql` mount, so a clean retained run no
longer fails because the old `/var/lib/postgresql/data` layout is mounted into a PostgreSQL 18 container.

## Verification

- Focused SDK coordinator, registry and committer suites passed 28 tests.
- The focused JDBC model-commit store suite passed 78 tests.
- The complete SDK reactor passed all nine modules, including 1,941 SDK tests plus protocol, test-server, proxy,
  annotation-processing and Java/Kotlin downstream compatibility.
- The complete runtime reactor passed all four modules and 643 runtime tests; the changed benchmark sources compiled.
- `git diff --check` passed in both repositories.

## Remaining boundary

Local coordination cannot eliminate a race between independent SDK processes. That remains the runtime's normal
`ACCEPT` rebase path and is deliberately not the primary model architecture. A future same-key pipelining protocol is
worth considering only if a real workload needs it and it preserves functional re-evaluation, atomic relationships,
direct-document visibility, commit idempotency and exactly-once global publication.
