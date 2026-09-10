Use this when package, class, and handler routes must compose, or when an endpoint binds path, query, header, cookie, or body values.

## Compose `@Path` deliberately

Fluxzero inspects `@Path` from outer packages through the concrete package, handler class, annotated properties, and handler method. Relative values append; an `@Path` value beginning with `/` or containing an absolute URL resets everything before it.

| Declaration | Effective base or route |
| --- | --- |
| package `@Path("/api")`, class `@Path("articles")` | `/api/articles` |
| package `@Path("/api")`, class `@Path("/articles")` | `/articles` |
| package `@Path("/api")`, class `@Path("articles")`, method `@Path("admin")` | `/api/articles/admin` |
| same hierarchy with method `@Path("/admin")` | `/admin` |
| package `@Path("/api")`, class `@Path("articles")`, `@HandleGet("/counts")` | `/api/articles/counts` |

Use one absolute root and relative nested `@Path` segments when you want composition:

```java
// package-info.java
@Path("/api")
package com.example.app.web;

import io.fluxzero.sdk.web.Path;
```

```java
@Component
@Path("articles")
final class KnowledgeEndpoint {
    @HandleGet("/{articleId}")
    ArticleView get(@PathParam("articleId") ArticleId articleId) {
        return Fluxzero.queryAndWait(new GetArticle(articleId));
    }
}
```

The reset rule above applies to nested `@Path` annotations. A leading slash in `@HandleGet`, `@HandlePost`, or another `@Handle*` mapping is not the same as a leading slash in `@Path`: the mapping is normalized and appended to the computed handler base. An absolute URL mapping can deliberately replace that base. Use an intentional absolute child `@Path` only when replacing the inherited prefix is the contract.

An instance/property-derived `@Path` is evaluated at runtime. Generated OpenAPI can compose static package, type, and method path values, but it cannot enumerate arbitrary runtime property values. Prefer static paths for discoverable application APIs; otherwise document and test the dynamic route separately instead of claiming the generated artifact contains every runtime value.

## Put transport annotations on handler parameters

`@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, `@FormParam`, and `@BodyParam` target handler method parameters. They do not belong on request-record components. `@WebParam` is the meta-annotation used to define parameter annotations; do not place it directly on an endpoint parameter or DTO field:

```java
@HandleGet
ArticlePage find(
        @QueryParam("term") String term,
        @QueryParam("offset") @PositiveOrZero int offset,
        @QueryParam("limit") @Min(1) @Max(100) int limit) {
    return Fluxzero.queryAndWait(new FindArticles(term, offset, limit));
}

@HandlePost
ArticleId add(@Valid PublishArticleRequest request) {
    // map the body DTO to a command
}

record PublishArticleRequest(
        @ApiDoc(required = true) @NotBlank String articleId,
        @ApiDoc(required = true) @NotBlank String title) {
}
```

Use a plain request DTO parameter for the whole JSON body. Use `@BodyParam` on method parameters only when deliberately extracting named body fields. Put Jakarta validation and `@ApiDoc(required = true)` on DTO record components according to the runtime and generated contract; do not put web injection annotations there.

Name injected parameters explicitly when the route or public contract depends on a different name. Java annotation processing records method parameter metadata, but an explicit `@PathParam("articleId")` or `@QueryParam("offset")` keeps the binding and OpenAPI intent visible.

## Verify runtime and generated paths together

For each static route added or changed:

1. Call the exact expected URL through `TestFixture`.
2. Use values that expose swapped or missing path/query parameters.
3. Parse served OpenAPI and assert the same exact path, method, and parameter names.
4. Exercise literal routes beside dynamic siblings.
5. If a child path deliberately resets a package prefix, add a test that would fail if the inherited prefix were appended.

For a dynamic property-derived path, drive the actual handler instance through the runtime router and assert the resolved value; do not require a build-time OpenAPI path that cannot be known statically.

A generated path alone does not prove runtime binding, and a direct method call proves neither routing nor OpenAPI. Read routed HTTP endpoint testing and generated API discovery for the two complementary matrices.
