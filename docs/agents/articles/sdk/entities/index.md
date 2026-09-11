# Legacy aggregates and entities

This topic preserves the aggregate API for already persisted 1.x state. Use `@Model` for new v2 code and follow the
Models topic for lifecycle boundaries, commands, graph navigation and conflict detection. Do not migrate stored state
by changing only its annotation.

Aggregates and entities are immutable state holders. They should contain data, not orchestration.

For an existing legacy aggregate consistency boundary:

```java
@Aggregate(searchable = true)
public record Project(
        @EntityId ProjectId projectId,
        ProjectDetails details,
        @Member List<Task> tasks) {
}
```

In a legacy aggregate, `@Member` identifies nested entities within the same persisted root. For new v2 state, independently created, changed or retained state is a separate `@Model` connected with `@Parent`. Use value objects for details that are replaced as a whole.

State transitions belong in command payload methods annotated with `@Apply`; invariants belong in `@AssertLegal`.

`@Aggregate` controls more than the class marker. Use it to opt into search indexing, event sourcing, snapshots, caching, commit policy, publication behavior, routing, and aggregate-level search behavior when those defaults matter.

Use `@Alias` when an aggregate or member needs alternate lookup IDs. Fluxzero maintains entity-to-aggregate relationships for app-facing lookup and repairs stale relationships as state changes; use entity loading APIs instead of depending on runtime storage details. For independently arriving component messages, read durable multi-key correlation: namespace alias families with prefixes and use the identical prefix in `Fluxzero.loadEntity(...)`.

`@Member` marks nested entities that have their own identity inside the aggregate. Member updates rebuild immutable parent state for you, so model nested mutable concepts as members instead of mutating collections manually in handlers.

Event-sourced aggregate updates are stored as structured batches ordered per aggregate ID. Normal applied events also enter the tracking stream; store-only events stay in aggregate history and are not visible as tracked event messages.
