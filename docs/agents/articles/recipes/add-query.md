# Recipe: Add A Model Query

Use `Fluxzero.loadModel(id).get()` for exact identity. Use `Graph<T>` for relationships and history, and typed search for lists. Define missing-value and authorization behavior in the query contract.

<a name="handlequery"></a>

Used for read-only requests. Usually self-handling. Queries MUST implement `Request<T>` to define the return type.

Prefer creating a dedicated query payload (with `@HandleQuery`) for data retrieval or computation instead of static utility methods. This keeps behavior explicit, reusable via messaging, and easy to test with `TestFixture`.

**Example: Self-Handling Query**

[//]: # (@formatter:off)
```java
public record GetUserProfile(@NotNull UserId userId) implements Request<UserProfile> {
    @HandleQuery
    UserProfile handleQuery() {
        return Fluxzero.loadModel(userId).get();
    }
}
```
[//]: # (@formatter:on)

**Memoization**

For self-handling commands or queries, use `Fluxzero.memoize(...)` or `Fluxzero.memoizeIfAbsent(...)` for lightweight
runtime caching. Values are scoped to the current `Fluxzero` instance and, by default, to the calling class.

**Example: Standalone Query Handler**

Queries can also be handled in a separate component. Adding `@LocalHandler` ensures the query is handled synchronously
in the publication thread. Without `@LocalHandler`, a standalone handler defaults to **tracking** (asynchronous).

[//]: # (@formatter:off)
```java
@Component
@LocalHandler
class UserQueryHandler {
    @HandleQuery
    UserProfile handle(GetUserProfile query) {
        return Fluxzero.loadModel(query.userId()).get();
    }
}
```
[//]: # (@formatter:on)

Use `@LocalOnly` sparingly on a payload or package when external publication would cross a security boundary. It invokes
local handlers only and suppresses `logMessage`; an unhandled request returns a failed future while an unhandled
non-request completes normally. Parent packages include child packages and `@LocalOnly(false)` restores normal fallback
for a more specific package or payload type.

> **Passive Listening**: All requests (commands, queries, web requests) can be handled passively using e.g.
`@HandleQuery(passive = true)`, meaning results won't be published. This is useful for auditing or logging without
> interfering with the primary request flow.

> **Expired Requests**: `skipExpiredRequests` controls whether an indexed request may be skipped when its effective
> timeout already expired before handler invocation. Commands default to `false`; queries and HTTP web handlers default
> to `true`. Skipped requests publish `IgnoreMessageEvent` metrics instead of handler metrics.

**Advanced Tip (Rare): Incremental Identifiers**

If random IDs are not acceptable, implement incremental ID allocation as a dedicated query backed by persisted counter
state. For the full consumer-pattern details, see Tracking: Incremental Identifiers (`/docs/sdk/tracking`).

<a name="events-notifications"></a>
