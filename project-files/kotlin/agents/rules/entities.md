# Models and state

Use `@Model` for persisted domain state. Do not introduce `@Aggregate` in new code. Existing aggregate APIs are legacy
Fluxzero 1.x compatibility surfaces and are scheduled for deprecation in 2.0.

## Core rules

1. Implement model state as immutable data classes and value objects.
2. Put action-specific `@AssertLegal`, `@InterceptApply` and `@Apply` methods on the command/update payload by default.
3. Keep `@Apply` pure and deterministic. It is reused during event sourcing.
4. Do not load, search, publish or perform I/O from `@Apply`.
5. Prefer a separate model for state with an independent identity, update frequency, search need, retention rule or
   lifecycle.
6. Use `@Member` only for values that deliberately share one modelstream, document, cache entry and lifecycle.
7. Use typed `Id<T>` values. The exact `Id.toString()` is the persisted model identity.

## Define a model

```kotlin
@Model(searchable = true)
data class Project(
    @EntityId val projectId: ProjectId,
    val details: ProjectDetails,
    val ownerId: UserId
)

class ProjectId(value: String) :
    Id<Project>(value, "project-")
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

```kotlin
data class CreateProject(
    val projectId: ProjectId,
    val details: ProjectDetails
) {
    @Apply
    fun apply(sender: Sender) =
        Project(projectId, details, sender.userId())
}

data class RenameProject(
    val projectId: ProjectId,
    val name: String
) {
    @Apply
    fun apply(project: Project) =
        project.copy(
            details = project.details.withName(name)
        )
}

data class DeleteProject(val projectId: ProjectId) {
    @Apply
    fun apply(project: Project): Project? = null
}
```

Returning `null` deletes the current value but still stores/publishes the update according to the model policy. Do not
use `Unit` for model applies.

Compatibility checks are inferred:

- A factory without current state requires the model to be absent.
- A non-null current-model parameter requires it to exist.
- A nullable model parameter allows either state.
- Use `disableCompatibilityCheck = true` only for deliberate advanced behavior.

Fluxzero automatically handles commands with applicable model applies. Do not add a pass-through `@HandleCommand`.
Use an explicit handler only for real orchestration and call `Fluxzero.assertAndApply(command)` once.

When the handler itself should remain asynchronous, return `Fluxzero.assertAndApplyAsync(command)` and let the handler
future represent the durable model commit.

## Assertions and interceptors

```kotlin
data class RenameProject(
    val projectId: ProjectId,
    val name: String
) {
    @AssertLegal
    fun assertOwner(project: Project, sender: Sender) {
        if (project.ownerId != sender.userId()) {
            throw ProjectErrors.unauthorized
        }
    }

    @InterceptApply
    fun ignoreNoChange(project: Project): Any? =
        if (project.details.name == name) null else this

    @Apply
    fun apply(project: Project) =
        project.copy(
            details = project.details.withName(name)
        )
}
```

Returning `null` from `@InterceptApply` suppresses that update. Assertions, interceptors and applies may inject every
direct target and related ancestor resolved for the action. They must not perform nested model writes.

## Multi-model commits

```kotlin
data class ReserveStock(
    val orderId: OrderId,
    val inventoryId: InventoryId,
    val quantity: Int
) {
    @AssertLegal
    fun assertAvailable(inventory: Inventory) {
        if (inventory.available < quantity) {
            throw InventoryErrors.insufficientStock
        }
    }

    @Apply
    fun apply(order: Order) = order.reserve(quantity)

    @Apply
    fun apply(inventory: Inventory) =
        inventory.reserve(quantity)
}
```

The SDK loads all targets at one state boundary and commits their events, direct documents, snapshots and relationship
deltas as one model commit. The event is globally published once.

Typed IDs resolve automatically. If two payload properties refer to the same model type, qualify the model parameter:

```kotlin
@Apply
fun debit(
    @Association("sourceId") source: Account
) = source.debit(amount)
```

## Batch-local command consistency

Automatic model commands in one tracking batch and ordered routing segment have read-your-writes. A later command sees
an earlier staged model update, including changed parent and ancestor relations, before the earlier commit completes.
When their read/write sets overlap, the later command waits for the predecessor's durable result and is then
reevaluated against canonical state before committing. Predecessor failure fails the dependent chain. Unrelated model
chains remain parallel.

This is not one transaction across commands: every command retains its own atomic commit, result and conflict policy.
Different consumers or routing segments have no implied ordering; configure a shared consumer and routing key when
that order is a domain requirement.

## Relationships

```kotlin
@Model(searchable = true)
data class Task(
    @EntityId val taskId: TaskId,
    @ParentId(path = "tasks")
    val projectId: ProjectId,
    val details: TaskDetails,
    val completed: Boolean
)
```

- Updating `projectId` moves the task.
- The parent and siblings do not need to load for a task-only change.
- Typed `Id<Parent>` supplies the relation type. A role is only needed for untyped/ambiguous IDs.
- `path` is a stable public graph-placement contract. Omit it if automatic tree stitching/projection is not wanted.
- Relationships are temporal; graph reconstruction can pin a `stateIndex`.

Inject parents and further ancestors:

```kotlin
@AssertLegal
fun assertOpen(
    task: Task,
    @Association("tasks") parent: Project,
    grandparent: Portfolio
) {
    // Read-only ancestor dependencies.
}
```

## Embedded members

`@Model` plus `@Member` is the intentional shared-stream option:

```kotlin
@Model(searchable = true)
data class Invoice(
    @EntityId val invoiceId: InvoiceId,
    @Member val lines: List<InvoiceLine>
)

