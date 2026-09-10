Use this when a query, endpoint, admin list, export, or WebSocket snapshot might return more than a deliberately bounded set. Choose the public completeness contract before choosing a search terminal operation.

| Contract | Supported shape |
| --- | --- |
| Complete and explicitly bounded | `fetchAll(type)` is acceptable when the business bound is real and safe to materialize |
| User-facing or potentially unbounded | Return a page with offset/continuation, limit, total or `hasMore`, and a stable order |
| Latest/top-N | A fixed cap is valid only when the route and response explicitly promise latest/top-N rather than all/current |

`fetch(100, Item.class)` returning `List<Item>` with no offset, cursor, or continuation is not pagination. It silently makes rows after 100 unreachable and must not implement contracts named “all,” “complete,” or “current knowledge base.” Raising the hidden cap to 250 or 1,000 does not fix that contract.

## Return an explicit page

Use indexed filters, count the same constrained search, apply a deterministic order, then skip and fetch:

```java
public record FindArticles(
        String term,
        @PositiveOrZero int offset,
        @Min(1) @Max(100) int limit) implements Request<ArticlePage> {

    @HandleQuery
    ArticlePage handle() {
        Search search = Fluxzero.search(KnowledgeArticle.class);
        if (term != null && !term.isBlank()) {
            search = search.lookAhead(term.strip(), "title", "description");
        }

        long total = search.count();
        List<KnowledgeArticle> items = search
                .sortBy("title")
                .sortBy("articleId")
                .skip(offset)
                .fetch(limit, KnowledgeArticle.class);
        boolean hasMore = (long) offset + items.size() < total;
        return new ArticlePage(items, offset, limit, total, hasMore);
    }
}

public record ArticlePage(
        List<KnowledgeArticle> items,
        int offset,
        int limit,
        long total,
        boolean hasMore) {
}
```

Put `@Sortable` on every model path used for sorting, including a unique tie-breaker such as `articleId`. A page-size maximum may reject or clamp `limit` only because the caller can still advance `offset`. Do not apply `skip` before computing a total intended to describe the whole filtered result.

Compound sort precedence follows the call order: the first `sortBy(...)` is primary and later calls are tie-breakers.
Use the focused compound-sorting guidance when a page uses a derived rank or hides technical indexed fields from its
public response.

Map `offset` and `limit` as documented query parameters at the endpoint. Preserve their runtime validation and describe defaults and maximums with `@ApiDoc`; do not hide a server cap inside the query handler.

## Audit secondary consumers

A WebSocket complete current snapshot, admin “all users” query, export, or background fan-out must not reuse a default first page unless the payload carries continuation/total information or the domain has a proven complete bound. For a large live view, send an explicit first page plus page metadata, stream or chunk the initial state, or change the product contract to latest/top-N. Later live updates do not repair items omitted from the initial snapshot.

## Test past the boundary

Seed `MAX_PAGE_SIZE + 1` ordered documents. Assert page one has the exact maximum, `hasMore` is true, and `total` covers every document. Fetch page two, assert its IDs are disjoint from page one, and assert the union covers all seeded IDs. Use duplicate sort values so the unique tie-breaker participates. A two-document test cannot expose a hidden 100-row cap.

For a complete bounded contract, test the documented maximum plus the expected complete result. For latest/top-N, assert the name or metadata says top-N and verify the ordering and exact cap instead of calling the result “all.”

When the public page is asserted through `whenQuery(...)`, bind the result before calling `items()`, `total()`, or
`hasMore()`: `.<ArticlePage>expectResult(page -> ...)`. The fixture query helper is declared as `Then<Object>`; an
untyped assertion lambda turns all page accessors into accessor-on-`Object` compiler errors.
