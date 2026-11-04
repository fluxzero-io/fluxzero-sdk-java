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
ordinary handlers or automatic Model commands during replay, and does not republish historical events. It registers
and maintains the declared Graph projections using their normal completion policy. Each Model commit completes before the durable consumer position advances. Source-event identity
makes restart overlap idempotent. Model documents and Graph projections are stored in their configured collections throughout replay. Give them
collections distinct from those still owned by the old application, including explicit Graph projection collections.
Validate values, relationships and ordinary searches through a separate reader, then compare against the old application.
Searches expose the latest indexed state; they do not provide historical versions of search documents.

Use a Runtime that supports live migration documents. An older Runtime is rejected before sending a live migration
commit. Persisted records from older staged migrations retain their original storage behavior. If staged documents
already exist, stop every old migration-runner instance, including failover standbys. The runner refuses live replay until they have been verified and adopted with the existing `adopt`
operation; do not silently resume document-only Models against an empty ordinary source.

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

## Transfer readers, then commands

1. Keep the old application as command owner while the runner rebuilds and follows its published events.
2. Wait for replay to catch up and verify current Model values, relationships, ordinary searches and declared Graph
   projections. Move read-only queries and listeners to the Model application; configure event-bound readers as above.
3. For command cutover, pause the old application's business writes, drain in-flight work and record the final event
   index. Wait for the migration consumer to cover that inclusive boundary, then stop replay.
4. Enable Model commands in the new application. The Model documents and search collections are already live; no
   document copy or adoption is required. Keep the old write paths disabled.

The retained `adopt(long)`/`run("adopt", index)` operation supports staged state from an older runner. It refuses a
negative boundary, active replay or a durable position that has not caught up. Per-Model adoption is resumable, and
projection rebuilding can be resumed after failure. It is not part of the normal new three-phase rollout.

Test restart overlap, late/unmapped events, bounded reader wait, historical serialized data, latest search and Graph
updates, deletion, legacy-collection isolation and the final ownership switch. Synthetic fixture seeding alone does
not prove durable migration progress or a retained Runtime cutover. Never invent an event index from wall-clock time
or silently replace a missing historical mapping with current state.
