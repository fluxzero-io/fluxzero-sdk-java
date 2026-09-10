Handlers receive Fluxzero messages. Keep them focused on orchestration and leave domain transitions in payload methods.

Common handlers:

- `@HandleCommand` changes state or invokes command behavior.
- `@HandleQuery` returns read-only data.
- `@HandleEvent` reacts asynchronously to facts.
- `@HandleDocument` maintains read models in stateful handlers.
- `@HandleSchedule` handles scheduled follow-up work.

Self-handling payloads are good when behavior is naturally attached to the message. Spring components are good for integration code, grouped orchestration, or handlers that need injected services.

Handler placement defaults:

- Use standalone Spring `@Component` handlers with `@Consumer` when a handler needs an isolated tracking group, retry stream, or segment processing.
- For command/query payloads that carry their own behavior, omit `@TrackSelf` for immediate local self-handling. Add `@TrackSelf` only when the payload must be published and processed through tracked delivery; read self-handling query placement before choosing because persistence, replay, timeout, and consumer behavior change.
- Use `@Stateful` for long-lived projections and sagas that maintain searchable state.
- Use `@LocalHandler` on standalone/component handlers for synchronous local reads or helpers that should bypass persisted tracking by default. An ordinary self-handling request without `@TrackSelf` is already local.

## Make standalone production handlers discoverable

In Spring, put `@Component` on an ordinary standalone handler. Add `@Consumer` when the handler needs a stable named tracking position or non-default tracking configuration:

```java
@Component
@Consumer(name = "release-note-projection")
final class ReleaseNoteProjection {
    @HandleEvent
    void on(ReleaseNoteChanged event) {
        // Maintain the projection.
    }
}
```

`@Consumer` configures message tracking; it is not a Spring stereotype and does not make the class discoverable. Outside Spring, register the instance on the configured client:

```java
configuredFluxzero.registerHandlers(new ReleaseNoteProjection());
```

`@Stateful` types and scanned `@TrackSelf` payloads have dedicated discovery paths; do not generalize those paths to an ordinary standalone handler. `TestFixture.create(handler)` and `fixture.registerHandlers(handler)` are fixture-only registration. They can make a test green even when the production class is neither a Spring bean nor explicitly registered, so every production-oriented snippet must show one production discovery mechanism.

Handler parameter resolution can inject the payload, `Sender`/user context, metadata, current time, entity state, schedules, web request/response/session objects, Spring beans, and other supported runtime context. Prefer these injected parameters over static lookups.

Use `@Consumer` for explicit tracking shape: name, thread count, fetch size, segments, single-tracker behavior, passive consumers, index bounds, namespaces, and interceptors. Keep defaults until a real reliability or throughput need appears, then read tracking and runtime interaction before changing replay or cross-app delivery behavior.

In tests, register class-based handlers explicitly when behavior depends on separately discovered classes such as `@Stateful` or `@SocketEndpoint`. Do not register ordinary local self-handling payload classes merely to dispatch them. A synchronous fixture handles a dispatched self-handler locally, while an asynchronous fixture can discover and register dispatched `@TrackSelf` payloads; register a track-self class explicitly only when the scenario cannot dispatch an instance first.

If multiple consumers handle the same command, each eligible handler may process it. For request commands, the first produced result completes the request, so avoid multiple result-producing command handlers unless that is deliberate.

Consumer positions advance per consumer and segment. Replay or position-management requests are platform operations; do not simulate them in application handler code. Application handlers must still be replay-safe because managed recovery can redeliver messages.

Read sagas before adding `@Stateful` workflow state, and read scheduling before using `@HandleSchedule`, `@Periodic`, or `CancelPeriodic`.

Read error corrections before adding `@HandleError` or retrying a failed trigger. The focused article separates the reported error from its `@Trigger`, compares retry/compensation/correction, and tests both the failure and observable recovery.
