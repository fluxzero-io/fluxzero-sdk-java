Events are for asynchronous reactions. Model and legacy aggregate events normally carry the applied command payload,
so avoid inventing duplicate past-tense event classes unless there is a separate external fact.

## Model state and event boundaries

Directly addressed Models and their parents or further ancestors can be injected as `T` or `Graph<T>`.
For events/notifications with Model-commit metadata, Fluxzero uses that event's exact historical state and relations.
Use `@Association("property")` for a different payload/metadata ID or a qualified ancestor path;
`excludeMetadata = true` excludes metadata lookup; payload IDs and reachable Graph ancestors remain usable.
An empty `Graph<T>` can represent logical deletion; a non-null `T`
requires a present value. An ordinary indexed event without a Model boundary resolves directly addressed Models at
one current pinned boundary, unless an Aggregate-to-Model migration maps it to a historical Model commit.
Follow `/docs/sdk/models/migration` for explicit migration-following configuration during live catch-up.

Other handler kinds can resolve these parameters when the payload or metadata addresses a Model, using their
coherent handler load context. Document-authoritative values remain current-only document reads; they are not an
implicit historical document snapshot. Prefer direct `T` for a value and `Graph<T>` for navigation/history; injected
Graph reads retain the scoped conflict dependencies documented under `/docs/sdk/models/conflicts`.

## Reactions and legacy streams

Use `@HandleEvent` for side effects, projections, notifications, and follow-up commands. Add a named `@Consumer` when the handler needs its own tracking and retry stream.

Do not put side effects in `@Apply`. Apply methods must be replayable. If an update should trigger email, metrics, or a secondary document, apply the state first and react from an event handler.

`Fluxzero.publishEvent(...)` is for explicit side effects and projections. It is not the persisted event-sourcing stream for an aggregate; aggregate history is built from applied updates flowing through the entity apply path.

Use the specialized handlers when the stream is not a normal aggregate event:

- `@HandleNotification` reacts to published notifications.
- `@HandleDocument` reacts to search/document collection updates, usually from a stateful projection or document store.
- `@HandleError` reacts to handler errors; inject the failed payload with `@Trigger` when retry, compensation, or a correction needs the original command/query/event. Read error corrections for trigger filters, idempotency, replay, and an asynchronous fixture recipe.

When testing error handlers, document handlers, or other asynchronous consumers, use `TestFixture.createAsync(...)` so tracking behavior is exercised.

Runtime aggregate events are stored in ordered batches per aggregate ID. Events published with a store-only strategy remain in aggregate history but are not emitted as tracked event messages for normal event consumers.
