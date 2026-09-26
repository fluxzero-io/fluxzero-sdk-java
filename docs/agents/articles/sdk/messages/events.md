Events are for asynchronous reactions. Most Fluxzero aggregate events are the applied command payload itself, so avoid inventing duplicate past-tense event classes unless there is a separate external fact.

Use `@HandleEvent` for side effects, projections, notifications, and follow-up commands. Add a named `@Consumer` when the handler needs its own tracking and retry stream.

Do not put side effects in `@Apply`. Apply methods must be replayable. If an update should trigger email, metrics, or a secondary document, apply the state first and react from an event handler.

`Fluxzero.publishEvent(...)` is for explicit side effects and projections. It is not the persisted event-sourcing stream for an aggregate; aggregate history is built from applied updates flowing through the entity apply path.

Use the specialized handlers when the stream is not a normal aggregate event:

- `@HandleNotification` reacts to published notifications.
- `@HandleDocument` reacts to search/document collection updates, usually from a stateful projection or document store.
- `@HandleError` reacts to handler errors; inject the failed payload with `@Trigger` when retry, compensation, or a correction needs the original command/query/event. Read error corrections for trigger filters, idempotency, replay, and an asynchronous fixture recipe.

When testing error handlers, document handlers, or other asynchronous consumers, use `TestFixture.createAsync(...)` so tracking behavior is exercised.

Runtime aggregate events are stored in ordered batches per aggregate ID. Events published with a store-only strategy remain in aggregate history but are not emitted as tracked event messages for normal event consumers.

## Delivery defaults and completion

`Guarantee.DEFAULT` applies to publication, results/HTTP responses, WebSocket messages/ping/close,
`indexAndForget`, bulk `executeAndForget`, schedule cancellation, low-level scheduling defaults, and retention.
SDK 1.x normally resolves it to `NONE` (schedule-client creation and cancellation retain `SENT` without an override); SDK 2.x resolves it to `STORED`, independently of `fluxzero.defaults.version`.
Override this with `fluxzero.publishing.defaultGuarantee` (`FLUXZERO_PUBLISHING_DEFAULT_GUARANTEE`), accepting
`NONE`, `SENT`, or `STORED`. Despite its historic name, the property applies beyond publication. The standard builder
resolves its own application source before creating components; namespace views inherit that policy. Direct
WebSocket client calls use the immutable policy from `ClientConfig.fromProperties(source)`.
SDK 1.x retains its synchronous key-value/scheduling convenience APIs and the existing concrete guarantees on
ordinary indexing, document maintenance, log truncation, and tracker disconnection. Internal persistence and metrics
also retain their explicit guarantees. If an app previously selected STORED through defaults date 2026.09.25,
configure `fluxzero.publishing.defaultGuarantee=STORED` explicitly to retain that choice on 1.x.
Explicit concrete guarantees remain unchanged. `DEFAULT` is an SDK choice, never a wire value.

Publication convenience calls return after dispatch/local handling, without waiting for each remote storage acknowledgement.
The default consumer's `awaitSendAndForgetFutures=true` waits for registered outgoing-command futures before committing
its position. A finished handler, a stored outgoing message, and a committed input position are separate boundaries.
This is at-least-once processing: a crash after publication but before position commit can repeat side effects;
use idempotent consumers. Storage acknowledgement does not mean that a downstream handler finished.

Outgoing commands within a batch can overlap their acknowledgements; independent trackers can keep processing while
another awaits storage. A tracker drains its own batch before claiming the next one, preserving segment ownership
and bounding pending completion state to the batch. Delayed acknowledgements apply backpressure there. Failed or
cancelled delivery prevents that batch's position from advancing, subject to the configured consumer error policy.
Transport retry/reconnect behaviour is unchanged. Shutdown cannot make an unacknowledged publication durable;
an uncommitted input remains replayable.

With asynchronous handling, the completion scope includes the framework-managed handler invocation. If
`awaitAsyncResults=false`, work started only by a later continuation of an unawaited returned stage is outside that
boundary. Incomplete streamed messages and invocations queued behind them retain deferred completion, because their bodies
may require later input batches; they cannot hold the current chunk position until the whole stream finishes.
Arbitrary application-created background work is also outside the tracked scope. Setting
`awaitSendAndForgetFutures=false` deliberately opts out of the outgoing-command barrier.

Outside tracking there is no consumer-position barrier. To observe acknowledgement or asynchronous failure,
keep the returned future:

```java
CompletableFuture<Void> delivery = Fluxzero.get().eventGateway()
        .publish(new Message(event), Guarantee.DEFAULT);
delivery.join(); // Wait at an explicit application boundary, not once per event in a handler.
```

```kotlin
val delivery = Fluxzero.get().eventGateway().publish(Message(event), Guarantee.DEFAULT)
delivery.join()
```

Locally handled or suppressed messages retain their existing local behaviour; `STORED` does not force a local-only
message into the Runtime. Request/response calls keep their response-completion contract. Metrics/transport diagnostics, internal durable persistence and position-store operations retain their explicit
guarantees. Low-level WebSocket client methods resolve DEFAULT from their client configuration; raw protocol
request objects require concrete guarantees.
