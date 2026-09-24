# Graph Change Subscriptions

## Complete graph-change handlers

Use an unqualified `Graph<T>` as the sole handler parameter to subscribe to every durable change of that root or one
of its descendants:

```java
@HandleEvent
void projectChanged(Graph<Project> graph) {
    Graph<Project> before = graph.previous();
}
```

Creation has no previous graph; deletion supplies an empty current graph and the complete deleted graph through
`previous()`; moving a child invokes both old and new roots. The previous graph is commit-exact and does not depend on
cache depth. One handler object may declare several such methods for distinct root types. Adding an explicit event
payload turns the method back into ordinary payload handling with direct/ancestor Graph injection.

## React to a business transition

Use event-bound `Graph<T>` state and `previous()` when deciding what changed; `@HandleDocument` is not a substitute
because intermediate document versions may be skipped. Check absence at creation/deletion. For example:

```java
@HandleEvent
void changed(Graph<Project> graph) {
    Project after = graph.get();
    Graph<Project> previous = graph.previous();
    Project before = previous == null ? null : previous.get();
    if (after != null && after.cancelled() && (before == null || !before.cancelled())) {
        // Durably dispatch an idempotent, bounded settlement reaction.
    }
}
```

Keep this historical comparison separate from current-intent reconciliation. A later batch should deliberately
open a current Graph and recheck each candidate before changing it. See `/docs/sdk/sagas` for bounded continuation
and the limits of retained pending-effect documents. Kotlin uses the same injected `Graph<Project>` contract;
read `graph.previous()?.get()` before comparing it with `graph.get()`.
