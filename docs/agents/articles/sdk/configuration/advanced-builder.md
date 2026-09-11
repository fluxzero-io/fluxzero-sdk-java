Use `DefaultFluxzero.builder()` only at application bootstrap. In Spring, apply these changes through one

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
`FluxzeroCustomizer` so auto-configuration, tests, and production use the same extension set.

## Custom parameter resolution and validation

Register a `ParameterResolver<? super DeserializingMessage>` when a handler method needs application context that is
not already supplied by payload, metadata, time, user, entity, schedule, web request, socket session, or Spring bean
injection:

```java
builder.addParameterResolver(new TenantContextResolver());
```

The resolver runs during handler parameter matching. Make it deterministic, return unresolved when the context is not
available, and avoid network/database work. A constrained validation method can use the same resolvers; unresolved
parameters cause that method constraint to be skipped, so always-required validation must not depend on an optional
resolver.

`replaceValidator(current -> replacement)` changes structural validation application-wide. Prefer the default SDK
validator unless a concrete unsupported constraint or integration requires replacement. Re-run payload, method
parameter/return, nested collection, group, and fixture tests after replacement.

## Consumer templates and secondary consumers

`configureDefaultConsumer(messageType, operator)` changes the template used for that message type.
`addConsumerConfiguration(configuration, messageTypes...)` creates another matching consumer. Use these for a named
replay/repair stream or a genuinely separate workload, not to split one ordering invariant accidentally.

```java
builder.configureDefaultConsumer(MessageType.EVENT,
        current -> current.toBuilder().threads(4).build());

builder.addConsumerConfiguration(
        ConsumerConfiguration.builder()
                .name("projection-rebuild")
                .minIndex(0L)
                .handlerFilter(handler ->
                        handler.getClass().isAnnotationPresent(RebuildProjection.class))
                .build(),
        MessageType.EVENT);
```

A different consumer name has an independent position and ordering boundary even when routing keys are equal. Confirm
replay safety and effect idempotency before adding a historical consumer.

## Host metrics and fetch limits

`enableHostMetrics()` publishes application-process CPU/JVM/memory/thread/disk signals when enabled. Configure a
deliberate interval and never put sensitive payload data in labels. Host metrics do not expose managed database or
cluster internals.

`fluxzero.tracking.maxFetchBytes` supplies the inherited serialized byte limit per consumer fetch. A consumer-specific
`maxFetchBytes = -1` inherits it; `0` is deliberately unbounded. Raise limits only after measuring payload size and
memory behavior.

## Compatibility forwarding and direct clients

`forwardWebRequestsToLocalServer(port)` bridges Fluxzero web requests to an existing local HTTP server. It is a
compatibility path for an application that already owns another HTTP stack, not the default for Fluxzero-first web
handlers. Test path, identity, timeout, body-size, and response mapping across the bridge.

`LocalClient.newInstance()` is useful for isolated tools/tests and loses state on restart. `WebSocketClient` with an
explicit `ClientConfig` is for a standalone non-Spring client that must connect to a configured runtime. Normal Cloud
applications should use template/auto-configuration and supplied connection properties rather than constructing
protocol clients inside domain code.
## Bound transport work with versioned defaults

`fluxzero.eventsourcing.maxFetchBytes` bounds serialized payload per aggregate-history page; it is independent of
`fluxzero.tracking.maxFetchBytes` for consumer fetches. Compatibility mode retains count-only history pages;
`fluxzero.defaults.version >= 2026.09.10` selects 100 MiB. An explicit `0` keeps count-only pages. One oversized event
is still returned to make progress, and older Runtimes ignore the optional byte limit. Read history fetching before
treating this as a strict memory cap.

`fluxzero.websocket.reconnectBackoff.enabled` enables capped exponential equal-jitter retries instead of fixed
one-second retries. The versioned default enables it from `2026.09.09`; an explicit value overrides the default.
Read WebSocket recovery for client/task identity and bounded diagnostics.
