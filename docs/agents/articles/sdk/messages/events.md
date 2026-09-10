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
