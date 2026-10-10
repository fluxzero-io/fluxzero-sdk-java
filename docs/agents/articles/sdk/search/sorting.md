Use this when a result needs more than one sort key, a stable page order, or a derived technical ordering value.

## Declare precedence in query order

Chained sort instructions are applied from left to right. The first `sortBy(...)` is the primary key; later calls only
break ties left by earlier keys:

```java
List<KnowledgeArticle> articles = Fluxzero.search(KnowledgeArticle.class)
        .sortBy("editorialRank")
        .sortBy("title")
        .sortBy("articleId")
        .fetch(query.limit(), KnowledgeArticle.class);
```

This orders by `editorialRank`, then `title` within equal ranks, then the unique `articleId`. Reversing the calls does
not make the final call primary. Set direction on the key that needs it, for example
`sortBy("publishedAt", true).sortBy("articleId")` for newest first with a stable identifier tie-breaker.

Every paged or top-N query needs a deterministic final key. Prefer a unique sortable identifier as the last key so two
documents with the same business value do not move between pages.

## Collections and Graph children

With `fluxzero.search.collectionValues=true` (`FLUXZERO_SEARCH_COLLECTION_VALUES`), or defaults version
`2026.10.09` or later, ascending field sorting uses MIN and descending uses MAX, with missing/empty/null-only values
last. Explicit `SearchValue.min(path)` / `max(path)` selects an extremum independently of direction. Java:
`search.sortBy(SearchValue.min("prices"), true)`; Kotlin uses the same call. Annotation presence affects execution cost,
not results once indexed documents have been reindexed through the application's normal document flow (for example
`@HandleDocument`). Each indexed row without a minimum uses its existing maximum, including explicit MIN. Partial reindexing
affects rows independently, with annotated filtering/sorting still in PostgreSQL. There is no Runtime preparation API. See
[search maintenance](../operations/search-maintenance.md) for the migration and cursor boundary.

In compatibility mode (absent/older defaults without override), a sortable collection path represents its maximum encoded value, regardless of sort direction. The same rule applies
to Graph children: `sortBy("children/price")` orders roots by the highest child price in live and materialized Graphs.
Range constraints on that sortable path also use the maximum. Use `whereChild(...)` when a range should select any
individual child instead. Exact matching and returned Graph content retain all children. This contract is identical
for Java and Kotlin Models; changing projection mode does not change the aggregation rule.

## Keep the indexed shape explicit

Put `@Sortable` on the exact property path used by each sort instruction. For a derived technical key, the most
portable model is an ordinary serializable property of the searchable Model or projection:

```java
@Model(searchable = true, persistence = {ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT})
public record KnowledgeArticle(
        @EntityId @Sortable ArticleId articleId,
        @Sortable int editorialRank,
        @Sortable String title,
        String body) {
}
```

Do not use response-serialization annotations such as `@JsonIgnore` as the primary way to shape a searchable document.
They also change what can be reconstructed from the stored document and can make a direct fixture search behave
differently from the intended indexed model. If a technical sort key must not appear in the public API, keep the
search document complete and map the fetched result to an endpoint DTO or use search projection methods such as
`exclude(...)` or `includeOnly(...)` where that result shape is appropriate.

Mapping an already filtered, sorted, and bounded result to a response DTO is fine. Fetching the whole collection and
sorting it with a Java `Comparator` is not: it bypasses indexed ordering and becomes incorrect or expensive as the
collection grows.

## Prove every level of the comparator

Seed documents in an order that disagrees with the desired result. Include duplicate primary values so the test also
exercises the tie-breakers:

```java
var later = new KnowledgeArticle(new ArticleId("later"), 2, "Overview", "...");
var beta = new KnowledgeArticle(new ArticleId("beta"), 1, "Same title", "...");
var alpha = new KnowledgeArticle(new ArticleId("alpha"), 1, "Same title", "...");

TestFixture.create()
        .givenDocument(later)
        .givenDocument(beta)
        .givenDocument(alpha)
        .whenSearching(KnowledgeArticle.class,
                search -> search.sortBy("editorialRank")
                                .sortBy("title")
                                .sortBy("articleId"))
        .expectResult(List.of(alpha, beta, later));
```

Also send the public query through `whenQuery(...)` to verify pagination metadata and DTO mapping. Keep the direct
`whenSearching(...)` test: it distinguishes an indexed compound sort from an implementation that happens to return the
same order after in-memory post-processing.

If a minimal direct-search test contradicts the left-to-right precedence above, isolate the indexed property shape and
the SDK/test-runtime behavior. Do not infer a different precedence rule from one failure and do not silently replace
the indexed sort with `fetchAll().stream().sorted(...)`.

An explicit `SearchValue` sort opts the entire search ordering into the collection-value profile. Ordinary field sorts
in the same search use MIN ascending / MAX descending, including those added before the explicit selector.

### Instant comparison in the collection-value profile

`Instant` fields use the existing fixed-millisecond timestamp comparison both with and without `@Sortable`.
A whole second compares as `.000Z`; serialized nanoseconds remain in the document but comparison retains the
existing millisecond precision. This changes comparison keys only, not the document format or stored timestamp text.

Unannotated document entries retain no Java type information: valid uppercase UTC ISO text is interpreted as a
timestamp in this profile, including a literal `String` with that exact content. This guarantee concerns `Instant`
fields; annotated `String` fields keep their existing normalized text indexes. Lowercase, offset-form and invalid
ISO strings are ordinary text. Use consistently typed `Instant` fields for timestamp comparison.

Collection-value keys use Unicode code point order, matching PostgreSQL `C`. This includes plain and sortable paths.
New writes also choose text extrema in that order with the query profile disabled; old maxima mixing supplementary
and high BMP characters require typed application-owned reindexing if the older writer discarded the true extremum.
On locale databases the new profile uses explicit `C` SQL comparisons, which may not use existing locale indexes.
