Fluxzero stores entity-to-aggregate relationships for `@Member`, `@Alias`, `loadFor(...)`, and reverse aggregate

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
lookup. After an entity-hierarchy or alias refactor, stored relationships can disagree with the aggregate reconstructed
from its events. Use repository repair instead of editing relationship storage directly.

```java
AggregateRepository repository = Fluxzero.get().aggregateRepository();
repository.repairRelationships(aggregateId).join();
```

The repository loads the aggregate, reconstructs the current root and relationship set, and replaces the stored
relationships for that aggregate. The typed-ID overload is preferred. An already loaded `Entity<?>` can be supplied
when the maintenance code deliberately controls reconstruction.

Repair is appropriate when:

- a valid aggregate loads by primary ID but member/alias reverse lookup is stale;
- a model refactor changes which nested IDs belong to the aggregate;
- relationship metrics or a reproducible lookup test show inconsistent ownership.

Repair is not a substitute for fixing duplicate IDs, ambiguous alias families, incorrect `@Member`/`@Alias`
annotations, failed event upcasting, or a wrong namespace. Correct the model or serialization defect first; otherwise a
later reconstruction can recreate the bad relationship set.

Use a controlled sequence:

1. Prove primary-ID aggregate reconstruction and record the expected member/alias IDs.
2. Stop concurrent structural updates for that aggregate or make the repair job retry-safe.
3. Run `repairRelationships(...)` and wait for stored completion.
4. Verify primary lookup, every affected reverse lookup, and absence of relationships that should have been removed.
5. Retain only non-sensitive identifiers and outcomes in operational audit output.

The low-level `EventStoreClient.repairRelationships(RepairRelationships)` accepts a caller-constructed type and entity
set. Prefer the repository method so those values come from reconstructed state. Use the low-level form only when a
specialized migration has independently established the complete expected set.
