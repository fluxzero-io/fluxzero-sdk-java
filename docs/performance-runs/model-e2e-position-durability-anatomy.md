# Position durability anatomy on the no-apply route

Measured on 2026-08-02. This report continues the intact durable command/result route from
[`model-e2e-no-apply-ceiling-anatomy.md`](model-e2e-no-apply-ceiling-anatomy.md). Model application and event publication
remain deliberately absent, but commands and ordinary results still cross their normal storage, tracking, transport
and caller-completion boundaries. Every complete run used Java 25, two independent sender clients, a fixed 8-GiB heap,
10,485,760 warm-up commands and 10,485,760 measured commands. Every run verified exactly 10,485,760 results and zero
model or global events.

## What a stored tracking position means

After a command consumer has handled a delivered batch, the SDK persists that consumer's new position before it asks
for the next command batch. This prevents the consumer from advancing its observable progress before the position is
durable. The command cycle is therefore:

```text
handle delivered command batch
  -> send StorePosition over the tracking WebSocket
  -> Runtime position endpoint
  -> Runtime position backlog
  -> merge updates for the same consumer
  -> PostgreSQL UPDATE in one transaction
  -> durable commit completes
  -> response completes the SDK position future
  -> SDK asks for the next command batch
```

The SDK command tracker waits for this complete round trip. Result tracking uses client-controlled/SENT progress in
this benchmark and does not wait for result-position durability on the caller's critical path. Result-position commits
still consume PostgreSQL and Runtime capacity and can therefore interfere with command and message-log durability.

The three measured position intervals are nested, not additive:

| Interval | Exact boundary |
| --- | --- |
| SDK tracker `storage` | SDK position-store invocation through completion of the returned future, including protocol, Runtime queueing, database work and response completion. |
| Runtime position endpoint `storage` | Runtime receipt of one `StorePosition` through completion of the Runtime position-store future. |
| Runtime JDBC position-store `storage` | One coalesced backlog batch's database call, including its transaction and commit. |

## Clean position baseline

E321 is the clean batch-JFR baseline. Batch-only JFR is used for causal anatomy, not as the campaign throughput pin.
Times are milliseconds. `mean`, `p50`, `p95`, `p99` and `max` describe one invocation of the named boundary.

| Fundamental interval | Calls | Items | Mean | p50 | p95 | p99 | Max | Interpretation |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Command handler callback | 15,037 | 10,485,760 | 0.868 | 0.677 | 1.790 | 3.367 | 47.758 | Payload deserialization, empty typed handler and ordinary result handoff for one delivered command batch. |
| **SDK command-position barrier** | 15,037 | 15,037 | **7.144** | 6.261 | **13.393** | 19.841 | 96.084 | Full synchronous position round trip that gates the next command read. |
| Runtime command-position endpoint | 15,037 | 15,037 | 4.924 | 4.353 | 9.520 | 13.376 | 95.002 | Server-side part of the same barrier, from decoded request through position-store completion. |
| Runtime command-position JDBC batch | 2,877 | 15,037 requests | 3.153 | 2.674 | 6.537 | 10.397 | 92.540 | Actual coalesced SQL update and durable transaction; 5.227 requests entered an average DB batch. |
| SDK result-position handoff | 4,054 | 4,054 | 0.011 | 0.007 | 0.027 | 0.056 | 1.391 | Non-blocking client-controlled progress handoff; it is not the result caller's durability barrier. |
| Runtime result-position endpoint | 4,054 | 4,054 | 4.811 | 4.183 | 9.493 | 14.858 | 94.386 | Server-side asynchronous result-position residence. |
| Runtime result-position JDBC batch | 2,862 | 4,540 requests | 3.106 | 2.644 | 6.564 | 10.266 | 91.177 | Result-position SQL and commit; 1.586 requests entered an average DB batch. |

The command consumer spends about 89% of its measured callback-plus-position service in the position barrier
(`7.144 / (7.144 + 0.868)`). The raw row update itself is not the dominant cost: E318's exact
`pg_stat_statements` window measured 3,129 command-position updates at 0.139 ms mean active SQL and 4,007
result-position updates at 0.144 ms. The larger JDBC interval is predominantly transaction/commit residence. The
remaining E321 difference between the 7.144-ms SDK boundary and 4.924-ms Runtime boundary is approximately 2.22 ms of
client/server dispatch, WebSocket transit and response completion.

E318 also recorded 19,100 committed transactions, 10,959 client-backend WAL fsyncs and 6,398.321 ms summed client
fsync time in the exact measurement window. Position updates therefore add durable boundaries comparable in count to
the 2,560 command and 4,426 result message-log transactions, even though each position row is tiny.

## Bounded coalescing sweep

The diagnostic candidate delays the position backlog immediately before each drain. It does **not** make positions
asynchronous and does not complete any position future early: all futures still complete only after the coalesced
database transaction commits. The window needs to cover only concurrently arriving updates that can join the next
transaction; it does not need to match the complete 5-8 ms position round trip, because the SQL and commit happen
after collection.

All rows below are full durable no-apply E2E runs under batch-only JFR. `Physical command reads` is the total number of
command records requested by physical store scans; a sharp increase shows that the consumer fell outside the message
cache. Throughput is diagnostic because machine load and JFR state varied during this back-to-back sweep.

