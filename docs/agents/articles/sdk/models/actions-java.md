# Model commands and atomic commits (Java)

## Apply actions

Creation:

```java
public record CreateProject(@NotNull ProjectId projectId,
                            @NotNull @Valid ProjectDetails details) {
    @Apply
    Project apply(Sender sender) {
        return new Project(
                projectId, details, sender.userId());
    }
}
```

Update:

```java
public record RenameProject(@NotNull ProjectId projectId,
                            @NotBlank String name) {
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

A mismatched factory/update rejects the action with the existing functional already-exists/not-found error; it does
not silently succeed. A nullable current-state parameter expresses an intentional upsert. Disabling compatibility
checks suppresses the rejection but does not turn a create-only factory into an overwrite. The existing
`fluxzero.assert.apply-compatibility` property and its `.exception.already-exists` / `.exception.not-found` overrides
also apply to Models. Accepted historical events retain their replay logic.

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

## Assertions and interceptors

Expected business refusals must be `FunctionalException` subclasses, not `IllegalStateException`,
`IllegalArgumentException`, Kotlin `require`/`check`, or an undefined application error constant. Use
`IllegalCommandException` for an invalid domain action, `UnauthorizedException` for an authorization refusal, and
Bean Validation for malformed input. These distinguish an expected refusal from a technical failure.

```java
public record RenameProject(@NotNull ProjectId projectId,
                            @NotBlank String name) {
    @AssertLegal
    void assertOwner(Project project, Sender sender) {
        if (!project.ownerId().equals(sender.userId())) {
            throw new UnauthorizedException("Not allowed to rename project");
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

A singular Model-returning `@Apply` may update an injected parent or further ancestor through the existing `@Parent`
relation. A directly supplied write-target ID keeps precedence; when none exists, the selected ancestor supplies the
write identity without duplicating its ID in the command. Merely injecting an ancestor does not update it. Selection
uses the pinned state before applying the changes, so a command may delete a child and update its parent atomically.
Ambiguous ancestors must be qualified with `@Association("parentPath")`; replay retains the corresponding dependencies.

A nullable read-only Model parameter also accepts a null identifying property, such as an optional enclosing
folder ID. Both a missing Model at a non-null ID and a null reference inject null there. A write target still needs
a non-null identity; nullable read support does not turn invalid writes or ambiguous ancestors into silent absence.

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

Model references are selected per parameter: first from the nested validator, then enclosing validators, and finally
the triggering payload. A returned `RemainingItem(otherItemId)` therefore validates that other item; a reference-less
validator returned by it inherits that selection. Explicit nulls and empty collections do not fall back. Explicit
`@Association` metadata retains its normal precedence for that parameter without replacing unrelated selections.
Ancestors are resolved from the selected references. The original command remains available as a payload parameter.

Returned objects are traversed in the returning method's before/after phase. Annotated fields and record components
delegate in both phases; their nested methods determine timing, not `afterHandler` on the field. Use a field for a
validator shared across phases: a no-arg assertion method is not called again after apply. `Fluxzero.assertLegal`
runs only immediate checks. Nulls are ignored; collection order is preserved. Identity-based cycle detection visits
an object once per payload or Model assertion phase; nesting beyond 256 levels fails. Direct accessor methods remain
eligible even when their return value has already been traversed.

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
            throw new IllegalCommandException("Insufficient stock");
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

## Dynamic write targets

A variable set of existing children can be updated by returning Models read from an injected Graph. For a `Child`
with `childId`, `@Parent RootId rootId` and an integer `value`:

```java
record IncrementChildren(RootId rootId) {
    @Apply List<Child> apply(Graph<Root> root) {
        return root.childModels(Child.class).stream()
                .map(child -> new Child(child.childId(), child.rootId(), child.value() + 1)).toList();
    }
}
```

The SDK retains each inspected Model's own revision; the children need not share a revision or sequence number.
The values must come from this evaluation's tracked Graph, not a detached search result or arbitrary current-state read.
An identity not read in the evaluation remains a new create-if-absent target, never a blind overwrite.

Use `@InterceptApply List<Graph<Child>>` when you want explicit `graph.update(...)` or `graph.delete()` operations.
Return those changed Graphs so their identity and read boundary travel with the mutation. Use an ordered collection
of ordinary command payloads when each child operation deserves its own domain command; later parts see earlier staged
changes and all parts commit atomically. The commit shares a commit ID, not one Model revision or state index.
Each stored event retains its exact commit substep during replay, including when a later part updates the same Model
again. A cold reader reconstructs the same state as the writer after the commit.
Each part's assertions see earlier staged changes too, including removed child memberships. If a validation needs
the pre-deletion collection, put its command before the Graph deletions in the returned collection. A later
validation failure rolls back the whole commit; reordering does not turn the parts into separate transactions.
RETRY rereads the selected graph and reevaluates the operation on a conflict. Do not add manual `previousValues` fields
to compensate for lost revisions; historical inspection through `previous()` requires `EVENT_SOURCED`.

## Batch-local command consistency

Automatic model commands in one tracking batch and ordered routing segment have read-your-writes. A later command sees
an earlier staged model update, including changed parent and ancestor relations, before the earlier commit completes.
When their read/write sets overlap, the later command waits for the predecessor's durable result and is then
reevaluated against canonical state before committing. Predecessor failure fails the dependent chain. Unrelated model
chains remain parallel.

A command rejected during validation does not fail independent commands in that batch, including deferred commits.
Each command retains its own commit/result outcome. A real outer batch abort still stops pending batch work;
this cannot roll back a commit already accepted by storage.

Do not treat this as one transaction across commands: each command retains its own atomic commit, result and conflict
policy. Different consumers or routing segments have no implied ordering; configure a shared consumer and routing key
when that order is a domain requirement.

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
