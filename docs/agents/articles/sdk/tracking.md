Use tracking guidance when a handler consumes messages asynchronously, needs an explicit consumer, or must replay history. Do not simulate tracking positions in application code.

`@Consumer` defines the logical stream position and tracker shape for a handler, class, or package:

- `name` is the durable consumer identity. Reusing it continues from the stored position.
- `threads` controls tracker concurrency for that consumer.
- `singleTracker = true` forces strict global ordering.
- `ignoreSegment = true` lets the consumer receive all segments; combine with `@RoutingKey` only when client-side filtering is intentional.
- `maxFetchBytes` caps serialized payload bytes per fetch.
- `awaitSendAndForgetFutures = true` waits for fire-and-forget sends started during a batch before storing the consumer position.

```java
@Component
@Consumer(name = "order-projection", threads = 4)
class OrderProjection {
    @HandleEvent
    void on(CreateOrder event) {
        // Update a projection or publish follow-up work.
    }
}
```

Handlers without explicit consumer configuration use the unconfigured-handler fallback. `fluxzero.tracking.unconfiguredHandlerConsumerMode=perHandler` gives each handler class a generated default consumer. `defaultAppConsumer` shares the application default consumer. With `fluxzero.defaults.version >= 2026.05.20`, `perHandler` is the default.

Replay is a code/configuration decision, not a casual runtime action:

```java
@Component
@Consumer(name = "rebuild-orders-v2", minIndex = 0)
class OrderRebuilder {
    @HandleDocument
    OrderDocument on(OrderDocument document) {
        return document;
    }
}
```

Use a new consumer name plus `minIndex = 0` for a full replay. Use `maxIndexExclusive` for a bounded correction window. Convert time windows with `IndexUtils.indexFromTimestamp(...)` when the source period is known.

Before adding replay logic, inspect the handler side effects:

- Projection/document rebuilds are usually safe.
- External writes, payments, emails, and business-impacting commands need idempotency or user confirmation.
- A live replay can run in a separate consumer while the original consumer continues, but only when duplicate effects are safe.

For document rebuilding after search or upcaster changes, increment `@Revision` on the document type and return the document from a new `@HandleDocument` replay consumer so the store writes the current representation.

For monotonic identifiers, model the sequence as a query backed by persisted counter state. Use `@Consumer(singleTracker = true)` only for a global sequence; omit it when routing keys partition independent counters naturally.
