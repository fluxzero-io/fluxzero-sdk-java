Use this article when a query needs more than a simple exact match and page. Express filtering and aggregation in the

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
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
