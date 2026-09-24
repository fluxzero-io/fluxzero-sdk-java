Use this when a product requires frontend operations to be discoverable through generated API documentation. Treat the generated HTTP contract as the source of truth; do not maintain a second handwritten OpenAPI document beside the handlers.

## Publish the generated HTTP contract

`@ApiDoc` opts packages, handler types, or methods into generated documentation. An empty annotation is enough for route, parameter, request, and response information that Fluxzero can infer. Add explicit metadata only where inference is insufficient, especially stable operation IDs:

```java
@ApiDoc(summary = "Create a project", operationId = "createProject")
@HandlePost("/projects")
ProjectId create(CreateProjectRequest request) {
    // map the request to the already-tested command
}
```

Put top-level metadata and serving options on the package or handler scope that owns the routes:

```java
@Path("/api")
@ApiDoc
@ApiDocInfo(
        title = "Project API",
        version = "1.0.0",
        serveOpenApi = true,
        openApiPath = "openapi.json",
        serveApiReference = true,
        apiReferencePath = "docs")
package com.example.projects;

import io.fluxzero.sdk.web.ApiDoc;
import io.fluxzero.sdk.web.ApiDocInfo;
import io.fluxzero.sdk.web.Path;
```

Relative document paths resolve against the `@Path` on the same package or handler type. This example serves `/api/openapi.json` and `/api/docs`. Absolute configured paths start at the application root. Nested handler `@Path` values follow the same composition rule: relative values append and a leading `/` resets the inherited chain. Read web routing and parameter binding before duplicating an inherited prefix. `serveApiReference = true` also serves the OpenAPI document because the reference page consumes it. The automatic document and reference endpoints are public, so enable them only when that is the intended product contract.

With annotation processing enabled, Fluxzero writes the generated document to `META-INF/fluxzero/openapi.json`. The automatic endpoint uses that generated resource when present and can render from runtime metadata as a fallback. Keep handler annotations and this generated artifact authoritative; do not copy their operations into a manually maintained JSON string.

## Make validation and security explicit

For a composed Model Graph response, select its root with
`@ApiDocResponse(status = 200, modelGraph = RootModel.class)`. Child declarations can add
`apiDoc = @ApiDoc(...)` to `@Parent(pathInParent = ...)`: the final path segment becomes a list of that child type,
with slash-separated prefixes represented as nested objects. Array/collection responses remain arrays of Graphs.
An empty `modelGraphPaths` includes every documented relation; select `{"children/grandchildren"}` for an endpoint
subgraph, including its ancestors but not siblings or deeper descendants. `type` and `modelGraph` are mutually
exclusive. Runtime-served docs include locally discovered/registered child Models from other modules.
`@Parent(apiDoc = @ApiDoc(exclude = true))` excludes a relationship from the documentation only; it does not filter
runtime responses or Graph reads. See the API-discovery options article for the broader generated/merged document
contract and the Graph guides for actual response scoping.

OpenAPI generation maps supported Jakarta constraints that are visible on the inspected element, but Java record-component propagation and annotation-processor visibility can still leave required arrays incomplete. Runtime security is separate: package-level `@RequiresUser` is not inferred as an OpenAPI scheme or requirement. Inspect the generated schema instead of assuming either contract is complete.

Keep runtime validation and add `@ApiDoc(required = true)` on required request record components when the generated schema would otherwise omit requiredness:

```java
public record AddProjectRequest(
        @ApiDoc(required = true) @NotBlank String projectId,
        @ApiDoc(required = true) @NotBlank String title,
        @ApiDoc(required = true) @NotNull ProjectType type) {
}
```

`@ApiDoc(required = true)` documents the contract; it does not replace Jakarta validation. Use it as an explicit fallback when a supported Jakarta constraint is not visible where generation inspects the property, including annotations propagated to a record's field, accessor, or type use rather than its record-component element.

For nested records, collection wrappers, and bulk items, inspect every reachable schema rather than only the request body's outer `$ref`. Read request DTO validation and OpenAPI contracts for a complete outer-list/item example and routed invalid-payload tests.

Declare the authentication mechanism the client actually uses. For a bearer-token API:

```java
@ApiDocInfo(
        title = "Project API",
        version = "1.0.0",
        security = "bearerAuth",
        components = @ApiDocComponent(
                path = "securitySchemes.bearerAuth",
                json = """
                        {"type":"http","scheme":"bearer","bearerFormat":"JWT"}
                        """),
        serveOpenApi = true)
```

