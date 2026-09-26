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

## Internal Messages

### Commands

Commands trigger domain behavior and state changes.

| Method                      | Return Type               | Description                                                |
|:----------------------------|:--------------------------|:-----------------------------------------------------------|
| `sendCommandAndWait(cmd)`   | `T` (result)              | Blocks until processed. Returns the result of the handler. |
| `sendCommand(cmd)`          | `CompletableFuture<T>`    | Dispatches asynchronously.                                 |
| `sendAndForgetCommand(cmd)` | `CompletableFuture<Void>` | Dispatches and returns when guarantee is met.              |

**Example: Blocking Send**

```kotlin
val id: UserId = Fluxzero.sendCommandAndWait(CreateUser("Charlie"))
```

### Queries

Queries are for read-only data retrieval.

**Example: Async Query**

```kotlin
val result: CompletableFuture<UserProfile> = 
    Fluxzero.query(GetUserProfile(UserId("user123")))
```

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

```kotlin
Fluxzero.publish("my-topic", MyCustomPayload(...))
```

For advanced isolation, you can publish via a dedicated custom gateway and set topic-specific retention:

```kotlin
Fluxzero.get()
    .customGateway("third-party-events")
    .sendAndForget(AuditEntry("User login"))

Fluxzero.get()
    .customGateway("third-party-events")
    .setRetentionTime(Duration.ofDays(90))
```

---

<a name="specialized-messages"></a>

## Specialized Messages

Fluxzero provides gateways for publishing uncommon system messages.

### Metrics, Errors, and Results

```kotlin
// Publishing a custom metric
Fluxzero.publishMetrics(CustomMetric("processing-time", 150))

// Publishing an error explicitly
Fluxzero.publishError(ErrorMessage("External service unavailable"))

// Publishing a result message
Fluxzero.publishResult(CommandResult(commandId, resultPayload))
```

---

<a name="schedules"></a>

## Schedules

Use schedules to trigger schedule messages in the future or periodically.
Prefer `ScheduleId.of(type, id)` when different schedule categories can share a domain ID, and reuse the same typed
value for scheduling, lookup and cancellation. Fluxzero persists its stable `type:id` representation.

**Example: One-off Schedule**

```kotlin
Fluxzero.schedule(
    TerminateAccount(userId),
    "AccountClosed-$userId",
    Duration.ofDays(30)
)
```

**Example: Scheduling a Command**

```kotlin
Fluxzero.scheduleCommand(
    ArchiveProject(projectId),
    "Archive-$projectId",
    Fluxzero.currentTime().plus(10, ChronoUnit.DAYS)
)
```

> ⚠️ **Best Practice**: Always use `Fluxzero.currentTime()` for scheduling or logic requiring the current time. This
> ensures your code is deterministic and testable via the `TestFixture`.

<a name="periodic-schedules"></a>

### @Periodic Schedules

For recurring tasks, use the `@Periodic` annotation. It can be placed on the schedule payload or the handler method.

**Payload-based:**

```kotlin
@Periodic(delay = 5, timeUnit = TimeUnit.MINUTES)
data class PollService()
```

**Cron-based:**

```kotlin
@Periodic(cron = "0 0 * * MON", timeZone = "Europe/Amsterdam")
data class WeeklyReport()
```

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

```kotlin
val scheduleId = "AccountClosed-$userId"
// Cancel using the ID provided during scheduling
Fluxzero.cancelSchedule(scheduleId)
```

**Inside a Handler:**
To stop a periodic schedule from within its own handler, throw a `CancelPeriodic` exception.

```kotlin
@HandleSchedule
fun onSchedule(schedule: RefreshData) {
    if (noMoreData) {
        throw CancelPeriodic()
    }
}
```

<a name="web-sending"></a>

## External Web Requests

The SDK message gateway decompresses gzip responses before typed deserialization, including in async fixtures.
Use `response.getPayloadAs<MyReply>(MyReply::class.java)` normally. Decoded responses omit `Content-Encoding` and
update any `Content-Length` to the uncompressed byte length; the published wire response is unchanged.

Streamed responses arrive as bytes, including JSON bodies; use `getPayloadAs(...)` to convert them. Local gzip
streams decode on body-value conversion. Requesting `InputStream` or `Object` does not consume the stream;
the caller still owns its one-shot consumption and closure. Empty HEAD/304 bodies are not decompressed.

Use `WebRequest` to interact with external HTTP APIs.

**Example: POST to External API**

```kotlin
val response: WebResponse = Fluxzero.sendWebRequestAndWait(
    WebRequest.post(ApplicationProperties.require("stripe.url"))
              .payload(paymentDetails)
              .build()
)

if (response.isSuccess) {
    val stripeId: String = response.getPayloadAs(String::class)
}
```

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
    - **Aggregate ID**: For events applied to an aggregate that do not have an explicit `@RoutingKey`, the **Aggregate
      ID** is used automatically.
    - **Default**: If no key is found, a random segment is assigned (no ordering guarantees).

```kotlin
data class CreateOrder(
    @RoutingKey val orderId: OrderId,
    // ...
) : Request<OrderId>
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

```kotlin
class CorrelationInterceptor : DispatchInterceptor {
    override fun interceptDispatch(message: Message, type: MessageType, topic: String?): Message {
        // Inject a correlation ID into metadata if missing
        return message.withMetadata("correlation-id", UUID.randomUUID().toString())
    }
}
```

## Delivery defaults and completion

`Guarantee.DEFAULT` applies to publication, results/HTTP responses, WebSocket messages/ping/close,
`indexAndForget`, bulk `executeAndForget`, schedule cancellation, low-level scheduling defaults, and retention.
SDK 1.x resolves it to `NONE`; SDK 2.x resolves it to `STORED`, independently of `fluxzero.defaults.version`.
Override this with `fluxzero.publishing.defaultGuarantee` (`FLUXZERO_PUBLISHING_DEFAULT_GUARANTEE`), accepting
`NONE`, `SENT`, or `STORED`. Despite its historic name, the property applies beyond publication. The standard builder
resolves its own application source before creating components; namespace views inherit that policy. Direct
WebSocket client calls use the immutable policy from `ClientConfig.fromProperties(source)`.
SDK 1.x retains its synchronous key-value/scheduling convenience APIs and the existing concrete guarantees on
ordinary indexing, document maintenance, log truncation, and tracker disconnection. Internal persistence and metrics
also retain their explicit guarantees. If an app previously selected STORED through defaults date 2026.09.25,
configure `fluxzero.publishing.defaultGuarantee=STORED` explicitly to retain that choice on 1.x.
Explicit concrete guarantees remain unchanged. `DEFAULT` is an SDK choice, never a wire value.

Publication convenience calls return after dispatch/local handling, without waiting for each remote storage acknowledgement.
The default consumer's `awaitSendAndForgetFutures=true` waits for registered outgoing-command futures before committing
its position. A finished handler, a stored outgoing message, and a committed input position are separate boundaries.
This is at-least-once processing: a crash after publication but before position commit can repeat side effects;
use idempotent consumers. Storage acknowledgement does not mean that a downstream handler finished.

Outgoing commands within a batch can overlap their acknowledgements; independent trackers can keep processing while
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
`awaitSendAndForgetFutures=false` deliberately opts out of the outgoing-command barrier.

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
message into the Runtime. Request/response calls keep their response-completion contract. Metrics/transport diagnostics, internal durable persistence and position-store operations retain their explicit
guarantees. Low-level WebSocket client methods resolve DEFAULT from their client configuration; raw protocol
request objects require concrete guarantees.
