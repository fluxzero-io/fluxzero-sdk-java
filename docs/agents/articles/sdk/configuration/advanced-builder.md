Use `DefaultFluxzero.builder()` only at application bootstrap. In Spring, apply these changes through one
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

`fluxzero.websocket.reconnectBackoff.enabled` enables capped exponential equal-jitter retries instead of fixed
one-second retries. The versioned default enables it from `2026.09.09`; an explicit value overrides the default.
Read WebSocket recovery for client/task identity and bounded diagnostics.

## Request shutdown grace period

`fluxzero.shutdown.requestTimeoutMillis` (`FLUXZERO_SHUTDOWN_REQUEST_TIMEOUT_MILLIS`) controls how long each
request gateway and response handler waits for outstanding results during shutdown. The application default is
`2000` milliseconds per component; `0` skips that grace period. This is not a deadline for the entire application
shutdown: consumers, callbacks, clients and other owned resources still close through their normal lifecycle.
A response handler completes remaining requests exceptionally when it closes.

The setting uses the application's configured `PropertySource`, is validated when building, and is read again when
closing. Negative values are rejected at build time. If a mutable source becomes invalid before shutdown, the component
logs the configuration error and skips the response grace period so mandatory resource cleanup still runs. The `withShutdownTimeout` methods on `DefaultGenericGateway` and
`DefaultRequestHandler` provide a programmatic supplier alternative for custom component construction.

Automatic JUnit `TestFixture` cleanup defaults this value to `0` only after its owning test has finished, unless it
was explicitly configured. Cleanup is still awaited and failures belong to the test outcome. Calling
`fixture.getFluxzero().close()` yourself retains the ordinary application grace period; this also permits tests of
responses arriving during graceful shutdown. Request timeouts, timeout metadata and cancellation semantics are unchanged.

When tracking closes, incomplete chunked payloads fail so their handlers cannot remain blocked waiting for missing
input. A fully received body remains readable, and ordinary asynchronous handler results retain their shutdown grace.
