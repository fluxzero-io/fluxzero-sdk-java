Use facets when a product needs fast counts or filters by categorical values such as status, type, labels, or tags. Use `count()` for one total; use `facetStats()` when the response needs counts per value.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

## Index the categorical paths

Put `@Facet` on the field/getter whose exact path should appear in facet results:

```java
import io.fluxzero.common.search.Facet;
import io.fluxzero.common.search.Sortable;
import io.fluxzero.sdk.modeling.Aggregate;

@Aggregate(searchable = true)
public record KnowledgeArticle(
        ArticleId articleId,
        @Sortable String title,
        String description,
        @Facet Set<String> tags,
        @Facet Visibility visibility) {
}
```

`@Facet` and `@Sortable` solve different problems. Facets support exact categorical matching and value counts; sortable paths support ordering, range, and existence indexes. A field may use both when the product requires both behaviors.

Facet values are strings in `FacetStats`. A scalar contributes one value. A collection contributes one facet value per element. Nested annotated values use nested paths, and map keys become path segments. Null and blank values do not contribute a facet.

## Return value counts from the current search

`facetStats()` applies the constraints already present on the search. This lets a browse query return facets for the filtered result set rather than for the whole collection:

```java
var search = Fluxzero.search(KnowledgeArticle.class);
if (term != null && !term.isBlank()) {
    search = search.lookAhead(term, "title", "description");
}
List<FacetStats> stats = search.facetStats();

Map<String, Integer> tagCounts = stats.stream()
        .filter(stat -> "tags".equals(stat.getName()))
        .collect(Collectors.toMap(
                FacetStats::getValue,
                FacetStats::getCount,
                Integer::sum,
                TreeMap::new));
```

Keep facet names in one place rather than scattering unchecked strings. Map the `name`, `value`, and `count` fields into a stable application response so callers do not depend on the SDK transport class.

To filter efficiently by an indexed facet, use the facet operation rather than a general text match:

```java
List<KnowledgeArticle> securityGuides = Fluxzero.search(KnowledgeArticle.class)
        .matchFacet("tags", "security")
        .sortBy("title")
        .fetch(50, KnowledgeArticle.class);
```

Passing a collection to `matchFacet` matches any supplied facet value. Combine separate constraints deliberately when the product requires all values.

## Test indexing, counts, and the public query

Seed documents in an order that cannot accidentally satisfy the expected result, then assert exact facet tuples:

```java
var access = new KnowledgeArticle(new ArticleId("access"), "Access policy", "Identity rules",
        Set.of("security", "reference"), Visibility.PUBLISHED);
var backup = new KnowledgeArticle(new ArticleId("backup"), "Backup guide", "Recovery steps",
        Set.of("operations", "reference"), Visibility.PUBLISHED);

fixture.givenDocument(backup)
        .givenDocument(access)
        .whenApplying(fc -> Fluxzero.search(KnowledgeArticle.class).facetStats())
        .<List<FacetStats>>expectResult(stats ->
                stats.contains(new FacetStats("visibility", "PUBLISHED", 2))
                && stats.contains(new FacetStats("tags", "security", 1))
                && stats.contains(new FacetStats("tags", "reference", 2)));
```

Also dispatch the public count/query request and assert its typed response. The direct search scenario proves the index mapping; the public query scenario proves application mapping, constraints, and authorization.

Type the public query assertion explicitly, for example `.<ArticleBrowsePage>expectResult(page -> ...)`. By contrast,
bind direct search results on `fixture.<KnowledgeArticle>whenSearching(...)`. These helpers have different declared
generic shapes; leaving a query result untyped makes its facet, total, and item accessors compile against `Object`.

Add an independently filtered scenario when the product promises facets for search results. A whole-knowledge base count does not prove that constraints are applied before `facetStats()`.

Search projections are eventually consistent outside the synchronous fixture. When `@Facet` is added to an existing document type, previously stored documents need a deliberate replay/reindex before their values can appear; changing the annotation alone does not backfill old indexes.
