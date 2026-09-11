Use manual indexing for plain projections, release notes, and other search documents whose lifecycle is not maintained automatically by an aggregate or `@Stateful` handler. Choose the document identity before writing the handler: replaying the same logical update should normally address the same document.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

## Know the overloads

The overloads differ by collection and ID, even though both values have type `Object`:

```java
Fluxzero.index(value);                                  // inferred collection and ID
Fluxzero.index(value, "release-notes");                 // second argument is the collection
Fluxzero.index(value, stableId, "release-notes");       // explicit ID and collection

Fluxzero.prepareIndex(value)
        .id(stableId)
        .collection("release-notes")
        .indexAndWait();
```

Never use `Fluxzero.index(value, stableId)` to supply a document ID. That call compiles, but `stableId` becomes the collection. Use the three-argument overload or the fluent `prepareIndex(...)` form when the ID is explicit.

`Fluxzero.index(...)` returns a `CompletableFuture<Void>`. Never drop that future from a `void` tracked projection handler: a storage failure may otherwise happen after the handler completes and after the consumer advances. The fluent `indexAndWait()` form uses a stored guarantee and blocks until the operation completes. Alternatively return the future and use `@Consumer(awaitAsyncResults = true)` when the consumer position must wait for indexing; merely starting asynchronous work is not durable projection completion.

Waiting for the write and choosing failure behavior are separate decisions. `indexAndWait()` surfaces a storage failure
to the handler, but the consumer's error policy decides whether tracking logs and continues, retries, or stops before the
failing index. Use the tracked projection failure recipe when a skipped projection update is unacceptable; test the
selected policy and tracker position instead of inferring them from the indexing call.

## Infer a stable ID from the document

For a plain type, `@Searchable` supplies collection and optional timestamp metadata. `@EntityId` supplies the document ID:

```java
@Searchable(collection = "release-notes")
public record ReleaseNote(
        @EntityId String noteId,
        String text) {
}

Fluxzero.index(releaseNote);
```

Adding `@Searchable` does not index a plain object automatically; code must still call an indexing operation. If neither the call nor an `@EntityId` property supplies an ID, Fluxzero generates a new technical ID. Repeating that call therefore creates another document instead of replacing the logical projection.

Use a deterministic business-derived ID for replayable projections, for example one release-note ID per source build or one processor status ID per processing request. Do not generate a fresh ID inside a replayed event handler when the product expects replacement or idempotency.

## Choose overwrite or create-once behavior

Normal indexing replaces the document addressed by the same ID and collection. Use that behavior for current projections and replay-safe corrections:

```java
Fluxzero.index(updatedNote,
               updatedNote.noteId(),
               "release-notes");
```

Use `ifNotExists(true)` only when the contract is explicitly create-once:

```java
Fluxzero.prepareIndex(releaseNote)
        .id(releaseNote.noteId())
        .collection("release-notes")
        .ifNotExists(true)
        .indexAndWait();
```

Do not use create-once indexing as a substitute for deciding how a correction, replay, or changed projection should update an existing document.

## Test identity and replacement

Use the verification-boundaries inventory before calling projection coverage complete. Stable replacement, public query
mapping, rejected-command absence, replay, an injected storage failure, and tracker-position behavior are distinct
observations; one successful index call cannot prove the others.

Drive the real projection handler twice and prove that one logical ID leaves one updated document. This fails if production code accidentally uses the two-argument overload as an ID overload, writes to the wrong collection, or generates a fresh ID for every delivery:

```java
public record ReleaseNoteChanged(String noteId, String text) {
}

@Component
@Consumer(name = "release-note-projection")
final class ReleaseNoteProjection {
    @HandleEvent
    void on(ReleaseNoteChanged event) {
        var document = new ReleaseNote(event.noteId(), event.text());
        Fluxzero.prepareIndex(document)
                .id(event.noteId())
                .collection("release-notes")
                .indexAndWait();
    }
}

var first = new ReleaseNoteChanged(
        "build-42", "Build 42 published");
var updated = new ReleaseNoteChanged(
        "build-42", "Build 42 superseded");

TestFixture.create(new ReleaseNoteProjection())
        .givenEvents(first)
        .whenEvent(updated)
        .expectNoErrors()
        .andThen()
        .<ReleaseNote>whenSearching("release-notes", search -> search)
        .expectResult(List.of(new ReleaseNote(
                "build-42", "Build 42 superseded")));
```

The production class is a Spring `@Component`; the named `@Consumer` gives the projection a stable tracked position. Outside Spring, register the instance with the configured `Fluxzero.registerHandlers(...)` API. `TestFixture.create(new ReleaseNoteProjection())` registers that instance only inside the fixture and cannot prove that production discovery is configured.

Also test the public query that exposes the projection. The projection-and-search scenario proves the production handler's collection and ID behavior; the query scenario proves application mapping, filtering, and authorization.

Before completion, keep stable replacement, a full-history rebuild, typed public-query mapping, rejected-command
absence, injected storage failure, and tracker-position behavior as separate evidence rows. Each detects a different
projection defect; one current-document assertion cannot stand in for all of them.
