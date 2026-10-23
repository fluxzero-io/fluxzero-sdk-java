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

## Local handler requirement in tests

`@LocalOnly` prevents external fallback; it does not register or promote a handler. Use an actual
`@LocalHandler` or an ordinary self-handling payload. A `@TrackSelf` method remains tracked unless it is explicitly
registered locally. Synchronous fixture tracking simulation does not satisfy a missing local handler: both fixture
modes reject such requests immediately without waiting for an external consumer. Explicit local registrations and
ordinary tracked messages retain their existing behavior, including when a dispatch interceptor replaces a
local-only payload with an unmarked type.
