# Model deletion and aggregate migration

This contract applies to both Java and Kotlin applications.

## Deletion

- Returning `null` from `@Apply` is logical deletion and preserves history.
- Logical parent deletion recursively deletes children whose relevant `@Parent` keeps the default
  `deleteOnParentDeletion = true`. This follows pathless relations and shared descendants too; a shared descendant is
  deleted when any owning parent disappears. Moving a child away in the same atomic commit preserves it.
- `modelRepository().deleteModel(id, NONE)` physically erases that model's stream, current document, snapshots and
  cache state while leaving the global event log untouched.
- Physical descendant erasure remains a separate destructive operation and requires `planDeletion(...)` followed by
  confirmation/execution of that exact plan.
- Erasure fences prevent delayed document, snapshot or projection writes from resurrecting deleted data.
- Detached descendants remain discoverable through deleted-parent lineage for later GDPR/lifecycle erasure.

## Legacy note

Do not migrate an existing `@Aggregate` by changing only its annotation: streams, documents, lifecycle and identity
boundaries change. Keep old persisted aggregate code on the 1.x compatibility API until a deliberate data migration.
For an event-sourced backfill, configure `PublishedEventModelMigration` with a stable name, isolated client, legacy
serializer/upcasters and the replacement Model packages or types. Run it without arguments for replay and as
`adopt <cutover-event-index>` for cutover. The SDK-owned consumer is always global, synchronous, single-tracker and
fail-fast; it completes each Model commit before advancing its durable position, and replicas with the same name
provide failover. Replay runs payload then Model `@Apply`, retains the original event index and message ID, does not
republish, and is idempotent. It does not recover legacy `STORE_ONLY` events. A listener application that gradually
moves legacy event handlers to Model/Graph injection should configure its owning repository with
`followPublishedEventMigration(theSameName)`. Mapped events stay on the ordinary read path; only a missing mapping waits
for the durable consumer and then retries exactly. Keep legacy Aggregates as the sole write owner during this read
phase, and do not let moved listeners apply changes back to them.
Document-backed Models are rebuilt in invisible staging; adoption through the owning `ModelRepository` upcasts and
compares every staged and production value, atomically adopts only unchanged equal results without rewriting existing
documents, and rebuilds declared materialized Graphs.
The accepted normalized source remains isolated from later staging until the first ordinary Model write, so resumed
legacy traffic can be caught up and re-adopted without using unverified document content in materialized Graph
composition. Repeat the plural operation to resume a partial cutover. Switch command ownership only after catch-up,
exact state and Graph comparisons, converted listeners and representative performance all report `GO`. The first
ordinary Model write makes recovery forward-only; durable Model commit history may feed an application-specific
emergency legacy projection, but there is no generic post-write rollback contract.
All new examples and implementations should use `@Model`.
