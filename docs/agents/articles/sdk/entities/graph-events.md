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
