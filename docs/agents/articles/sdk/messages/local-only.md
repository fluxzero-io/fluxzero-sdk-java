Use `io.fluxzero.sdk.publishing.LocalOnly` sparingly when publishing a message outside this process would cross a
security boundary. Ordinary `@LocalHandler` dispatch can fall back to the runtime when there is no matching local
handler. `@LocalOnly` makes that external fallback impossible for the annotated payload scope.

```java
@LocalOnly
public record ReadLocalSetting(String key) implements Request<String> {}
```

Import `Request` from `io.fluxzero.sdk.tracking.handling`. Kotlin payloads can use the same annotation and contract.
The annotation can be placed on payload types, meta-annotations and packages. A package declaration includes child
packages; `@LocalOnly(false)` on a more specific package or payload type restores normal fallback for that scope.

## Dispatch contract

- Only matching local handlers may run. The message is never serialized or published externally.
- `logMessage` does not publish a copy of a local-only message.
- A request without a matching local handler returns a future failed with `LocalOnlyDispatchException`.
- An unhandled non-request completes normally without external publication.
- If a dispatch interceptor replaces the payload, a marked original stays local-only; a marked replacement also
  activates the restriction. Rewriting cannot remove the original boundary.

Handler code can still perform its own side effects, including sending other messages. The annotation constrains
dispatch of this message; it is not a sandbox or an authorization check for everything the handler executes.

Verify successful local handling, unmatched requests, unmatched non-requests, inherited package scope, explicit
opt-out and interceptor replacement. Include a negative assertion that no external message or log copy was sent.

## Actual local handlers, including Models

`@LocalOnly` does not register a handler or turn a tracked handler into a local handler. A payload's ordinary
`@HandleCommand`/`@HandleQuery` self-handler is local unless `@TrackSelf` selects tracking. An explicit
`@LocalHandler` registration is another local route. Automatic Model `@Apply` handling is a tracked route;
a local-only command with only `@Apply` methods fails immediately when no local handler accepts it.

For a deliberate process-local Model mutation, put the execution boundary on the command itself:

```java
@LocalOnly
public record SaveLocalSetting(String settingId, String value) {
    @HandleCommand
    void handle() { Fluxzero.assertAndApply(this); }

    @Apply
    Setting apply(@Nullable Setting current) { return new Setting(settingId, value); }
}
```

This is an intentional local execution boundary, not a reason to add pass-through handlers to ordinary Model
commands. Assertions, compatibility checks and atomic multi-Model commits still use the normal Model pipeline.
The resulting Model writes/events keep their own persistence/publication policies; LocalOnly does not hide them.

Both synchronous and asynchronous `TestFixture` modes preserve this boundary. Synchronous tracking simulation must
not satisfy an absent local handler, including after interceptor replacement. Dispatch attempts remain observable,
but unpublished local-only messages do not become pending external consumer work.
