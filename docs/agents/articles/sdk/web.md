Use web handlers as transport adapters. Domain behavior should already exist as commands, queries, and tests. This page covers inbound application endpoints; use one-way outbound HTTP for processor calls and webhooks published through `WebRequestGateway`.

```java
@Component
@Path("/api/projects")
class ProjectEndpoint {
    @HandlePost
    ProjectId create(CreateProjectRequest request) {
        ProjectId id = Fluxzero.generateId(ProjectId.class);
        Fluxzero.sendCommandAndWait(new CreateProject(id, request.details()));
        return id;
    }
}
```

Preserve the product contract at this boundary. Request DTO fields should map deliberately to command/query fields, including identifiers, roles, ownership, and other values that affect behavior. Generate an identifier only when the API contract makes it server-owned; if the caller must choose a stable business identifier, accept and validate it instead. Read request DTO validation and OpenAPI contracts for nested records and collections; `@Valid` alone does not make them required.

```java
record CreateUserRequest(
        @NotBlank String userId,
        @NotBlank String name,
        @NotNull Role role) {
}

@HandlePost
UserId create(CreateUserRequest request) {
    UserId id = new UserId(request.userId());
    Fluxzero.sendCommandAndWait(new CreateUser(id, request.name(), request.role()));
    return id;
}
```

Do not weaken a tested domain command when exposing it: do not replace caller-provided identity with a random value, pass required fields as `null`, or return a response model that omits product-required fields. Avoid adding unrelated fields merely because another example has them.

Return `WebResponse` when you need explicit status, headers, or content type. Add `@NoUserRequired` only for public endpoints such as health checks, login callbacks, public docs, or unauthenticated MCP docs. For protected frontend routes, `@RequiresUser` only enforces an identity; read production HTTP identity to implement the cookie or bearer path that establishes it.

Use `/api` as the default application API prefix, usually through package-level `@Path("/api")`. Route matching supports composed `@Path` values, literal and parameter segments, regex parameters, wildcards, optional segments, trailing-slash handling, request parameter injection, and most-specific route selection. In a composed hierarchy, relative child `@Path` values append; a child `@Path` beginning with `/` resets the earlier package/class chain. Read web routing and parameter binding for exact examples and annotation placement.

Avoid static/dynamic path collisions such as `/articles/counts` and `/articles/{articleId}` when an identifier could equal the reserved word. Most-specific matching favors the literal route, but the public contract should still include a web-level test proving the static route reaches the intended handler and the dynamic route still accepts normal IDs. Reordering methods is not a substitute for a route test; use a less ambiguous path or a constrained parameter when the contract permits it.

Before treating an HTTP surface as complete, use routed HTTP endpoint testing to execute every advertised operation through `TestFixture`. Command/query tests prove the domain and served OpenAPI proves discovery; neither proves path/query/body binding, caller propagation, or response conversion for the operation itself.

Default HTTP mapping is intentionally predictable: objects map to 200 JSON, `void`/`null` map to 204, validation/deserialization failures map to 400, `UnauthenticatedException` and `UnauthorizedException` map to 401, other `FunctionalException` failures map to 403, timeouts map to 503, and unexpected failures map to 500. Return `WebResponse` when the default is not the API contract.

`@ApiDoc` opts annotated packages or endpoints into generated API docs; it does not by itself publish a document endpoint. `@ApiDocInfo` supplies document metadata and controls the served OpenAPI and reference paths. Use generated API discovery for package configuration, stable operation IDs, runtime fixture assertions, and the intentional separation between HTTP OpenAPI and WebSocket protocol documentation. Do not maintain a handwritten duplicate of generated HTTP operations. `@ServeStatic` is for static assets; keep its `ignorePaths` covering `/api/*` unless the app deliberately serves static content over API routes.

Automatic `HEAD`/`OPTIONS` support belongs in routing, not hand-written endpoint methods. Prefer explicit `@HandleHead` or `@HandleOptions` only when the app needs custom behavior.

Runtime probes and websocket handshakes are infrastructure concerns. If the runtime is not available, non-probe paths can return unavailable responses before app code sees the request.

For a stateful `@SocketEndpoint`, let Fluxzero own the session registry. The open handler should return a handler instance that holds exactly one `SocketSession`; Fluxzero caches that instance by session ID, routes later messages and matching notifications to it, and removes it after close.

```java
@SocketEndpoint
@Path("/api/knowledge/live")
record KnowledgeSocket(SocketSession session) {
    @HandleSocketOpen
    static KnowledgeSocket open(SocketSession session) {
        session.sendMessage(Fluxzero.queryAndWait(new FindArticles(null)));
        return new KnowledgeSocket(session);
    }

    @HandleNotification(allowedClasses = ArticlePublished.class)
    void on(KnowledgeArticle article) {
        session.sendMessage(article);
    }
}
```

Do not put one mutable `SocketSession` field on a singleton endpoint and overwrite it in every open call. That design silently leaves only the most recently opened viewer receiving updates. Use associations when only selected sessions should receive a message; otherwise a matching notification is dispatched to each cached per-session endpoint instance.

If `FindArticles` returns only a first page, this is not a complete current snapshot. Either send explicit page metadata or continuation, stream chunks, or use a complete bounded query; read search pagination before reusing a capped list query on socket open.

Use WebSocket testing for fixture patterns around `@SocketEndpoint`, `@HandleSocketOpen`, `@HandleSocketMessage`, session IDs, multiple simultaneous viewers, associations, ping/close behavior, and socket authorization.

Direct runtime WebSocket endpoints are a low-level integration path. They require a namespace or legacy project ID plus `clientId` and `clientName`, negotiate JSON/CBOR and compression, and compose a runtime session ID from that context. App code should normally use the SDK client APIs instead of speaking this protocol directly.
