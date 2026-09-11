Use error handlers when a tracked handler failure must produce a deliberate retry, compensation, correction event, or durable operational record. Do not wrap every handler in broad `try/catch`; let Fluxzero report an unhandled failure to the error stream, then react from a focused `@HandleError` consumer.

## Relate the error to its trigger

`@HandleError` consumes error messages. Add `@Trigger` to inject the command, query, or event whose handler failed:

```java
@FunctionalInterface
interface ReceiptGateway {
    void send(OrderId orderId);
}

@Component
@Consumer(name = "receipt-delivery")
final class ReceiptDeliveryHandler {
    private final ReceiptGateway gateway;

    ReceiptDeliveryHandler(ReceiptGateway gateway) {
        this.gateway = gateway;
    }

    @HandleEvent
    void deliver(OrderPaid event) {
        gateway.send(event.orderId());
    }

    @HandleError
    void correct(Throwable error, @Trigger OrderPaid event) {
        Fluxzero.publishEvent(new ReceiptDeliveryFailed(
                event.orderId(), error.getMessage()));
    }
}
```

The error parameter describes the reported failure; the `@Trigger` parameter is the original payload. A handler may omit the error parameter when only the trigger matters. If the trigger type is not compatible, the handler is skipped.

Use trigger filters when one broad method could otherwise react to unrelated failures:

```java
@HandleError
@Trigger(
        value = OrderPaid.class,
        messageType = MessageType.EVENT,
        consumer = "receipt-delivery")
void correct(@Trigger OrderPaid event) {
    // publish one idempotent correction
}
```

`@HandleError.allowedClasses` filters the error payload class actually published to the error log. Fluxzero preserves `FunctionalException` and `TechnicalException`, but wraps other uncaught failures such as `IllegalStateException` in `TechnicalException` before publishing them. The fixture may still expose the original thrown error to `expectError(...)`; do not copy that original class into `allowedClasses` unless it is also the published payload. `@Trigger.value`, `messageType`, and `consumer` filter the originating message. Do not confuse the two sides.

## Choose retry, compensation, or correction deliberately

| Response | Use when | Required safety |
| --- | --- | --- |
| Retry original trigger | The operation is transient and safe to repeat | Bounded attempts, backoff/scheduling, and idempotency |
| Compensation | Earlier business effects need an explicit inverse action | Correlation to the completed effect and duplicate protection |
| Correction event/document | Users or operators need a durable replacement/failure record | Stable identity so replay overwrites or deduplicates correctly |
| Observe only | The normal error stream and alerting are sufficient | No extra business side effect |

An error handler does not turn the original request into a success. It adds follow-up behavior after the failure was reported. Avoid immediate blind resend from `@HandleError`; an unchanged poison message can create a retry loop. Prefer a scheduled, counted retry or a durable correction when recovery is not guaranteed.

Error consumers can replay. Any email, payment, outgoing request, command, or published correction must therefore be idempotent or use a stable business key. Review tracking and runtime interaction before resetting or renaming the consumer.

Local-only handlers are not the normal error-reporting path. Use a tracked `@Consumer` when the correction must be durable and replayable.

## Prove both the failure and the correction

Use an asynchronous fixture so error reporting and the `@HandleError` consumer participate. Inject a deterministic failing adapter; do not contact a real service:

```java
var delivery = new ReceiptDeliveryHandler(
        orderId -> { throw new IllegalStateException("gateway unavailable"); });

TestFixture.createAsync(delivery, ReceiptProjection.class)
        .whenEvent(new OrderPaid(orderId))
        .expectError(IllegalStateException.class)
        .expectEvent((ReceiptDeliveryFailed event) ->
                event.orderId().equals(orderId))
        .andThen()
        .whenQuery(new GetReceiptStatus(orderId))
        .expectResult(ReceiptStatus.FAILED);
```

Assert the original error with `expectError(...)`, the compensation/correction message, and the final observable document/query/outgoing request separately. Seeing only the error does not prove recovery; seeing only a published correction does not prove that the real failing handler and trigger routing produced it.

Keep a successful control beside the failure scenario when the same handler normally produces a product-visible delivery or projection. Prove three rows independently: a successful adapter produces the ordinary notification/document, a failing adapter reports the error and emits the correction, and the public query/feed observes the final ordinary or corrected record. A correction-only test can pass even when the normal trigger-to-delivery path or the projection is disconnected.

Add a no-duplicate scenario when the product promises exactly-once visible correction. Replaying or redelivering the trigger should not produce duplicate payments, emails, documents, or user-facing feed entries.
