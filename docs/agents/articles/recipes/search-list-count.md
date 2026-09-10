Use this recipe when a product asks for a browse page, text search, filters, sorting, pagination, totals, or grouped counts.

1. Make the aggregate or projection searchable.
2. Put `@Sortable` on fields used for sorting, numeric/range filtering, or existence checks.
3. Model the user intent as a typed `Request<T>`.
4. Build every supplied filter in `Fluxzero.search(...)` inside the query handler.
5. Choose a complete, paged, or explicitly top-N contract. For a page, expose offset or continuation plus page metadata; a hidden maximum is not pagination.
6. Test both the search constraints and the public query.

```java
import io.fluxzero.common.search.Sortable;
import io.fluxzero.sdk.modeling.Aggregate;

@Aggregate(searchable = true)
public record KnowledgeArticle(
        @Sortable ArticleId articleId,
        @Sortable String title,
        String description,
        Topic topic,
        Visibility visibility) {
}

public record BrowseArticles(
        String term,
        Topic topic,
        @PositiveOrZero int offset,
        @Min(1) @Max(100) int limit)
        implements Request<ArticlePage> {

    @HandleQuery
    ArticlePage handle() {
        var search = Fluxzero.search(KnowledgeArticle.class);
        if (term != null && !term.isBlank()) {
            search = search.lookAhead(term, "title", "description");
        }
        if (topic != null) {
            search = search.match(topic, true, "topic");
        }
        long total = search.count();
        var items = search.sortBy("title").sortBy("articleId")
                .skip(offset).fetch(limit, KnowledgeArticle.class);
        return new ArticlePage(items, offset, limit, total,
                (long) offset + items.size() < total);
    }
}

public record ArticlePage(
        List<KnowledgeArticle> items, int offset, int limit, long total, boolean hasMore) {
}
```

Map `offset` and `limit` as documented HTTP query parameters, including the default and maximum. The maximum is safe only because callers can request the next offset; validate the same bounds for non-HTTP query callers.

Use the same constraint shape for counts:

```java
long published = Fluxzero.search(KnowledgeArticle.class)
        .match(Visibility.PUBLISHED, true, "visibility")
        .count();
```

For grouped product statistics, prefer facets or aggregations over loading documents and grouping them in Java. Read facet filters and counts before implementing categorical totals: put `@Facet` on the exact indexed paths, use `matchFacet(...)` for exact category filters, and return `facetStats()` tuples from the constrained search.

Never implement a user-facing search as `fetchAll(...).stream().filter(...).sorted(...)`. That moves work and memory use into the application, bypasses indexed behavior, and becomes incorrect once pagination or a large collection matters. `fetchAll()` remains acceptable for genuinely small, bounded, complete-set contracts. Read complete lists and pagination for page metadata, stable ordering, WebSocket snapshot completeness, and a test with more documents than one page.

```java
var access = new KnowledgeArticle(new ArticleId("access"), "Access policy", "Identity rules", Topic.SECURITY, Visibility.PUBLISHED);
var backup = new KnowledgeArticle(new ArticleId("backup"), "Backup guide", "Recovery steps", Topic.SECURITY, Visibility.PUBLISHED);

TestFixture.create()
        .givenDocument(backup)
        .givenDocument(access)
        .whenSearching(KnowledgeArticle.class,
                search -> search.match(Topic.SECURITY, true, "topic")
                                .sortBy("title"))
        .expectResult(List.of(access, backup));
```

The reversed seed order makes this test prove the `@Sortable title` path and ascending sort instead of merely proving membership. Also send `BrowseArticles` through `whenQuery(...)` to verify the typed contract. Keep the direct `whenSearching(...)` scenario because a query-result assertion alone does not show which indexed constraints the application is expected to use.

When chaining sort keys, read compound search sorting: the first key is primary and each later key only breaks ties.
Include duplicate primary values in the direct search test so a reversed key order cannot pass accidentally.
