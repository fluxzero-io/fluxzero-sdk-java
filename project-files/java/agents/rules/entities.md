# Models and state

Use `@Model` for persisted domain state. Do not introduce `@Aggregate` in new code. Existing aggregate APIs are legacy
Fluxzero 1.x compatibility surfaces and are scheduled for deprecation in 2.0.

## Core rules

1. Implement model state as immutable records or value objects.
2. Put action-specific `@AssertLegal`, `@InterceptApply` and `@Apply` methods on the command/update payload by default.
3. Keep `@Apply` pure and deterministic. It is reused during event sourcing.
4. Do not load, search, publish or perform I/O from `@Apply`.
5. Prefer a separate model for state with an independent identity, update frequency, search need, retention rule or
   lifecycle.
6. Use `@Member` only for values that deliberately share one modelstream, document, cache entry and lifecycle.
7. Use typed `Id<T>` values. The exact `Id.toString()` is the persisted model identity.

## Define a model

```java
@Model(searchable = true)
public record Project(
        @EntityId ProjectId projectId,
        ProjectDetails details,
        UserId ownerId) {
}

public final class ProjectId extends Id<Project> {
    public ProjectId(String value) {
        super(value, "project-");
    }
}
```

Important settings:

- `eventSourced`: controls the current-state load route. Events are still stored when `false`.
- `searchable`: maintains an independently searchable synchronous current-state document. `false` suppresses only the
  model's own collection; an explicit `@ParentId(path = "...")` still retains a private graph-component document.
- `collection`: stable direct search collection name.
- `eventPublication`: controls whether unchanged transitions create an event.
- `publicationStrategy`: `STORE_AND_PUBLISH`, `STORE_ONLY`, `PUBLISH_ONLY` or `NEVER`.
- `snapshotPeriod` and `maxSnapshotCount`: event-sourcing optimizations.
- `cached` and `cachingDepth`: current and previous revisions retained in the SDK cache.
- `automaticHandling`: opt out when an explicit command handler must call `Fluxzero.assertAndApply`.
- `graphProjection`: optional durable whole-tree read model.

`eventSourced = false` does not disable event storage or publication. It means current state loads from the direct
document. Historical event-boundary loads still use stored model events.

## Apply actions

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
    return Fluxzero.assertAndApply(command);
}
```

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

## Multi-model commits

One payload can read and update unrelated models:

```java
public record ReserveStock(
        OrderId orderId,
        InventoryId inventoryId,
        int quantity) {
    @AssertLegal
    void assertAvailable(Inventory inventory) {
        if (inventory.available() < quantity) {
            throw InventoryErrors.insufficientStock;
        }
    }

    @Apply
    Order apply(Order order) {
        return order.reserve(quantity);
    }

    @Apply
    Inventory apply(Inventory inventory) {
        return inventory.reserve(quantity);
    }
}
```

The SDK loads all targets at one state boundary and commits their events, direct documents, snapshots and relationship
deltas as one model commit. The event is globally published once.

Typed IDs resolve automatically. If two payload properties refer to the same model type, qualify the model parameter:

```java
@Apply
Account apply(@Association("sourceId") Account source) {
    return source.debit(amount);
}
```

## Relationships

Use `@ParentId` on the child:

```java
@Model(searchable = true)
public record Task(
        @EntityId TaskId taskId,
        @ParentId(path = "tasks") ProjectId projectId,
        TaskDetails details,
        boolean completed) {
}
```

- Updating `projectId` moves the task.
- The parent and siblings do not need to load for a task-only change.
- Typed `Id<Parent>` supplies the relation type. A role is only needed for untyped/ambiguous IDs.
- `path` is a stable public graph-placement contract. Omit it if automatic tree stitching/projection is not wanted.
- Relationships are temporal; graph reconstruction can pin a `stateIndex`.

Inject parents and further ancestors into assertions, interceptors and applies:

```java
@AssertLegal
void assertOpen(
        Task task,
        @Association("tasks") Project parent,
        Portfolio grandparent) {
    // Read-only ancestor dependencies.
}
```

Use `@Association` when the relation/path or same-type target would otherwise be ambiguous.

## Embedded members

`@Model` plus `@Member` is the intentional shared-stream option:

```java
@Model(searchable = true)
public record Invoice(
        @EntityId InvoiceId invoiceId,
        @Member List<InvoiceLine> lines) {
}

public record InvoiceLine(
        @EntityId LineId lineId,
        BigDecimal amount) {
}
```

Choose this only if members always load, search, move, retain and disappear with the root model. An independently
addressed or erasable child should be its own `@Model`.

## Loading

```java
Entity<Project> entity = Fluxzero.loadModel(projectId);
Project project = entity.get();

ModelGraph<Project> graph =
        Fluxzero.modelRepository().loadGraph(projectId);
```

Current loads use the model cache and its long-polling update tracker. Event handlers use the event's exact model-commit
boundary:

```java
@HandleEvent
void on(RenameProject event,
        Project project,
        Entity<Project> entity) {
    // Exact state after this event, not latest state.
}
```

Directly affected event/notification models support `T` and `Entity<T>`. `Entity<T>` is required to observe an absent
model after logical deletion. Ordinary events without model-commit metadata do not receive model injection.

## Search and graph composition

Direct searchable-model documents are synchronous with successful commit completion:

```java
List<Task> open = Fluxzero.search(Task.class)
        .match(false, "completed")
        .fetchAll(Task.class);
```

Filter by current related documents:

```java
List<Task> tasks = Fluxzero.search(Task.class)
        .whereAncestor(
                Project.class,
                MatchConstraint.match(
                        "active", "status"))
        .fetchAll(Task.class);
```

Use `whereParent`, `whereAncestor`, `whereChild` and `whereDescendant`. Use `searchGraph(Root.class)` for a complete
graph-shaped JSON result. It reads a configured `@GraphProjection` by default and otherwise stitches current direct
documents live; `searchGraph(Root.class, true)` forces live composition.

## Conflict policy

Model commits default to `ModelConflictPolicy.DEFAULT`, resolved from apply/model, builder configuration or application
properties. Public policies are:

- `ACCEPT`: preserve the event once; rebase derived documents and relationships on current merged model state.
- `RETRY`: reload and rerun assertions/interceptors/applies.
- `FAIL`: return the conflict.

If multiple applies request different policies, the stricter applicable policy wins; failure is not weakened by retry.

## Deletion

- Returning `null` from `@Apply` is logical deletion and preserves history.
- `modelRepository().deleteModel(id, NONE)` physically erases that model's stream, current document, snapshots and
  cache state while leaving the global event log untouched.
- Descendant cascade requires `planDeletion(...)` followed by confirmation/execution of that exact plan.
- Erasure fences prevent delayed document, snapshot or projection writes from resurrecting deleted data.
- Detached descendants remain discoverable through deleted-parent lineage for later GDPR/lifecycle erasure.

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

## Legacy note

Do not migrate an existing `@Aggregate` by changing only its annotation: streams, documents, lifecycle and identity
boundaries change. Keep old persisted aggregate code on the 1.x compatibility API until a deliberate data migration.
All new examples and implementations should use `@Model`.
