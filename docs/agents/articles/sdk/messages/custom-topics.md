Use a custom topic when a durable message flow does not fit commands, queries, aggregate events, metrics, schedules, or

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
web requests and benefits from its own retention, replay, and consumer positions. Do not use a custom topic merely to
avoid defining the correct domain message type.

## Publish through the configured gateway

```java
record ExternalSignal(String sourceReference, String kind) {
}

GenericGateway gateway = Fluxzero.get().customGateway("external-signals");
gateway.sendAndForget(
        new ExternalSignal("source-42", "REFRESH"),
        Metadata.empty(),
        Guarantee.STORED);
```

Use a stable, deployment-independent topic name. `Guarantee.STORED` confirms durable append to the custom log; it does
not mean a consumer handled the message. Keep credentials and protected raw values out of the payload.

For a request/reply flow, implement `Request<R>` and call `gateway.send(...)` or `sendAndWait(...)`. Prefer ordinary
commands or queries when their semantics match; custom request/reply is for a deliberately isolated protocol.

## Handle the exact topic

```java
@Component
@Consumer(name = "external-signal-projection")
final class ExternalSignalHandler {
    @HandleCustom("external-signals")
    void on(ExternalSignal signal) {
        // Keep replayed effects idempotent.
    }
}
```

`@HandleCustom` requires the topic. It supports `passive` and `allowedClasses`, but a concrete payload parameter is
clearer than a broad zero-parameter handler. A Spring `@Component` is still required for ordinary standalone discovery;
`@Consumer` supplies the durable position.

## Test publication and handling separately

Use exact custom-message assertions for the publisher, then a handler scenario for consumption:

```java
fixture.whenCommand(trigger)
        .expectOnlyCustom("external-signals",
                new ExternalSignal("source-42", "REFRESH"));
```

A passing handler unit test does not prove that production publishes to the same topic.

## Retention, replay, and deletion

`DOCUMENT` and `CUSTOM` logs have topic-specific tracking positions. Read tracker-position management before inspecting
or resetting one. Read message-log retention before changing retention or truncating a custom topic. Truncation deletes
the log and its consumer positions; it is not a convenient test/reset operation.
