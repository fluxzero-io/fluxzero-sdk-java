# Domain Models and identity

Use immutable `@Model` state for a domain concept with its own creation, changes, history, retention or deletion.
Connect independent lifecycles with `@Parent`; use ordinary value objects for details replaced with their owner.

```java
@Model(searchable = false)
public record Project(@EntityId ProjectId projectId, ProjectDetails details) {
}
```

```kotlin
@Model(searchable = false)
data class Project(@EntityId val projectId: ProjectId, val details: ProjectDetails)
```

Typed `Id<T>` values connect commands and queries to the intended Model. Their `toString()` is the persisted
identity; prefixes and parent scope must be designed deliberately. Use `@Alias` for alternate lookup keys and keep
primary and alias namespaces disjoint.

Put transitions in `@Apply` and business invariants in `@AssertLegal`. Applicable Model applies provide automatic
command handling. One action can update several Models in one atomic commit; a separately sent command or external
API call has a separate completion boundary.

Start with event-sourced `@Model(searchable = false)`. Enable `searchable` for node and Graph queries; composed
descendants are included by default. Add `DOCUMENT` only for internal current-state persistence, and optional
ASYNC/AWAIT Graph materialization to precompute a composed search view. These choices do not change the domain's
lifecycle boundaries.

Read the linked Model articles for complete Java/Kotlin state, action, conflict and Graph contracts. Embedded members
are a specialized owner-bound choice, not the default way to model child concepts.
