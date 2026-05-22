# Stateful Sagas & Workflows

Use `@Stateful` when you need a long-lived workflow or process manager that must remember its progress between messages
and be directly addressable via `@Association` keys.

---

## Quick Navigation

- [When to use Stateful Sagas](#when-to-use)
- [Lifecycle & Implementation](#lifecycle)
- [Associations & Correlation](#associations)
- [Stateful Members](#stateful-members)
- [Error Handling & Retries](#error-handling)
- [Stateless Orchestration Alternative](#stateless-alternative)

---

<a name="when-to-use"></a>

## When to use Stateful Sagas

- Use **@Stateful** when you need a workflow that remembers progress between messages and requires independent
  addressing (e.g., a Stripe payment process).
- Use a **stateless @Component** when the process can derive its state from existing aggregates or queries each time.
- **Rule of Thumb**: If you need explicit correlation keys, timers, or a lifecycle not tied to a single aggregate,
  prefer `@Stateful`.

---

<a name="lifecycle"></a>

## Lifecycle & Implementation

Stateful handlers are typically implemented as `records` and follow a strict lifecycle based on return types:

- **Entity ID**: It is recommended to annotate a single field with `@EntityId`. This becomes the saga's primary
  identifier in its document collection.

**The "Uber-Document" Pattern**:
Sagas can listen to changes in other document collections using `@HandleDocument`. This allows a saga to maintain an "
uber-document" (a broad view of the world) by aggregating data from multiple sources as it changes.

```java

@Stateful
public record SystemMonitor(@EntityId String id, List<HealthStatus> statuses) {
    @HandleDocument(ServerStatus.class)
    SystemMonitor onServerUpdate(ServerStatus status) {
        // Update internal state based on a change in another document collection
        return this.updateStatus(status);
    }
}
```

| Action          | Method Type | Return Value         | Effect                                         |
|:----------------|:------------|:---------------------|:-----------------------------------------------|
| **Create**      | `static`    | `NewSaga`            | Returns a new instance; automatically stored.  |
| **Update**      | Instance    | `this` copy          | Returns a modified copy; updates storage.      |
| **Split/Fan-out** | Instance  | `Collection<SameSaga>` | Stores each returned same-type instance.    |
| **Complete**    | Instance    | `null`               | Deletes the saga instance from the repository. |
| **Stay Active** | Instance    | `void` or `Duration` | Continues running without state mutation.      |

Important nuance:

- Returning the saga type updates persisted state.
- Returning a collection stores each same-type instance.
- Returning an empty collection deletes the current instance.
- If a returned collection omits the current saga ID, the current instance is deleted.
- Returning a same-type instance with a different `@EntityId` replaces the current instance (old ID removed).
- Returning `null` (with saga-compatible return type) deletes the saga.
- Returning any other type (or `void`) does **not** mutate saga state.

```java
@HandleSchedule
Duration poll(PollPaymentStatus tick) {
    // Schedules next run; does not mutate saga state by itself.
    return Duration.ofMinutes(5);
}
```

```java
@HandleEvent
Collection<StripeTransaction> split(PaymentSplitRequested event) {
    return List.of(
        this.toBuilder().transactionId(event.primaryId()).build(),
        this.toBuilder().transactionId(event.secondaryId()).build()
    );
}
```

**Example: Stripe Payment Saga**

[//]: # (@formatter:off)
```java
@Stateful
@Consumer(name = "stripe")
@Builder(toBuilder = true)
public record StripeTransaction(
    @Association TransactionId transactionId, 
    @Association String stripeId, 
    int retries
) {
    @HandleEvent
    static StripeTransaction handle(MakePayment event) {
        String stripeId = makePayment(event);
        // Create: Automatically stores the handler
        return new StripeTransaction(event.transactionId(), stripeId, 0);
    }

    @HandleEvent
    StripeTransaction handle(StripeApproval event) {
        // Update: Handled if it has a matching `stripeId` property
        Fluxzero.publishEvent(new PaymentCompleted(transactionId));
        // Complete: Returns null to delete the saga
        return null; 
    }

    @HandleEvent
    StripeTransaction handle(StripeFailure event) {
        if (retries > 3) {
            Fluxzero.publishEvent(new PaymentRejected(transactionId, "failed repeatedly"));
            return null;
        }
        // Update: Return a modified copy
        return toBuilder().stripeId(makePayment(event)).retries(retries + 1).build();
    }
}
```
[//]: # (@formatter:on)

---

<a name="associations"></a>

## Associations & Correlation

Fields marked with **@Association** correlate incoming messages to saga instances.

- **Payload Correlation**: A message is handled if it contains a property matching the `@Association` field name and
  value.
- **Multiple Properties**: You can associate with multiple properties using `@Association({"property1", "property2"})`.
- **Method-level Override**: `@Association("someProperty")` can also be placed on a handler method to select a specific
  field from the payload for that specific handler.
- **Parameter-level Association**: `@Association` can also be placed on a handler parameter and uses the resolved
  parameter value, including `@Trigger` parameters.
- **Multiple Instances**: Multiple instances can match a single message.

---

<a name="stateful-members"></a>

## Stateful Members

`@Stateful` handlers may contain `@Member` objects. A member can declare its own `@Handle...` methods and
`@Association` properties; Fluxzero loads the parent stateful, invokes the matching member, and stores the updated
parent.

Use this when a child has its own lifecycle but should remain persisted inside the parent stateful.

```java
@Stateful
public record Customer(
    @EntityId @Association String customerId,
    @Member List<Payment> payments
) {}

public record Payment(@Association String paymentId, int captureCount) {
    @HandleEvent
    static Payment start(PaymentStarted event, Customer customer) {
        return new Payment(event.paymentId(), 0);
    }

    @HandleEvent
    Payment capture(PaymentCaptured event, Customer customer) {
        return new Payment(paymentId, captureCount + 1);
    }

    @HandleEvent
    Payment cancel(PaymentCancelled event) {
        return null;
    }
}
```

- A message with only `paymentId` can target the matching `Payment` inside the matching `Customer`.
- If multiple members match, all matching members are invoked, including multiple children in one parent or across
  parents.
- Returning a member instance creates or replaces that member inside the parent.
- Returning a collection of member instances adds/replaces those members; an empty collection deletes the current
  matched member.
- Returning `null` from a member-compatible instance method deletes that member.
- The parent stateful can be injected into member handlers for context.
- For map-backed members, newly added members use `@EntityId` or `@Member(idProperty = "...")` as the map key.
- Within one member collection, non-null `@EntityId` values must be unique. Use `@Association` for non-unique business
  keys.
- A static member create needs a parent association in the message unless the handler deliberately uses
  `@Association(always = true)`.
- Records can be rebuilt through their canonical constructor; use `@With` or `@Member(wither = "...")` only for custom
  update behavior.

---


<a name="error-handling"></a>

## Error Handling & Retries

- **Transient Failures**: Handled by the consumer's `errorHandler`. The default is to log and continue.
- **Scheduled Retries**: Model as `@HandleSchedule` returning a `Duration` for the next attempt; return `null` to stop.
- **Batching**: Use `@Stateful(commitInBatch = true)` for higher throughput; association lookups remain correct within
  the batch.

### Concurrency & Tuning

- **ignoreSegment = true**: This setting load balances handling over multiple trackers (if configured).
    - The distribution is managed by the **Stateful Entity ID**, ensuring that different trackers never process the same
      saga instance simultaneously.
    - This prevents race conditions and accidental state overwrites while maximizing throughput.
- **singleTracker = true**: Ensures strict global ordering for related messages.

---

<a name="stateless-alternative"></a>

## Stateless Orchestration Alternative

You can also implement a stateless `@Component` that loads/queries aggregates to drive orchestration.

- **Pros**: Leverages aggregate caching and natural event synchronization.
- **Cons**: Progress is implicit in aggregate state; correlation may be less explicit than with `@Stateful`; `@Stateful`
  documents are searchable.
- **Recommendation**: If the workflow is naturally expressed as state transitions on one aggregate, stateless is often
  simpler.