| Run | Collection window | Throughput/s | Command position DB batches | Raw command position requests | Requests/DB batch | DB mean ms | Runtime endpoint mean ms | SDK barrier mean ms | Physical command reads |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| E321 control | 0 | 898,056 | 2,877 | 15,037 | 5.227 | 3.153 | 4.924 | 7.144 | 733,184 |
| E322 | 0.250 ms | 867,626 | 2,390 | 13,712 | 5.737 | 3.480 | 5.916 | 8.339 | 647,168 |
| E323 | 1 ms | 437,865 | 1,867 | 10,152 | 5.438 | 3.919 | 7.498 | 19.588 | **30,838,784** |
| E324 control | 0 | 702,390 | 2,732 | 12,901 | 4.722 | 3.615 | 6.359 | 10.114 | 540,672 |
| E325 | 2 ms | 798,258 | 1,585 | 12,482 | 7.875 | 3.966 | 7.893 | 10.203 | 606,208 |
| E326 control | 0 | 687,560 | 3,115 | 14,512 | 4.659 | 3.581 | 6.387 | 9.757 | 626,688 |

The sweep establishes two separate facts:

1. Explicit collection works locally. Relative to adjacent controls, 250 microseconds removes roughly 9% of command
   position transactions; 2 ms removes roughly 42-49% of command position transactions and a similar share of result
   position transactions.
2. More local coalescing is not automatically more E2E capacity. E323 entered a bistable cache/backpressure failure:
   the command consumer fell behind, physical reads grew by roughly fifty times and result latency exploded. E325 did
   not reproduce that failure, so one delayed run cannot be generalized into a monotonic delay curve.

E319 separately tested PostgreSQL `commit_delay=200us` with `commit_siblings=1`, while retaining
`synchronous_commit=on` and `fsync=on`. Client WAL fsyncs fell from 10,959 to 5,722, but measured E2E fell from E318's
877,873/s to 834,557/s; the valid reverse default control E320b measured 801,781/s under later host interference. This
is diagnostic evidence that PostgreSQL group-commit activity can be changed, not evidence for accepting that database
setting.

## Profiler-free throughput decision so far

| Run | Window | Throughput/s | Latency p50 / p95 / p99 / max ms | Role |
| --- | ---: | ---: | --- | --- |
| E327 | 0 | 929,190 | 64.631 / 86.955 / 96.459 / 115.473 | Leading control |
| E328 | 2 ms | 821,209 | 68.261 / 106.046 / 140.395 / 649.744 | Candidate |
| E329 | 0 | 771,645 | 71.169 / 119.176 / 189.116 / 614.578 | Trailing control after sustained host heating |
| E330 | 0.250 ms | 477,224 | 83.061 / 358.426 / 444.239 / 878.209 | Cooled repeat still enters the cache cliff; time-window mechanism rejected |

The controls' geometric mean is 846,761/s; E328 is 3.02% lower. The laptop was visibly hot after repeated 20M-command
runs, while macOS had not yet registered a formal thermal-warning level. E330 then repeated the smaller 250-microsecond
candidate after cooling and collapsed to 477,224/s with severe tail latency. The exact penalty is state-dependent, but
both non-zero windows can push the route into the same cache/backpressure cliff. No time-based collection window is
retained and the complete candidate was reverted.

## Position-only asynchronous commit

E331-E333 test an opt-in diagnostic that keeps ordinary message/model/event/result commits synchronous while setting
`synchronous_commit=off` only for monotone tracking-position transactions. Position futures still complete after
PostgreSQL reports the transaction committed. A database or operating-system crash may therefore replay a recent
position window, but cannot acknowledge a position that PostgreSQL has not logically committed; explicit position
resets remain synchronous. This is diagnostic-only because changing the durability represented by `STORED` requires an
explicit compatibility decision.

The first candidate uses the existing Runtime helper, which executes `SET LOCAL synchronous_commit=off` as a separate
statement before each position transaction. E331 and its reverse sync control E332 both entered the command-cache
fall-through state, requesting 142.3M and 117.2M physical command records respectively. Those throughput values cannot
attribute the cliff to async commit. E333 repeated async commit in the healthy route state and is directly comparable
to healthy sync anatomy E321:

| Run | Position commit | Throughput/s | Command position DB mean | Result position DB mean | SDK command barrier mean | Physical command reads |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| E321 | synchronous control | 898,056 | 3.153 ms | 3.106 ms | 7.144 ms | 733,184 |
| E333 | async via separate `SET LOCAL` | 900,736 | 4.526 ms | 4.608 ms | 9.029 ms | 430,080 |

Throughput is neutral (+0.30%), while each position database transaction becomes roughly 1.4-1.5 ms slower. The extra
`SET LOCAL` protocol round trip costs more than the avoided WAL flush wait. This concrete implementation is therefore
not checkpoint-worthy. PostgreSQL 18.3 locally confirms that a transaction-local `set_config` invoked from the first
position update stays `off` through commit and returns to `on` after rollback/transaction completion. That supplies the
next narrow diagnostic: embed the setting into the first position statement so the async mechanism is tested without
an additional client/server round trip, then return to the full E2E route for the decision.

## Current decision and next evidence

- Retain the JFR position instrumentation: it exposed a real, previously aggregated synchronous boundary.
- Do not accept the generic per-batch collection delay or any non-zero production default from E322-E330.
- Do not accept the separate-`SET LOCAL` async implementation from E331-E333; it adds more position service time than
  it removes and has no complete-route throughput gain.
- Test transaction-local async position commit without an extra round trip, while keeping every position future behind
  the real PostgreSQL commit response. A candidate must improve complete-route throughput without increasing cache
  fall-through or materially worsening latency.
- If bounded waiting remains neutral or negative, investigate a shared arrival-driven transaction owner for command
  and result positions. That mechanism should combine already-arrived cross-log work without sleeping and must complete
  each existing future only after the shared commit.
- A larger protocol change such as fusing the previous position with the next read can remove the roughly 2.22-ms
  command-side request/response portion, but it requires explicit compatibility and failure-semantics design.
