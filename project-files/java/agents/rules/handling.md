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

Payloads are typically implemented as immutable `records`. This is where you define the data required for an operation
and the constraints that must be met.

### Validation & Security

Fluxzero integrates with Jakarta Validation. Additionally, security annotations are checked **before** the message
reaches any handler.

```java
// @formatter:off
@RequiresRole(Role.admin)
public record CreateProject(
    @NotNull ProjectId projectId,
    @NotBlank @Size(max = 100) String name,
    @Valid ProjectDetails details
) implements Request<ProjectId> {}
// @formatter:on
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

```java
// @formatter:off
@TrackSelf
@Consumer(name = "user-update")
public interface UserUpdate {
    @NotNull
    UserId userId();

    @HandleCommand
    default UserProfile handle() {
        return Fluxzero.loadAggregate(userId())
                .assertAndApply(this)
                .get();
    }
}
// @formatter:on
```

**Passive Listening**: Use `@HandleCommand(passive = true)` to listen to commands without returning a result. This is
useful for auditing or logging without interfering with the primary command flow.

**Example: Standalone Command Handler**

Used for actions that don't directly target an aggregate's state (e.g., sending an external notification).

```java
// @formatter:off
@Component
class EmailHandler {
    @HandleCommand
    void handle(SendWelcomeEmail command) {
        // Logic to trigger email via an external gateway
    }
}
// @formatter:on
```

### @HandleQuery

<a name="handlequery"></a>

Used for read-only requests. Usually self-handling. Queries should implement `Request<T>` to define the return type (
though not mandatory).

**Example: Self-Handling Query**

```java
// @formatter:off
public record GetUserProfile(@NotNull UserId userId) implements Request<UserProfile> {
    @HandleQuery
    UserProfile handleQuery() {
        return Fluxzero.loadAggregate(userId).get();
    }
}
// @formatter:on
```

**Example: Standalone Query Handler**

Queries can also be handled in a separate component. Adding `@LocalHandler` ensures the query is handled synchronously
in the publication thread. Without `@LocalHandler`, a standalone handler defaults to **tracking** (asynchronous).

```java
// @formatter:off
@Component
@LocalHandler
class UserQueryHandler {
    @HandleQuery
    UserProfile handle(GetUserProfile query) {
        return Fluxzero.loadAggregate(query.userId()).get();
    }
}
// @formatter:on
```

<a name="events-notifications"></a>

### Handling Events & Notifications

Events are handled asynchronously after a command is applied or when an event is published explicitly via
`Fluxzero.publishEvent(...)`.

#### @HandleEvent

Used for side effects like sending emails or updating secondary projections within a specific context.

```java
// @formatter:off
@Component
class AnalyticsHandler {
    @HandleEvent
    void handle(CreateOrder event) {
        // Asynchronous logic
    }
}
// @formatter:on
```

#### @HandleNotification

Enables handling ALL events of a filtered type across all message segments. This is often used for global statistics
collection or broadcasting updates over WebSockets.

```java
// @formatter:off
@Component
class GlobalStatsHandler {
    @HandleNotification
    void handle(CompletePayment event) {
        // Collect statistics globally
    }
}
// @formatter:on
```

<a name="specialized-handlers"></a>

### Specialized Handlers

Fluxzero supports handling a variety of specialized message types.

#### @HandleSchedule

Used to handle scheduled messages (unless you are scheduling a command, which is handled via `@HandleCommand`).

```java
// @formatter:off
@HandleSchedule
void onSchedule(TerminateAccount schedule) {
    // Logic to execute when the schedule triggers
}
// @formatter:on
```

#### @HandleCustom

Used to subscribe to custom topics.

```java
// @formatter:off
@HandleCustom("my-topic")
void onCustomMessage(MyCustomPayload payload) {
    // Handle messages from the specified topic
}
// @formatter:on
```

#### @HandleDocument

Handles updates to specific document types. This handler receives a notification whenever a document has been added or
modified in the search index.

> **Nuance**: There is no guarantee that every intermediate update is received; the handler will always receive the last
> known state of the document.

For information on how to retroactively update a collection of documents, see
the [Retroactive Updates](#retroactive-updates) section in this manual.

```java
// @formatter:off
@HandleDocument(OrderDocument.class)
void onOrderDocument(OrderDocument document) {
    // Handle document-related changes
}
// @formatter:on
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

```java
// @formatter:off
@HandleMetrics
void onMetrics(HostMetrics metrics) {
    // Collect or process system metrics
}

@HandleError
void onError(ErrorMessage error, @Trigger CreateOrder failedCommand) {
    // Handle domain or system errors
    // failedCommand contains the original payload that caused the error
}
// @formatter:on
```

<a name="results-responses"></a>

#### @HandleResult & @HandleWebResponse

Used to handle the outcomes of asynchronous requests.

```java
// @formatter:off
@HandleResult
void onResult(CommandResult result) {
    // Handle the result of a previously sent command
}

@HandleWebResponse
void onWebResponse(WebResponse response) {
    // Handle the response from an external web request
}
// @formatter:on
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

```java
// @formatter:off
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
// @formatter:on
```

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

---

<a name="advanced-endpoints"></a>

## Advanced Endpoint Patterns

### Parameter Injection

Use annotations to inject specific parts of the HTTP request:

- **@PathParam**: Extracts values from the URL path template (e.g., `/api/users/{id}`).
- **@QueryParam**: Extracts values from the query string (e.g., `?name=Charlie`).
- **@HeaderParam**: Extracts values from HTTP headers.
- **@FormParam**: Extracts values from `application/x-www-form-urlencoded` or `multipart/form-data` bodies.

```java
// @formatter:off
@HandlePost("/{userId}/avatar")
void uploadAvatar(
    @PathParam UserId userId, 
    @FormParam byte[] imageData,
    @HeaderParam("Content-Type") String contentType
) {
    // ...
}
// @formatter:on
```

---

<a name="serve-static"></a>

### @ServeStatic

Serves static assets (like a frontend) from the classpath. By default, it serves files from the `static` directory on
the classpath for any GET request that does not match an API route (starting with `/api`).

```java
// @formatter:off
@Component
@ServeStatic
public class UiEndpoint {
}
// @formatter:on
```

<a name="websocket"></a>

### WebSocket

Use `@SocketEndpoint` for bi-directional communication. Unlike other components, these are often implemented as
`records` to hold the `SocketSession` state. The `@SocketEndpoint` handles session lifecycle events and message
processing, while typically being reached via a path like `/api/ws/...`.

```java
// @formatter:off
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
// @formatter:on
```

---

### Handling Multiple Payloads

<a name="multiple-payloads"></a>

You can use a single handler method to listen to multiple payload types using the `allowedClasses` attribute. This is
particularly useful for generic actions (e.g., deleting a stateful saga if any of several events happen).

When using multiple classes, the payload is typically not injected as a parameter.

```java

@HandleEvent(allowedClasses = {OrderCancelled.class, OrderExpired.class})
void onOrderFinished(@Association String orderId) {
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

<a name="identifiers"></a>

## Identifiers

Fluxzero uses strongly typed identifiers.

```java
// @formatter:off
public class ProjectId extends Id<Project> {
    public ProjectId(String id) {
        super(id);
    }
}
// @formatter:on
```

**Important**: Always use `Fluxzero.generateId(ProjectId.class)` to create new IDs.

---

<a name="common-pitfalls"></a>

## Common Pitfalls

- **Infrastructure in Handlers**: Don't build 'services' or use SQL. Use queries or load entities directly.
- **Aggregates Handling Messages**: Aggregates should be kept as "dumb" immutable state holders.
