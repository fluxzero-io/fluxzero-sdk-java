# Testing Model Transitions

## Testing

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
