Dispatch, handler, and batch interceptors run at different boundaries. Select one deliberately; registering the same
cross-cutting behavior at multiple levels can duplicate logs, metrics, or mutations.

## Dispatch boundary

`DispatchInterceptor.interceptDispatch(...)` runs before local handling or runtime publication and before final
serialization. It may return the original/modified message, return `null` to suppress dispatch, or throw to fail it.
`modifySerializedMessage(...)` operates after serialization; `monitorDispatch(...)` observes the final dispatch and
must not mutate it.

```java
final class CorrelationDispatchInterceptor implements DispatchInterceptor {
    @Override
    public Message interceptDispatch(Message message, MessageType type, String topic) {
        return message.getMetadata().containsKey("correlation-id")
                ? message
                : message.addMetadata(
                        "correlation-id", Fluxzero.generateId());
    }
}
```

Do not add protected values or caller-controlled identity to metadata here. Prefer the SDK correlation facilities when
they already express the requirement.

For deliberately propagated trace context, use `Metadata.withTrace(key, value)`. It stores the entry under the
reserved `$trace.` namespace so chained dispatch can recognize trace metadata. Use stable low-cardinality references,
not payload fields or credentials, and do not replace business routing/correlation with tracing metadata.

## Handler boundary

`HandlerInterceptor` wraps one resolved handler method and can inspect the `DeserializingMessage`, skip invocation, or
map its result:

```java
final class TimingHandlerInterceptor implements HandlerInterceptor {
    @Override
    public Function<DeserializingMessage, Object> interceptHandling(
            Function<DeserializingMessage, Object> next,
            HandlerInvoker invoker) {
        return message -> {
            long started = System.nanoTime();
            try {
                return next.apply(message);
            } finally {
                recordDuration(invoker, System.nanoTime() - started);
            }
        };
    }
}
```

If an interceptor replaces the message, the replacement must still match a handler in the same target class. Do not
use this to redirect arbitrary payload types.

## Batch boundary

`BatchInterceptor` wraps a tracker's `Consumer<MessageBatch>`. Use it for resources or diagnostics whose lifetime is
one fetched batch:

```java
final class ResourceBatchInterceptor implements BatchInterceptor {
    @Override
    public Consumer<MessageBatch> intercept(
            Consumer<MessageBatch> next, Tracker tracker) {
        return batch -> {
            try (var scope = openBatchScope()) {
                next.accept(batch);
            }
        };
    }
}
```

Do not swallow the downstream exception; the tracker must know that the batch failed before committing its position.
Use `shutdown(tracker)` only for interceptor-owned cleanup.

## Registration and order

Register with `addDispatchInterceptor`, `addHandlerInterceptor`, or `addBatchInterceptor`, optionally limited to
message types. Implementations found through Java `ServiceLoader` are also loaded by normal Fluxzero instances and
`TestFixture`. `@Order` controls precedence; lower values run earlier, and negative values can run before built-ins.

Test the extension with the same registration path used in production. A `ServiceLoader` interceptor on the test
classpath can affect unrelated fixtures, so isolate it or make its activation explicit.
