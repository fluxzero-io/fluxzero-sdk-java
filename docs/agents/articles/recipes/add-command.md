# Recipe: Add A Model Command

Choose the owning Model lifecycle, typed IDs and invariants. Put pure `@Apply` methods on the command; automatic handling performs the commit. Add a handler only for orchestration.

Creation:

```java
public record CreateProject(ProjectId projectId,
                            ProjectDetails details) {
    @Apply
    Project apply(Sender sender) {
        return new Project(
                projectId, details, sender.userId());
    }
}
```

Update:

```java
public record RenameProject(ProjectId projectId,
                            String name) {
    @Apply
    Project apply(Project project) {
        return new Project(
                projectId,
                project.details().withName(name),
                project.ownerId());
    }
}
```

Logical deletion:

```java
public record DeleteProject(ProjectId projectId) {
    @Apply
    Project apply(Project project) {
        return null;
    }
}
```

Returning `null` deletes the current value but still stores/publishes the update according to the model policy. Do not
use `void` for model applies.

`@Apply` compatibility checks are inferred:

- A factory without current state requires the model to be absent.
- A non-null current-model parameter requires it to exist.
- `@Nullable` allows either state.
- Use `disableCompatibilityCheck = true` only for deliberate advanced behavior.

Fluxzero automatically handles commands with applicable model applies. Do not add a pass-through `@HandleCommand`.
Use an explicit handler only for real orchestration:

```java
@HandleCommand
CompletableFuture<Void> handle(ImportProject command) {
    // Orchestrate external work, then execute one model commit.
    return Fluxzero.assertAndApplyAsync(command);
}
```

Fluxzero commits automatically. Only when a later step in the same handling context must force an already produced
automatic Model commit to durability, use `Fluxzero.commit()` and compose on its returned `CompletableFuture<Void>`.
It is a release of the existing commit, not another mutation path: repeated calls share its completion, automatic
commit remains enabled, and a context without pending changes completes without Runtime transport. Do not call or wait
on it inside `@Apply`; the apply has not returned its change yet.

## Verify behavior

Cover model behavior through commands and observable results:

```java
TestFixture.create()
        .givenCommands(
                new CreateProject(projectId, details))
        .whenCommand(
                new RenameProject(projectId, "New"))
        .expectEvents(
                new RenameProject(projectId, "New"))
        .expectThat(fluxzero ->
                assertEquals(
                        "New",
                        Fluxzero.loadModel(projectId)
                                .get().details().name()));
```

For relationship and persistence changes, also cover direct search, modelstream reconstruction, logical/hard deletion,
event-boundary injection and a real runtime integration flow.
