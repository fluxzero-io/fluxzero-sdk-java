# No-model E2E capacity model toward 1.5-2.0M commands/s

Established on 2026-08-02 from the intact durable no-model command/result route. This is a route-wide selection model,
not a new benchmark result. It combines the clean production pin E315 with the detailed healthy E279 batch profile and
the supporting E280-E310 isolation runs. No production candidate is accepted by this document.

## Scope and evidence hierarchy

The full route still performs command preparation and serialization, request registration, WebSocket transport,
durable command storage, command tracking, typed payload injection into an explicit no-op handler, ordinary result
creation, durable result storage, result tracking and caller-future completion. Model loading, model commits and event
publication remain absent so that the shared base-route ceiling can be raised before those costs return.

| Evidence | Role |
| --- | --- |
| E315 profiler-free intact E2E | Current clean production pin: **962,888 commands/s** |
| E279 intact batch-JFR E2E | Detailed service, queue, batching and concurrency anatomy at 948,289 commands/s |
| E280-E282 completion isolation | Supporting capacity bound; cannot establish E2E progress |
| E300-E306 ordered-appender isolation | Supporting storage-mechanism bound; cannot establish E2E progress |

All E279 intervals below overlap where work is concurrent and must not be added. `Service capacity` means messages
divided by summed active service time. For a concurrent component, the table explicitly marks a concurrency-scaled
estimate rather than presenting it as a directly sustained full-route measurement.

## Route-wide capacity scoreboard

| Boundary | Concurrency in E279 | Batch shape | Measured or derived capacity | Utilization at E279 | 1.5M implication | 2.0M implication | Evidence status |
| --- | ---: | ---: | ---: | ---: | --- | --- | --- |
| **Durable result writer** | **1 ordered commit lane; inserts reached 9-way overlap** | **2,476 mean; 4,096 max** | **1.063M results/s active service** | **89.2%** | **Needs 29.2% less total service per result** | **Needs 46.9% less total service per result** | Current hard limiter |
| **Durable command writer** | **1 ordered commit lane; inserts reached 4-way overlap** | **4,096 mean and max** | **1.386M commands/s active service** | 68.4% | Needs 8.2% more capacity / 7.6% less service | Needs 44.3% more capacity / 30.7% less service | Next storage limiter |
| **Command consumer cycle** | **16 active handlers/trackers** | 689 commands per delivered SDK batch | **About 1.54M commands/s if all 16 lanes retain E279's 7.169-ms mean cycle** | About 61.9% of the concurrency-scaled estimate | Only about 2.5% estimated headroom | Needs about 23.1% less cycle service or more useful parallelism | Near-future limiter; derived, not a sustained ceiling run |
| SDK result preparation/publication | 1 ordered SDK result-worker lane | 2,431 mean | 2.671M results/s active service | 35.6% | Headroom | About 25% headroom | Not current limiter |
| Full SDK result completion plus benchmark callback | Isolated caller path | 2,048-8,192 | 4.285-4.797M results/s | Not derivable from E279 | Headroom | Headroom | Supporting isolation only |
| One synthetic SDK sender chain | 1 caller thread | Driver-controlled | 0.954M commands/s in E233 | Not a Runtime utilization measure | One caller is insufficient; E279 deliberately uses two independent clients | Multiple real clients or faster per-client offering remain required | Client-quality bound, not aggregate Runtime bound |

The 1.54M command-consumer estimate is `16 * 10,485,205 / (15,221 * 7.169 ms)`. It assumes that the current mean
handler-plus-position cycle survives at higher load. It is useful as an early warning, not proof that 16 consumers can
sustain exactly that throughput.

## Result-writer budget

E279 stored 10,485,760 results in 4,235 transactions. The single result commit executor spent approximately 9.868
seconds in active writer service during an approximately 11.058-second measured route. The writer is therefore not a
CPU hotspot that merely happens to appear frequently: it is occupied for almost the entire route and has a growing
queue (`9.565 ms` mean, `19.843 ms` p95).

| Result-writer interval | Mean per transaction | Summed active time | Share of writer service | What it means |
| --- | ---: | ---: | ---: | --- |
| Complete ordered writer service | **2.330 ms** | **9.868 s** | 100% | Wait for preparation if needed, stage, commit and publication bookkeeping on one executor |
| **Synchronous commit call** | **1.748 ms** | **7.403 s** | **75.0%** | Dominant serialized residence inside the writer |
| Non-commit writer residue | 0.582 ms | 2.465 s | 25.0% | Preparation wait, occasional staging and small bookkeeping |
| Direct LTS insert | 2.611 ms | Overlapping; do not sum into writer time | Parallel, up to 9 active | Already starts on job-owned connections before the ordered executor reaches the job |

At E279's mean 2,476-result transaction, the target budgets are:

| Target | Maximum total writer mean at unchanged batch shape | Required total-service reduction | Remaining commit budget if all non-commit work stays unchanged | Required commit-residence reduction |
| --- | ---: | ---: | ---: | ---: |
| 1.5M/s | 1.651 ms | **29.2%** | 1.069 ms | **38.9%** |
| 2.0M/s | 1.238 ms | **46.9%** | 0.656 ms | **62.5%** |

