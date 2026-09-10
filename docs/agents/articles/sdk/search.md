Use `Fluxzero.search(...)` for indexed read models. Push user-facing filtering, sorting, pagination, and counting into the search builder. Do not fetch an entire collection and then use Java streams to implement browse, search, or count behavior.

Co-locate sortable model paths with the query that uses them:

```java
import io.fluxzero.common.search.Sortable;
import io.fluxzero.sdk.modeling.Aggregate;

@Aggregate(searchable = true)
public record Project(
        ProjectId projectId,
        @Sortable String name,
        String description,
        ProjectStatus status) {
}

@HandleQuery
List<Project> handle(FindProjects query) {
    var search = Fluxzero.search(Project.class);
    if (query.status() != null) {
        search = search.match(query.status(), true, "status");
    }
    if (query.term() != null && !query.term().isBlank()) {
        search = search.lookAhead(query.term(), "name", "description");
    }
    return search.sortBy("name").fetch(query.limit(), Project.class);
}
```

For a filtered count, apply the same constraints and finish with `count()`:

```java
long activeProjects = Fluxzero.search(Project.class)
        .match(ProjectStatus.ACTIVE, true, "status")
        .count();
```

Avoid this anti-pattern for product search:

```java
Fluxzero.search(Project.class).fetchAll(Project.class).stream()
        .filter(project -> matchesUserInput(project))
        .sorted(...)
        .toList();
```

`fetchAll()` is appropriate only when the contract genuinely requires the complete, bounded matching set. It is not a substitute for indexed constraints, `sortBy(...)`, `fetch(limit, type)`, or `count()`.

Keep search query construction in query handlers so it stays testable and reusable.

Supported indexing choices:

| Model | How it becomes searchable | Use it for |
| --- | --- | --- |
| Aggregate state | Set `@Aggregate(searchable = true)`; aggregate updates then maintain the document | Current domain state that is also a consistency boundary |
| Stateful projection | `@Stateful` is searchable and maintains its projection document | Event-driven read models with explicit lifecycle/state |
| Plain read-model record | Add `@Searchable` plus `@EntityId` and call `Fluxzero.index(value)`, or call `Fluxzero.index(value, stableId, collection)` with an explicit ID and collection | Release notes, denormalized views, and durable query projections that do not need aggregate behavior |
| Transient socket update | Send through `SocketSession`; do not index it unless the product also requires searchable history | Live delivery only |

Plain records are supported indexing targets. Give them a stable document ID so later updates replace the intended document instead of creating accidental duplicates. `@Searchable` supplies default collection/timestamp metadata; it does not publish a plain record by itself. The two-argument overload `Fluxzero.index(value, secondArgument)` treats the second argument as the collection, not the document ID. Read manual indexing before choosing an overload, deciding how handler completion observes storage failure, or testing replay-safe replacement. Use `@SearchExclude` and `@SearchInclude` to control indexed shape.

Never discard the `CompletableFuture<Void>` returned by `Fluxzero.index(...)` from a `void` tracked projection handler. The handler may finish and its consumer may advance before an asynchronous storage failure is observed. For straightforward projections, use `prepareIndex(...).indexAndWait()`. If a handler returns the future instead, configure the owning tracked consumer with `awaitAsyncResults = true` when its position must not advance before indexing completes.

Use the search builder for match/query filters, time windows, logical groups, pagination, sorting, async results, aggregations, facets, histograms, and stats. Keep this logic server-side in a query handler instead of rebuilding it in clients. Read complete lists and pagination before returning a capped `List<T>`: an inaccessible fixed maximum is not pagination and cannot satisfy “all/current” contracts. Read facet filters and counts when a response needs categorical value counts or exact facet filtering; it maps `@Facet`, `matchFacet(...)`, and `facetStats()` with a concrete test.

Fields used for sorting, quantity filters, and existence filters need `@Sortable`. Put it on the field/getter that owns the exact path used by `sortBy(...)`, `between(...)`, or existence constraints; every literal `sortBy("field")` in a query should have a visible matching `@Sortable` model property. Default runtime sort is newest first by timestamp, and sortable paths are indexed for performance. Search is eventually consistent, so command success does not guarantee that a secondary search projection is visible immediately.

For compound ordering, sort keys are applied from left to right: the first `sortBy(...)` is primary and later calls are
tie-breakers. Read compound search sorting when a query chains keys, uses a derived technical rank, or must keep that
rank out of its public response without losing a testable indexed shape.

Test the indexed behavior with `givenDocument(...)` plus `whenSearching(type, search -> ...)`, and test the public query separately with `whenQuery(...)`. A result-only query test cannot reveal whether production code fetched everything and filtered in memory, so retain the explicit no-`fetchAll` rule during code review.

Manual indexing and bulk updates overwrite existing documents by ID unless `ifNotExists` is set. The focused manual-indexing article maps every overload, stable-ID alternatives, and an executable replacement test. Use collection deletion APIs deliberately; document collections are also exposed as read-only message logs of document updates for handlers such as `@HandleDocument`.

Runtime search pushes filtering and sorting into managed storage when possible and may use an in-memory fallback for unsupported parts. Application code should express the required indexed constraints and verify their behavior; storage extensions and database tuning belong to the managed platform.

Runtime search supports PostgreSQL full-text style indexes, facets, histograms, stats, bulk updates, and document collection messages. Do not promise embedding, semantic, or vector search unless a future implementation adds it.
