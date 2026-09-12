Use `AggregateRepository.deleteAggregate(...)` when the intent is to remove all persisted state owned by one aggregate.
This is destructive application data management inside the configured namespace and requires explicit approval for
the concrete aggregate identifier and environment.

```java
CompletableFuture<Void> deletion = Fluxzero.get()
        .aggregateRepository()
        .deleteAggregate(aggregateId);
deletion.join();
```

The repository-level operation evicts the aggregate cache and coordinates event storage, relationships, stored
snapshots, and the searchable representation where applicable. Prefer a typed `Id<?>` or otherwise unambiguous
identifier so the repository can resolve the aggregate type and collection correctly.

Before deletion:

1. Confirm that the request means permanent data removal, not a reversible domain transition such as deactivation.
2. Resolve the aggregate through its primary ID and inspect dependent entities, aliases, projections, schedules, and
   external records.
3. Decide whether downstream projections must receive an explicit domain event before physical removal.
4. Capture only the minimum audit evidence permitted by the data-protection policy.
5. Define after-state checks for events, snapshot/search state, relationships, public queries, and scheduled work.

After the future completes, run those checks. Eventually consistent consumers may still hold a derived view until
their own deletion/update signal is handled; repository deletion cannot retract an already completed external effect.

## Avoid partial low-level deletion

`Fluxzero.get().client().getEventStoreClient().deleteEvents(aggregateId)` deletes only the event stream. It does not by
itself express removal of snapshots, searchable documents, schedules, or all relationship state. Use it only for an
advanced recovery or data-protection procedure that explicitly manages every remaining artifact. Do not call raw
`DeleteEvents` protocol payloads.

Never implement aggregate deletion by manipulating managed tables, storage partitions, or Kubernetes workloads.
