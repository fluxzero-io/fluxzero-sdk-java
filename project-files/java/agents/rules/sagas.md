# Stateful Sagas & Workflows

Use `@Stateful` when you need a long-lived workflow or process manager that must remember its progress between messages
and be directly addressable via `@Association` keys.


## Choose business state or process memory first

Keep business facts and invariants in Models: an order, payment capture, refund obligation or invoice has meaning
independently of the system used to execute it. Use `@Stateful` for durable execution progress: provider correlation,
operation keys, pending effects, retries and compensation. An attempt having its own identity or lifecycle does not
by itself make it business state. Do not add provider attempts to the business graph solely to retain their progress.
A provider-specific workflow invokes provider-independent domain commands when verified observations justify them.

For webhooks, verify the boundary input and durably publish an internal notification before acknowledging receipt.
Let the associated workflow interpret it; receipt alone does not complete the business transaction. Keep each external
HTTP interaction in a specific local command/query handler using the Fluxzero webrequest API. A consumer is appropriate
for the durable workflow, not as a substitute for calling one local integration operation.

Persist execution intent before issuing the effect. A later `@HandleDocument` observer can reload that intent and call
the local operation. Configure its starting position to include retained pending documents when recovery requires it.
Keep a stable idempotency key through retries, and acknowledge completion only after the domain command is durable.
Stateful storage, external HTTP and domain persistence are separate failure boundaries; test restart and both sides of
each acknowledgement. Partition independent workflows by their correlation identity; do not serialize all business
transactions behind one global consumer or put unbounded related-state scans in the core transaction.

## Choose the trigger and preserve pending work

`@HandleDocument` observes current documents; a consumer can miss intermediate versions. Use `@HandleEvent` for
business transitions that must each cause a reaction. Prefer document handlers for projection or transformation.
A document observer is suitable for **reconciliation** only
when the latest stored state still describes every unfinished effect. A newer observation may supersede an older one only
when it preserves or fully subsumes that unfinished work. Do not delete intent before its effect is acknowledged. Reload current intent before execution;
an old document delivery is a wake-up signal, not permission to execute its obsolete action.

A small explicit workflow can follow these boundaries:

1. An event handler validates correlation and returns state retaining the next action and its stable identity.
2. A later dispatcher reloads that state and selects the action once. Execution and error correlation use that same
   decision, including when another notification arrives during execution.
3. A specific local command/query performs external HTTP. Its provider idempotency key survives uncertain responses
   and retries. A domain command records verified business facts and reaches its durable commit boundary.
4. The dispatcher durably publishes the corresponding acknowledgement. Publication failure must remain retryable;
   do not turn a failed acknowledgement into a completed action or swallow it in provider-error handling.
5. The workflow accepts the matching acknowledgement or retains a problem associated with that action. Stale results
   and failures cannot complete or pause newer work.

Expected conflicting provider facts are explicit reconciliation outcomes: retain the accepted financial fact and
persist the problem. Throwing from the later state-transition handler does not reach an earlier dispatcher's catch
block. `@HandleError` with `@Trigger` is useful for unexpected tracked failures, but correlate its correction with the
actual failed action and consumer; the trigger document may predate the state reloaded by the dispatcher. Do not
correct the same failure at both the local request and outer workflow boundary. A default error policy is not a
guarantee that expected domain failures will be retried.

Bound handler execution as well as individual transactions. For large fan-out, commit a bounded page, then store a
continuation before the consumer advances. Reprocessing either the original trigger or a continuation must be
idempotent. Search may discover candidates, but each command rechecks authoritative state. Define when discovery is
complete; an eventually consistent projection returning no matches alone cannot prove completion. A retryable
consumer and durable continuation do not provide atomic external execution or exactly-once effects.

Keep these rules explicit and domain-sized. Share small wire/failure helpers where useful; do not build an application
workflow engine, duplicate next-action selection, or mistake an in-memory after-save callback for crash recovery.


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
- Use a **stateless @Component** when the process can derive its state from existing models or queries each time.
- **Rule of Thumb**: If you need explicit correlation keys, timers, or a lifecycle not tied to one model,
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
    @HandleDocument
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
    @EntityId @Association TransactionId transactionId,
    String operationKey,
    @Association String stripeId,
    Phase phase
) {
    enum Phase { REQUESTED, RECORD_CAPTURE, COMPLETE }

    @HandleEvent
    static StripeTransaction handle(MakePayment event) {
        // A document observer executes this committed intent using the retained key.
        return new StripeTransaction(event.transactionId(), event.operationKey(), null, Phase.REQUESTED);
    }

    @HandleEvent
    StripeTransaction handle(StripeApproval event) {
        // Verified observation also carries transactionId, so it can bind the first provider ID.
        if (phase == Phase.COMPLETE) return this;
        return toBuilder().stripeId(event.stripeId()).phase(Phase.RECORD_CAPTURE).build();
    }

    @HandleEvent
    StripeTransaction handle(PaymentRecorded event) {
        // The observer emits this acknowledgement only after the idempotent core command is durable.
        return toBuilder().phase(Phase.COMPLETE).build();
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

You can also implement a stateless `@Component` that loads/queries models to drive orchestration.

- **Pros**: Leverages model caching and exact event-boundary loading.
- **Cons**: Progress is implicit in model state; correlation may be less explicit than with `@Stateful`; `@Stateful`
  documents are searchable.
- **Recommendation**: If the workflow is naturally expressed as model state transitions, stateless is often
  simpler.
