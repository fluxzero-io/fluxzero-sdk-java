# Sending Messages

In Fluxzero, you interact with the system and external services by sending messages via static methods in the `Fluxzero`
interface.

---

## Quick Navigation

- [Core Rules](#core-rules)
- [Internal Messages](#internal-messages)
    - [Commands](#sending-commands)
    - [Queries](#sending-queries)
- [Request Timeouts](#request-timeouts)
- [Custom Topics](#custom-topics)
- [Specialized Messages](#specialized-messages)
- [Schedules](#schedules)
    - [@Periodic Schedules](#periodic-schedules)
    - [Cancelling Schedules](#cancelling-schedules)
- [External Web Requests](#web-sending)
- [Routing Keys & Segments](#routing-keys)
- [Dispatch Interceptors](#dispatch-interceptors)

---

<a name="core-rules"></a>

## Core Rules

1. **Fire-and-Forget vs. Wait**:
    - Use `AndWait` methods when you need the result or want to ensure the message was processed before continuing. This
      is vital for back-pressure and ensuring messages are properly handled during tracking.
    - Use `AndForget` when the result is not of interest. In this case, a result message will not even be published. The
      future returns when the specified `Guarantee` is met.
2. **Wait is not an Anti-Pattern**: Blocking for a result is often the preferred way to interact, as it provides natural
   back-pressure. Asynchronous handling of results can lead to reliability issues (e.g., consumer moving on before
   command completion) if not handled with extreme care.
3. **No Direct Calls**: Never call handler methods directly. Always dispatch via `Fluxzero.send...` to ensure proper
   tracking, interceptors, and distribution.
4. **Guarantee Levels**: When sending commands, you can specify `Guarantee.STORED` if you need to be sure the message
   has reached the Fluxzero runtime persistent storage.

---

<a name="internal-messages"></a>

## Logical identity and transport envelopes

`Message` contains payload, metadata, message ID and timestamp. Source/target/request ID/log index belong to
`SerializedMessage`, not the logical Message. Redispatch preserves logical identity unless explicitly replaced,
but starts a new request envelope: local handling has no transport source; tracked requests use the sending
client's ID for response correlation. Blocking versus future-returning methods do not select local versus remote.
Never use a supplied source or message ID as authentication.

Use `incoming.withMessage(incoming.toMessage().withMessageId("new-logical-id"))` for an intentional identity
replacement. Complete raw serialized edits before deserializing; the wrapper lazily caches its logical Message
and does not synchronize later raw mutations. Re-deserialize after edits. Dispatch interceptors should return
logical replacements; serialized modification only affects serialized publication. Real native HTTP carries
headers/body, not a Fluxzero command/query envelope. Fixture HTTP stubs do not prove network behavior.

## Internal Messages

### Commands

Commands trigger domain behavior and state changes.

| Method                      | Return Type               | Description                                                |
|:----------------------------|:--------------------------|:-----------------------------------------------------------|
| `sendCommandAndWait(cmd)`   | `T` (result)              | Blocks until processed. Returns the result of the handler. |
| `sendCommand(cmd)`          | `CompletableFuture<T>`    | Dispatches asynchronously.                                 |
| `sendAndForgetCommand(cmd)` | `CompletableFuture<Void>` | Dispatches and returns when guarantee is met.              |

**Example: Blocking Send**

[//]: # (@formatter:off)
```java
UserId id = Fluxzero.sendCommandAndWait(new CreateUser("Charlie"));
```
[//]: # (@formatter:on)

### Queries

Queries are for read-only data retrieval.

**Example: Async Query**

[//]: # (@formatter:off)
```java
CompletableFuture<UserProfile> result = 
    Fluxzero.query(new GetUserProfile(new UserId("user123")));
```
[//]: # (@formatter:on)

---

<a name="request-timeouts"></a>

## Request Timeouts

Request/response sends have an effective timeout. `@Timeout` on the request payload applies to asynchronous sends and
blocking `AndWait` sends. If no timeout is configured, blocking `sendAndWait` keeps its 60 second wait behavior, while
asynchronous runtime requests use the request handler default.

The SDK stamps the effective timeout on request metadata so tracking handlers can identify stale indexed requests. See
[Metrics: Ignored Messages](metrics.md#ignored-messages) for how expired requests are skipped and reported.

Use handler-level `skipExpiredRequests` deliberately:

- Commands default to false so timed-out senders do not change command execution semantics.
- Queries and HTTP web handlers default to true because stale responses are usually not useful.
- Set it to false for replay/debug handlers that should inspect historical requests.

---

<a name="custom-topics"></a>

## Custom Topics

You can publish payloads to custom topics for specialized handling.

This is especially useful for second-rank or integration-heavy messages (for example external-source events) so the main
event log stays focused on core domain flow. This keeps future replay and debugging of core behavior cleaner.

[//]: # (@formatter:off)
```java
Fluxzero.publish("my-topic", new MyCustomPayload(...));
```
[//]: # (@formatter:on)

For advanced isolation, you can publish via a dedicated custom gateway and set topic-specific retention:

[//]: # (@formatter:off)
```java
Fluxzero.get()
    .customGateway("third-party-events")
    .sendAndForget(new AuditEntry("User login"));

Fluxzero.get()
    .customGateway("third-party-events")
    .setRetentionTime(Duration.ofDays(90));
```
[//]: # (@formatter:on)

---

<a name="specialized-messages"></a>

## Specialized Messages

Fluxzero provides gateways for publishing uncommon system messages.

### Metrics, Errors, and Results

[//]: # (@formatter:off)
```java
// Publishing a custom metric
Fluxzero.publishMetrics(new CustomMetric("processing-time", 150));

// Publishing an error explicitly
Fluxzero.publishError(new ErrorMessage("External service unavailable"));

// Publishing a result message
Fluxzero.publishResult(new CommandResult(commandId, resultPayload));
```
[//]: # (@formatter:on)

---

<a name="schedules"></a>

## Schedules

For delayed work owned by a Model, annotate the payload ID: `record RunReminder(@Parent ReminderId reminderId) {}`.
The parent must already be committed in the schedule's namespace. Direct deletion (`@Apply` returning `null`),
cascade deletion and hard erasure asynchronously cancel the schedule, without an application cleanup handler.
Use `new Schedule(payload, id, deadline).withParents(parentId)` for explicit ownership; `withParents()` opts out.
Any parent deletion suffices; null/non-owning references are ignored. A schedule is not a Model or Graph node.
Automatic periodic continuations preserve the original lifetime and cannot revive work after delete/recreate.
Explicit `withParents(...)` selects a fresh lifetime. Keep current-intent guards for already delivered work and status
or deadline changes. `ScheduleAutoCancelled` reports actual removal as best-effort payload-free metrics.
Assert `expectOnlyActiveScheduledCommands(...)` to check all active work, including schedules from Given.

Use schedules to trigger schedule messages in the future or periodically.
Prefer `ScheduleId.of(type, id)` when different schedule categories can share a domain ID, and reuse the same typed
value for scheduling, lookup and cancellation. Fluxzero persists its stable `type:id` representation.

**Example: One-off Schedule**

[//]: # (@formatter:off)
```java
Fluxzero.schedule(
    new TerminateAccount(userId),
    "AccountClosed-" + userId,
    Duration.ofDays(30)
);
```
[//]: # (@formatter:on)

**Example: Scheduling a Command**

[//]: # (@formatter:off)
```java
Fluxzero.scheduleCommand(
    new ArchiveProject(projectId),
    "Archive-" + projectId,
    Fluxzero.currentTime().plus(10, ChronoUnit.DAYS)
);
```
[//]: # (@formatter:on)

> ⚠️ **Best Practice**: Always use `Fluxzero.currentTime()` for scheduling or logic requiring the current time. This
> ensures your code is deterministic and testable via the `TestFixture`.

<a name="periodic-schedules"></a>

### @Periodic Schedules

For recurring tasks, use the `@Periodic` annotation. It can be placed on the schedule payload or the handler method.

**Payload-based:**

[//]: # (@formatter:off)
```java
@Periodic(delay = 5, timeUnit = TimeUnit.MINUTES)
public record PollService() {}
```
[//]: # (@formatter:on)

**Cron-based:**

[//]: # (@formatter:off)
```java
@Periodic(cron = "0 0 * * MON", timeZone = "Europe/Amsterdam")
public record WeeklyReport() {}
```
[//]: # (@formatter:on)

**Configuration Options:**

- `delay`: Fixed delay between executions.
- `cron`: Standard cron expression.
- `autoStart`: If `false`, the schedule won't start automatically on application startup.
- `continueOnError`: Whether to continue scheduling if a previous execution failed (default `true`).
- `delayAfterError`: Optional delay override if the last execution failed.

<a name="cancelling-schedules"></a>

### Cancelling Schedules

You may need to cancel a schedule if it is no longer relevant (e.g., a reminder for an order that was cancelled).

**Using Schedule ID:**

```java
String scheduleId = "AccountClosed-" + userId;
// Cancel using the ID provided during scheduling
Fluxzero.

cancelSchedule(scheduleId);
```

**Inside a Handler:**
To stop a periodic schedule from within its own handler, throw a `CancelPeriodic` exception.

```java

@HandleSchedule
void onSchedule(RefreshData schedule) {
    if (noMoreData) {
        throw new CancelPeriodic();
    }
}
```

<a name="web-sending"></a>

## External Web Requests

Use `WebRequest` and `WebRequestGateway` for external HTTP APIs. The default message/proxy route preserves
auditability and correlation and supports configurable retries and `TestFixture` assertions/stubs. Do not replace
it with a separate HTTP client for an ordinary integration.

The SDK message gateway decompresses gzip responses before typed deserialization, including in async fixtures.
Use `response.getPayloadAs(MyReply.class)` normally. Decoded responses omit `Content-Encoding` and update any
`Content-Length` to the uncompressed byte length; the published wire response is unchanged.

Fluxzero Auditlog masks standard credential headers such as `Authorization` and `X-Api-Key` case-insensitively as
`<value scrambled>` in visible records and Auditlog downloads. Authenticated requests therefore remain auditable
without showing these secrets. Resolve credentials through `ApplicationProperties` and use the API's required
header. This is visible-log masking, not deletion from the HTTP transport or a guarantee for arbitrary secret fields
or application-written logs.

Streamed responses arrive as bytes, including JSON bodies; use `getPayloadAs(...)` to convert them. Local gzip
streams decode on body-value conversion. Requesting `InputStream` or `Object` does not consume the stream;
the caller still owns its one-shot consumption and closure. Empty HEAD/304 bodies are not decompressed.


Pass `WebRequestSettings` to `Fluxzero.sendWebRequestAndWait(request, settings)` or the gateway's `send`/`sendAndWait`.
`timeout` bounds all attempts, `maxRetries` counts additional attempts (default zero), and `retryDelay` plus
`retryableStatusCodes` select retry behavior. Repeat writes only with appropriate idempotent semantics.

`useNativeHttpClient(true)` is an explicit direct-transport alternative in the same SDK API. It bypasses message
logging, local handlers, dispatch interceptors and consumer isolation; do not select it merely to hide credentials.
TestFixture still routes native-configured requests to remote stubs and applies retry counts/statuses without real
delays. Use fixture web assertions and absolute `@HandleGet`/`@HandlePost` handlers for ordinary integration tests.

### Give each external interaction a local command or query

Use a named command for an external action and a typed query (`Request<T>`) for an external read. Put the actual
`WebRequestGateway` call in the payload's `@HandleCommand` or `@HandleQuery` method. Other handlers invoke the
operation through `Fluxzero.sendCommandAndWait(...)` or `Fluxzero.queryAndWait(...)`, just like other application
behavior. Choose command versus query by the operation's meaning, not only its HTTP verb.

These self-handlers run locally by default. They need no `@TrackSelf`, `@Consumer`, `@Component`, extra
`@LocalHandler`, injected API-service bean, or explicit handler registration. Dispatch the payload; do not call its
`handle()` method directly.

The example partner API returns an order as JSON, with HTTP 200 for lookup and 201 for creation:

```java
public record GetPartnerOrder(@NotNull UUID orderId) implements Request<PartnerOrder> {
    @HandleQuery
    PartnerOrder handle() {
        var request = WebRequest.get("https://partner.example/api/orders/" + orderId)
                .header("Authorization", "Bearer " + ApplicationProperties.requireProperty("partner.api.token"))
                .build();
        var response = Fluxzero.sendWebRequestAndWait(request);
        if (response.getStatus() != 200) {
            throw new IllegalStateException("Order lookup returned HTTP " + response.getStatus());
        }
        return response.getPayloadAs(PartnerOrder.class);
    }
}

public record PlacePartnerOrder(@NotNull @Valid OrderDetails details) implements Request<PartnerOrder> {
    @HandleCommand
    PartnerOrder handle() {
        var request = WebRequest.post("https://partner.example/api/orders")
                .header("Authorization", "Bearer " + ApplicationProperties.requireProperty("partner.api.token"))
                .contentType("application/json")
                .body(details)
                .build();
        var response = Fluxzero.sendWebRequestAndWait(request);
        if (response.getStatus() != 201) {
            throw new IllegalStateException("Order placement returned HTTP " + response.getStatus());
        }
        return response.getPayloadAs(PartnerOrder.class);
    }
}

public record OrderDetails(@NotBlank String productCode, @Positive int quantity) {}
public record PartnerOrder(UUID orderId, String status) {}
```

```java
PartnerOrder existing = Fluxzero.queryAndWait(new GetPartnerOrder(orderId));
PartnerOrder placed = Fluxzero.sendCommandAndWait(
        new PlacePartnerOrder(new OrderDetails("book", 2)));
```

Shared URL construction, headers, settings and response mapping may live in a small helper, interface or base class.
Keep each operation recognizable as its own command/query; do not replace them with a generic HTTP-command envelope
or require callers to inject an API service. Resolve configuration through `ApplicationProperties` at this integration
boundary. If settings need parsing, a typed settings value can be loaded here without making it a bean. The fixed
example URLs stand in for a configured, validated endpoint; validate the configured endpoint before constructing requests.

A local command/query and its outgoing HTTP request have separate delivery rules. Local self-handling does **not**
select native HTTP: the nested `WebRequest` still uses the auditable proxy route and configured transport retries.
The local command/query itself is not a persisted job and has no independent tracker retry. Add `@TrackSelf` only
when that operation itself must arrive through the Runtime, have a durable consumer position, or be replayed/retried
independently. Add `@Consumer` only for a required tracking configuration. Neither is needed merely to call an API.

For an external write that depends on committed Model state, a registered post-commit event handler can dispatch the
local command and wait for its outcome. Let failures reach that caller's error/retry policy. Local dispatch does not
make HTTP part of the Model transaction: do not perform external I/O in `@Apply` or send the write from a mutation
handler while its changes are still pending. Read-only external queries may run from ordinary query/orchestration
handlers; their results are not replayable Model state until explicitly recorded.

---

<a name="routing-keys"></a>

## Routing Keys & Segments

Every message published to the Fluxzero runtime is assigned a **Segment** (a number from 0 to 127). This segment
determines which tracker (thread) will process the message, ensuring that related messages are handled sequentially by
the same tracker.

### How Segments are Assigned

1. **Consistent Hashing**: Fluxzero uses consistent hashing on a **Routing Key** to determine the segment.
2. **Routing Key Selection**:
    - **@RoutingKey**: You can annotate a field in your payload with `@RoutingKey`. The value of this field will be used
      to calculate the segment.
    - **Model routing**: `fluxzero.model.automaticRouting=true` (default from defaults version `2026.09.10`) adds a
      canonical Model-ID fallback for single statically unambiguous apply commands and single-Model events. Never
      choose an arbitrary target for multi-Model updates. Explicit segments and `@RoutingKey` declarations win,
      including missing values; do not blindly inherit an unknown command segment.
    - **Default**: If no key is found, a random segment is assigned (no ordering guarantees).

```java
public record CreateOrder(
        @RoutingKey OrderId orderId,
        // ...
) implements Request<OrderId> {
}
```

---

<a name="dispatch-interceptors"></a>

## Dispatch Interceptors

Dispatch interceptors allow you to hook into the **message publication phase**—just before a message leaves your
application or is handled locally.

- **Typical Use Cases**: Injecting metadata (correlation IDs), blocking/suppressing messages, or mutating payloads.
- **Out-of-the-box**: Fluxzero provides several interceptors automatically, such as logging and validation.
- **Registration**: `FluxzeroBuilder.addDispatchInterceptor(interceptor)`.
- **Tracing Tip**: Use `Metadata.withTrace(key, value)` to propagate trace values across chained messages. This writes
  entries as `$trace.<key>` automatically.
- **Message Type Tip**: `WebRequest`, `WebResponse`, and `Schedule` are also `Message` subtypes, so interceptors can
  detect and handle them directly when needed.

```java
public class CorrelationInterceptor implements DispatchInterceptor {
    @Override
    public Message interceptDispatch(Message message, MessageType type, String topic) {
        // Inject a correlation ID into metadata if missing
        return message.withMetadata("correlation-id", UUID.randomUUID().toString());
    }
}
```

## Reconcile Model schedules from current intent

A registered tracked post-commit `@HandleEvent` method whose only parameter is `Graph<Reminder>` receives direct and
cascaded reminder changes. Read `Fluxzero.loadCurrentGraph(change.id(), Reminder.class)` (Kotlin:
`Reminder::class.java`) to inspect current intent rather than the triggering event's older state. Use one stable
`ScheduleId.of("reminder", change.id())`: cancel when absent/completed; otherwise replace with the current deadline.
Use `@Consumer(singleTracker = true, ...)` for this reconciler and keep all writes to those schedule IDs there, so a
parent-routed cascade and a child-routed update do not race. Let failures reach tracked retry.

`ifAbsent = true` keeps an existing deadline; it does not replace stale work and is not a once-only marker after
cancellation. Guard the delivered command with the expected deadline/generation and current state as well: cancellation
cannot recall already delivered work. Use `@InterceptApply` to suppress stale/early work, keep replayed `@Apply`
deterministic, and use `Fluxzero.currentTime()` for evaluation. Scheduling is an eventual post-commit effect, not part of
the Model transaction. For historical `previous()` values, event sourcing is required; `DOCUMENT` alone has no versions.

## Delivery defaults and completion

`Fluxzero.publishEvent`, `publishEvents`, command/custom send-and-forget overloads, and error publication use
`Guarantee.DEFAULT`. Web-request and metrics publication can select it explicitly. The owning application resolves this before transport: absent or older
`fluxzero.defaults.version` keeps `NONE`; `2026.09.25` or later selects `STORED`. Configure
`fluxzero.publishing.defaultGuarantee` (`FLUXZERO_PUBLISHING_DEFAULT_GUARANTEE`) as `NONE`, `SENT`, or `STORED`
to override either direction. The standard builder resolves its own property source when creating gateways;
configure these properties before building the application (including a TestFixture's builder).
Explicit concrete guarantees remain unchanged. `DEFAULT` is an SDK choice, never a wire value.

Convenience calls return after dispatch/local handling, without waiting for each remote storage acknowledgement.
The default consumer's `awaitSendAndForgetFutures=true` waits for registered delivery futures before committing
its position. A finished handler, a stored outgoing message, and a committed input position are separate boundaries.
This is at-least-once processing: a crash after publication but before position commit can repeat side effects;
use idempotent consumers. Storage acknowledgement does not mean that a downstream handler finished.

Publications within a batch can overlap their acknowledgements; independent trackers can keep processing while
another awaits storage. A tracker drains its own batch before claiming the next one, preserving segment ownership
and bounding pending completion state to the batch. Delayed acknowledgements apply backpressure there. Failed or
cancelled delivery prevents that batch's position from advancing, subject to the configured consumer error policy.
Transport retry/reconnect behaviour is unchanged. Shutdown cannot make an unacknowledged publication durable;
an uncommitted input remains replayable.

With asynchronous handling, the completion scope includes the framework-managed handler invocation. If
`awaitAsyncResults=false`, work started only by a later continuation of an unawaited returned stage is outside that
boundary. Incomplete streamed messages and invocations queued behind them retain deferred completion, because their bodies
may require later input batches; they cannot hold the current chunk position until the whole stream finishes.
Arbitrary application-created background work is also outside the tracked scope. Setting
`awaitSendAndForgetFutures=false` deliberately opts out of the publication barrier.

Outside tracking there is no consumer-position barrier. To observe acknowledgement or asynchronous failure,
keep the returned future:

```java
CompletableFuture<Void> delivery = Fluxzero.get().eventGateway()
        .publish(new Message(event), Guarantee.DEFAULT);
delivery.join(); // Wait at an explicit application boundary, not once per event in a handler.
```

```kotlin
val delivery = Fluxzero.get().eventGateway().publish(Message(event), Guarantee.DEFAULT)
delivery.join()
```

Locally handled or suppressed messages retain their existing local behaviour; `STORED` does not force a local-only
message into the Runtime. Request/response calls keep their response-completion contract. Automatic responses,
metrics/transport diagnostics, scheduling, persistence, retention and position-management defaults retain their
operation-specific guarantees. For low-level clients/protocol requests use a concrete guarantee.
