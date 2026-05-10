# Message Handling

In Fluxzero, every interaction—be it a domain command, a query, or a web request—is a message. This unified approach
eliminates infrastructure boilerplate and ensures your logic is consistent, testable, and scalable.

---

## Quick Navigation

- [Handling Messages](#handling-messages)
    - [@HandleCommand (Self-Handling & Standalone)](#handlecommand)
    - [@HandleQuery](#handlequery)
    - [Handling Events & Notifications](#events-notifications)
    - [Specialized Handlers](#specialized-handlers)
        - [@HandleSchedule](#handleschedule)
        - [@HandleCustom](#handlecustom)
        - [@HandleDocument](#handledocument)
        - [@HandleError](#handleerror)
        - [@HandleMetrics](#handlemetrics)
        - [@HandleResult / @HandleWebResponse](#results-responses)
    - [Routing Keys (Client-side Filtering)](#routing-keys)
    - [Handler Types (Tracking vs. Local)](#handler-types)
    - [Handler Parameters](#handler-parameters)
- [Defining Payloads (Commands & Queries)](#payloads)
- [Web & WebSocket Handling](#web-handling)
    - [@HandleGet / @HandlePost](#web-requests)
    - [HTTP Status Mapping](#http-mapping)
    - [Advanced Endpoint Patterns](#advanced-endpoints)
    - [@ServeStatic](#serve-static)
    - [WebSocket (@SocketEndpoint)](#websocket)
- [Handling Multiple Payloads](#multiple-payloads)
- [Handler Interceptors](#handler-interceptors)
- [Identifiers](#identifiers)
- [Common Pitfalls](#common-pitfalls)

---

## Handling Messages

### @HandleCommand

<a name="handlecommand"></a>

Used for messages that intend to change state.

**Example: Self-Handling Command (Interface Pattern)**

Recommended for updates to aggregates. Combined with `@TrackSelf` to ensure asynchronous tracking. The `@Consumer`
annotation creates an isolated named consumer, allowing this command type to be tracked and processed independently.

[//]: # (@formatter:off)
```java
@TrackSelf
@Consumer(name = "user-update")
public interface UserUpdate {
    @NotNull
    @RoutingKey
    UserId userId();

    @HandleCommand
    default UserProfile handle() {
        return Fluxzero.loadAggregate(userId())
                .assertAndApply(this)
                .get();
    }
}
```
[//]: # (@formatter:on)

**Example: Creating, Updating, and Deleting Aggregates**

[//]: # (@formatter:off)
```java
// 1. Create Aggregate
public record CreateProject(ProjectId projectId, @NotNull @Valid ProjectDetails details) implements ProjectUpdate {
    @Apply
    Project apply() {
        return Project.builder().projectId(projectId).details(details).build();
    }
}

// 2. Update Aggregate
public record UpdateProjectDetails(ProjectId projectId, @NotNull @Valid ProjectDetails details) implements ProjectUpdate {
    @Apply
    Project apply(Project project) {
        return project.toBuilder().details(details).build();
    }
}

// 3. Delete Aggregate
public record DeleteProject(ProjectId projectId) implements ProjectUpdate {
    @Apply
    Project apply(Project project) {
        return null; // Clears the aggregate value (but leaves the stored events)
    }
}
```
[//]: # (@formatter:on)

**Example: Creating, Updating, and Deleting Sub-Entities**

Sub-Entities can be added without modifying the parent aggregate directly. Fluxzero will take of updating the parent
aggregate's state automatically.

[//]: # (@formatter:off)
```java
// 1. Create Sub-Entity (Task within Project)
public record CreateTask(ProjectId projectId, @NotNull TaskId taskId, @NotNull @Valid TaskDetails details) implements ProjectUpdate {
    @Apply
    Task apply() {
        return Task.builder().taskId(taskId).details(details).build();
    }
}

// 2. Update Sub-Entity
public record UpdateTaskStatus(ProjectId projectId, @NotNull TaskId taskId, boolean completed) implements ProjectUpdate {
    @Apply
    Task apply(Task task) {
        return task.toBuilder().completed(completed).build();
    }
}

// 3. Delete Sub-Entity
public record RemoveTask(ProjectId projectId, @NotNull TaskId taskId) implements ProjectUpdate {
    @Apply
    Task apply(Task task) {
        return null; // Deletes the entity
    }
}
```
[//]: # (@formatter:on)

**Example: Standalone Command Handler**

Used for actions that don't directly target an aggregate's state (e.g., sending an external notification).

[//]: # (@formatter:off)
```java
@Component
class EmailHandler {
    @HandleCommand
    void handle(SendWelcomeEmail command) {
        // Logic to trigger email via an external gateway
    }
}
```
[//]: # (@formatter:on)

### @HandleQuery

<a name="handlequery"></a>

Used for read-only requests. Usually self-handling. Queries MUST implement `Request<T>` to define the return type.

Prefer creating a dedicated query payload (with `@HandleQuery`) for data retrieval or computation instead of static utility methods. This keeps behavior explicit, reusable via messaging, and easy to test with `TestFixture`.

**Example: Self-Handling Query**

[//]: # (@formatter:off)
```java
public record GetUserProfile(@NotNull UserId userId) implements Request<UserProfile> {
    @HandleQuery
    UserProfile handleQuery() {
        return Fluxzero.loadAggregate(userId).get();
    }
}
```
[//]: # (@formatter:on)

**Memoization**

For self-handling commands or queries, use `Fluxzero.memoize(...)` or `Fluxzero.memoizeIfAbsent(...)` for lightweight
runtime caching. Values are scoped to the current `Fluxzero` instance and, by default, to the calling class.

**Example: Standalone Query Handler**

Queries can also be handled in a separate component. Adding `@LocalHandler` ensures the query is handled synchronously
in the publication thread. Without `@LocalHandler`, a standalone handler defaults to **tracking** (asynchronous).

[//]: # (@formatter:off)
```java
@Component
@LocalHandler
class UserQueryHandler {
    @HandleQuery
    UserProfile handle(GetUserProfile query) {
        return Fluxzero.loadAggregate(query.userId()).get();
    }
}
```
[//]: # (@formatter:on)

> **Passive Listening**: All requests (commands, queries, web requests) can be handled passively using e.g.
`@HandleQuery(passive = true)`, meaning results won't be published. This is useful for auditing or logging without
> interfering with the primary request flow.

**Advanced Tip (Rare): Incremental Identifiers**

If random IDs are not acceptable, implement incremental ID allocation as a dedicated query backed by persisted counter
state. For the full consumer-pattern details, see [Tracking: Incremental Identifiers](tracking.md#incremental-identifiers).

<a name="events-notifications"></a>

### Handling Events & Notifications

Events are handled asynchronously. Usually the flow is: `Command -> @Apply -> Event payload`, or when an event is
published explicitly via `Fluxzero.publishEvent(...)`.

#### @HandleEvent

Used for side effects like sending emails or updating secondary projections within a specific context.

[//]: # (@formatter:off)
```java
@Component
@Consumer(name = "analytics")
class AnalyticsHandler {
    @HandleEvent
    void handle(CreateOrder event) {
        // Asynchronous logic
    }
}
```
[//]: # (@formatter:on)

#### @HandleNotification

Enables handling ALL events of a filtered type across all message segments. This is often used for global statistics
collection or broadcasting updates over WebSockets.

[//]: # (@formatter:off)
```java
@Component
class GlobalStatsHandler {
    @HandleNotification
    void handle(CompletePayment event) {
        // Collect statistics globally
    }
}
```
[//]: # (@formatter:on)

<a name="specialized-handlers"></a>

### Specialized Handlers

Fluxzero supports handling a variety of specialized message types.

#### @HandleSchedule

Used to handle scheduled messages.

[//]: # (@formatter:off)
```java
@HandleSchedule
void onSchedule(TerminateAccount schedule) {
    // Logic to execute when the schedule triggers
}
```
[//]: # (@formatter:on)

> Note that scheduled commands are handled via `@HandleCommand`.

#### @HandleCustom

Used to handle messages in custom topics.

[//]: # (@formatter:off)
```java
@HandleCustom("my-topic")
void onCustomMessage(MyCustomPayload payload) {
    // Handle messages from the specified topic
}
```
[//]: # (@formatter:on)

#### @HandleDocument

Handles updates to specific document types. This handler receives a notification whenever a document has been added or
modified in the search index.

> **Nuance**: There is no guarantee that every intermediate update is received; the handler will always receive the last
> known state of the document.

For information on how to retroactively update a collection of documents using `@HandleDocument`, see
the [Retroactive Updates](#retroactive-updates) section.

[//]: # (@formatter:off)
```java
@HandleDocument(OrderDocument.class)
void onOrderDocument(OrderDocument document) {
    // Handle document-related changes
}
```
[//]: # (@formatter:on)

<a name="handleerror"></a>

#### @HandleError

Used for error monitoring or handling.

`@HandleError` can be used to retroactively update earlier handler errors, acting similarly to a dead-letter queue (DLQ)
when needed. By using the `@Trigger` annotation on a parameter, you can inject the original payload that failed. For
more details, see the [Error Correcting](tracking.md#error-correcting) chapter.

When testing `@HandleError` behavior, use an asynchronous fixture:
`TestFixture.createAsync(...)`.

[//]: # (@formatter:off)
```java
@HandleError
void onError(ErrorMessage error, @Trigger CreateOrder failedCommand) {
    // Handle domain or system errors
    // failedCommand contains the original payload that caused the error
}
```
[//]: # (@formatter:on)

<a name="handlemetrics"></a>

#### @HandleMetrics

Used for monitoring metrics of all applications in a Fluxzero cluster.

[//]: # (@formatter:off)
```java
@HandleMetrics
void on(HostMetrics metrics) {
    // Collect or process host metrics
}

@HandleMetrics
void on(SearchDocuments metrics) {
    // Collect or process search metrics
}
```
[//]: # (@formatter:on)

<a name="results-responses"></a>

#### @HandleResult & @HandleWebResponse

Used to handle the outcomes of asynchronous requests. Very rarely used directly.

[//]: # (@formatter:off)
```java
@HandleResult
void onResult(CommandResult result) {
    // Handle the result of a previously sent command
}

@HandleWebResponse
void onWebResponse(WebResponse response) {
    // Handle the response from an external web request
}
```
[//]: # (@formatter:on)

<a name="handler-types"></a>

### Handler Types (Tracking vs. Local)

The following table summarizes how handlers are categorized and configured:

| Type                            | Pattern                                 | Configuration                                       |
|:--------------------------------|:----------------------------------------|:----------------------------------------------------|
| **Tracking** (Async/Persistent) | **Standalone**: `@Component`            | Use `@Consumer` to configure threads, retries, etc. |
|                                 | **Self-Handling**: `@TrackSelf`         | Isolated via `@Consumer`.                           |
|                                 | **Stateful**: `@Stateful`               | For sagas and long-running processes.               |
| **Local** (Sync/In-thread)      | **Standalone**: `@LocalHandler`         | Handled in the publication thread.                  |
|                                 | **Self-Handling**: Plain `@HandleQuery` | Optionally add `@LocalHandler` for settings.        |

### Handler Parameters

Handlers can inject various context parameters:

- **Payload**: The message object itself.
- **Sender**: The user/system that sent the message. User context MUST be injected via `Sender`; command/query payloads
  MUST NOT contain user IDs.
- **Metadata**: Key-value pairs attached to the message.
- **Instant**: The message timestamp.
- **Entity<T> or T**: The current state of the entity. In `@HandleEvent`, the entity is automatically played back to
  reflect its state immediately after the event occurred.
- **Entity<T> for optional state**: Use `Entity<T>` when the entity may not exist yet. In that case the injected wrapper
  is present but its value is empty. Useful for upsert-style handlers and idempotent startup/replay flows.
- **WebRequest / WebResponse / Schedule**: These extend `Message` and can be injected directly into handler methods when
  transport/scheduling metadata is needed.
- **@Autowired**: Standard Spring beans.

---

<a name="payloads"></a>

## Defining Payloads (Commands & Queries)

Payloads are typically implemented as immutable `records`. This is where you define the data required for an operation
and the constraints that must be met.

### Validation & Security

Fluxzero integrates with Jakarta Validation. Additionally, security annotations are checked **before** the message
reaches any handler.

[//]: # (@formatter:off)
```java
@RequiresRole(Role.admin)
public record CreateProject(
    @NotNull ProjectId projectId,
    @Valid ProjectDetails details
) implements ProjectUpdate, Request<ProjectId> { ... }
```
[//]: # (@formatter:on)

---

<a name="web-handling"></a>

## Web & WebSocket Handling

### Web Requests

<a name="web-requests"></a>

Expose REST APIs using `@HandleGet`, `@HandlePost`, etc. API paths SHOULD start with `/api` because this is safest when
the backend also serves static content. Use a different base path only when explicitly requested.

### `@Path` Composition Rules

`@Path` values compose from outer to inner scope (package -> class -> method). Use these rules:

- A path starting with `/` resets the composed path.
- An empty `@Path` segment uses the simple package/class name.
- Method-level `@Path` appends to class-level `@Path` unless it starts with `/`.

```java
@Path("/api")
public class ProjectsEndpoint {

    @Path("projects")
    @HandleGet
    List<Project> list() { ... } // -> /api/projects

    @Path("/health")
    @HandleGet
    String health() { ... } // -> /health (reset)
}
```

[//]: # (@formatter:off)
```java
@Component
@Path("/api/projects")
public class ProjectsEndpoint {
    @HandlePost
    ProjectId createProject(ProjectDetails details) {
        var id = Fluxzero.generateId(ProjectId.class);
        Fluxzero.sendCommandAndWait(new CreateProject(id, details));
        return id;
    }
}
```
[//]: # (@formatter:on)

### Route Matching Rules

`@Handle...` paths support literal segments, `{name}` path parameters, `{name:regex}` constrained parameters, and `*`
wildcards.

- A non-final `*` matches within one path segment, for example `/api/meters/*/readings`.
- A final `*` matches the rest of the path and is mainly useful for static or SPA fallback routes.
- Optional path fragments use square brackets, for example `/api/users[/{id}]` matches both `/api/users` and
  `/api/users/42`.
- Trailing slashes on non-root paths are ignored, so `/users` and `/users/` match the same route.
- If multiple handlers match, Fluxzero selects the most specific route. Literal segments win over path parameters,
  constrained parameters win over plain parameters, and wildcard/catch-all routes are treated as fallbacks.
- Example order for `/api/projects/active`: `/api/projects/active`, then `/api/projects/{id:[a-z]+}`, then
  `/api/projects/{id}`, then `/api/projects/*`.

### Automatic `HEAD` and `OPTIONS`

Fluxzero can derive HTTP helpers from route declarations:

- `HEAD` may use the matching `GET` handler when no explicit `@HandleHead`, `@HandleWeb(method = "HEAD")`, or `ANY`
  route matches. The response keeps status and headers, but has no body.
- `OPTIONS` may return `204 No Content` with an `Allow` header when no explicit `@HandleOptions`,
  `@HandleWeb(method = "OPTIONS")`, or `ANY` route matches.
- Explicit handlers win, including wildcard handlers in another handler class in the same application.
- In multi-service setups, disable generated helpers with `autoHead = false` and/or `autoOptions = false` on routes
  that should leave `HEAD` or `OPTIONS` to another application.
- If requests enter through `fluxzero-proxy`, configured and allowed CORS preflight requests are answered by the proxy
  before they reach the runtime; automatic `OPTIONS` only applies to forwarded `WebRequest`s.

### API Documentation and OpenAPI

Fluxzero can extract a format-neutral `ApiDocCatalog` from web handlers and render it as OpenAPI 3.0.1 JSON.
OpenAPI 3.1 can be enabled with `OpenApiOptions` or `-Afluxzero.openapi.specVersion=3.1.0`.

- Prefer automatic inference from `@Handle...`, `@Path`, and web parameter annotations.
- Generated API docs are opt-in: only endpoints with `@ApiDoc` on a super-package/package, handler class, or handler
  method are included. Empty `@ApiDoc` is enough when all endpoint metadata can be inferred.
- Use `@ApiDoc` only for summaries, descriptions, operation ids, tags, operation security requirements, deprecation
  metadata, or schema hints that cannot be inferred.
  It may also document fields, parameters, record components, and type arguments such as
  `List<@ApiDoc(description = "Connection item") Connection>`; prefer this over OpenAPI-specific array annotations.
  For dependency-free schema metadata, use its optional `type`, `format`, `example`, `defaultValue`, `minimum`,
  `maximum`, `allowableValues`, `required`, and `implementation` attributes instead of Swagger `@Schema`.
- Use repeatable `@ApiDocResponse` annotations for additional status/error responses, or to describe an inferred
  response without repeating its body type. Use `ref = "error"` to reference `#/components/responses/error`.
- Use `@ApiDocExclude` to exclude package/class/method endpoints or model fields/record components/parameters from
  generated docs only; it does not disable runtime handling.
- Use `@ApiDocInfo` on a package or handler type for document-level metadata such as title, version, description,
  contact, logo, servers, top-level security requirements, shared components via `@ApiDocComponent`, and top-level
  vendor extensions. Prefer this over external Swagger configuration files.
- Set `@ApiDocInfo(serveOpenApi = true)` to expose the generated spec through an internal `@NoUserRequired` web
  endpoint. The default `openApiPath` is `openapi.json`, resolved relative to the `@Path` value on the same package or
  handler type; use an absolute path to serve from the application root.
- Set `@ApiDocInfo(serveApiReference = true)` to expose a small HTML API reference page for the same document. The
  default `apiReferencePath` is `docs`, resolved relative to the same `@Path`; enabling this also serves the OpenAPI
  JSON document. The default renderer is Redoc; `ApiReferenceRenderer.SCALAR` and `SWAGGER_UI` are also available.
  Renderer assets are referenced by URL and are not bundled by the SDK; use `apiReferenceScriptUrl` and
  `apiReferenceStylesheetUrl` for self-hosted assets.
- Jakarta validation annotations on endpoint parameters and model fields/record components are reflected in schemas
  where possible, including required flags, numeric bounds, sizes, patterns, and email format.
- Array properties in response models are required by default; array properties in input models must be made required
  explicitly with validation or `@ApiDoc(required = true)`.
- Render JSON with `OpenApiRenderer.render(...)`, `renderJson(...)`, or `renderPrettyJson(...)` and configure global
  title/version/servers with `OpenApiOptions`.
- When annotation processing is enabled, `OpenApiProcessor` generates `META-INF/fluxzero/openapi.json` for modules that
  contain web handlers opted in with `@ApiDoc`. Configure it with javac options like `-Afluxzero.openapi.title=...`,
  `-Afluxzero.openapi.version=...`, `-Afluxzero.openapi.servers=...`, `-Afluxzero.openapi.specVersion=3.1.0`, or
  disable it with `-Afluxzero.openapi.enabled=false`.
- If route paths depend on runtime `@Path` properties, use `ApiDocExtractor.extract(handlerInstance)` for exact runtime
  docs; the compile-time processor can only see static annotation values.

---

<a name="http-mapping"></a>

## HTTP Status Mapping

Fluxzero's `DefaultWebResponseMapper` automatically maps handler results and exceptions to HTTP status codes:

| Result / Exception            | HTTP Status                 |
|:------------------------------|:----------------------------|
| **Object** (non-null)         | `200 OK`                    |
| **null** (void)               | `204 No Content`            |
| `ValidationException`         | `400 Bad Request`           |
| `UnauthenticatedException`    | `401 Unauthorized`          |
| `UnauthorizedException`       | `401 Unauthorized`          |
| `FunctionalException` (other) | `403 Forbidden`             |
| `TimeoutException`            | `503 Service Unavailable`   |
| Any other `Throwable`         | `500 Internal Server Error` |

> You can always return a full `WebResponse` object if you need to override these defaults or set custom headers.

Automatically mapped web responses also perform best-effort content negotiation from the request `Accept` header:
regular objects support `application/json`, strings support `text/plain` and JSON strings, and `byte[]`/`InputStream`
support `application/octet-stream`. Explicit `WebResponse` `Content-Type` headers always win. If no supported
representation matches, keep the normal default instead of expecting `406 Not Acceptable`.

---

<a name="advanced-endpoints"></a>

## Advanced Endpoint Patterns

### Parameter Injection

Use annotations to inject specific parts of the HTTP request:

- **@PathParam**: Extracts values from the URL path template (e.g., `/api/users/{id}`).
- **@QueryParam**: Extracts values from the query string (e.g., `?name=Charlie`).
- **@HeaderParam**: Extracts values from HTTP headers.
- **@FormParam**: Extracts values from `application/x-www-form-urlencoded` bodies or `multipart/form-data` parts.
- **@BodyParam**: Extracts fields from a JSON request body.

For multipart uploads, use `@FormParam String` for text fields, `@FormParam byte[]` or `@FormParam InputStream` for file
contents, or `@FormParam WebFormPart` when the handler needs the filename, content type, or part headers.

These injected parameters can also use standard validation annotations directly, for example
`@PathParam @Positive Long id` or `@QueryParam @NotBlank String search`.

[//]: # (@formatter:off)
```java
@HandlePost("/{userId}/avatar")
void uploadAvatar(
    @PathParam UserId userId,
    @FormParam WebFormPart image,
    @HeaderParam("Content-Type") String contentType
) {
    // ...
}
```
[//]: # (@formatter:on)

[//]: # (@formatter:off)
```java
@HandleGet("/projects/{id}")
Project getProject(@PathParam @Positive Long id) {
    // ...
}
```
[//]: # (@formatter:on)

[//]: # (@formatter:off)
```java
@HandlePost("/bookings")
BookingId createBooking(
    @BodyParam HotelId hotelId,
    @BodyParam RoomId roomId,
    @BodyParam BookingDetails details
) {
    // JSON body contains hotelId, roomId and details
    // ...
}
```
[//]: # (@formatter:on)

---

<a name="serve-static"></a>

### @ServeStatic

Serves static assets (like a frontend) from the classpath.

Routing safety notes:

- `@ServeStatic` defaults to `resourcePath = "/static"` and `ignorePaths = "/api/*"` (from `ServeStatic` annotation
  defaults).
- This means static serving ignores `/api/...` by default, so API handlers under `/api` will not clash with static
  fallback routes.
- Recommended convention: keep HTTP APIs under `/api` and reserve non-`/api` paths for SPA/static routes.
- If you use a different API prefix, set `ignorePaths` explicitly.
- In Fluxzero Cloud, you can still expose a separate API host through proxy mapping (for example
  `api.domain.com -> domain.com/api`).

[//]: # (@formatter:off)
```java
@Component
@ServeStatic
public class UiEndpoint {
}
```
```java
@Component
@ServeStatic("/ui")
public class UiEndpoint {
}
```
[//]: # (@formatter:on)

<a name="websocket"></a>

### WebSocket

Use `@SocketEndpoint` for bi-directional communication. Unlike other components, these are often implemented as
`records` to hold the `SocketSession` state. The `@SocketEndpoint` handles session lifecycle events and message
processing, while typically being reached via a path like `/api/ws/...`.

Non-socket handlers on the same endpoint, such as `@HandleEvent`, can also use `@Association` to route only to
matching open endpoint instances. Parameter-level `@Association` works here too and uses the resolved parameter value.

[//]: # (@formatter:off)
```java
@SocketEndpoint
@Path("/api/ws/notifications")
public record NotificationSocket(SocketSession session) {
    @HandleSocketOpen
    static NotificationSocket onOpen(SocketSession session) {
        return new NotificationSocket(session);
    }

    @HandleSocketMessage
    void onMessage(String message) {
        Fluxzero.publishEvent(new UserMessage(message));
    }

    @HandleNotification
    void onNotification(CreateOrder event) {
        session.send(event);
    }
}
```
[//]: # (@formatter:on)

---

### Handling Multiple Payloads

<a name="multiple-payloads"></a>

You can use a single handler method to listen to multiple payload types using the `allowedClasses` attribute. This is
particularly useful for generic actions (e.g., deleting a stateful saga if any of several events happen).

When using multiple classes, the payload is typically not injected as a parameter.

```java
@HandleEvent(allowedClasses = {OrderCancelled.class, OrderExpired.class})
void onOrderFinished() {
    // Logic to handle any of the specified event types
}
```

The same works for resolved parameters such as `@Trigger @Association("orderId") SendOrder command`.

---

<a name="handler-interceptors"></a>

### Handler Interceptors

Handler interceptors wrap around the **execution of a handler method**.

- **Typical Use Cases**: Custom validation, auditing, or modifying handler results.
- **Out-of-the-box**: Fluxzero provides many interceptors automatically, such as logging and standard validation.
- **Registration**: `builder.addHandlerInterceptor(interceptor)`.

```java
public class ValidationInterceptor implements HandlerInterceptor {
    @Override
    public Function<DeserializingMessage, Object> interceptHandling(
            Function<DeserializingMessage, Object> next, HandlerInvoker invoker) {
        return message -> {
            // Logic before handler
            Object result = next.apply(message);
            // Logic after handler
            return result;
        };
    }
}
```

---

<a name="routing-keys"></a>

### Routing Keys (Client-side Filtering)

When a handler is configured with `ignoreSegment = true` (on a `@Consumer`), it receives messages from all segments. You
can use `@RoutingKey("propertyX")` on the **handler method** to perform client-side filtering.

- **Effect**: The handler will only be invoked if the message's routing key (or a property in metadata/payload) matches
  the specified value.
- **Stateful Handlers**: For `@Stateful` handlers, the **Saga ID** is used automatically to decide whether to handle a
  message; `@RoutingKey` is not required on the method in this case.

For more on how segments are assigned, see [Sending: Routing Keys](sending.md#routing-keys).

---

<a name="retroactive-updates"></a>

### Retroactive Updates

If you need to retroactively update a collection of documents or handle errors in historical data, you can use the
Replay mechanism. See [Tracking: Replays](tracking.md#replays) for more details.

---

<a name="identifiers"></a>

## Identifiers

Fluxzero uses strongly typed identifiers.

[//]: # (@formatter:off)
```java
public class ProjectId extends Id<Project> {
    public ProjectId(String id) {
        super(id);
    }
}
```
[//]: # (@formatter:on)

**Important**: Always use `Fluxzero.generateId(ProjectId.class)` to create new IDs.

---

<a name="common-pitfalls"></a>

## Common Pitfalls

- **Infrastructure in Handlers**: Don't build 'services' or use SQL. Use queries or load entities directly.
- **Aggregates Handling Messages**: Aggregates should be kept as "dumb" immutable state holders.
