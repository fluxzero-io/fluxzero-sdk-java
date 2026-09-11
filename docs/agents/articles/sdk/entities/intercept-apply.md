# Model Interception

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