data class InvoiceLine(
    @EntityId val lineId: LineId,
    val amount: BigDecimal
)
```

Choose this only if members always load, search, move, retain and disappear with the root model.

## Loading and event parameters

```kotlin
val entity: Entity<Project> =
    Fluxzero.loadModel(projectId)
val project = entity.get()

val graph: ModelGraph<Project> =
    Fluxzero.modelRepository().loadGraph(projectId)
```

Use `@Alias` for a current alternative identity of an independently stored model:

```kotlin
@Model
data class Project(
    @EntityId val projectId: ProjectId,
    @Alias(prefix = "external:") val externalId: String
)

val project = Fluxzero.loadModel(
    "external:123", Project::class.java
).get()
```

The complete alias set is replaced atomically with each transition. Independent-model aliases are global and must be
unique; primary model IDs take precedence over equal aliases.

Current loads use the model cache and its long-polling update tracker. Event handlers use the exact commit boundary:

```kotlin
@HandleEvent
fun on(
    event: RenameProject,
    project: Project,
    entity: Entity<Project>
) {
    // Exact state after this event.
}
```

Use `Entity<T>` to observe an absent model after logical deletion. Ordinary events without model-commit metadata do not
receive model injection.

## Search and graph composition

```kotlin
val open = Fluxzero.search(Task::class.java)
    .match(false, "completed")
    .fetchAll(Task::class.java)

val related = Fluxzero.search(Task::class.java)
    .whereAncestor(
        Project::class.java,
        MatchConstraint.match("active", "status")
    )
    .fetchAll(Task::class.java)
```

Use `whereParent`, `whereAncestor`, `whereChild` and `whereDescendant`. Use `searchGraph(Root::class.java)` for a
complete graph-shaped JSON result. It reads a configured `@GraphProjection` by default and otherwise stitches current
direct documents live; pass `true` as the second argument to force live composition.

## Conflict and deletion

`ModelConflictPolicy.DEFAULT` resolves from apply/model, builder configuration or application properties:

- `ACCEPT`: preserve the event once; rebase derived documents and relationships on current merged state.
- `RETRY`: reload and rerun assertions/interceptors/applies.
- `FAIL`: return the conflict.

Returning `null` is logical deletion. `modelRepository().deleteModel(id, NONE)` physically erases modelstream, direct
document, snapshots and cache state. Descendant cascade requires an explicit plan. Erasure fences prevent delayed
writes from resurrecting data.

## Testing

```kotlin
TestFixture.create()
    .givenCommands(
        CreateProject(projectId, details)
    )
    .whenCommand(
        RenameProject(projectId, "New")
    )
    .expectEvents(
        RenameProject(projectId, "New")
    )
    .expectThat {
        assertEquals(
            "New",
            Fluxzero.loadModel(projectId)
                .get().details.name
        )
    }
```

Cover direct search, relationship movement, modelstream reconstruction, logical/hard deletion, event-boundary
injection and a real runtime integration flow where relevant.

## Legacy note

Do not migrate an existing `@Aggregate` by changing only its annotation: streams, documents, lifecycle and identity
boundaries change. Keep old persisted aggregate code on the 1.x compatibility API until a deliberate data migration.
All new examples and implementations should use `@Model`.
