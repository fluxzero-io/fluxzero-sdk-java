# Phase 16 — Single-active-runtime recovery and operational truth

Date: 2026-07-27

## Decision

Fluxzero currently permits one active runtime through `AvailabilityCheck`. Model-cache tracking therefore uses a true
namespace-local long poll: commits, materialization completion and deletion wake the memoized operational store
directly. The discarded 100-ms database generation observer paid ten idle queries per second per active namespace for
a multi-active topology that Fluxzero does not support.

A runtime may still own separate primary and search databases. That is one logical runtime action, not one XA
transaction:

1. the primary transaction commits streams, action metadata, relationships and the exact serialized
   document/snapshot projection;
2. the runtime applies that projection to its search store through monotone state-index fences;
3. success is returned only after the search write completes;
4. projection bytes are cleared only after success.

Existing model namespaces activate their operational store asynchronously when their event-sourcing websocket endpoint
is recreated. That store scans only IDs and byte sizes through the partial pending-materialization index, then loads at
most 128 actions and 8 MiB of retained projection bytes per batch; one oversized action is always admitted for
progress. It applies exact retained bytes and retries temporary failures from 250 ms up to 30 seconds. An idle
namespace with no pending work is never polled. Aggregate-only namespaces retain lazy initialization and do not acquire
model/search schema work.

## Retention

No model-table TTL is silently enabled:

- compact action results remain indefinitely because exact handler loads, the update cursor and idempotency require
  them;
- pending document/snapshot bytes remain until success and are then cleared immediately;
- completed deletion-target worksets are removed;
- completed deletion identity and erasure fences remain for safe retries and model-ID reuse rejection;
- protected lineage remains while detached descendants must still be lifecycle-discoverable.

Action-result archival and fence-retention changes need an explicit replacement correctness contract. They are capacity
and production-soak gates, not background behavior already present in this branch.

## Erasure keys

The runtime owns erasure keys; the SDK never receives them. Configuration is optional. Each owning database generates
and durably retains a random 256-bit key by default, including when primary and search are separate. Protected tokens
do not cross that database boundary. `fluxzero.modelErasureKey` and its namespace-specific form are available when
secret-manager ownership or database-independent recovery is desired; a configured mismatch fails startup.

## Observability

Built-in signals include:

- durable versus safely materialized model cursors in `TrackModelUpdatesResult.Metric`;
- exact repair counts/bytes in `ModelMaterializationRepairMetric`;
- graph-projection batch metrics and status/backlog;
- normal websocket request latency/failure metrics, cache evictions and backlog rejection logs;
- warning logs for repeated materialization, graph projection and deletion recovery failures.

The runtime does not install customer alerting rules. Deployments choose thresholds for sustained cursor lag, pending
projection age, graph backlog, deletion failure, write latency and WAL pressure.

## Verification

The focused JDBC model-action suite covers automatic recovery after restart, reuse of the original committed document,
temporary search failure followed by autonomous retry, direct materialization fencing, local long-poll wake-up,
duplicate idempotency, graph projection and hard-deletion recovery.

Focused JDBC verification passed 78 tests. The complete runtime reactor passed all four modules and 643 tests. The
complete SDK reactor passed all nine modules, including 1,941 SDK tests and Java/Kotlin downstream compatibility.
`git diff --check` passed in both repositories.

The final adversarial review checked concurrent live/recovery materialization, duplicate retries, delete-versus-repair
ordering, graph readiness, bounded heap exposure, idle behavior, namespace failover activation and shutdown:

- equal or older recovery writes cannot cross a newer document or delete fence;
- clearing an already-cleared action is idempotent and advances the materialized cursor from durable pending state;
- graph projection starts only after the direct projection bytes have been cleared;
- the first oversized action is admitted for progress, while ordinary batches remain bounded to 128 actions and
  8 MiB of retained serialized input;
- aggregate-only namespaces perform only the existing-table probe and retain lazy model/search initialization;
- a recovery worker blocks on an interruptible search future and cannot keep shutdown hostage.

No wire or stored-format migration was introduced. The existing `update_generation` column is retained for rolling
schema compatibility even though the unsupported periodic observer no longer consumes it.

Remaining gates are deliberately operational rather than hidden background behavior: action-result archival needs a
replacement correctness contract; multi-active runtimes need request/result-log notification; deployments still own
alert thresholds, production-duration soak and absolute 100-GB/min hardware qualification.
