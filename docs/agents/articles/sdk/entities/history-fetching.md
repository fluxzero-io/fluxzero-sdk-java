Aggregate reconstruction fetches historical events in pages. Use a payload-byte bound when large events would make a

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
count-only page too large; this changes transport page size, not the aggregate's event ordering or logical history.

```properties
fluxzero.eventsourcing.maxFetchBytes=104857600
```

The conventional environment variable is `FLUXZERO_EVENTSOURCING_MAX_FETCH_BYTES`. Compatibility mode keeps
count-only paging. `fluxzero.defaults.version >= 2026.09.10` selects a 100 MiB serialized-event-payload limit;
an explicit property overrides that default. Set `0` to retain count-only pages.

The existing event-count page bound still applies. A page returns one oversized event when necessary to make
progress, so this is not an absolute process-memory limit or a rule rejecting large individual events. Older Runtimes
ignore the optional byte bound; byte-bounded paging requires runtime support as well as the updated SDK.

Keep this separate from `fluxzero.tracking.maxFetchBytes`, which controls consumer tracking fetches. Increasing or
reducing one does not configure the other. Configure through `ApplicationProperties`; do not introduce a custom
environment-variable reader in an aggregate loader.

Test reconstruction and ordering across page boundaries with large payloads. Include an event larger than the bound
and verify progress. The same event stream must reconstruct the same state regardless of the page size.
