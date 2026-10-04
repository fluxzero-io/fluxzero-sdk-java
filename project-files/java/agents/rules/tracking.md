# Tracking & Reliability

In Fluxzero, 'Tracking' refers to the mechanism of asynchronous message consumption in isolated consumers and their
trackers (threads). This is where you configure how messages are processed at scale and how to handle reliability
concerns like replays and error correction.

---

## Quick Navigation

- [Consumers & Trackers](#consumer)
- [Message Interceptors](#interceptors)
- [Message Replays](#replays)
- [Replay Readiness Checklist](runtime-interaction.md#replay-safety)
- [Error Correcting & Retroactive Updates](#error-correcting)
- [Document Rebuilding](#document-rebuilding)
- [Message Retention](#retention)
- [Incremental Identifiers](#incremental-identifiers)

---

<a name="consumer"></a>

## Consumers & Trackers

A **Consumer** is a logical group of message handlers that process messages from the stream.

### Configuration (@Consumer)

Annotate your handler class or `package-info.java` with `@Consumer` to define processing behavior:

- **threads**: The number of concurrent trackers (threads) assigned to this consumer.
- **maxFetchBytes**: The serialized payload byte limit per fetch. The default is 104857600 bytes (100 MiB); set
  `fluxzero.tracking.maxFetchBytes` to change the global default, omit the value or set `maxFetchBytes = -1` to inherit
  it, or set `maxFetchBytes = 0` to disable the byte limit for a specific consumer.
- **singleTracker = true**: Ensures strict global ordering by assigning all segments to a single thread.
- **ignoreSegment = true**: Used for custom sharding or global processing where segment-based ordering is not required.
    - **Client-side filtering**: Combine this with `@RoutingKey("propertyX")` on the handler method to perform filtering
      based on the message's routing key or metadata.
    - **Stateful Sagas**: For `@Stateful` handlers, the saga's ID is used automatically for load balancing and
      filtering; `@RoutingKey` is not required.
- **awaitSendAndForgetFutures = true**: Default behavior. Outgoing commands started during batch processing are
  awaited before the consumer stores its position; disable this when those sends may complete independently. Failures
  while awaiting are handled by the consumer's `errorHandler`.

> Multiple handlers can share the same `@Consumer(name=...)`. This means they will share the same tracker
> threads and be processed in strict order if they share segments.

Handlers without an explicit `@Consumer` or matching builder-level `ConsumerConfiguration` use the
unconfigured-handler fallback. `fluxzero.tracking.unconfiguredHandlerConsumerMode=perHandler` gives each handler class
its own generated default consumer. `defaultAppConsumer` assigns those handlers to the shared application default
consumer. When the mode is absent, `perHandler` is the default behavior for
`fluxzero.defaults.version >= 2026.05.20`; older or missing defaults versions keep `defaultAppConsumer` for
compatibility.

[//]: # (@formatter:off)
```java
@Component
@Consumer(name = "order-tracking", threads = 4)
class OrderTracker {
    @HandleEvent
    void on(CreateOrder event) { ... }
}
```
[//]: # (@formatter:on)

---

<a name="batch-interceptor"></a>

## Batch Interceptor

Wraps around the processing of a **full message batch** by a consumer.

Nested message handling shares its enclosing batch boundary. If the caller catches a nested failure, batch callbacks
and deferred writes remain pending until the enclosing scope completes. If the failure escapes that scope, completion
receives the failure. This is a lifecycle boundary, not a transaction or a rollback of earlier effects.

- **Typical Use Cases**: Performance monitoring, bulk resource allocation, or structured logging for a whole batch.
- **Registration**: `FluxzeroBuilder.addBatchInterceptor(interceptor)`.

```java
public class LoggingBatchInterceptor implements BatchInterceptor {
    @Override
    public Consumer<MessageBatch> intercept(Consumer<MessageBatch> consumer, Tracker tracker) {
        return batch -> {
            log.info("Start processing {} messages", batch.size());
            consumer.accept(batch);
            log.info("Finished batch");
        };
    }
}
```

> `DispatchInterceptor` and `HandlerInterceptor` are documented in
> the [Sending](sending.md#dispatch-interceptors) and [Handling](handling.md#handler-interceptors) manuals respectively.

---

<a name="replays"></a>

## Message Replays

Fluxzero allows you to 'replay' message history for a specific consumer. This is useful when:

- You introduce a new projection or statistics handler and need to populate it with past data.
- You have fixed a bug in a handler and need to re-process historical messages to correct the state.

### Triggering a Replay

To trigger an automatic replay when the application launches:

1. **New Consumer Name**: Ensure you use a unique consumer name (one that hasn't been used before).
2. **minIndex = 0**: Set the `minIndex` to 0 on the `@Consumer` annotation.

```java

@Consumer(name = "my-new-projection", minIndex = 0)
public class MyProjection { ...
}
```

### Advanced Replay Control

- **maxIndexExclusive**: Use this to stop the replay at a specific message index.
- **IndexUtils**: Use the `IndexUtils` utility to compute the correct `long` index from a specific `Instant` or
  timestamp if you want to start or stop at a specific point in time.
- **Conversion Note**: Conceptually, timestamp-to-index can be seen as `idx = ts << 16`.
- **Safety Checklist**: Before replaying, use the [Replay Readiness Checklist](runtime-interaction.md#replay-safety).

**Example: Computing an Index**

```java
// Get the index for a specific point in time (e.g., January 1st, 2026)
long minIndex = IndexUtils.indexFromTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
```

---

<a name="error-correcting"></a>

## Error Correcting & Retroactive Updates

If a message fails during tracking, it is handled by the consumer's `errorHandler`.

**Start with the default `LoggingErrorHandler`.** It logs technical failures at ERROR and functional failures at WARN,
then continues without retrying. A completed batch can advance past the failed effect; monitor failures and arrange
reconciliation or replay when required.

**`ThrowingErrorHandler` does not retry or automatically resume. It can stop the affected tracker until explicit
restart**, typically application restart or redeployment after repair, even for `FunctionalException`. Other trackers
or instances may continue. Select this only for deliberate operator intervention with alerts and a restart procedure.

Use `RetryingErrorHandler` for bounded recovery: by default up to five retries, then continue; an explicit
`stopConsumerOnFailure = true` also stops on excluded or exhausted failures. Choose `ForeverRetryingErrorHandler`
when a recoverable outage must hold up progress and effects can safely repeat, such as replacement by stable ID or
current-state schedule reconciliation. A permanent failure can block the tracker/batch indefinitely, so monitor lag
and provide operational repair. Retry can repeat a handler or batch without rolling back earlier effects.

```java
@Consumer(name = "item-projection") // LoggingErrorHandler by default
final class ItemProjection { /* tracked handlers */ }

// Explicit opt-in for idempotent effects that must recover before progress:
@Consumer(name = "reconciled-projection", errorHandler = ForeverRetryingErrorHandler.class)
final class ReconciledProjection { /* tracked handlers */ }
```

Both retry handlers skip an initial `FunctionalException` by default. Their initial filter is not checked again on
later failures: `RetryConfiguration.errorTest` controls those and excludes `Error` by default, so a later functional
failure can keep retrying. The first retry is immediate; later delays are two seconds for the bounded default, or
10 seconds increasing to a one-minute cap for unlimited retries. Interruption or rejected retry failures can end the
loop. A rejected retry failure returns `null` by default; interruption normally returns the mapped original error.
Unlimited retries are not an unconditional delivery or exactly-once guarantee.


### Targetted Retroactive Correction

You can use `minIndex` and `maxIndexExclusive` to target a specific period when a bug was active. Use `IndexUtils` to
convert dates to indices.

```java

@Component
@Consumer(
        name = "fix-order-bug-v2",
        minIndex = 98453488271360000L, // IndexUtils.indexFromTimestamp(Instant.parse("2023-01-01T00:00:00Z"))
        maxIndexExclusive = 99307905158348800L // When the bug was fixed (e.g., 2023-04-01)
)
class ErrorCorrectionHandler {
    @HandleError
    void recover(ErrorMessage error, @Trigger CreateOrder failedCommand) {
        // Correct the issue or trigger compensatory actions for this specific period
    }
}
```

---

<a name="document-rebuilding"></a>

## Document Rebuilding

A custom serializer that creates entirely new decoded document messages can preserve source metadata with
`DocumentMessageReader.retainSource(decodedOutput, originalInput)`. Keep the unchanged original stored input
associated with each output, including split or reordered outputs; preserve ordinary envelope fields separately.
Standard input-envelope withers already retain this source. See that method's Javadoc for the full contract.
Only payload and metadata of a returned `Message` participate in document replacement; other envelope fields
are ignored for the write.

Ordinary `@HandleDocument` replacements preserve the handled stored version’s metadata through upcasting.
Return a `Message` to replace its complete metadata explicitly; `new Message(document, Metadata.empty())` removes it.
The payload must still have a higher revision. Indexed times and document identity retain their existing rules,
and returning `null` directly still deletes the document.

When you modify your search indexing configuration (e.g., adding a new `@Facet`, changing a `@Searchable` field, or
adding an **Upcaster**), you may need to rebuild your document collection.

### How it works

1. **New Consumer**: Create a new component with a unique `@Consumer` name.
2. **minIndex = 0**: Start from the beginning of the stream.
3. **@HandleDocument**: Subscribe to the document type you want to rebuild.
4. **@Revision**: You **must** increase the `@Revision` of the document class for the rebuild to take effect.
5. **Upcasting**: If you just added an upcaster and want the documents to be updated in the store, simply return the
   document (even as-is) from the handler.

```java

@Consumer(name = "rebuild-orders-v2", minIndex = 0)
public class OrderRebuilder {
    @HandleDocument
    OrderDocument onOrder(OrderDocument doc) {
        // Returning the document triggers an update in the store
        return doc;
    }
}
```

---

<a name="retention"></a>

## Message Retention

Fluxzero ensures that messages are retained in the stream based on your configuration, allowing for the replays and
retroactive corrections mentioned above.

---

<a name="incremental-identifiers"></a>

## Incremental Identifiers

If you need monotonic/incremental identifiers (instead of random IDs), model them as a dedicated query backed by
persisted counter state (for example, a document store record).

Consumer guidance:

- Use `@Consumer(singleTracker = true)` for a global, strictly ordered sequence.
- If a routing key partitions sequences naturally (for example one counter per tenant/project), `singleTracker` can
  often be omitted.

```java
@Component
@Consumer(name = "invoice-number-seq", singleTracker = true)
public class InvoiceNumberQueryHandler {
    @HandleQuery
    long handle(NextInvoiceNumber query) {
        // Read + increment + store counter in a document.
        return ...;
    }
}
```

### Stopping a consumer

Canceling a tracking registration lets an active batch finish before requesting release of its segment ownership
with a `DisconnectTracker` using `STORED` delivery. This also applies when other consumers keep the client connected. Cancellation from
inside a handler releases ownership only after the processing stack has returned. A Runtime handover is not an
exactly-once guarantee after a crash; retain the usual replay-safe handling of external side effects.

Closing the client gives outstanding terminal releases a bounded grace period before closing the transport.
The caching wrapper and WebSocket tracking client each wait at most two seconds; an already completed release
adds no wait. Admitted result callbacks receive at most one additional second. Other WebSocket clients allow
one second for already-issued commands, without waiting for long polls. These are per-component budgets and
can accumulate during full application shutdown. A disconnected Runtime or a timeout still requires the usual
replay-safe recovery; closing from a result callback does not wait on that callback itself.
