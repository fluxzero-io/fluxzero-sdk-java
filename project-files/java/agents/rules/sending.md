# Sending Messages

In Fluxzero, you interact with the system and external services by sending messages via static methods in the `Fluxzero`
interface.

---

## Quick Navigation

- [Core Rules](#core-rules)
- [Internal Messages](#internal-messages)
    - [Commands](#sending-commands)
    - [Queries](#sending-queries)
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

Use schedules to trigger schedule messages in the future or periodically.

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

Use `WebRequest` to interact with external HTTP APIs.

**Example: POST to External API**

[//]: # (@formatter:off)
```java
WebResponse response = Fluxzero.sendWebRequestAndWait(
    WebRequest.post(ApplicationProperties.require("stripe.url"))
              .payload(paymentDetails)
              .build()
);

if (response.isSuccess()) {
    String stripeId = response.getPayloadAs(String.class);
}
```
[//]: # (@formatter:on)

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
