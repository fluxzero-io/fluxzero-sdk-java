Use `@Stateful` when a workflow needs its own persisted memory, explicit correlation keys, timers, or a lifecycle that is not naturally owned by one aggregate. Use a stateless Spring `@Component` when the handler can derive progress from aggregates or queries every time.

Default path:

- Keep domain state in aggregates.
- Use a stateless handler for simple event reactions.
- Add `@Stateful` only when the process needs durable workflow state, searchable process documents, or independent `@Association` routing.

Lifecycle is driven by handler return type:

- A static handler that returns the saga type creates a new stateful instance.
- An instance handler that returns the saga type stores the returned copy.
- Returning a collection of the same saga type stores those instances; an empty collection deletes the current instance.
- Returning `null` from a saga-compatible handler deletes the saga instance.
- Returning `void`, `Duration`, or another type does not mutate saga state.

```java
@Stateful
@Consumer(name = "payment-saga")
public record PaymentSaga(
    @EntityId PaymentId paymentId,
    @Association String providerPaymentId,
    int retries
) {
    @HandleEvent
    static PaymentSaga on(PaymentRequested event) {
        String providerId = startPayment(event);
        return new PaymentSaga(event.paymentId(), providerId, 0);
    }

    @HandleEvent
    PaymentSaga on(PaymentAccepted event) {
        return null;
    }
}
```

## Separate stateful persistence from outgoing effects

The stateful handler method runs before Fluxzero persists its returned state or deletion. Therefore publishing an event, scheduling work, or calling an external system inside the method and then returning new state is not an atomic state/effect transaction. In particular, do not add this sequence to the deletion handler above:

```java
Fluxzero.publishEvent(new PaymentCompleted(paymentId));
return null;
```

`Fluxzero.publishEvent(...)` uses `Guarantee.NONE`, and the saga deletion happens only after the method returns. Publication can succeed before deletion or tracker completion fails, so retry can publish a duplicate. Conversely, the no-guarantee publication can be lost while deletion succeeds.

If completion needs an outgoing effect, retain a durable terminal or pending-effect state until the effect protocol has completed instead of deleting the saga in the same invocation. Returning that marker does not make it visible during the current method body: arrange a separate retryable dispatcher or later durable trigger that reloads the stored marker before sending. Give the effect a stable business or correlation key and make its receiver idempotent. If a committed aggregate transition is the source of truth, use the registered post-commit consumer pattern from aggregate commit/effect guidance instead of publishing from pre-commit code. Never claim exactly-once behavior across stateful document persistence and a separately published message.

Test both failure windows: failure after the outgoing call but before tracker/state completion must not create a second logical effect on retry, and failure before durable publication must leave enough state for recovery. Synthetically
reconstruct the stateful instance in a new fixture and prove terminal, pending-effect, and deletion behavior
independently. Use a retained runtime/client without manual reseeding for persistence-backed restart evidence.

`@Association` correlates incoming messages to persisted saga instances. A message can match by a payload property with the same name, by an explicit `@Association("property")` override, or by parameter-level association including resolved `@Trigger` values. More than one saga instance can match one message. Read durable multi-key correlation before using processor or payment references, and prove each association independently after reconstruction.

Use `@Member` inside a stateful handler when child workflow objects have their own lifecycle but should persist inside the parent. Returning a member instance creates or replaces it; returning `null` from a member-compatible handler deletes that member.

Retry and ordering defaults:

- Model timed retries with `@HandleSchedule`; returning a `Duration` schedules the next tick without changing saga state by itself.
- Use `@Stateful(commitInBatch = true)` only for throughput-sensitive handlers that can tolerate batched commits.
- `ignoreSegment` belongs to a named `@Consumer`, not to `@Stateful`. Keep normal segmented consumption unless the handler needs client-side or custom routing. With `@Consumer(name = "payment-saga", ignoreSegment = true)`, Fluxzero receives all segments and filters matched stateful instances by their persisted entity IDs.
- Use `@Consumer(name = "payment-saga", singleTracker = true)` only when strict global ordering is actually required.

When a real custom-sharding need exists, put the option on the consumer while keeping `@Stateful` separate:

```java
@Stateful
@Consumer(name = "payment-saga", threads = 4, ignoreSegment = true)
public record PaymentSaga(@EntityId PaymentId paymentId) {
}
```

Avoid loading data or searching inside saga state transition logic. If the workflow is better expressed as aggregate state plus events, keep it stateless. Ordinary cancellation, expiry, and rejection compensation belongs in the workflow state machine; it is separate from error-stream correction.
