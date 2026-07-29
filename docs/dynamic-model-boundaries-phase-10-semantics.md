# Dynamic model boundaries — Phase 10 semantics and completion controls

## Decision

Phase 10 closes the pre-merge semantic corrections without changing the central model:

- one SDK evaluation still produces one authoritative `CommitModels` package containing its events, direct
  documents, snapshots, and relationship transitions;
- the runtime commits streams, heads, temporal relations, commit result, durable materialization intent, and the
  co-located global event publication atomically;
- direct model documents remain synchronously searchable when commit succeeds;
- materialized whole-graph documents remain durable projections and can either continue asynchronously or delay the
  command result until the relevant root boundary is visible.

Conflict resolution is not the design frame. It is a scoped policy for the uncommon case in which a model actually read
by an commit changed before commit.

## Shared event payload ownership

Single-target events always remain inline. Small multi-target events also remain inline; only the measured larger
multi-target cases use one shared payload row with lightweight stream memberships.

Shared storage does not make model lifecycles shared:

1. models A and B own separate memberships;
2. hard deletion of A removes only A's membership;
3. current and historical reconstruction of B still resolves the shared payload;
4. the payload is removed only after B's final surviving membership is removed;
5. logical deletion retains both histories;
6. the independently owned global event-log message is never removed by model erasure.

Concurrent erasure and commit tests prove that reference cleanup cannot race a surviving membership into data loss.
The ordinary single-target commit/load path gains no payload-reference query.

## Time-derived state indices

`stateIndex` is a separate ordered namespace from `SerializedMessage#index`. The first state position of an accepted
batch is:

```text
max(IndexUtils.indexForCurrentTime(), previousStateIndex + 1)
```

Later substeps consume the following positions. It is therefore monotone across multiple commits in one millisecond,
clock rollback, and runtime restart, while `IndexUtils.timestampFromIndex(stateIndex)` still yields the originating
millisecond whenever the wall-clock candidate won.

Callers must treat state positions as opaque boundaries. Historical reconstruction no longer derives a previous
boundary through `stateIndex - 1`; it resolves an exact earlier commit substep. This matters when an earlier substep
moves a child and a later substep injects its new parent: live evaluation and later event sourcing now observe the same
ancestor. Temporal graph code asks explicitly for relations valid immediately before a boundary; it never assumes that
the previous committed position is numerically adjacent. Projection coalescing traverses every edge whose validity
overlaps the commit/batch range in one set-based query per graph depth, covering old, moved, and final roots without a
query per signal. Explicit hard deletion obtains its erasure fence from the same time-derived allocator.

## Conflict policy

The public policies are `DEFAULT`, `ACCEPT`, `FAIL`, and `RETRY`.

- `ACCEPT` is the application fallback. The original event is appended. If a model actually read by the commit changed,
  only applies are rerun against the merged model boundary to derive current documents, snapshots, and relationships.
  Assertions and interceptors are not rerun.
- `FAIL` rolls the complete commit back and returns the conflict.
- `RETRY` rolls the complete commit back, reloads the complete commit context, and reruns assertions, interceptors, and
  applies up to the configured bound.

Resolution order is `@Apply`, returned `@Model`, application builder/property, then `ACCEPT`. Read-only dependencies use
their model policy. A multi-model commit combines policies as `FAIL > RETRY > ACCEPT`, so it can never partially commit.
`ModelConflictResolver` remains the application SPI; a public `CUSTOM` enum is intentionally deferred until named,
scoped resolver selection has a concrete use case.

Configuration is available through `FluxzeroBuilder.configureModelConflictHandling(...)` and the properties
`fluxzero.model.conflictPolicy` and `fluxzero.model.maxConflictRetries`.

The ACCEPT rebase runs away from the serialized websocket result callback. A stale commit can therefore issue its
historical load without waiting on the callback that must dispatch that load's response. The offload activates the
request's captured `ThreadLocalContext`, so the same Fluxzero instance, message, user, tracker, namespace, and
correlation context remain available to the rebase. Strict resolver and full-retry evaluation use the same mechanism.

## Automatic model command handling

`AutomaticModelHandling.DEFAULT`, `ENABLED`, and `DISABLED` resolve in this order:

1. `@Apply`;
2. returned `@Model`;
3. `FluxzeroBuilder.configureAutomaticModelHandling(...)`;
4. `fluxzero.model.automaticHandling`;
5. `ENABLED`.

If any model-producing apply in a command is disabled, the automatic registry declines the whole command. An explicit
`@HandleCommand` can then call `Fluxzero.assertAndApply(update)` once. Explicit invocation, fixtures, repository loads,
and event sourcing are unaffected.

Automatic handling is registered with the consumer selected for the command payload. Package and root-package
consumers therefore retain their normal routing, concurrency, filters, and interceptors; model classes do not create a
separate hidden consumer.

## Graph projection completion and metrics

`GraphProjectionCompletion.DEFAULT`, `ASYNC`, and `AWAIT` resolve from `@Apply`, the active consumer, the root
`@GraphProjection`, application configuration/property, then `ASYNC`.

`ASYNC` writes the authoritative commit and durable projection signal before returning. The runtime worker coalesces
affected roots, includes both sides of moves, waits for direct-document materialization, stitches the bounded current
graph, and fences the write by configuration version and state index.

`AWAIT` uses the same worker and storage transaction. It changes only result publication: the command result is held
until every affected root requiring AWAIT has crossed the commit boundary. The wait request carries the first and last
state position occupied by the commit, so a move in an early substep includes both its pre-commit and final roots. It
does not move graph search into the authoritative transaction. Duplicate requests resume waiting for the durable task
and never reevaluate the commit. If an affected root can no longer be resolved from a current head, the waiter falls
back to the collection boundary instead of treating an empty root scope as complete; this may conservatively wait for
unrelated work, but a root-document deletion can never be released too early.

`ModelGraphProjectionBatchMetric` reports bounded, ID-free batch data: collection/root type, configuration and state
boundaries, root/upsert/delete counts, bytes, stage durations, retry state, and remaining backlog. Exact root IDs are
not metrics dimensions.

Configuration is available through `FluxzeroBuilder.configureGraphProjectionCompletion(...)`,
`Consumer.graphProjectionCompletion()`, and `fluxzero.model.graphProjectionCompletion`.

## Exact graph reconstruction performance

Current and historical `loadGraph` first pin one runtime graph boundary. Independent model streams are then
reconstructed in at most eight concurrent batches and merged back in deterministic graph order. Each batch owns its
reconstruction context; an apply that historically reads another model still resolves that dependency through the
normal exact-boundary path. The fixed bound prevents graph width from creating unbounded requests or threads.

An actual current graph boundary may seed and reuse the normal model cache. An explicitly historical graph may use an
older compatible cached model only as a replay base, but never installs its historical result as current state and
never uses a cache entry newer than its boundary.

## Verification

Focused SDK verification covers policy precedence, mixed policies, automatic-handling opt-outs, root-package consumer
selection, static model-side creation/replay, stale ACCEPT materialization, callback-thread rebase safety, AWAIT
selection, and same-commit ancestor moves.

Focused runtime verification covers time-derived indices and clock rollback, restart monotonicity, inline/shared
thresholds, shared-payload erasure and races, relation diagnostics, durable AWAIT/restart semantics, scoped root waits,
projection-definition changes, and bounded metrics. The complete retained evidence is listed in the
[implementation backlog](dynamic-model-boundaries-backlog.md).
