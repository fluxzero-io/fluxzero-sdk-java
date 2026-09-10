Use this when a task adds or changes frontend-callable HTTP operations. Test the domain deeply, but also prove that every advertised operation crosses Fluxzero's web router with the intended caller, request mapping, and response conversion.

Before completion, map every advertised operation and required contract field to direct routed or structural evidence.
Use the verification-boundaries inventory so one successful route or broad OpenAPI substring check does not stand in for
independent binding, requiredness, failure, and response-shape proof.

## Keep the three contracts separate

| Boundary | What it proves | What it does not prove |
| --- | --- | --- |
| Command/query fixture call | Domain rules, persisted state, and typed results | HTTP route selection, parameter binding, or response mapping |
| Routed HTTP fixture call | Handler selection, path/query/body binding, caller metadata, and `WebResponse` conversion | A real proxy socket, TLS, or browser behavior |
| Served OpenAPI assertion | Generated route, method, schema, operation ID, and discovery metadata | That the advertised operation can actually execute |

Generated OpenAPI proves discoverability; it does not execute the advertised operation. A direct endpoint-method call also bypasses routing and does not count as an HTTP contract test.

## Build an operation matrix

Before writing endpoint tests, make one row for every frontend operation added or changed by the task:

| Operation | Method and path | Caller | Binding to prove | Success contract | Critical negative |
| --- | --- | --- | --- | --- | --- |
| Create project | `POST /api/projects` | Editor ID | JSON body to typed request | `200` and returned `ProjectId` | Ordinary user denied |
| Get project | `GET /api/projects/{projectId}` | Viewer ID | Path segment to `ProjectId` | `200` and matching project | Unknown ID mapped as specified |
| Activate project | `POST /api/projects/{projectId}/activate` | Owner ID | Path segment plus caller identity; no body | Expected status and state | Other user denied |

Add one routed fixture call for every advertised HTTP operation in the matrix. This is a coverage rule, not a request to repeat every domain scenario through HTTP. Keep the deepest invariant tests at the command/query boundary, then add product-critical validation, identity, and error-mapping rows at the routed boundary.

Before completion, compare this operation matrix with both the served OpenAPI matrix and the requirement-to-evidence
inventory. A route that appears in generated discovery but has no routed success/binding assertion remains unexecuted;
one happy-path routed call does not prove its critical validation, identity, or error mapping.

## Drive the router through `TestFixture`

Use the helper matching the HTTP method. Prefer the `...ByUser` form when testing authorization after identity is established, and pass a user ID in at least one configured-provider scenario so `getUserById(...)` participates. These helpers inject or resolve `$user`; they do not prove that a real cookie or bearer header is authenticated. When the task requires a signed-in frontend, add a separate raw `whenWebRequest(...)` credential-establishment scenario from the HTTP authentication boundary article.

```java
private TestFixture fixture() {
    return TestFixture.create(
                    DefaultFluxzero.builder()
                            .registerUserProvider(new SenderProvider()),
                    new ProjectEndpoint(), CreateUser.class,
                    CreateProject.class, GetProject.class)
            .givenCommands(
                    new CreateUser(editorId, "Editor", Role.EDITOR),
                    new CreateUser(viewerId, "Viewer", Role.VIEWER));
}

fixture().whenPostByUser(editorId, "/api/projects",
                new CreateProjectRequest("project-1", "First project"))
        .expectWebResult(response -> response.getStatus() == 200
                && new ProjectId("project-1").equals(
                        response.<ProjectId>getPayloadAs(ProjectId.class)));

fixture().givenCommandsByUser(editorId,
                new CreateProject(new ProjectId("project-1"), "First project"))
        .whenGetByUser(viewerId, "/api/projects/project-1")
        .expectWebResult(response -> response.getStatus() == 200
                && new ProjectId("project-1").equals(
                        response.<Project>getPayloadAs(Project.class).projectId()));

fixture().whenPostByUser(viewerId, "/api/projects",
                new CreateProjectRequest("forbidden", "Forbidden"))
        .expectExceptionalResult(UnauthorizedException.class)
        .expectWebResponse(response -> response.getStatus() == 401);
```

The helper returns a fresh fixture for each scenario and seeds the aggregate-backed profiles before passing their IDs to a routed call. Do not reuse a scenario that already created the same aggregate, and do not pass an unresolved ID to a configured provider.

Keep the explicit `<Project>` witness before `getPayloadAs(Project.class)` whenever a domain method is chained. The
SDK accepts `Type`, not `Class<R>`, so the class argument alone leaves the static return type as `Object`.

Use `whenGet`, `whenPost`, `whenPut`, `whenPatch`, and `whenDelete` for normal HTTP methods, with their `...ByUser` variants for caller-sensitive routes. Use `whenWebRequest` or `whenWebRequestByUser` when headers, cookies, content type, or a less common method is part of the contract. For successful transport conversion, prefer `expectWebResult(...)`; for a contractual failure, assert the exact exception and the mapped `WebResponse` status when both are significant.

## Prove the bindings that can drift

- For path and query parameters, choose values that would expose a swap, omission, or fallback default.
- When package and class `@Path` values compose, call the exact effective route. A leading slash on a child `@Path` resets the inherited prefix; read web routing and parameter binding before assuming both segments remain.
- For request bodies, assert that every product-relevant field reaches the command or result; do not pass convenient `null` placeholders.
- For actor-sensitive operations, send no actor ID in the body. Use the routed caller and verify ownership or role behavior.
- For a literal path beside a dynamic path, call both routes. OpenAPI entries alone do not prove most-specific selection.
- For bodyless actions, pass `null` deliberately and assert the resulting status or payload.
- Keep each scenario's preconditions independent when a long chain could let earlier state mask a routing defect.

Pair this matrix with a separate structural test of the served OpenAPI document. The two matrices should name the same HTTP operations, but they detect different failures.

Use a local runtime client when the product requires a real proxy/network handshake, cookie/bearer authentication smoke test, serialization smoke test, or browser behavior. Ordinary endpoint contract coverage should stay fast and deterministic in `TestFixture`. Do not claim anonymous rejection from the default fixture wrapper, which supplies a system fallback; prove the production provider or proxy path separately.
