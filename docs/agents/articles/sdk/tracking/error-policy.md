A consumer error policy decides whether a failed tracked message is skipped, retried, or stops tracking. This is an
operational correctness choice, not merely logging configuration. Select it per consumer according to the effect and
replay contract.

## Start with the default

**Start with `LoggingErrorHandler`, the consumer default.** It logs technical failures at error level and functional
failures at warning level, returns the error, and lets tracking continue without retrying. No explicit `errorHandler`
setting is needed. A normally completed batch can advance its position past a failed effect: monitor failures and
provide reconciliation or replay where needed. Select another policy when those recovery requirements demand it.

```java
@Consumer(name = "item-projection") // LoggingErrorHandler is the default
final class ItemProjection {
    // tracked handlers
}
```

```kotlin
@Consumer(name = "item-projection") // LoggingErrorHandler is the default
class ItemProjection {
    // tracked handlers
}
```

## Beware of stopping instead of retrying

**`ThrowingErrorHandler` does not retry or automatically resume. It can stop the affected tracker until explicit
restart, typically application restart or redeployment after repair.** Even an ordinary `FunctionalException` can
stop it. Other trackers or application instances may continue; this is not a global pause of the consumer.

Choose this only when stopping for operator intervention is deliberate, with an alert and a repair/restart procedure.
For a message failure with a known index and automatic position storage, the tracker can store progress before that
index and then stop. Already completed effects are not rolled back and can repeat during recovery. A temporary outage
by itself is not a reason to select this policy if automatic retry is the intended recovery.

## Choose a built-in policy deliberately

| Policy | Behavior | Suitable boundary |
| --- | --- | --- |
| `LoggingErrorHandler` | log and continue without retry | recommended starting point; continued failures are observable and recoverable if needed |
| `RetryingErrorHandler` | default: up to five retries, then continue; configurable stop | bounded recovery from transient failures, with repeatable effects |
| `ForeverRetryingErrorHandler` | no retry-count limit for eligible failures | recoverable failures must hold up progress; idempotent effects, lag alerts and operator recovery |
| `ThrowingErrorHandler` | rethrow immediately and stop the affected tracker | deliberate operator-controlled stop with explicit restart |
| `SilentErrorHandler` | continue without retry, with configurable or no logging | deliberately best-effort work with separate observability |

### When unlimited retries fit

Use `ForeverRetryingErrorHandler` when skipping an effect during a recoverable outage is unacceptable, repeating the
operation is safe, and delaying subsequent processing is acceptable. Examples are replacing a document by stable ID
or reconciling schedules from durable current intent. This is an explicit per-consumer choice:

```java
@Consumer(name = "reconciled-projection", errorHandler = ForeverRetryingErrorHandler.class)
final class ReconciledProjection {
    // idempotent tracked effects; alert on sustained lag and failures
}
```

```kotlin
@Consumer(name = "reconciled-projection", errorHandler = ForeverRetryingErrorHandler::class)
class ReconciledProjection {
    // idempotent tracked effects; alert on sustained lag and failures
}
```

The first retry is immediate. After failed retries, the default backoff starts at 10 seconds and caps at one minute.
While retries continue, the affected tracker/batch waits. A permanent bug or poison message can block it indefinitely;
monitor lag and provide operational repair. This is neither an exactly-once guarantee nor a guarantee that every error
will be retried until success.

Both retry handlers exclude an **initial** `FunctionalException` by default and let processing continue. That filter
is checked when entering error handling, not on every retry. Later failures use `RetryConfiguration.errorTest`, which
excludes `Error` by default; a later functional failure can therefore keep retrying. Interruption or a rejected retry
failure can end an unlimited loop. A rejected retry failure returns `null` by default; interruption normally returns
the mapped original error. Custom policy code can also throw. Do not use unlimited retry for deterministic business rejection or non-idempotent external effects.

Use `ConsumerConfiguration.errorHandler(...)` through application bootstrap when a retry filter, delay, maximum,
error mapping, or final stop/continue choice needs a configured instance. `RetryingErrorHandler` defaults to an
immediate first retry and two-second delays after failed retries. With `stopConsumerOnFailure = true`, exhausted
**or initially excluded** failures can stop tracking until explicit restart.

## Awaiting failures

The same policy receives failures from asynchronous handler results or fire-and-forget dispatch futures when the
consumer is configured to await them. `awaitSendAndForgetFutures = true` prevents position storage before those
futures reach their requested guarantee, but the selected error handler still determines what happens after a
failure.

## Protect ordering and effects

Nested message handling shares its enclosing batch boundary. If the caller catches a nested failure, batch callbacks
and deferred writes remain pending until the enclosing scope completes. If the failure escapes that scope, completion
receives the failure. This is a lifecycle boundary, not a transaction or a rollback of earlier effects.

The supplied retry operation can cover a handler or a batch, depending on the failure boundary.
Retrying can repeat every effect that happened before the failure. Make outbound requests,
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
