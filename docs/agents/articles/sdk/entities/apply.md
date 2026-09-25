# Applying Model changes

`@Apply` describes a deterministic immutable transition. A factory without current state creates; a method requiring
current state updates; returning `null` logically deletes. A nullable state parameter permits an intentional upsert.
`void` is not a Model transition.

```java
public record CompleteTask(TaskId taskId) {
    @Apply
    Task apply(Task task) {
        return task.withCompleted(true);
    }
}
```

```kotlin
data class CompleteTask(val taskId: TaskId) {
    @Apply
    fun apply(task: Task) = task.copy(completed = true)
}
```

Fluxzero resolves the target from the typed ID. Creation against existing state and update against absent state fail
unless deliberately configured otherwise. `@Apply(disableCompatibilityCheck = true)` removes the automatic signature
check; it does not make a factory an overwrite or make an implementation safe for null input.

Payload applies can be followed by Model applies. Multiple applicable transitions can update independent Models in
one commit. Keep the transition deterministic because event-sourced reconstruction runs it again. Inject required
Models/Graphs and persisted message context; do not manually load, search, generate random IDs, call an external
service or consult a wall clock inside `@Apply`.

Inject `Sender` for actor-dependent state and `Instant` for the stored message time. Avoid ambient user state.
Use `@AssertLegal` for business refusals and `@InterceptApply` to transform, suppress or expand the accepted update.
Injected dependencies participate in the Model operation's conflict contract; arbitrary search does not.

A staged `Graph.update(...)` value is not evidence of durability. Explicit independent `Graph.assertAndApply(...)`
and `Fluxzero.assertAndApply(...)` do await their Model commit. Keep external effects
behind durable intent and use the commit/effect article for delivery and recovery. The language-specific Model action
articles cover automatic handling, explicit orchestration and atomic multi-Model updates in detail.
