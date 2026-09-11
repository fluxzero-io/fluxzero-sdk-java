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

## Keep the indexed shape explicit

Put `@Sortable` on the exact property path used by each sort instruction. For a derived technical key, the most
portable model is an ordinary serializable property of the searchable aggregate or projection:

```java
@Aggregate(searchable = true)
public record KnowledgeArticle(
        @Sortable ArticleId articleId,
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
