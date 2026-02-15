# Message Handling

In Fluxzero, every interaction—be it a domain command, a query, or a web request—is a message. This unified approach
eliminates infrastructure boilerplate and ensures your logic is consistent, testable, and scalable.

---

## Quick Navigation

- [Core Rules](#core-rules)
- [The Anatomy of a Message](#message-anatomy)
- [Defining Payloads (Commands & Queries)](#payloads)
- [Handling Messages](#handling-messages)
    - [@HandleCommand (Self-Handling & Standalone)](#handlecommand)
    - [@HandleQuery](#handlequery)
    - [Handling Events & Notifications](#events-notifications)
    - [Specialized Handlers](#specialized-handlers)
        - [@HandleSchedule](#handleschedule)
        - [@HandleCustom](#handlecustom)
        - [@HandleDocument](#handledocument)
        - [@HandleMetrics / @HandleError](#metrics-errors)
        - [@HandleResult / @HandleWebResponse](#results-responses)
    - [Routing Keys (Client-side Filtering)](#routing-keys)
    - [Handler Types (Tracking vs. Local)](#handler-types)
    - [Handler Parameters](#handler-parameters)
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

<a name="core-rules"></a>

## Core Rules

1. **Logic First**: Business logic resides in handlers. Infrastructure (databases, persistence) is managed automatically
   by the Fluxzero runtime.
2. **Naming Convention**: Commands are imperative (`CreateUser`), Queries are descriptive (`GetUserProfile`). Events
   reflect facts and are typically the action payload (`CreateUser`).
3. **Dumb Aggregates**: Aggregates are immutable state holders. They do not handle messages themselves. Use
   self-handling commands or standalone handlers instead.
4. **Deterministic Handling**: Do not use non-deterministic lookups. Inject `Instant` for message time.
5. **No Infrastructure in Handlers**: Handlers should focus on domain logic or message routing. Never use SQL or build
   traditional "services".
6. **Method Precedence**: When multiple handler methods match a message, the most specific one (matching the payload
   type exactly) wins.
7. **Multiple Handlers**: A message can be handled by multiple independent handlers. Each handler will process the
   message once.
8. **Eventual Consistency (The Consistency Window)**: `sendCommandAndWait` ensures the command was handled and stored,
   but **not** necessarily that side-effects (like search index updates) have completed. Use async patterns (e.g.,
   WebSockets) to update the UI when side-effects finish.

---

<a name="message-anatomy"></a>

## The Anatomy of a Message

In Fluxzero, a **Message** is the fundamental unit of work. When you send a payload (like a Command or Query), it is
automatically wrapped in a rich message envelope.

### Local Message Structure

Before a message leaves your application, it contains:

- **Payload**: The actual domain object (e.g., `CreateOrder`).
- **Metadata**: Key-value pairs providing context (e.g., the `Sender` user, correlation IDs, or tracing info).
- **Message ID**: A unique identifier for this specific message instance.
- **Timestamp**: An `Instant` representing when the message was created.

### Serialized Message Structure

When a message is sent to the remote Fluxzero runtime, it is transformed into a **SerializedMessage**, which adds
infrastructure-level details:

- **Message Index**: A unique, sequential `long` assigned by the runtime.
- **Routing Segment**: An `int` used for distribution and ordering. Fluxzero uses **consistent hashing** to assign
  segments.
- **Routing Key**: A value extracted from a payload property (marked with `@RoutingKey`) or metadata, used to calculate
  the segment. This ensures that related messages (e.g., for the same Order ID) are always processed in the correct
  order by the same handler instance.
- **Source & Target**: Routing information identifying the originating and destination consumers.

---

<a name="payloads"></a>

## Defining Payloads (Commands & Queries)

Payloads are typically implemented as immutable `data classes`. This is where you define the data required for an
operation and the constraints that must be met.

### Validation & Security

Fluxzero integrates with Jakarta Validation. Additionally, security annotations are checked **before** the message
reaches any handler.

```kotlin
@RequiresRole(Role.admin)
data class CreateProject(
    @field:NotNull val projectId: ProjectId,
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Valid val details: ProjectDetails
) : Request<ProjectId>
```

---

## Handling Messages

### @HandleCommand

<a name="handlecommand"></a>

Used for messages that intend to change state.

**Example: Self-Handling Command (Interface Pattern)**

Recommended for updates to aggregates. Combined with `@TrackSelf` to ensure asynchronous tracking. The `@Consumer`
annotation creates an isolated named consumer, allowing this command type to be tracked and processed independently.
Commands may implement `Request<T>` if they return a value (e.g., the ID of a created entity).

#### Conventions & Shared Consumers

- **Naming**: Consumer names should typically follow the domain or aggregate name (e.g., `order-processing`,
  `user-update`).
- **Versioning**: If you make breaking changes to how a command is handled (not just the payload structure, which is
  handled by upcasters), you might want to increment the consumer name (e.g., `user-update-v2`) to trigger a fresh
  tracking of the stream.
- **Shared Consumers**: Multiple command interfaces *can* share the same `@Consumer(name=...)`. This means they will
  share the same tracker threads and be processed in strict order if they share segments. Use a shared consumer for
  related commands that must be processed sequentially relative to each other.

```kotlin
@TrackSelf
@Consumer(name = "user-update")
interface UserUpdate {
    @NotNull
    fun userId(): UserId

    @HandleCommand
    fun handle(): UserProfile {
        return Fluxzero.loadAggregate(userId())
            .assertAndApply(this)
            .get()
    }
}
```

**Passive Listening**: Use `@HandleCommand(passive = true)` to listen to commands without returning a result. This is
useful for auditing or logging without interfering with the primary command flow.

**Example: Standalone Command Handler**

Used for actions that don't directly target an aggregate's state (e.g., sending an external notification).

```kotlin
@Component
class EmailHandler {
    @HandleCommand
    fun handle(command: SendWelcomeEmail) {
        // Logic to trigger email via an external gateway
    }
}
```

### @HandleQuery

<a name="handlequery"></a>

Used for read-only requests. Usually self-handling. Queries should implement `Request<T>` to define the return type (
though not mandatory).

**Example: Self-Handling Query**

```kotlin
data class GetUserProfile(@field:NotNull val userId: UserId) : Request<UserProfile> {
    @HandleQuery
    fun handleQuery(): UserProfile {
        return Fluxzero.loadAggregate(userId).get()
    }
}
```

**Example: Standalone Query Handler**

Queries can also be handled in a separate component. Adding `@LocalHandler` ensures the query is handled synchronously
in the publication thread. Without `@LocalHandler`, a standalone handler defaults to **tracking** (asynchronous).

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

<a name="events-notifications"></a>

### Handling Events & Notifications

Events are handled asynchronously after a command is applied or when an event is published explicitly via
`Fluxzero.publishEvent(...)`.

#### @HandleEvent

Used for side effects like sending emails or updating secondary projections within a specific context.

```kotlin
@Component
class AnalyticsHandler {
    @HandleEvent
    fun handle(event: CreateOrder) {
        // Asynchronous logic
    }
}
```

#### @HandleNotification

Enables handling ALL events of a filtered type across all message segments. This is often used for global statistics
collection or broadcasting updates over WebSockets.

```kotlin
@Component
class GlobalStatsHandler {
    @HandleNotification
    fun handle(event: CompletePayment) {
        // Collect statistics globally
    }
}
```

<a name="specialized-handlers"></a>

### Specialized Handlers

Fluxzero supports handling a variety of specialized message types.

#### @HandleSchedule

Used to handle scheduled messages (unless you are scheduling a command, which is handled via `@HandleCommand`).

```kotlin
@HandleSchedule
fun onSchedule(schedule: TerminateAccount) {
    // Logic to execute when the schedule triggers
}
```

#### @HandleCustom

Used to subscribe to custom topics.

```kotlin
@HandleCustom("my-topic")
fun onCustomMessage(payload: MyCustomPayload) {
    // Handle messages from the specified topic
}
```

#### @HandleDocument

Handles updates to specific document types. This handler receives a notification whenever a document has been added or
modified in the search index.

> **Nuance**: There is no guarantee that every intermediate update is received; the handler will always receive the last
> known state of the document.

For information on how to retroactively update a collection of documents, see
the [Retroactive Updates](#retroactive-updates) section in this manual.

```kotlin
@HandleDocument(OrderDocument::class)
fun onOrderDocument(document: OrderDocument) {
    // Handle document-related changes
}
```

### Retroactive Updates

If you need to retroactively update a collection of documents or handle errors in historical data, you can use the
Replay mechanism. See [Tracking: Replays](tracking.md#replays) for more details.

<a name="metrics-errors"></a>

#### @HandleMetrics & @HandleError

Used for system monitoring and error handling.

`@HandleError` can be used to retroactively update earlier handler errors, acting similarly to a dead-letter queue (DLQ)
when needed. By using the `@Trigger` annotation on a parameter, you can inject the original payload that failed. For
more details, see the [Error Correcting](tracking.md#error-correcting) chapter.

```kotlin
@HandleMetrics
fun onMetrics(metrics: HostMetrics) {
    // Collect or process system metrics
}

@HandleError
fun onError(error: Throwable, @Trigger failedCommand: CreateOrder) {
    // Handle domain or system errors
    // failedCommand contains the original payload that caused the error
}
```

<a name="results-responses"></a>

#### @HandleResult & @HandleWebResponse

Used to handle the outcomes of asynchronous requests.

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

> **Note on WebResponse Mapping**: The `DefaultWebResponseMapper` is used to convert handler return values into standard
`WebResponse` objects, mapping exceptions (like `ValidationException`) to appropriate HTTP status codes.

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
- **Sender**: The user/system that sent the message.
- **Metadata**: Key-value pairs attached to the message.
- **Instant**: The message timestamp.
- **Entity<T> or T**: The current state of the entity. In `@HandleEvent`, the entity is automatically played back to
  reflect its state immediately after the event occurred.
- **@Autowired**: Standard Spring beans.

---

<a name="web-handling"></a>

## Web & WebSocket Handling

### Web Requests

<a name="web-requests"></a>

Expose REST APIs using `@HandleGet`, `@HandlePost`, etc. All API paths should start with `/api`.

```kotlin
@Component
@Path("/api/projects")
class ProjectsEndpoint {
    @HandlePost
    fun createProject(details: ProjectDetails): ProjectId {
        val id = Fluxzero.generateId(ProjectId::class.java)
        Fluxzero.sendCommandAndWait(CreateProject(id, details))
        return id
    }
}
```

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

---

<a name="advanced-endpoints"></a>

## Advanced Endpoint Patterns

### Parameter Injection

Use annotations to inject specific parts of the HTTP request:

- **@PathParam**: Extracts values from the URL path template (e.g., `/api/users/{id}`).
- **@QueryParam**: Extracts values from the query string (e.g., `?name=Charlie`).
- **@HeaderParam**: Extracts values from HTTP headers.
- **@FormParam**: Extracts values from `application/x-www-form-urlencoded` or `multipart/form-data` bodies.

```kotlin
@HandlePost("/{userId}/avatar")
fun uploadAvatar(
    @PathParam userId: UserId,
    @FormParam imageData: ByteArray,
    @HeaderParam("Content-Type") contentType: String
) {
    // ...
}
```

---

<a name="serve-static"></a>

### @ServeStatic

Serves static assets (like a frontend) from the classpath. By default, it serves files from the `static` directory on
the classpath for any GET request that does not match an API route (starting with `/api`).

```kotlin
@Component
@ServeStatic
class UiEndpoint
```

<a name="websocket"></a>

### WebSocket

Use `@SocketEndpoint` for bi-directional communication. Unlike other components, these are often implemented as
`data classes` to hold the `SocketSession` state. The `@SocketEndpoint` handles session lifecycle events and message
processing, while typically being reached via a path like `/api/ws/...`.

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

---

### Handling Multiple Payloads

<a name="multiple-payloads"></a>

You can use a single handler method to listen to multiple payload types using the `allowedClasses` attribute. This is
particularly useful for generic actions (e.g., deleting a stateful saga if any of several events happen).

When using multiple classes, the payload is typically not injected as a parameter.

```kotlin
@HandleEvent(allowedClasses = [OrderCancelled::class, OrderExpired::class])
fun onOrderFinished(@Association orderId: String) {
    // Logic to handle any of the specified event types
}
```

---

<a name="handler-interceptors"></a>

### Handler Interceptors

Handler interceptors wrap around the **execution of a handler method**.

- **Typical Use Cases**: Custom validation, auditing, or modifying handler results.
- **Out-of-the-box**: Fluxzero provides many interceptors automatically, such as logging and standard validation.
- **Registration**: `builder.addHandlerInterceptor(interceptor)`.

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

<a name="identifiers"></a>

## Identifiers

Fluxzero uses strongly typed identifiers.

```kotlin
class ProjectId(id: String) : Id<Project>(id)
```

**Important**: Always use `Fluxzero.generateId(ProjectId::class.java)` to create new IDs.

---

<a name="common-pitfalls"></a>

## Common Pitfalls

- **Infrastructure in Handlers**: Don't build 'services' or use SQL. Use queries or load entities directly.
- **Aggregates Handling Messages**: Aggregates should be kept as "dumb" immutable state holders.
