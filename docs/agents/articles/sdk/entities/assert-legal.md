# Model Assertions

## Assertions and interceptors

```java
public record RenameProject(ProjectId projectId,
                            String name) {
    @AssertLegal
    void assertOwner(Project project, Sender sender) {
        if (!project.ownerId().equals(sender.userId())) {
            throw ProjectErrors.unauthorized;
        }
    }

    @InterceptApply
    Object ignoreNoChange(Project project) {
        return project.details().name().equals(name)
                ? null : this;
    }

    @Apply
    Project apply(Project project) {
        return project.withDetails(
                project.details().withName(name));
    }
}
```

Returning `null` from `@InterceptApply` suppresses that update. Assertions, interceptors and applies may inject every
direct target and related ancestor resolved for the action. They must not perform nested model writes.

Interception selects the payloads to which assertions apply:

| Interceptor outcome | Assertions and application |
|:--------------------|:---------------------------|
| Retain the payload | Its matching immediate `@AssertLegal` methods run before `@Apply` |
| Suppress the payload | Neither its assertions nor its apply methods run |
| Replace the payload | Only the replacement's matching assertions and apply methods run |
| Split the payload | Each part's immediate assertions and apply run in order; later parts see earlier changes |

Never assume an `@AssertLegal` method that only matches the original payload will run after replacement. Put an
invariant that must survive rewriting on the effective replacement or in shared/Model-side assertion logic that also
matches it. `@AssertLegal(afterHandler = true)` retains its deferred handler-completion timing.

## Recursive Model assertions

Return a validation object (or collection) from `@AssertLegal` to run its matching checks recursively. The original
payload, metadata, user and application resolvers remain available; injected Models use the pinned commit boundary
and count toward RETRY/FAIL dependencies. ACCEPT rebase and replay do not rerun assertions.

Returned objects are traversed in the returning method's before/after phase. Annotated fields and record components
delegate in both phases; their nested methods determine timing, not `afterHandler` on the field. Use a field for a
validator shared across phases: a no-arg assertion method is not called again after apply. `Fluxzero.assertLegal`
runs only immediate checks. Nulls are ignored; collection order is preserved. Identity-based cycle detection visits
an object once per payload or Model assertion phase; nesting beyond 256 levels fails. Direct accessor methods remain
eligible even when their return value has already been traversed.