For a BFF session, define an `apiKey` security scheme with `in: cookie` and the application's actual configured cookie name instead. Do not advertise bearer auth for a cookie-only app or invent a generic cookie name. `@RequiresUser` enforces runtime identity but does not automatically create an OpenAPI security scheme.

## Verify the runtime boundary

Treat the emitted document as a separate evidence boundary. Map each promised method, path, operation ID, input
requiredness rule, item schema, and response shape to a structural assertion; the verification-boundaries inventory
helps keep those checks independent from routed execution and domain behavior.

Reflection over `@ApiDoc` proves only that annotations exist. Reading a build file proves only that processing was configured. Request the served document through `TestFixture` so route registration, document generation/loading, and response conversion all participate:

```java
TestFixture.create(new ProjectEndpoint())
        .whenGet("/api/openapi.json")
        .expectWebResult(response -> {
            String openApi = response.<String>getPayloadAs(String.class);
            JsonNode document = JsonUtils.readTree(openApi);
            JsonNode operation = document.path("paths")
                    .path("/api/projects").path("post");
            JsonNode requestSchema = document.path("components")
                    .path("schemas").path("AddProjectRequest");
            Set<String> required = StreamSupport.stream(
                            requestSchema.path("required").spliterator(), false)
                    .map(JsonNode::asText).collect(Collectors.toSet());
            return response.getStatus() == 200
                   && "application/json".equals(response.getContentType())
                   && "createProject".equals(operation.path("operationId").asText())
                   && operation.path("requestBody").path("content")
                           .path("application/json").has("schema")
                   && required.equals(Set.of("projectId", "title", "type"))
                   && operation.path("responses").has("200");
        });
```

Bind the response payload before parsing. `getPayloadAs(Type)` does not infer its generic return type from
`String.class`. Passing an unbound result directly to overloaded `JsonUtils.fromJson(...)` is ambiguous between its
String and byte-array overloads; `JsonUtils.readTree(openApi)` expresses the intended JSON-tree boundary directly.

Build a structural assertion matrix from the advertised HTTP surface:

| Contract part | Assert |
| --- | --- |
| Route | Exact path and HTTP method |
| Operation | Stable `operationId`, summary, and security when required |
| Input | Path/query parameter names, request-body fields, and exact required properties |
| Output | Status codes and response schema |
| Serving | Configured OpenAPI/reference path and content type |

Give every advertised operation a matching row in routed HTTP tests, and give every product-critical required field or
response branch an independently falsifiable assertion. Keep discovery structure, routed execution, and domain behavior
as separate evidence rows; success in one does not imply the others.

Also assert the top-level or operation security requirement and its matching `components.securitySchemes` entry. A named requirement without a defined scheme, or a scheme that does not match the actual cookie/bearer flow, is not a usable client contract.

Do not replace these checks with broad string containment. A copied operation name can remain present while the route, method, or schema is missing.

Pair every advertised path-and-method row added or changed by the task with the focused routed HTTP endpoint test matrix. Requesting this OpenAPI document proves that discovery is served; it does not execute the documented application operations.

## WebSocket discoverability is separate

Fluxzero's generated OpenAPI contains standard HTTP operations and intentionally excludes WebSocket lifecycle pseudo-methods such as `WS_OPEN` and `WS_MESSAGE`. A socket route missing from `paths` is therefore not evidence that socket registration failed.

Document the live protocol separately in the product-facing reference or API prose. Include the route, how the handshake supplies authentication, the initial snapshot shape, later update shapes, close/error behavior, and reconnect expectations. Verify it through the WebSocket fixture boundary: open the real route, assert the initial snapshot, trigger the real command or event, and assert delivery to the open session. If a consumer requires machine-readable live-protocol metadata in the same artifact, add a deliberately designed vendor extension rather than replacing the generated HTTP operations with a handwritten document. Keep the socket fixture test as the registration and delivery proof.

## Multi-module discovery and merged resources

The automatic endpoint discovers every `META-INF/fluxzero/openapi.json` visible to the handler classloader. It merges
compatible paths, components and metadata in stable resource order. Exact duplicates are accepted. Conflicting
values and duplicate operation IDs fail during handler registration with source and JSON-path context; they are not
silently resolved by whichever resource happens to load first. A manual document at the same path follows the same rules.

Spring Boot nested JARs are discovered normally. A classic shaded JAR must preserve overlapping resources in the
application packaging. Maven Shade's `AppendingTransformer` can concatenate these resources because the SDK reads
consecutive JSON documents separately. Verify merged endpoints/components and a deliberate conflict against the
packaged multi-module application, not only one module's generated file.
