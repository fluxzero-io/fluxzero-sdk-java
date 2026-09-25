# Domain Models and identity

Use immutable `@Model` state for a domain concept with its own creation, changes, history, retention or deletion.
Connect independent lifecycles with `@Parent`; use ordinary value objects for details replaced with their owner.

```java
@Model
public record Project(@EntityId ProjectId projectId, ProjectDetails details) {
}
```

```kotlin
@Model
data class Project(@EntityId val projectId: ProjectId, val details: ProjectDetails)
```

Typed `Id<T>` values connect commands and queries to the intended Model. Their `toString()` is the persisted
identity; prefixes and parent scope must be designed deliberately. Use `@Alias` for alternate lookup keys and keep
primary and alias namespaces disjoint.

Put transitions in `@Apply` and business invariants in `@AssertLegal`. Applicable Model applies provide automatic
command handling. One action can update several Models in one atomic commit; a separately sent command or external
API call has a separate completion boundary.

Start with plain event-sourced `@Model`. Add direct `DOCUMENT` persistence for an application-wide search requirement,
or Graph materialization for a composed query. These read choices do not change the domain's lifecycle boundaries.

Read the linked Model articles for complete Java/Kotlin state, action, conflict and Graph contracts. Embedded members
are a specialized owner-bound choice, not the default way to model child concepts.
