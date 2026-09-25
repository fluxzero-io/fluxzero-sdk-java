Model commands with applicable `@Apply` methods are handled automatically. This article covers explicit payload
handlers for local operations or tracked orchestration, such as external integrations.

Use this article when command behavior is implemented on the command payload itself. Decide whether the command is local or tracked from the production delivery contract, not from whichever fixture happens to pass.

## Local self-handling commands

Use this as the default for a named external action invoked by another handler in the same application: the command's
`@HandleCommand` performs the `WebRequestGateway` call and returns/maps its outcome. No injected API-service bean,
`@Consumer` or `@TrackSelf` is needed for that interaction. The HTTP request retains its own gateway audit and retry
settings. See `/docs/sdk/web/outbound-requests` for a complete example and the post-commit boundary for external writes.

A command payload with `@HandleCommand` but no `@TrackSelf` is handled immediately in the sending Fluxzero process. It bypasses the command log and tracker infrastructure. This is useful for deliberately local composition, but it is not a production ingress handler for commands published by another application through the runtime.

```java
public record RecalculateQuote(QuoteId quoteId) {
    @HandleCommand
    void handle() {
        // Orchestrate the recalculation, then apply its Model changes.
        Fluxzero.assertAndApply(this);
    }
}
```

`TestFixture.create()` discovers a handler on the dispatched payload, so a synchronous test can pass even when no tracked consumer exists. That proves local dispatch only.

## Tracked self-handling commands

Add `@TrackSelf` when this application must consume the command from the runtime message log, survive process boundaries, participate in replay, or use tracker ordering. Give the handler a stable named `@Consumer` when its replay position and operational ownership should be explicit.

```java
@TrackSelf
@Consumer(name = "shipment-command")
public interface ShipmentUpdate {
    @RoutingKey
    ShipmentId shipmentId();

    @HandleCommand
    default Shipment handle() {
        return Fluxzero.<Shipment>loadGraph(shipmentId()).assertAndApply(this).get();
    }
}
```

With Spring, scanned `@TrackSelf` types are registered as tracked payload handlers; they are not ordinary injectable beans. Outside Spring, register the type explicitly. An asynchronous fixture can discover a dispatched `@TrackSelf` payload, but production still requires that the payload type is in the application's scan/registration scope.

## Prove the difference

Keep a normal synchronous domain test, then add one asynchronous proof when tracked ingress is part of the architecture.
Exercise an actual production payload implementing the tracked interface. A separate probe can supplement diagnostics,
but it cannot prove that `AdvanceShipment` itself has the right annotation and consumer.

```java
record AdvanceShipment(
        ShipmentId shipmentId,
        ShipmentStatus status) implements ShipmentUpdate {
    @Apply
    Shipment apply(Shipment current) {
        return current.withStatus(status);
    }
}

AdvanceShipment command = new AdvanceShipment(shipmentId, IN_TRANSIT);
AtomicReference<String> actualConsumer = new AtomicReference<>();
HandlerInterceptor captureActualPayload = (next, invoker) -> message -> {
    if (message.getPayload() instanceof AdvanceShipment) {
        actualConsumer.set(Tracker.current()
                .map(Tracker::getName)
                .orElse("local"));
    }
    return next.apply(message);
};

TestFixture.createAsync(DefaultFluxzero.builder()
                .addHandlerInterceptor(
                        captureActualPayload, MessageType.COMMAND))
        .givenCommands(createShipment)
        .whenCommand(command)
        .expectEvents(command)
        .expectThat(fc -> assertEquals(
                "shipment-command", actualConsumer.get()));
```

Use `TestFixture.create(...)` for deterministic domain rules and `createAsync(...)` for the separate claim that the
real command is tracked. Fixture auto-discovery proves the payload's tracked semantics, not that a production Spring
application scans its package; keep a package/registration smoke check for that boundary. Do not add `@TrackSelf`
merely to fix broad fixture registration or a zero-parameter handler collision; constrain handler matching instead.
