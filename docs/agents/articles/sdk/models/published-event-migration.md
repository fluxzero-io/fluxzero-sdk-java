# Migrate published Aggregate events to Models

Use `io.fluxzero.sdk.configuration.PublishedEventModelMigration` for a deliberate data migration, not as a side
effect of upgrading the SDK. Existing Aggregate applications remain supported. The runner reads the global EVENT
log; it cannot reconstruct transitions that were never published there or are no longer retained.

## Prepare replacement definitions

Give every replacement Model replay-safe `@Apply` methods for the original published events. Include creation,
updates, relationships, moves and deletion, not just the newest happy-path event. Preserve serialized event names,
aliases and required upcasters. A command handler is not a replay mapping: the migration runs replay applies, not
ordinary business handlers, command legality assertions or command interceptors.

For example, a synthetic archive might have published this event before introducing independent Models:

```java
record FolioRegistered(String folioId, String caption) {}

@Model(searchable = false)
record Folio(@EntityId String folioId, String caption) {
    @Apply
    static Folio from(FolioRegistered event) {
        return new Folio(event.folioId(), event.caption());
    }
}
```

```kotlin
data class FolioRegistered(val folioId: String, val caption: String)

@Model(searchable = false)
data class Folio(@EntityId val folioId: String, val caption: String) {
    companion object {
        @JvmStatic
        @Apply
        fun from(event: FolioRegistered) = Folio(event.folioId, event.caption)
    }
}
```

This fragment illustrates one existing event; it is not a complete migration catalog. Use the real historical event
contract and all replacement Models. If old and new state types share a fully qualified name, run them in separate
processes/classloaders rather than trying to register both definitions in one application.

## Replay in an isolated application

Create a dedicated client for the same environment as the old application and a serializer that reads the legacy
events. The migration owns that client and closes it; do not lend it the live application's client.

```java
PublishedEventModelMigration migration = PublishedEventModelMigration.builder()
        .name("archive-model-migration")
        .client(migrationClient)
        .serializer(serializerWithLegacyUpcasters)
        .modelTypes(Folio.class)
        .build();
migration.run(args);
```

```kotlin
val migration = PublishedEventModelMigration.builder()
    .name("archive-model-migration")
    .client(migrationClient)
    .serializer(serializerWithLegacyUpcasters)
    .modelTypes(Folio::class.java)
    .build()
migration.run(*args)
```

`.modelsInPackage("com.example.archive")` is an alternative to listing the catalog. Keep the consumer name stable
across restarts and failover instances. `run()` without arguments starts replay and keeps following events. For
explicit lifecycle control, `replay()` returns a `Registration`; cancel it to stop replay and close the migration
when finished.

The runner registers only the catalog and one globally ordered, single-threaded EVENT consumer. It does not start
ordinary handlers, automatic Model commands or materialized Graph projections during replay, and does not republish
historical events. Each Model commit completes before the durable consumer position advances. Source-event identity
makes restart overlap idempotent. Staged direct documents are not adopted for ordinary search yet; validate staged
values and relationships through `migration.repository()`, then compare against the old application.

## Coordinate readers without starting another replay

Before enabling new read-only legacy-event listeners, configure their repository:

```java
Fluxzero.get().modelRepository()
        .followPublishedEventMigration("archive-model-migration");
```

```kotlin
Fluxzero.get().modelRepository()
    .followPublishedEventMigration("archive-model-migration")
```

This coordinates event-bound Model/Graph reads; it does not launch the migration or make arbitrary current-state
queries wait. A mapped event uses an ordinary read. Only a missing event mapping waits for the durable migration
position and retries the same boundary. The default maximum wait is 30 seconds; the overload accepting `Duration`
sets another bounded wait. If replay has reached the event but the mapping is still missing, the read fails rather
than returning stale or future state. Preserve normal handler retry and listener idempotency. New read-only
listeners must not write to the old Aggregates.

## Adopt at a verified cutover boundary

1. Pause the old application's business writes and drain in-flight work. Record the final published event index.
2. Wait until the migration's durable consumer position covers that inclusive boundary, then stop replay.
3. Run a single adoption job with the same catalog, serializer and consumer name:
   `migration.run("adopt", Long.toString(cutoverEventIndex))` in Java, or
   `migration.run("adopt", cutoverEventIndex.toString())` in Kotlin.
4. Verify Models, relationships, searches and declared Graph projections before enabling new writes. Keep the old
   write paths disabled; there must be one business-write owner.

`adopt(long)` returns `CompletableFuture<Integer>` and refuses a negative boundary, active replay, or durable position
that has not caught up. It delegates to `ModelRepository.adoptModelMigrations()`. Per-Model adoption is atomic and
resumable, not one transaction over the whole migration. Projection rebuilding can also be resumed; qualify the
application's declared projections before claiming the cutover is complete. The isolated runner itself deliberately
has no materialized projections registered during replay. `run("adopt", ...)` waits for adoption and closes the
migration; callers using `adopt(...)` directly own that lifecycle.

Test restart overlap, late/unmapped events, bounded reader wait, rejected early adoption, rerun after partial failure,
historical serialized data and the final ownership switch. Synthetic fixture seeding alone does not prove durable
migration progress or a retained Runtime cutover. Never invent an event index from wall-clock time or silently
replace a missing historical mapping with current state.
