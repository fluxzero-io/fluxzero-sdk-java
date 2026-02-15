# Tracking & Reliability

In Fluxzero, 'Tracking' refers to the mechanism of asynchronous message consumption in isolated consumers and their
trackers (threads). This is where you configure how messages are processed at scale and how to handle reliability
concerns like replays and error correction.

---

## Quick Navigation

- [Consumers & Trackers](#consumer)
- [Message Interceptors](#interceptors)
- [Message Replays](#replays)
- [Error Correcting & Retroactive Updates](#error-correcting)
- [Document Rebuilding](#document-rebuilding)
- [Message Retention](#retention)

---

<a name="consumer"></a>

## Consumers & Trackers

A **Consumer** is a logical group of message handlers that process messages from the stream.

### Configuration (@Consumer)

Annotate your handler class or `package-info.java` with `@Consumer` to define processing behavior:

- **threads**: The number of concurrent trackers (threads) assigned to this consumer.
- **singleTracker = true**: Ensures strict global ordering by assigning all segments to a single thread.
- **ignoreSegment = true**: Used for custom sharding or global processing where segment-based ordering is not required.
    - **Client-side filtering**: Combine this with `@RoutingKey("propertyX")` on the handler method to perform filtering
      based on the message's routing key or metadata.
    - **Stateful Sagas**: For `@Stateful` handlers, the saga's ID is used automatically for load balancing and
      filtering; `@RoutingKey` is not required.

```kotlin
@Component
@Consumer(name = "order-tracking", threads = 4)
class OrderTracker {
    @HandleEvent
    fun on(event: CreateOrder) { ... }
}
```

---

<a name="batch-interceptor"></a>

## Batch Interceptor

Wraps around the processing of a **full message batch** by a consumer.

- **Typical Use Cases**: Performance monitoring, bulk resource allocation, or structured logging for a whole batch.
- **Registration**: `FluxzeroBuilder.addBatchInterceptor(interceptor)`.

```kotlin
class LoggingBatchInterceptor : BatchInterceptor {
    override fun intercept(consumer: Consumer<MessageBatch>, tracker: Tracker): Consumer<MessageBatch> {
        return Consumer { batch ->
            log.info("Start processing ${batch.size()} messages")
            consumer.accept(batch)
            log.info("Finished batch")
        }
    }
}
```

**Note**: `DispatchInterceptor` and `HandlerInterceptor` are documented in
the [Sending](sending.md#dispatch-interceptors) and [Handling](handling.md#handler-interceptors) manuals respectively.

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

```kotlin
@Consumer(name = "my-new-projection", minIndex = 0)
class MyProjection { ... }
```

### Advanced Replay Control

- **maxIndexExclusive**: Use this to stop the replay at a specific message index.
- **IndexUtils**: Use the `IndexUtils` utility to compute the correct `long` index from a specific `Instant` or
  timestamp if you want to start or stop at a specific point in time.

**Example: Computing an Index**

```kotlin
// Get the index for a specific point in time (e.g., January 1st, 2026)
val minIndex: Long = IndexUtils.indexFromTimestamp(Instant.parse("2026-01-01T00:00:00Z"))
```

---

<a name="error-correcting"></a>

## Error Correcting & Retroactive Updates

If a message fails during tracking, it is handled by the consumer's `errorHandler`.

### Targetted Retroactive Correction

You can use `minIndex` and `maxIndexExclusive` to target a specific period when a bug was active. Use `IndexUtils` to
convert dates to indices.

```kotlin
@Component
@Consumer(
    name = "fix-order-bug-v2",
    minIndex = 98453488271360000L, // IndexUtils.indexFromTimestamp(Instant.parse("2023-01-01T00:00:00Z"))
    maxIndexExclusive = 99307905158348800L // When the bug was fixed (e.g., 2023-04-01)
)
class ErrorCorrectionHandler {
    @HandleError
    fun recover(error: Throwable, @Trigger failedCommand: CreateOrder) {
        // Correct the issue or trigger compensatory actions for this specific period
    }
}
```

---

<a name="document-rebuilding"></a>

## Document Rebuilding

When you modify your search indexing configuration (e.g., adding a new `@Facet`, changing a `@Searchable` field, or
adding an **Upcaster**), you may need to rebuild your document collection.

### How it works

1. **New Consumer**: Create a new component with a unique `@Consumer` name.
2. **minIndex = 0**: Start from the beginning of the stream.
3. **@HandleDocument**: Subscribe to the document type you want to rebuild.
4. **@Revision**: You **must** increase the `@Revision` of the document class for the rebuild to take effect.
5. **Upcasting**: If you just added an upcaster and want the documents to be updated in the store, simply return the
   document (even as-is) from the handler.

```kotlin
@Consumer(name = "rebuild-orders-v2", minIndex = 0)
class OrderRebuilder {
    @HandleDocument(OrderDocument::class)
    fun onOrder(doc: OrderDocument): OrderDocument {
        // Returning the document triggers an update in the store
        return doc
    }
}
```

---

<a name="retention"></a>

## Message Retention

Fluxzero ensures that messages are retained in the stream based on your configuration, allowing for the replays and
retroactive corrections mentioned above.
