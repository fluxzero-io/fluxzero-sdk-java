# Stateful Sagas & Workflows

Use `@Stateful` when you need a long-lived workflow or process manager that must remember its progress between messages
and be directly addressable via `@Association` keys.

---

## Quick Navigation

- [When to use Stateful Sagas](#when-to-use)
- [Lifecycle & Implementation](#lifecycle)
- [Associations & Correlation](#associations)
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

Stateful handlers are typically implemented as `data classes` and follow a strict lifecycle based on return types:

- **Entity ID**: It is recommended to annotate a single field with `@EntityId`. This becomes the saga's primary
  identifier in its document collection.

**The "Uber-Document" Pattern**:
Sagas can listen to changes in other document collections using `@HandleDocument`. This allows a saga to maintain an "
uber-document" (a broad view of the world) by aggregating data from multiple sources as it changes.

```kotlin
@Stateful
data class SystemMonitor(@EntityId val id: String, val statuses: List<HealthStatus>) {
    @HandleDocument(ServerStatus::class)
    fun onServerUpdate(status: ServerStatus): SystemMonitor {
        // Update internal state based on a change in another document collection
        return this.updateStatus(status)
    }
}
```

| Action          | Method Type | Return Value         | Effect                                         |
|:----------------|:------------|:---------------------|:-----------------------------------------------|
| **Create**      | `companion` | `NewSaga`            | Returns a new instance; automatically stored.  |
| **Update**      | Instance    | `this` copy          | Returns a modified copy; updates storage.      |
| **Complete**    | Instance    | `null`               | Deletes the saga instance from the repository. |
| **Stay Active** | Instance    | `Unit` or `Duration` | Continues running without state mutation.      |

**Example: Stripe Payment Saga**

```kotlin
@Stateful
@Consumer(name = "stripe")
data class StripeTransaction(
    @Association val transactionId: TransactionId, 
    @Association val stripeId: String, 
    val retries: Int = 0
) {
    companion object {
        @HandleEvent
        @JvmStatic
        fun handle(event: MakePayment): StripeTransaction {
            val stripeId = makePayment(event)
            // Create: Automatically stores the handler
            return StripeTransaction(event.transactionId(), stripeId, 0)
        }
    }

    @HandleEvent
    fun handle(event: StripeApproval): StripeTransaction? {
        // Update: Handled if it has a matching `stripeId` property
        Fluxzero.publishEvent(PaymentCompleted(transactionId))
        // Complete: Returns null to delete the saga
        return null 
    }

    @HandleEvent
    fun handle(event: StripeFailure): StripeTransaction? {
        if (retries > 3) {
            Fluxzero.publishEvent(PaymentRejected(transactionId, "failed repeatedly"))
            return null
        }
        // Update: Return a modified copy
        return copy(stripeId = makePayment(event), retries = retries + 1)
    }
}
```

---

<a name="associations"></a>

## Associations & Correlation

Fields marked with **@Association** correlate incoming messages to saga instances.

- **Payload Correlation**: A message is handled if it contains a property matching the `@Association` field name and
  value.
- **Multiple Properties**: You can associate with multiple properties using `@Association(["property1", "property2"])`.
- **Method-level Override**: `@Association("someProperty")` can also be placed on a handler method to select a specific
  field from the payload for that specific handler.
- **Multiple Instances**: Multiple instances can match a single message.

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