The result writer is slower in messages/s even though a no-op result has a smaller payload. A result transaction is
slightly cheaper than a command transaction, but E279 needed 4,235 result transactions versus 2,560 command
transactions for the same 10,485,760 messages. The nearly fixed commit cost is consequently spread across 2,476
results instead of 4,096 commands: about 0.706 microseconds of commit residence per result versus 0.451 microseconds
per command. Result envelopes also contain request/correlation metadata; in the controlled E300/E301 shapes they are
222 versus 206 native bytes and produce somewhat larger compressed LTS data. Payload size is therefore secondary here;
transaction amortization is the primary difference.

The target need not preserve the current batch shape. This calculation instead quantifies the minimum scale of a
useful mechanism: a small local optimization cannot close the gap. E302 proves that full 4,096-result transactions can
drive the isolated appender at 4.653M/s, but E311-E315 prove that simply enlarging a backlog does not create that gain
on the intact route.

## What the current writer actually serializes

`JdbcMessageStore` already separates preparation from publication:

```text
index reservation and job planning
  -> envelope serialization/compression on the CPU executor
  -> direct LTS insert on a job-owned connection (many jobs may overlap)
  -> one per-log commit executor waits for that job's insert
  -> optional staging/co-located task
  -> synchronous transaction commit
  -> tracking notification, monitor update and future completion
```

The serial executor is required by the current visibility design, not by the inserts. Readers query committed LTS and
staging rows directly by message index. If transaction N+1 became visible before N, a tracker could read N+1, advance
its position and never revisit the temporarily missing lower range. Merely running the existing commits concurrently
would therefore violate delivery correctness even if commit calls were submitted in index order: PostgreSQL does not
make independent connection completion order a message-log publication guarantee.

E279 also shows that the result writer is not starved for prepared work. Its mean queue wait is 9.565 ms while the
ordered executor waits only 0.374 ms on preparation on average, with a zero p50. Later inserts are commonly already in
flight or complete before their turn. This makes the physical-commit-versus-logical-publication boundary a stronger
structural research target than adding another timed backlog window.

## Backlog and downstream preparation semantics

`fluxzero.messageStoreBacklogSize` controls the maximum message batch passed to the asynchronous store consumer.
`ReadWriteMessageStore` uses `Backlog.forAsyncConsumer`: one backlog thread constructs those batches serially, starts
the consumer and associates append completion with its returned future, but deliberately does not await that future
before forming the next batch. End-to-end pressure is carried by those append futures and the caller's in-flight
limit. `JdbcMessageStore` intentionally uses the resulting overlap to serialize and insert later jobs while the
per-log commit executor durably commits earlier jobs. The backlog therefore behaves as designed; the relevant
optimization question is how JDBC maps its logical jobs to physical transactions.

E266-E269 screened pending result-job limits 1, 2, 4 and 8. N=2 initially reached 962,092/s, but the qualifying
same-binary E275-E278 comparison put the narrow production candidate at 934,109/s geometric mean versus 959,204/s for
the legacy control (**-2.62%**). A small admission cap loses useful insert overlap and is rejected. The distinct new
hypothesis is transaction fusion: retain the current asynchronous preparation and parallel inserts, but coalesce
multiple contiguous ordinary logical jobs into fewer byte-bounded physical transactions before insertion. Those
physical transactions can still insert in parallel and commit on the existing ordered per-log executor.

## Candidate classes and evidence gates

These are research classes, not selected implementations:

| Candidate class | Why it could address the measured limiter | Correctness condition | Evidence required before production code |
| --- | --- | --- | --- |
| Separate physical row commit from logical log publication with a durable per-log high-water mark | Could retain parallel inserts, allow multiple data transactions to commit, and publish several contiguous completed ranges with one ordered frontier advance | Rows above the durable frontier must remain invisible to every read/cache path; futures and notifications complete only after frontier publication; crash recovery, retries, conditional gaps, retention and staging must remain correct | First quantify how many contiguous jobs are prepared/committable together in the intact route; then use a representative diagnostic prototype before touching production semantics |
| Reduce commit residence while keeping the same visibility model | Directly attacks 75% of result-writer service without a new publication protocol | `STORED` must still mean synchronous durable commit; no async durability concession | A matched full-route boundary reduction, not an isolated PostgreSQL setting win |
| Increase natural transaction amortization without sleeping or suppressing insert overlap | E302 proves large small-message transactions have ample storage headroom | Existing futures, byte bounds, latency/backpressure and parallel inserts remain intact | Must beat E315 in matched intact E2E; count-only backlog and pending-job caps are already rejected |

A durable high-water design is currently the only identified class that could make same-log physical commits parallel
without exposing out-of-order messages. It is deliberately not yet chosen: its blast radius is large, and the next
step is a cheap readiness/publication-opportunity measurement on the intact route.

## Rejected or deferred branches

