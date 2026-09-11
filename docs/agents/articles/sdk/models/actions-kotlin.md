# Model commands and atomic commits (Kotlin)

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

Fluxzero commits automatically. Only when a later step in the same handling context must force an already produced
automatic Model commit to durability, use `Fluxzero.commit()` and compose on its returned `CompletableFuture<Void>`.
It releases the existing commit rather than starting another mutation path: repeated calls share its completion,
automatic commit remains enabled, and a context without pending changes completes without Runtime transport. Do not
call or wait on it inside `@Apply`; the apply has not returned its change yet.

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

## Combine payload and Model handlers

Keep action-specific handlers on the payload. Put genuinely cross-cutting state behavior on the Model when several
payload types share it. If both owners have an applicable handler, Fluxzero always evaluates the payload phase before
the Model phase for each annotation family:

1. payload `@InterceptApply`, then Model `@InterceptApply`;
2. payload immediate `@AssertLegal`, then Model immediate `@AssertLegal`;
3. all payload `@Apply` results, then all Model `@Apply` results;
4. payload `afterHandler = true` assertions, then Model after-handler assertions.

`priority` orders handlers only within a phase. Model applies receive the complete intermediate state produced by all
payload applies. This lets an instance Model apply finalize a newly created Model and makes multi-Model finalization
independent of Model-handler iteration order. Both phases are reduced to one atomic `Change` per Model ID. Static Model
applies remain valid; an independent static creation factory is used only when the payload did not already create its
target. Keep every phase pure and deterministic because live handling, retry, rebase and replay share this route.

## Recursive Model assertions

Return a validation object (or collection) from `@AssertLegal` to run its matching checks recursively. The original
payload, metadata, user and application resolvers remain available; injected Models use the pinned commit boundary
and count toward RETRY/FAIL dependencies. ACCEPT rebase and replay do not rerun assertions.

Returned objects are traversed in the returning method's before/after phase. Use `@field:AssertLegal` to delegate a
property's validation object in both phases; its nested methods determine timing, not `afterHandler` on the field.
A no-arg assertion method is not called again after apply. `Fluxzero.assertLegal` runs only immediate checks. Nulls
are ignored; collection order is preserved. Identity-based cycle detection visits an object once per payload or Model
assertion phase; nesting beyond 256 levels fails. Direct accessor methods remain eligible even when their return value
has already been traversed.

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
