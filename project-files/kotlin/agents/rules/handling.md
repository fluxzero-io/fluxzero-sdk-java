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
```kotlin
@TrackSelf
@Consumer(name = "user-update")
interface UserUpdate {
    @NotNull
    @RoutingKey
    fun userId(): UserId

    @HandleCommand
    fun handle(): UserProfile {
        return Fluxzero.loadAggregate(userId())
            .assertAndApply(this)
            .get()
    }
}
```
[//]: # (@formatter:on)

**Example: Creating, Updating, and Deleting Aggregates**

[//]: # (@formatter:off)
```kotlin
// 1. Create Aggregate
data class CreateProject(val projectId: ProjectId, @field:NotNull @field:Valid val details: ProjectDetails) : ProjectUpdate {
    @Apply
    fun apply(): Project {
        return Project(projectId = projectId, details = details)
    }
}

// 2. Update Aggregate
data class UpdateProjectDetails(val projectId: ProjectId, @field:NotNull @field:Valid val details: ProjectDetails) : ProjectUpdate {
    @Apply
    fun apply(project: Project): Project {
        return project.copy(details = details)
    }
}

// 3. Delete Aggregate
data class DeleteProject(val projectId: ProjectId) : ProjectUpdate {
    @Apply
    fun apply(project: Project): Project? {
        return null // Clears the aggregate value (but leaves the stored events)
    }
}
```
[//]: # (@formatter:on)

**Example: Creating, Updating, and Deleting Sub-Entities**

Sub-Entities can be added without modifying the parent aggregate directly. Fluxzero will take of updating the parent
aggregate's state automatically.

[//]: # (@formatter:off)
```kotlin
// 1. Create Sub-Entity (Task within Project)
data class CreateTask(val projectId: ProjectId, @field:NotNull val taskId: TaskId, @field:NotNull @field:Valid val details: TaskDetails) : ProjectUpdate {
    @Apply
    fun apply(): Task {
        return Task(taskId = taskId, details = details)
    }
}

// 2. Update Sub-Entity
data class UpdateTaskStatus(val projectId: ProjectId, @field:NotNull val taskId: TaskId, val completed: Boolean) : ProjectUpdate {
    @Apply
    fun apply(task: Task): Task {
        return task.copy(completed = completed)
    }
}

// 3. Delete Sub-Entity
data class RemoveTask(val projectId: ProjectId, @field:NotNull val taskId: TaskId) : ProjectUpdate {
    @Apply
    fun apply(task: Task): Task? {
        return null // Deletes the entity
    }
}
```
[//]: # (@formatter:on)

**Example: Standalone Command Handler**

Used for actions that don't directly target an aggregate's state (e.g., sending an external notification).

[//]: # (@formatter:off)
```kotlin
@Component
class EmailHandler {
    @HandleCommand
    fun handle(command: SendWelcomeEmail) {
        // Logic to trigger email via an external gateway
    }
}
```
[//]: # (@formatter:on)

### @HandleQuery

<a name="handlequery"></a>

Used for read-only requests. Usually self-handling. Queries MUST implement `Request<T>` to define the return type.

Prefer creating a dedicated query payload (with `@HandleQuery`) for data retrieval or computation instead of static utility methods. This keeps behavior explicit, reusable via messaging, and easy to test with `TestFixture`.

**Example: Full Query Implementation**

[//]: # (@formatter:off)
```kotlin
data class GetProjectDetails(@field:NotNull val projectId: ProjectId) : Request<Project> {
    @HandleQuery
    fun handleQuery(): Project {
        return Fluxzero.loadAggregate(projectId).get()
    }
}

// Usage:
val project = Fluxzero.query(GetProjectDetails(myId))
```
[//]: # (@formatter:on)

**Example: Self-Handling Query**

[//]: # (@formatter:off)
```kotlin
data class GetUserProfile(@field:NotNull val userId: UserId) : Request<UserProfile> {
    @HandleQuery
    fun handleQuery(): UserProfile {
        return Fluxzero.loadAggregate(userId).get()
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
```kotlin
@Component
@LocalHandler
class UserQueryHandler {
    @HandleQuery
    fun handle(query: GetUserProfile): UserProfile {
        return Fluxzero.loadAggregate(query.userId).get()
    }
}
```
[//]: # (@formatter:on)

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
```kotlin
@Component
class AnalyticsHandler {
    @HandleEvent
    fun handle(event: CreateOrder) {
        // Asynchronous logic
    }
}
```
[//]: # (@formatter:on)

#### @HandleNotification

Enables handling ALL events of a filtered type across all message segments. This is often used for global statistics
collection or broadcasting updates over WebSockets.

[//]: # (@formatter:off)
```kotlin
@Component
class GlobalStatsHandler {
    @HandleNotification
    fun handle(event: CompletePayment) {
        // Collect statistics globally
    }
}
```
[//]: # (@formatter:on)

<a name="specialized-handlers"></a>

### Specialized Handlers

Fluxzero supports handling a variety of specialized message types.

#### @HandleSchedule

Used to handle scheduled messages (unless you are scheduling a command, which is handled via `@HandleCommand`).

[//]: # (@formatter:off)
```kotlin
@HandleSchedule
fun onSchedule(schedule: TerminateAccount) {
    // Logic to execute when the schedule triggers
}
```
[//]: # (@formatter:on)

#### @HandleCustom

Used to subscribe to custom topics.

[//]: # (@formatter:off)
```kotlin
@HandleCustom("my-topic")
fun onCustomMessage(payload: MyCustomPayload) {
    // Handle messages from the specified topic
}
```
[//]: # (@formatter:on)

#### @HandleDocument

Handles updates to specific document types. This handler receives a notification whenever a document has been added or
modified in the search index.

> **Nuance**: There is no guarantee that every intermediate update is received; the handler will always receive the last
> known state of the document.

For information on how to retroactively update a collection of documents, see
the [Retroactive Updates](#retroactive-updates) section in this manual.

[//]: # (@formatter:off)
```kotlin
@HandleDocument(OrderDocument::class)
fun onOrderDocument(document: OrderDocument) {
    // Handle document-related changes
}
```
[//]: # (@formatter:on)

### Retroactive Updates

If you need to retroactively update a collection of documents or handle errors in historical data, you can use the
Replay mechanism. See [Tracking: Replays](tracking.md#replays) for more details.

#### @HandleError

Used for error monitoring or handling.

`@HandleError` can be used to retroactively update earlier handler errors, acting similarly to a dead-letter queue (DLQ)
when needed. By using the `@Trigger` annotation on a parameter, you can inject the original payload that failed. For
more details, see the [Error Correcting](tracking.md#error-correcting) chapter.

When testing `@HandleError` behavior, use an asynchronous fixture:
`TestFixture.createAsync(...)`.

[//]: # (@formatter:off)
```kotlin
@HandleError
fun onError(error: Throwable, @Trigger failedCommand: CreateOrder) {
    // Handle domain or system errors
    // failedCommand contains the original payload that caused the error
}
```
[//]: # (@formatter:on)

<a name="handlemetrics"></a>

#### @HandleMetrics

Used for monitoring metrics of all applications in a Fluxzero cluster.

[//]: # (@formatter:off)
```kotlin
@HandleMetrics
fun onMetrics(metrics: HostMetrics) {
    // Collect or process system metrics
}
```
[//]: # (@formatter:on)

<a name="results-responses"></a>

#### @HandleResult & @HandleWebResponse

Used to handle the outcomes of asynchronous requests. Very rarely used directly.

[//]: # (@formatter:off)
```kotlin
@HandleResult
fun onResult(result: CommandResult) {
    // Handle the result of a previously sent command
}

@HandleWebResponse
fun onWebResponse(response: WebResponse) {
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

Payloads are typically implemented as immutable `data classes`. This is where you define the data required for an
operation and the constraints that must be met.

### Validation & Security

Fluxzero integrates with Jakarta Validation. Additionally, security annotations are checked **before** the message
reaches any handler.

[//]: # (@formatter:off)
```kotlin
@RequiresRole(Role.admin)
data class CreateProject(
    @field:NotNull val projectId: ProjectId,
    @field:Valid val details: ProjectDetails
) : ProjectUpdate, Request<ProjectId>
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

```kotlin
@Path("/api")
class ProjectsEndpoint {

    @Path("projects")
    @HandleGet
    fun list(): List<Project> = TODO() // -> /api/projects

    @Path("/health")
    @HandleGet
    fun health(): String = "OK" // -> /health (reset)
}
```

[//]: # (@formatter:off)
```kotlin
@Component
@Path("/api/projects")
class ProjectsEndpoint {
    @HandlePost
    fun createProject(details: ProjectDetails): ProjectId {
        val id = Fluxzero.generateId(ProjectId::class)
        Fluxzero.sendCommandAndWait(CreateProject(id, details))
        return id
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

Fluxzero can extract a format-neutral `ApiDocCatalog` from web handlers and render it as OpenAPI 3.1 JSON.

- Prefer automatic inference from `@Handle...`, `@Path`, and web parameter annotations.
- Use `@ApiDoc` only for summaries, descriptions, operation ids, tags, or deprecation metadata that cannot be inferred.
- Use repeatable `@ApiDocResponse` annotations for additional status/error responses.
- Use `@ApiDocExclude` to exclude package/class/method endpoints from generated docs only; it does not disable runtime
  handling.
- Render JSON with `OpenApiRenderer.render(...)`, `renderJson(...)`, or `renderPrettyJson(...)` and configure global
  title/version/servers with `OpenApiOptions`.

---

<a name="http-mapping"></a>

## HTTP Status Mapping

Fluxzero's `DefaultWebResponseMapper` automatically maps handler results and exceptions to HTTP status codes:

| Result / Exception            | HTTP Status                 |
|:------------------------------|:----------------------------|
| **Object** (non-null)         | `200 OK`                    |
| **null**                      | `204 No Content`            |
| `ValidationException`         | `400 Bad Request`           |
| `UnauthenticatedException`    | `401 Unauthorized`          |
| `UnauthorizedException`       | `401 Unauthorized`          |
| `FunctionalException` (other) | `403 Forbidden`             |
| `TimeoutException`            | `503 Service Unavailable`   |
| Any other `Throwable`         | `500 Internal Server Error` |

> You can always return a full `WebResponse` object if you need to override these defaults or set custom headers.

Automatically mapped web responses also perform best-effort content negotiation from the request `Accept` header:
regular objects support `application/json`, strings support `text/plain` and JSON strings, and `ByteArray`/`InputStream`
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

For multipart uploads, use `@FormParam String` for text fields, `@FormParam ByteArray` or `@FormParam InputStream` for
file contents, or `@FormParam WebFormPart` when the handler needs the filename, content type, or part headers.

These injected parameters can also use standard validation annotations directly, for example
`@PathParam @Positive id: Long` or `@QueryParam @NotBlank search: String`.

[//]: # (@formatter:off)
```kotlin
@HandlePost("/{userId}/avatar")
fun uploadAvatar(
    @PathParam userId: UserId,
    @FormParam image: WebFormPart,
    @HeaderParam("Content-Type") contentType: String
) {
    // ...
}
```
[//]: # (@formatter:on)

[//]: # (@formatter:off)
```kotlin
@HandleGet("/projects/{id}")
fun getProject(@PathParam @Positive id: Long): Project {
    // ...
}
```
[//]: # (@formatter:on)

[//]: # (@formatter:off)
```kotlin
@HandlePost("/bookings")
fun createBooking(
    @BodyParam hotelId: HotelId,
    @BodyParam roomId: RoomId,
    @BodyParam details: BookingDetails
): BookingId {
    // JSON body contains hotelId, roomId and details
    TODO()
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
```kotlin
@Component
@ServeStatic
class UiEndpoint
```
```kotlin
@Component
@ServeStatic("/ui")
class UiEndpoint
```
[//]: # (@formatter:on)

<a name="websocket"></a>

### WebSocket

Use `@SocketEndpoint` for bi-directional communication. Unlike other components, these are often implemented as
`data classes` to hold the `SocketSession` state. The `@SocketEndpoint` handles session lifecycle events and message
processing, while typically being reached via a path like `/api/ws/...`.

Non-socket handlers on the same endpoint, such as `@HandleEvent`, can also use `@Association` to route only to
matching open endpoint instances. Parameter-level `@Association` works here too and uses the resolved parameter value.

[//]: # (@formatter:off)
```kotlin
@SocketEndpoint
@Path("/api/ws/notifications")
data class NotificationSocket(val session: SocketSession) {
    companion object {
        @HandleSocketOpen
        @JvmStatic
        fun onOpen(session: SocketSession): NotificationSocket {
            return NotificationSocket(session)
        }
    }

    @HandleSocketMessage
    fun onMessage(message: String) {
        Fluxzero.publishEvent(UserMessage(message))
    }

    @HandleNotification
    fun onNotification(event: CreateOrder) {
        session.send(event)
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

[//]: # (@formatter:off)
```kotlin
@HandleEvent(allowedClasses = [OrderCancelled::class, OrderExpired::class])
fun onOrderFinished() {
    // Logic to handle any of the specified event types
}
```
[//]: # (@formatter:on)

The same works for resolved parameters such as `@Trigger @Association("orderId") command: SendOrder`.

---

<a name="handler-interceptors"></a>

### Handler Interceptors

Handler interceptors wrap around the **execution of a handler method**.

- **Typical Use Cases**: Custom validation, auditing, or modifying handler results.
- **Out-of-the-box**: Fluxzero provides many interceptors automatically, such as logging and standard validation.
- **Registration**: `builder.addHandlerInterceptor(interceptor)`.

[//]: # (@formatter:off)
```kotlin
class ValidationInterceptor : HandlerInterceptor {
    override fun interceptHandling(
        next: Function<DeserializingMessage, Any>,
        invoker: HandlerInvoker
    ): Function<DeserializingMessage, Any> {
        return Function { message ->
            // Logic before handler
            val result = next.apply(message)
            // Logic after handler
            result
        }
    }
}
```
[//]: # (@formatter:on)

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
```kotlin
class ProjectId(id: String) : Id<Project>(id)
```
[//]: # (@formatter:on)

**Important**: Always use `Fluxzero.generateId(ProjectId::class)` to create new IDs.

---

<a name="common-pitfalls"></a>

## Common Pitfalls

- **Infrastructure in Handlers**: Don't build 'services' or use SQL. Use queries or load entities directly.
- **Aggregates Handling Messages**: Aggregates should be kept as "dumb" immutable state holders.
