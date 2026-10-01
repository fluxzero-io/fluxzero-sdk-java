Use this article when a query needs more than a simple exact match and page. Express filtering and aggregation in the
Fluxzero search builder so managed storage can execute it and tests can verify the exact constraint tree.

## Text, equality, range, and existence

```java
Search search = Fluxzero.search(KnowledgeEntry.class)
        .lookAhead(term, "title", "summary")
        .match(Visibility.PUBLISHED, true, "visibility")
        .between(10, 100, "score")
        .anyExist("ownerId", "teamId");
```

- `lookAhead` is prefix-oriented text matching; `query` is full-text query syntax.
- `match(value, true, paths...)` requests strict matching.
- numeric range and existence constraints can inspect ordinary indexed document paths. Add `@Sortable` to paths used
  frequently for those filters so the runtime can use an index lookup; sorting by a custom property does require its
  sortable index.
- `matchFacet` is preferable for a field indexed with `@Facet`.

Do not accept unchecked query syntax directly from an untrusted caller when operators/wildcards are not part of the
product contract. Normalize or choose constrained builder operations.

### Know which text comparisons normalize

| Operation | String comparison |
| --- | --- |
| `match(value, paths...)` | Non-strict: trims, lowercases and removes diacritics from strings |
| `lookAhead(term, paths...)` | Normalized, prefix-oriented text matching, not raw equality |
| `match(value, true, paths...)` | Strict: compares the raw string exactly, including case, accents and whitespace |

For an indexed title `"Café"`, an ordinary `match("cafe", "title")` matches, but the strict version does not.
Use strict matching for exact identifiers/enums where that is the contract; do not describe every search operation
as case/accent-insensitive. Test normalized case/accent variants and strict mismatches through `whenSearching(...)`.

## Time windows

`since`, `before`, `inLast`, `beforeLast`, and `inPeriod` filter the indexed document timestamps:

```java
List<KnowledgeEntry> recent = Fluxzero.search(KnowledgeEntry.class)
        .inPeriod(windowStart, windowEnd)
        .sortByTimestamp(true)
        .fetch(100, KnowledgeEntry.class);
```

Use injected/fixture time for relative windows. Confirm inclusive/exclusive boundaries when product behavior depends on
an exact instant; do not assume a date boundary from a small happy-path test.

## Logical grouping

```java
Search filtered = Fluxzero.search(KnowledgeEntry.class)
        .all(
                MatchConstraint.match("PUBLISHED", true, "visibility"),
                AnyConstraint.any(
                        MatchConstraint.match("REFERENCE", true, "kind"),
                        MatchConstraint.match("GUIDE", true, "kind")))
        .not(MatchConstraint.match(true, true, "archived"));
```

Keep the logical tree visible in one query method. A sequence of builder constraints is normally an AND; use `any` or
`not` only where the product rule says so.

## Projections and aggregations

Use `includeOnly(...)` or `exclude(...)` to shape returned documents without fetching protected/internal fields. This
is response shaping, not authorization; enforce visibility before returning the result.

Available terminal operations include `count`, field `aggregate`, grouped aggregation, facet statistics, timestamp
histograms, and their async variants. Return a `CompletableFuture` directly from an async query handler when no further
synchronous work is required:

```java
@HandleQuery
CompletableFuture<Long> count(CountVisibleEntries query) {
    return Fluxzero.search(KnowledgeEntry.class)
            .match(query.visibility(), true, "visibility")
            .countAsync();
}
```

Do not call `.join()` inside an otherwise asynchronous handler. Test direct search constraints with
`whenSearching(...)`, then test the public query mapping separately.

### Asynchronous terminal operations

The following are futures of the public result, not another search builder:

| Call | Future value |
| --- | --- |
| `Search<R>.fetchAsync(maxSize)` | `List<R>` |
| `search.fetchAsync(maxSize, DocumentType.class)` | `List<DocumentType>` |
| `search.countAsync()` | `Long` |
| `search.aggregateAsync(fields...)` | `Map<String, FieldStats>` |
| `search.groupBy(paths...).aggregateAsync(fields...)` | `Map<Group, Map<String, FieldStats>>` |
| `search.groupBy(paths...).countAsync()` | `Map<Group, Long>` |
| `search.facetStatsAsync()` | `List<FacetStats>` |

`FieldStats` is `io.fluxzero.common.api.search.DocumentStats.FieldStats`; `Group` and `FacetStats` are in
`io.fluxzero.common.api.search`. Statistics operations are not supported for relationship queries or live Graph
composition. Use the ordinary document-search route for these aggregates; an async method does not change that limit.

```java
record FindVisibleEntries(String visibility) implements Request<List<KnowledgeEntry>> {}

@HandleQuery
CompletableFuture<List<KnowledgeEntry>> find(FindVisibleEntries query) {
    return Fluxzero.search(KnowledgeEntry.class)
            .match(query.visibility(), true, "visibility")
            .fetchAsync(50);
}
```

```kotlin
data class FindVisibleEntries(val visibility: String) : Request<List<KnowledgeEntry>>

@HandleQuery
fun find(query: FindVisibleEntries): CompletableFuture<List<KnowledgeEntry>> =
    Fluxzero.search(KnowledgeEntry::class.java)
        .match(query.visibility, true, "visibility")
        .fetchAsync(50)
```

These methods belong to an ordinary production-discovered handler, such as a Spring `@Component`, or an explicitly
registered handler outside Spring. `Request<R>` declares the unwrapped value even when the handler returns a future.
