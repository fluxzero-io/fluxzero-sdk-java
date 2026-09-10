A consumer error policy decides whether a failed tracked message is skipped, retried, or stops tracking. This is an
operational correctness choice, not merely logging configuration. Select it per consumer according to the effect and
replay contract.

## Know the default

`LoggingErrorHandler` is the default. It logs technical failures at error level and functional failures at warning
level, returns the error as the handler result, and lets tracking continue without retrying. The failed message is
therefore not automatically tried again before the consumer advances. Do not call the default "guaranteed delivery."

The same policy receives failures from asynchronous handler results or fire-and-forget dispatch futures when the
consumer is configured to await them. `awaitSendAndForgetFutures = true` prevents position storage before those
futures reach their requested guarantee, but the selected error handler still determines what happens after a
failure.

## Choose a built-in policy deliberately

| Policy | Behavior | Suitable boundary |
| --- | --- | --- |
| `LoggingErrorHandler` | log and continue without retry | independent projection where skipping is accepted and observable |
| `ThrowingErrorHandler` | rethrow and stop tracking | invariant-critical processing that must not advance past a failure |
| `RetryingErrorHandler` | retry matching failures, then stop or continue according to configuration | bounded transient technical failures with idempotent effects |
| `ForeverRetryingErrorHandler` | retry indefinitely | rare strict-delivery flow with safe retry and active alerting |
| `SilentErrorHandler` | continue with configurable or no logging | deliberately best-effort/passive work with separate observability |

Configure a no-argument policy class directly on `@Consumer`:

```java
@Consumer(name = "critical-projection",
          errorHandler = ThrowingErrorHandler.class)
final class CriticalProjection {
    // tracked handlers
}
```

Use `ConsumerConfiguration.errorHandler(...)` through application bootstrap when a retry filter, delay, maximum,
error mapping, or final stop/continue choice needs a configured instance. Do not retry `FunctionalException` merely
because the built-in retry handler can: a deterministic business rejection normally remains rejected.

## Protect ordering and effects

Retrying a complete handler can repeat every effect that happened before the failure. Make outbound requests,
schedules, document writes, and message dispatch idempotent, or split the effect behind a durable post-commit intent.
For an ordered consumer, continuing after a failed message means later messages can observe a missing transition;
stopping preserves the gap but requires operational recovery.

Publish metrics/alerts for repeated retries, stopped trackers, and continued failures. Keep the message ID, consumer,
segment, index, and sanitized cause. Never include protected payloads or credentials in policy logs.

## Verify the actual consumer behavior

Test success, functional failure, transient-then-success, exhausted retry, and application restart. Assert the number
of effect attempts and the final consumer-visible state; an exception assertion alone does not prove position
behavior. Use a local runtime or supported tracker observation for position advancement, because a synchronous unit
call to `ErrorHandler.handleError(...)` does not exercise consumer tracking.

For a tracked search projection, use an asynchronous `TestFixture.spy()` scenario to fail the actual
`DocumentStore.index(...)` call and verify whether the tracking client stores the failing position. Keep that focused
failure test beside the ordinary successful replacement and public-query scenarios.
