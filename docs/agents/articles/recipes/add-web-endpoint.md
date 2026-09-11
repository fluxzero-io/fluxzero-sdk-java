Only add a web endpoint after command/query behavior exists.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

Use `@Path` at class level for a stable base path and `@HandleGet`, `@HandlePost`, `@HandlePut`, or related annotations at method level. Read web routing and parameter binding when package and class paths compose: a relative child `@Path` appends, while a child `@Path` beginning with `/` resets the inherited prefix. Put `@PathParam`, `@QueryParam`, and other web injection annotations on handler method parameters, not request-record components.

Before writing the adapter, make a small field map from the product request to the endpoint DTO, command/query, aggregate identity, and response. Preserve every product-relevant field across that path.

For nested records and collections, read request DTO validation and OpenAPI contracts. `@Valid` cascades into a present value but does not make the outer field, list, or list item required; keep runtime constraints and generated required arrays aligned at every DTO level.

| Product field | Endpoint request | Command/query | Response/test |
| --- | --- | --- | --- |
| Caller-chosen identifier | Required and validated | Same typed ID | Duplicate ID is tested through HTTP |
| Server-owned identifier | Omitted from request | Generate once at the boundary | Returned to the caller |
| Role/ownership/status | Required when behavior depends on it | Passed without `null` placeholders | Authorized and unauthorized cases are tested |

Generate new IDs at the endpoint boundary only when the API contract makes them server-owned. Do not silently replace a caller-specified business ID with a random value.

Return plain typed objects for normal JSON responses. Return `WebResponse` when you need a specific status, content type, or headers.

Add `@NoUserRequired` only for intentionally public endpoints. Otherwise let the app's normal authentication rules apply. When the product says the frontend is signed in or authenticated, follow the authenticated-frontend recipe: `@RequiresUser`, roles, and `getUserById(...)` do not establish a cookie or bearer credential.

Let route annotations do the transport work: path params, query params, optional segments, content negotiation, and automatic `HEAD`/`OPTIONS` are part of the web handler model. Keep business decisions in commands, queries, and legal assertions.

Check every literal route against sibling dynamic routes. For paths such as `/counts` beside `/{articleId}`, add a web-level test for both paths and avoid identifiers that collide with reserved literals when the product contract allows a clearer route.

Add transport tests for the public contract, not only direct command/query tests. Use `whenPostByUser`, `whenGetByUser`, or `whenWebRequestByUser` when the endpoint depends on identity or roles. Include the success path, missing/invalid required fields, duplicate identifiers, and forbidden callers that matter to the product brief.

Follow the focused routed HTTP endpoint testing article: list every advertised operation added or changed by the task, then execute each one through the matching `TestFixture` web helper. Do not count a command/query test or an OpenAPI path assertion as execution of that endpoint.

When API documentation is part of the product contract, give every advertised HTTP operation a stable `@ApiDoc(operationId = "...")`, enable the generated document with `@ApiDocInfo`, request it through the fixture route, and assert its operations and schemas structurally. Do not use reflection or broad text containment as proof that runtime discovery works. Generated OpenAPI does not include WebSocket lifecycle pseudo-methods; document and fixture-test live routes separately.
