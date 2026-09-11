Use a query for read-only work. A query should implement `Request<T>` so the return type is part of the API.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

```java
public record GetProject(ProjectId projectId) implements Request<Project> {
    @HandleQuery
    Project handle() {
        return Fluxzero.loadAggregate(projectId).get();
    }
}
```

The example is a local self-handling query: its payload class is discovered when that query instance is dispatched, so a fixture does not need to register `GetProject.class`. Read self-handling query placement before registering several query classes, using a zero-component record, or adding `@TrackSelf`; local and tracked self-handling have different delivery semantics. Read result contracts when a handler returns `null` or `Optional.empty()`; `Request<R>` always names the unwrapped value type and the fixture observes the unwrapped result.

Standalone query handlers can be Spring components. Include the typed query payload as a handler parameter so routing is constrained. Add `@LocalHandler` when the query must run synchronously in the publication thread. For list screens and filters, prefer a search-backed query using `Fluxzero.search(...)`.

`@HandleQuery` defaults `skipExpiredRequests` to `true`. That is usually correct for reads because older indexed requests can be stale by the time a replay reaches them.

Keep query handlers read-only. They may load aggregates, read documents, or search, but they should not apply updates or publish side effects.

Prefer dedicated query payloads over static utility methods for reusable reads. A standalone query without `@LocalHandler` still participates in normal tracking and request handling; use `@LocalHandler` only when local synchronous execution is the intended behavior.

For lightweight local caching in self-handling commands or queries, use `Fluxzero.memoize(...)` or `Fluxzero.memoizeIfAbsent(...)` instead of ad hoc static caches.