- Time-based message or position collection windows: rejected; they can trigger the cache/backpressure cliff.
- Count-only backlog growth: rejected on the intact route.
- Hard pending-job caps: rejected; they sacrifice useful insert overlap.
- One large single-connection insert: rejected as a direction; existing parallel inserts are intentional and measured.
- Position-only asynchronous commit: rejected and reverted; it provided no targeted service headroom.
- Further position protocol/coalescing work: deferred until the generic result/command writer ceiling is materially
  higher. Position work may return when the command-consumer cycle becomes the measured limiter.

## Next measurement

Before implementing a storage protocol, measure on a fresh healthy intact E2E profile:

1. how many later same-log jobs have completed serialization/direct insert when each ordered commit begins and ends;
2. the contiguous message and byte ranges those ready jobs represent;
3. commit residence and PostgreSQL group-commit/fsync deltas for the exact window;
4. command and result writers separately, because the result writer limits now but the command writer limits before
   1.5M/s;
5. the full command-consumer cycle concurrently, so a storage gain is not mistaken for final 2M capacity.

Only if that measurement shows a useful multi-job publication opportunity should a high-water or equivalent prototype
be built. The prototype must then win on the complete durable no-model route before it can become a checkpoint.

E336 validates the readiness instrumentation itself on a short isolated result-appender smoke. At commit start, 104
snapshots contained 14.019 contiguous ready jobs on average (p50 15, p95/max 17); at commit completion the mean was
14.865 (p50 16, p95/max 18). All 524,288 warm-up plus measured messages were durable with zero ordering or overlap
violations. The host was heavily loaded and the isolated route lacks complete E2E feedback, so its 2.213M/s throughput
is explicitly non-qualifying. E336 proves only that the event semantics work and that multi-job readiness can exist;
the intact route must still establish its relevant distribution.

E337 exercised the same instrumentation on the intact 10,485,760-command no-apply route and again completed with
exactly 10,485,760 durable results and no model/global events. At result commit start it observed 4.261 contiguous
ready jobs on average (p50 3, p95 12, p99 18, max 25), representing 8,712 ready messages per snapshot on average.
Commit completion raised this to 4.597 jobs (p50 3, p95 13, p99 18, max 25) and 9,499 messages. Command readiness was
lower: 1.446 jobs at commit start and 1.847 at completion. This confirms that a multi-job publication opportunity can
exist in the complete route.

E337 is nevertheless diagnostic-only. `kernelmanager_helper` consumed most of a core and command consumers fell out
of the cache: 1,141 physical command scans read 85,889,024 messages, about 8.2 times the logical command count. The
measured 365,471/s, 2,135-result transaction average and readiness distribution describe that backpressured failure
state, not the healthy E279/E315 route. They must not select or size a production mechanism; a clean intact repeat is
still required.

## E338-E343: logical-job transaction fusion

The first diagnostic prototype implements the narrower transaction-fusion mechanism suggested by the readiness
evidence. A second asynchronous stage may combine up to N contiguous direct-only logical jobs into one byte-bounded
physical transaction before connection acquisition and insertion. Physical transactions still insert concurrently and
are submitted to the unchanged single-thread per-log commit executor in index order. Conditional/co-located work,
staging tails and staging flushes are barriers on the legacy one-job transaction path. Append futures and original
monitor batches remain attached to their logical jobs. The feature is disabled by default (`maxJobs=1`).

The isolated E338/E339 pair proves both the mechanism and a feedback hazard:

| Run | Shape | Logical jobs | Physical transactions | Messages/transaction | Throughput | Interpretation |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| E338 | Legacy control | 105 | 105 | 2,496.6 | 2.189M/s | Healthy isolated reference under the loaded host |
| E339 | Result fusion N=3 | 710 | 253 | 1,036.1 | 1.114M/s | 2.81 logical jobs/transaction, but faster callback dispatch fragments the upstream 256-message offer shape |

All 524,288 warm-up plus measured messages were durable with zero ordering/overlap violations in both runs. E339
therefore rejects the isolated producer shape as a representative selector for this mechanism; it does not reject
fusion on the actual SDK result-wave topology.

The intact, short, batch-profiled E340-E343 screen is materially different:

| Run | Candidate | E2E | Logical result jobs | Physical result transactions | Messages/transaction | Active result-writer capacity |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| E340 | Control | 596,503/s | 397 | 397 | 2,641.2 | 0.699M/s |
| **E341** | **Result fusion N=3** | **768,363/s** | **405** | **359** | **2,920.8** | **0.837M/s** |
| E342 | Reverse control | 709,996/s | 423 | 423 | 2,478.9 | 0.781M/s |
| E343 | Result fusion N=4 | 722,659/s | 409 | 367 | 2,857.2 | 0.789M/s |

E341 is 18.1% above the 650,780/s geometric control, reduces physical result transactions without fragmenting the
405 logical SDK result jobs, and improves the targeted writer boundary. Fusion N=4 does not improve natural
coalescing or writer capacity further. Every run produced exactly 1,048,576 durable results and zero model/global
events. These are still diagnostic-only: the short warm-up and persistent `kernelmanager_helper` load make absolute
throughput and the full-route delta non-qualifying. N=3 has earned a long matched clean-host qualification; it is not
yet an accepted production default or checkpoint.
