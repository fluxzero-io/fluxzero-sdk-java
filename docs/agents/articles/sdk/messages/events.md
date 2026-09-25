Events are for asynchronous reactions. Model events normally carry the applied command payload,
so avoid inventing duplicate past-tense event classes unless there is a separate external fact.

## Model state and event boundaries

Directly addressed Models and their parents or further ancestors can be injected as `T` or `Graph<T>`.
For events/notifications with Model-commit metadata, Fluxzero uses that event's exact historical state and relations.
Use `@Association("property")` for a different payload/metadata ID or a qualified ancestor path;
`excludeMetadata = true` excludes metadata lookup; payload IDs and reachable Graph ancestors remain usable.
An empty `Graph<T>` can represent logical deletion; a non-null `T`
requires a present value. An ordinary indexed event without a Model boundary resolves directly addressed Models at
one current pinned boundary.

Other handler kinds can resolve these parameters when the payload or metadata addresses a Model, using their
coherent handler load context. Document-authoritative values remain current-only document reads; they are not an
implicit historical document snapshot. Prefer direct `T` for a value and `Graph<T>` for navigation/history; injected
Graph reads retain the scoped conflict dependencies documented under `/docs/sdk/models/conflicts`.

## Reactions and other streams

Use `@HandleEvent` for side effects, projections, notifications, and follow-up commands. Add a named `@Consumer` when the handler needs its own tracking and retry stream.

Do not put side effects in `@Apply`. Apply methods must be replayable. If an update should trigger email, metrics, or a secondary document, apply the state first and react from an event handler.

`Fluxzero.publishEvent(...)` is for explicit side effects and projections. It does not mutate Model state; apply an update for a persisted state transition.

Use the specialized handlers when the stream is not a normal Model event:

- `@HandleNotification` reacts to published notifications.
- `@HandleDocument` reacts to search/document collection updates, usually from a stateful projection or document store.
- `@HandleError` reacts to handler errors; inject the failed payload with `@Trigger` when retry, compensation, or a correction needs the original command/query/event. Read error corrections for trigger filters, idempotency, replay, and an asynchronous fixture recipe.

When testing error handlers, document handlers, or other asynchronous consumers, use `TestFixture.createAsync(...)` so tracking behavior is exercised.

Model commits retain ordered per-Model event memberships. Store-only updates remain in that history but are not published to ordinary event consumers.

## Delivery defaults and completion

`Fluxzero.publishEvent`, `publishEvents`, command/custom send-and-forget overloads, and error publication use
`Guarantee.DEFAULT`. Web-request and metrics publication can select it explicitly. The owning application resolves this before transport: absent or older
`fluxzero.defaults.version` keeps `NONE`; `2026.09.25` or later selects `STORED`. Configure
`fluxzero.publishing.defaultGuarantee` (`FLUXZERO_PUBLISHING_DEFAULT_GUARANTEE`) as `NONE`, `SENT`, or `STORED`
to override either direction. The standard builder resolves its own property source when creating gateways;
configure these properties before building the application (including a TestFixture's builder).
Explicit concrete guarantees remain unchanged. `DEFAULT` is an SDK choice, never a wire value.

Convenience calls return after dispatch/local handling, without waiting for each remote storage acknowledgement.
The default consumer's `awaitSendAndForgetFutures=true` waits for registered delivery futures before committing
its position. A finished handler, a stored outgoing message, and a committed input position are separate boundaries.
This is at-least-once processing: a crash after publication but before position commit can repeat side effects;
use idempotent consumers. Storage acknowledgement does not mean that a downstream handler finished.

Publications within a batch can overlap their acknowledgements; independent trackers can keep processing while
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
`awaitSendAndForgetFutures=false` deliberately opts out of the publication barrier.

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
message into the Runtime. Request/response calls keep their response-completion contract. Automatic responses,
metrics/transport diagnostics, scheduling, persistence, retention and position-management defaults retain their
operation-specific guarantees. For low-level clients/protocol requests use a concrete guarantee.
