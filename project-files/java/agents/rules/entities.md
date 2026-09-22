# Models and state

For complete companion, derived-preference and execution examples, read [Model recipes](model-recipes.md).

Model discovery is independent of optional `@RegisterType` serialization aliases. Enable SDK annotation processing
(Kotlin: kapt) in every Model contract module; Model declarations contribute
`META-INF/io.fluxzero.sdk.modeling.Model`. Rebuild older contract JARs to generate this index. A classic shaded JAR
must append all contributing Model indexes (Maven Shade: `AppendingTransformer`); service merging alone is insufficient.
Cold discovery uses locally available classes and never registers their handlers or loads missing JARs automatically.
Abstract/interface contracts with an identity remain discoverable; identity-less inheritance templates are excluded.

For explicit replay-free current state, use `Fluxzero.loadCurrentModelState(id, ModelType.class)` (Kotlin:
`ModelType::class.java`) or the typed-ID overload. It returns read-only `ModelState<T>`, verified against a durable
head, and requires a maintained Model document plus shared state contracts. Missing/deleted Models are distinct from
missing/stale/unversioned documents, which fail. It does not populate replay caches or join transaction readsets.
It also requires a matching Runtime and a document whose body/head proof was captured during trusted Model
materialization/adoption. Older unproven documents and ordinary search overwrites are not silently accepted.
Use injected Models/Graphs for invariants; `loadCurrentGraph` still uses the authoritative load path, not this API.

Use `@Model` for persisted domain state. Do not introduce `@Aggregate` in new code. Existing aggregate APIs remain the
compatibility boundary for already persisted aggregate state.

## Core rules

1. Implement model state as immutable records or value objects.
2. Put action-specific `@AssertLegal`, `@InterceptApply` and `@Apply` methods on the command/update payload by default.
3. Keep `@Apply` pure and deterministic. It is reused during event sourcing.
4. Do not load, search, publish or perform I/O from `@Apply`.
5. Choose every business-model boundary by lifecycle first. Business state that can be created, changed, retained, deleted, or whose
   history matters independently is a separate `@Model`, even when it is normally placed in a parent's collection.
6. Treat a meaningful identity, separate retention, or independent updates as evidence for that boundary, not as
   competing criteria. A child without a globally unique functional ID can use `@EntityId(parentScoped = true)`.
7. Use `@Member` only when creation, every change, history, stream, document, cache, retention, and deletion all
   deliberately belong to the root. Collection shape, searchability, storage choice, and update frequency never make
   independently living state a member.
8. Use typed `Id<T>` values. The exact `Id.toString()` is the persisted model identity.

## Choose the owner before the lifecycle boundary

First distinguish business facts from integration execution. A payment capture or refund obligation belongs in a
Model; provider attempt IDs, idempotency keys and retry progress usually belong in a `@Stateful` workflow. Choose
Model versus Member only after deciding that the state belongs in the domain. See
[stateful handlers](https://fluxzero.io/docs/guides/modeling-and-persistence/stateful-handlers) for durable effects and recovery.

## Choose details, configuration and state

Business details copied into Model state belong in a cohesive immutable value object, even if it initially contains
only `name`. Choose the group by domain meaning and shared validation, not by field count or Java/Kotlin type.

| Kind of field | Put it where | Examples and boundary |
| --- | --- | --- |
| Descriptive business data | A details value object | `ProjectDetails(name, description)`; start with `ProjectDetails(name)` if that is all the product needs. |
| Configuration / desired policy | A focused settings value object | `NotificationSettings` groups related choices; do not mix unrelated settings into descriptive details. |
| Identity | On the Model, with a typed ID | `@EntityId ProjectId projectId` is not an editable detail. |
| Relationships | An explicit typed reference on the Model | `ownerId` or `@Parent workspaceId`; a reference is not the related Model's details. |
| Current status / control | A simple Model field, or a focused state value when several fields form one invariant | `archived`, `status`, `completedAt`; a business date such as a requested delivery date belongs with its business details instead. |
| Execution bookkeeping | Decide whether it is domain state or workflow memory first | Provider correlation, retries and pending effects usually belong in `@Stateful`; an independent attempt lifecycle alone does not justify a business Model. |

A boolean can be a user preference or observed state; a timestamp can be business input or execution bookkeeping.
Their meaning decides placement. A details object is not a bag for every field left over after the ID.
Use multiple small named values when the concepts differ. It has no independently addressable lifecycle:
plain `ProjectDetails` needs neither `@Model`, `@EntityId` nor `@Member`. Replacing that value is a change to
its owning Model, not a separate entity update. The Model/Member lifecycle rules still apply to actual entities.

## Define a model

```java
@Model
@With
public record Project(
        @EntityId ProjectId projectId,
        ProjectDetails details,
        UserId ownerId) {
}

@With
public record ProjectDetails(
        @NotBlank String name,
        @Size(max = 500) String description) {
}
```

Java uses Lombok `@With` and Jakarta Validation constraints.
`ProjectId` and `UserId` are application-owned typed IDs; `Sender` is the application's `User` implementation.

The creation command carries the whole details value and uses `@Valid` to cascade into its constraints.
A focused `RenameProject(id, name)` is still the right contract for renaming: command shape expresses intent,
not the stored object's shape. Validate its new name and replace only that field of the existing details.
Keep the description, identity and owner unchanged; do not construct an otherwise empty replacement details object.
Reserve whole-details replacement for an operation that intentionally edits the whole group.

Bean constraints validate incoming values; `@AssertLegal` protects state-dependent rules such as ownership.
`@Apply` only constructs the new immutable state. For a rule involving both the changed field and retained fields,
validate that combined candidate in `@AssertLegal` as well; a field constraint alone cannot express that rule.
The SDK does not automatically validate every returned Model. Enable cascaded bean validation at each input boundary
that accepts details; simply annotating a field inside `ProjectDetails` does not cascade from an unannotated command.

This is a modeling convention, not a new SDK restriction. For already stored Models, moving `name` to `details.name`
changes serialized shape and query paths: plan the appropriate event/document upcasting or migration rather than
silently renaming fields in an existing application's history.

## Storage is a separate choice

**Want to use `previous()`? Keep `EVENT_SOURCED` enabled** (the default `@Model` already does). `DOCUMENT` alone
stores only the current document, not previous versions. Adding `DOCUMENT` to event sourcing preserves history;
replacing event sourcing with `DOCUMENT` removes that guarantee. Cache depth and snapshots are optimizations, not
substitutes for a durable event history. Every historical Graph node whose value you inspect needs that history.

The default above is event sourcing without a direct document or periodic snapshots. Add storage only for a concrete
read requirement. Use [the central Model query matrix](model-queries.md).
Relationship-scoped search can use an internal component without `DOCUMENT`; ordinary `search(T.class)` lists cannot.

Important settings:

- `name`: durable logical Model type name; defaults to the concrete class's simple name. Keep an explicit value stable
  across Java class/package renames. It is separate from serializer payload types and has no aliases or FQN fallback.
  `fluxzero.model.namePrefix` is prepended literally for applications sharing a namespace (`billing` + `Invoice` =
  `billingInvoice`). Changing either value after data exists requires an application-managed data transition.
- `persistence`: selects a non-empty set of durable representations:
  - `{EVENT_SOURCED}` (default): reconstruct from Model events, without a direct document.
  - `{EVENT_SOURCED, DOCUMENT}`: reconstruct from events; maintain an internal source and separate public DOCUMENT projection.
  - `{DOCUMENT}`: load authoritative state from the internal source, not an independently rewritten public projection.
- `ignoreUnknownEvents`: deliberately tolerates unhandled stored events during event-sourced reconstruction.
- `document`: optional `@DocumentProjection` configuration for the direct collection, timestamp paths, and public
  searchability. It is valid only when `persistence` contains `DOCUMENT`; use `searchable = false` for a document that
  remains parent/ancestor-queryable but has no public content indexes. The separate internal source supports Model
  loads, verified state and Graph composition; a Graph role retains its own internal indexes. Public rewrites cannot
  change that source. Use `@HandleDocument(modelState = T.class)` (Kotlin: `T::class`) for schema-only source reindexing;
  `documentClass` selects the public projection and `modelGraph` the materialized Graph. See the migration guide.
- `eventPublication`: controls whether unchanged transitions create an event.
- `publicationStrategy`: `DEFAULT`, `STORE_AND_PUBLISH`, `STORE_ONLY` or `PUBLISH_ONLY`.
- `snapshotPeriod` and `maxSnapshotCount`: event-sourcing optimizations.
- `checkpointPeriod`: bounds repeated replay work within one reconstruction session.
- `cached` and `cachingDepth`: current and previous revisions retained in the SDK cache.
- `conflictPolicy`: `ACCEPT`, `RETRY`, `FAIL` or inherited `DEFAULT` for concurrent writes.
- `commitPolicy`: controls commit timing and completion-phase concurrency; normally keep `DEFAULT`.
- `automaticHandling`: opt out when an explicit command handler must call `Fluxzero.assertAndApply`.
- `materializeGraph`: enables the optional durable whole-tree read model.
- `graphProjection`: optional advanced `@GraphProjection` configuration; its collection defaults to the resolved direct
  Model collection plus `-graphs` when a direct document exists, or `<logical Model name>-graphs` otherwise, and
  materializes the complete finite graph without implicit size limits.

Persistence does not control event storage or publication. Those remain owned by `eventPublication`,
`publicationStrategy` and per-apply overrides. Internal Graph-component documents are also orthogonal: they neither
make an `EVENT_SOURCED` Model directly searchable nor change its load path. Event-sourcing-only options such as
`ignoreUnknownEvents`, snapshots and replay checkpoints are rejected on `DOCUMENT`-only Models.

## Persistence and protection boundaries

Storage and query visibility are not authorization. `DOCUMENT` with effective `eventPublication = NEVER`
can persist current state without Model events, but has no history or `previous()`. Event-sourced state changes
must store their event. This does not suppress incoming request logs, results or application logs.
`@DocumentProjection(searchable = false)` removes unrestricted typed search, not identity or exact
parent/ancestor reads. Graph predicates can still use internal content indexes independently of the public projection.

`@ProtectData` on an input does not carry over to copies in Model state, snapshots, documents or return values.
Result payloads can declare their own protected fields for normal RESULT dispatch; do not infer HTTP-body protection.
Use trusted handlers and deployment access controls for sensitive state; do not treat a Graph or non-searchable
document as a secret store. There is no implicit KMS, encryption-at-rest or backup guarantee.
Returning `null` from `@Apply` is logical deletion, not physical erasure. Model erasure fences stale writes
but global event logs, surviving shared event references, external copies and backups have separate lifecycles.
Use one Model action for atomic related state changes; external I/O and schedules are outside that transaction.

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

An incompatible create/update rejects the action with the functional already-exists/not-found error, not silent
success. A nullable current-state parameter expresses an explicit upsert. Disabling compatibility checks suppresses
the refusal but does not turn a create-only factory into an overwrite. The existing
`fluxzero.assert.apply-compatibility` property and its exception overrides also apply to Models.

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

Use `FunctionalException` subclasses for expected business refusals: `IllegalCommandException` for an invalid action,
`UnauthorizedException` for authorization, and Bean Validation for malformed input. Do not use technical exceptions
such as `IllegalStateException`, `IllegalArgumentException`, or Kotlin `require`/`check` for expected domain failures.

```java
public record RenameProject(@NotNull ProjectId projectId,
                            @NotBlank String name) {
    @AssertLegal
    void assertOwner(Project project, Sender sender) {
        if (!project.ownerId().equals(sender.userId())) {
            throw new UnauthorizedException("Not allowed to rename this project");
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

Returning an injected parent/ancestor from a singular Model-returning `@Apply` makes it a write target. When no direct
write ID is supplied, the existing `@Parent` relation supplies it at the pinned pre-apply boundary, even when another
apply removes the child. Direct IDs retain precedence; qualify ambiguous ancestors with `@Association`.
A nullable read-only Model parameter also accepts a null identifying property, such as an optional enclosing folder
ID. Write identities must remain non-null. Merely injecting an ancestor does not update it.

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

## Relationships

Choose which relationships belong in the Graph first, then choose the deletion policy for each relationship.
A meaningful Graph relation without ownership is a normal use of `@Parent`, not an exception to the model.

| Intent | Modeling |
| --- | --- |
| Store an ID without registering a Graph relation | A plain typed ID, without `@Parent` |
| Register a Graph relation with cascade deletion | `@Parent` (the default `deleteOnParentDeletion = true`) |
| Register a Graph relation without cascade deletion | `@Parent(deleteOnParentDeletion = false)` |

A typed ID alone does not create a Graph edge. Adding `@Parent` makes the relation available to Graph navigation;
`pathInParent` separately chooses whether to include it at a named document/serialization path.

For example, one Model can have two parents with different meanings:

```java
@Model
record LineItem(
        @EntityId LineItemId lineItemId,
        @Parent(pathInParent = "lines") OrderId orderId,
        @Parent(deleteOnParentDeletion = false) ProductId productId,
        int quantity) {
}
```

Here `LineItemId`, `OrderId` and `ProductId` are typed `Id<T>` values for their respective Models.
The same line belongs to its order and has a non-owning Graph relation to its product. Deleting the order cascades
to the line; deleting the product does not. Both relations support typed Graph navigation. The product edge has no
`pathInParent`, so it is not automatically included in a product's composed document. Leave off `@Parent` on
`productId` instead when only the reference value is needed, without Graph navigation.

`deleteOnParentDeletion = false` does **not** prevent the referenced Model from being deleted. Enforce a domain
rule separately when deletion must be refused while references exist. `@Parent` is not an unrestricted foreign-key
annotation: concrete cycles between Model IDs are rejected, including cycles containing non-owning edges.

Use `@Parent` on the child:

```java
@Model
public record Task(
        @EntityId TaskId taskId,
        @Parent(pathInParent = "tasks") ProjectId projectId,
        TaskDetails details,
        boolean completed) {
}
```

The child remains an independent Model with its own lifecycle boundary. This task's `@Parent` expresses both a
Graph relation and cascade ownership. Being displayed below or deleted with the parent does not make it a `@Member`.

- Updating `projectId` moves the task.
- The parent and siblings do not need to load for a task-only change.
- Typed `Id<Parent>` supplies the relation type. A role is only needed for untyped/ambiguous IDs.
- For one polymorphic typed relation, use `@Parent(types = {Project.class, Folder.class}, ...) Id<?> parentId`; the
  concrete typed ID selects one statically declared parent type. Use separate properties for distinct relation roles.
- `pathInParent` is a stable public graph-placement and serialization contract. A pathless relation remains available through
  typed `Graph` traversal and parent-deletion lifecycle handling, but is not emitted as a named JSON graph edge.
- A child is logically deleted by default when any parent referenced by that `@Parent` is finally deleted. Set
  `deleteOnParentDeletion = false` for a non-owning relation, including shared or independently retained children.
- Relationships are temporal; graph reconstruction can pin a `stateIndex`.
- Same-type recursion is supported. A `Folder` may hold
  `@Parent(pathInParent = "folders") FolderId parentFolderId`; Fluxzero accepts an arbitrarily deep tree and atomically rejects
  a concrete cycle. This remains a relation between independent Models, not an embedded recursive object.

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

Every root and descendant in a materialized Graph retains its own serialized type and `@Revision`. The ordinary
serializer upcasts nodes independently and lazily; do not create a Graph-wide upcaster. Use
`@HandleDocument(modelGraph = Root.class)` and return the complete Graph only when evolved node JSON must be persisted
back into the derived projection. That operation must preserve the root, state boundary, nodes and placements and does
not modify direct Models, histories or relationships.

## Embedded members

`@Model` plus `@Member` is the intentional shared-stream option:

```java
@Model
public record Invoice(
        @EntityId InvoiceId invoiceId,
        @Member List<InvoiceLine> lines) {
}

public record InvoiceLine(
        @EntityId LineId lineId,
        BigDecimal amount) {
}
```

Choose this only if each line has no meaningful lifecycle outside its invoice: creation, every change, history,
retention and deletion all belong to the root. If any of those concerns can diverge, use a separate `@Model` plus
`@Parent`. A list-shaped field, frequent updates, or convenient whole-document storage is never sufficient reason to
use `@Member`.

Member updates still address the owning Model: include its typed ID in a command or select it explicitly with
`Fluxzero.loadGraph(ownerId).assertAndApply(update)`. A member ID alone does not identify a Model stream.
Matching member `@Apply` methods update the immutable owner (including lists, maps and singletons); records use their
constructor, Kotlin data classes their copy operation, or a configured member wither. Payload-root changes run first,
then embedded member changes, then the root's Model apply. Each runs once and the composed result has one root event
membership, not a separate member stream. Member and member-dependent payload assertions participate in the root
operation before/after application, including their Model/Graph read dependencies. Replay reconstructs the same
member changes without re-running assertions.

Handlers on concrete subtypes of an open member hierarchy also work when the owning Model is addressed.
Use `loadGraph(ownerId).assertAndApply(update)`, or `Fluxzero.assertAndApply(update)` with a typed owner ID.
The loaded member determines its handlers and Model/Graph dependencies, including after payload-root changes.
Those dependencies use the same commit boundary; replay loads their historical values.
Automatic command subscriptions still require a discoverable payload/declared/sealed handler contract: registering
an owner does not scan the classpath for arbitrary implementations of an open member interface.
The corrected member replay also applies to existing RC event history. Previously produced snapshots may retain
the old, incomplete state: reconstruct affected RC data from its event history rather than treating those snapshots
as equivalent to a fresh replay.

## Loading and event parameters

```java
Project project = Fluxzero.loadModel(projectId).get();

Graph<Project> graph = Fluxzero.loadGraph(projectId);
Project sameProject = graph.get();
String publicId = graph.functionalId();
List<Task> tasks = graph.childModels("tasks", Task.class);
Graph<Project> previous = graph.previous();
```

Prefer direct `T` injection when only the current value is needed. Inject `Graph<T>` when code needs parents,
children, descendants, history or staged updates. Resolving the graph itself costs the same model load as direct value
injection; relationships are fetched only when traversed. Typed ancestor lookup follows relationship identities first
and loads only the selected ancestor value. Every child is itself a graph, so `parent()`, `root()`,
`previous()`, `atStateIndex(...)`, `apply(...)` and `assertAndApply(...)` remain available at every placement.

`graph.assertAndApply(command)` selects that Model's writes, but does not narrow assertions on the command:
an `@AssertLegal` that reads another Model still runs, including before/after checks and returned validation objects.
Its read dependencies participate in RETRY/FAIL. Model-owned handlers retain their existing target filtering;
unselected applies do not become extra writes. Required assertion bindings must resolve rather than silently disappear.

`id()` is the collision-safe repository identity; `functionalId()` is the public ID from the current or last present
model value and omits repository affixes or parent scope. `stateIndex()` pins the complete graph read, while
`revisionStateIndex()` reports when the selected node revision became current.

Within a Model mutation, injected Graphs and synchronous `loadGraph`, `loadCurrentGraph` and `graph.current()`
reads on the same repository/namespace share the attempt's pinned boundary and staged state. Actual value and
relationship reads join its conflict dependencies, including empty collections. `current` does not open a second
snapshot mid-mutation. Outside mutations, ordinary event-handler reads remain event-bound; explicit current reads
open a fresh view for deliberate reconciliation, such as scheduling against current intent.

Outside historical event handling, a new detached Graph establishes its snapshot against storage, not the age of a
cached root. Reading `get()` before `children(...)` therefore does not hide already committed relation changes.
Typed lazy loads pin on their first storage read; an untyped load pins when it resolves the root identity.
Once pinned, that Graph stays on its snapshot: use a new view to observe later commits.

A mutation may start from a coherent cached boundary. Successful decisions validate their relevant read dependencies
at commit: RETRY reevaluates after a conflict; FAIL rejects. An assertion that already rejects is not refreshed or
retried merely because newer state might allow it. Existing injection paths can establish freshness up front, but a
manual Graph discovered during apply keeps the attempt's boundary; it does not force a read-RPC on every ordinary
cache-only command. Relationships remain lazy. Pending state belongs to the owning repository family and namespace,
never another application's cache or batch. See the invariant example at `/docs/sdk/entities/assert-legal`.

DOCUMENT-only mutations read authoritative documents without replaying published history. A simple single-target
write using built-in RETRY needs no extra namespace head read unless it requests an additional transactional
dependency. The first such read verifies the original document revision and pins one shared namespace boundary.
If that revision changed, the whole evaluation restarts eagerly, consuming the normal retry budget; assertions
and applies must therefore be repeatable. Once pinned, the boundary never moves.
Complex targets, known Graph dependencies, aliases, parent bindings, batches and custom conflict handling retain
eager preparation: one batched head read, with head/document preparation races retried at most eight times before
user code runs. An unavailable pinned document version fails explicitly; use EVENT_SOURCED for historical reads.

Open `graph.current()` for the same Model without replacing the original Graph. Outside mutations it pins a new
current boundary during the call and captures pending batch changes; inside a mutation it shares that attempt's
boundary, staged values and read dependencies. It retains exact identity and owning repository/namespace, including
affixes and parent scope. A moved node gets the parents at that selected boundary; a deleted Model is empty. Filters,
mapped values, uncommitted edits on the source Graph and response context are not copied. Reapply presentation filters.
Unknown nodes and custom repositories without the required current-read capability fail explicitly.

Use `graph.delete()` to stage logical deletion of a selected node; return or explicitly commit that resulting graph
according to the surrounding handler contract.

Use `optional()`, `map(...)`, `mapIfPresent(...)` or `filterPresent()` for wrapper/value handling that must not load
relationships. `stream()` walks every placement lazily in deterministic order; `find(idOrAlias)` and
`find(idOrAlias, ModelType.class)` search primary IDs and `@Alias` values without hard-coding paths. For event-driven
before/after logic, use `previous()`, `hasChanged(selector)`, `previousValue(selector)` or `revisions()`. These APIs keep
`Entity<T>` as a persistence/legacy-aggregate detail rather than a normal handler parameter.

Returning a `Graph<T>` from a handler serializes the current model plus all explicitly named relationship paths.
Pathless relations remain queryable through the typed graph API but are intentionally absent from that JSON shape.
Use `selectPaths(...)`, `filterNodes(...)` or ancestor-preserving `filterBranches(...)` for immutable response views;
accepted model values are shared.
Annotate a model method with `@GraphProperty` when a serialized property is derived from the current graph or a typed
ancestor graph. It is evaluated only during graph serialization and reuses the graph already in memory.
For one response-wide lookup that several nodes consume, attach the already-batched result once with
`graph.withContext(value)` and read it inside the property method with `graph.context(ValueType.class)`; graph context
is immutable, shared across the view and never persisted as Model state.

Use `children(path)`, `children(path, modelName)`, `namedChildren(modelName)` and the corresponding
`descendants`/`namedDescendants` forms for metadata-first Graph selection. They return Graph nodes and
accept a final `knownOnly` boolean, default `true`. Pass `false` to include unknown types when counting all
matching placements; known-only results are not a complete cross-app quota. Names match exact resolved
Model names, including any prefix. `modelName()` remains readable for unknown nodes; `knownType()` is empty.
Their identities/relations can be traversed, but type/value/history/update access fails, never pretends the
Model is absent. Class-based selection matches only locally known assignable types; unknown intermediate
nodes do not hide known descendants. Values remain lazy and pinned; injected membership reads, including
empty selections, participate in conflict handling. Full materialization still requires the value/replay contracts.
Lazy root aliases resolve through head metadata without replay in the default repository. The initial lookup uses
the current alias table, even for historical reads; its canonical ID or absence and value/relationship boundary
then stay pinned. Inside a Model mutation, consumed alias lookups participate in commit conflict detection,
including missing aliases, `id()` and relation-only access. Exact-ID reads do not depend on alias mappings.
This does not introduce historical alias reconstruction. `ACCEPT` retains only apply-time dependencies.
Remote alias navigation requires the accompanying Runtime update: alias heads use the existing general
transport to preserve both requested and canonical IDs; non-alias compact replies remain unchanged.


With metadata-capable repositories, `selectPaths(...)` selects before reconstruction: creating a view performs
no storage reads. Its paths are relative to the selected Graph, including a child Graph, and its ancestor placements
remain navigable. Custom repositories retain their existing value-based fallback. Exact-ID `find(...)` scans relationship metadata
before inspecting aliases; typed lookup registers the requested local contract and excludes unrelated unknown
types. Alias and parent-scoped functional-ID matching may still require values of matching types.
`sequenceNumber()` and `revisionStateIndex()` use pinned head evidence for still-lazy persisted nodes; pending
and custom revisions retain their own semantics.

The default repository discovers roots for `loadCurrentGraph(...)` and untyped `Fluxzero.loadGraph(id)` from
head metadata without replay. These factories pin their boundary during the call; later value and relationship
reads retain that boundary. Untyped root discovery still requires a locally known root Model contract. This
changes when values are reconstructed, not their authoritative persistence/replay contract or transaction scope.

Outside a mutation, `loadCurrentGraph` establishes a fresh storage boundary even when the root is cached;
an older root observation cannot prove unchanged relationships. Event-sourced root values remain lazy.
For a DOCUMENT-only root, the factory resolves its head and document coherently before returning and retains
that value: subsequent replacement or deletion cannot change the returned root. Relationships and descendants
remain lazy; this does not create historical document versions.
Within a mutation it instead reuses the attempt boundary and joins its readset.
An unpinned current value read retries the complete lookup a bounded number of times if its head and document
change during resolution. Historical, already pinned and mutation-shared reads never silently advance.
Persistent instability fails with an explicit platform error.

Use `@Alias` for a current alternative identity of an independently stored model:

```java
@Model
record Project(
        @EntityId ProjectId projectId,
        @Alias(prefix = "external:") String externalId) {
}

Project project = Fluxzero.loadModel("external:123", Project.class).get();
```

The complete alias set is replaced atomically with each transition. Independent-model aliases are global and must be
unique; primary model IDs take precedence over equal aliases.

Current loads use the model cache and its long-polling update tracker. Event handlers use the event's exact model-commit
boundary:

```java
@HandleEvent
void on(RenameProject event,
        Project project,
        Graph<Project> graph) {
    // Exact state after this event, not latest state.
}
```

Directly affected event/notification models support `T` and `Graph<T>`. Use `Graph<T>` to observe an absent model after
logical deletion or to compare `get()` with `previous()`. An ordinary indexed event without model-commit metadata may
inject directly addressed Models at one current pinned boundary. If a migration has linked that global event to a
Model commit, the same injection resolves its exact historical state instead. Such an event is not implicitly a
complete graph-change subscription; that requires the durable Model commit metadata.

## Complete graph-change handlers

Use an unqualified `Graph<T>` as the sole handler parameter to subscribe to every durable change of that root or one
of its descendants:

```java
@HandleEvent
void projectChanged(Graph<Project> graph) {
    Graph<Project> before = graph.previous();
}
```

Creation has no previous graph; deletion supplies an empty current graph and the complete deleted graph through
`previous()`; moving a child invokes both old and new roots. The previous graph is commit-exact and does not depend on
cache depth. One handler object may declare several such methods for distinct root types. Adding an explicit event
payload turns the method back into ordinary payload handling with direct/ancestor Graph injection.

Cascade deletions use the same contract: a sole `Graph<Task>` handler sees an empty Task and its previous value even
when a Project deletion caused it. No parent-specific cleanup handler or extra public technical event is required.
The original domain event identifies the internal deletion boundary. Ordinary payload handlers keep their own event
boundary; a child updated and subsequently cascaded in one commit is observed at each change's own boundary. Surviving
shared ancestors also observe removal; an already deleted ancestor is not notified twice for the same deletion.
The cascaded child's `previous()` retains the state before its own deletion substep: its value is available, but
an ancestor already deleted in an earlier substep is no longer reachable. It does not rewind the whole commit.
This linkage is emitted by new commits, not retroactively added to older events. Suppressed event publication and
physical erasure are not new domain-event notifications. Handlers remain subject to normal retry/redelivery rules.

Historical value comparison requires stored Model history. With `EVENT_SOURCED` (also when combined with `DOCUMENT`),
previous values can be replayed after a cache clear; cache depth and snapshots do not automatically prune Model events.
`DOCUMENT` alone maintains current state, not document versions: a normal loaded Model has no durable `previous()`,
and an event-boundary read cannot recover an overwritten document. Historical Graph values must be available for
every node you actually inspect. Use event sourcing when before/after processing is required, not duplicated
`previous...` fields as a general workaround. Explicit physical erasure or intentionally incomplete history remains
a separate limit; there is no general automatic event-retention policy implied here.

## Search and graph composition

Read [Choosing Model and Graph queries](model-queries.md) before selecting storage or query APIs. Its matrix covers plain event-sourced Models, explicit component paths, direct documents, reference-only
documents and materialized Graphs, with executable Java/Kotlin query examples and state guarantees.

Start with `@Model`. An explicit `@Parent(pathInParent = "...")` maintains an indexed internal component and supports
relationship-scoped search without `DOCUMENT`. Use a direct public document for unrestricted typed Model lists.
Identity-based Graph navigation needs neither document nor composition path. Search Graphs include explicit paths
only and are document-backed; they do not inherit an event handler's historical boundary or transaction readset.
Materialized Graphs may lag, whereas live composition can require broad candidate work before filtering/pagination.
Relation queries and live Graph composition do not support the statistics-based `count()` terminal.

## Conflict policy

Model commits default to `ModelConflictPolicy.DEFAULT`, resolved from apply/model, builder configuration or application
properties. Public policies are:

- `ACCEPT`: preserve the event once; rebase derived documents and relationships on current merged model state.
- `RETRY`: reload and rerun assertions/interceptors/applies.
- `FAIL`: return the conflict.

If multiple applies request different policies, the stricter applicable policy wins; failure is not weakened by retry.

`DEFAULT` inherits explicit Model/application configuration and otherwise means `RETRY`, for both updates and first
creations, independently of `fluxzero.defaults.version`. Use `fluxzero.model.conflictPolicy`
(`FLUXZERO_MODEL_CONFLICT_POLICY`) or builder/Model/Apply settings to choose an explicit policy.
A changed Product or parent collection therefore reevaluates a new child's creation. This does not make a factory an
upsert: the normal apply-compatibility check still rejects an occupied target after retry. An intentionally nullable
existing-Model apply is a separate upsert choice. A staged Graph update that started from absence also cannot overwrite
a concurrent creation. Explicit ACCEPT still fails a first-creation conflict instead of rebasing it into an overwrite.
ACCEPT validates apply dependencies and writes, excluding
assertion-/interceptor-only reads; RETRY and FAIL validate the full evaluation readset. Conflict-free eligible Runtime
commits use the same cached-head/atomic-boundary optimization regardless of policy.

With ASYNC consumer handling, automatic Model commits that start after the handler also coordinate overlapping
readsets within the tracking batch. Evaluation stays parallel; a ready commit first waits for earlier evaluations to
discover their readsets, then waits only for overlapping predecessors in the same namespace and reevaluates before
committing. Disjoint scopes can commit concurrently. A Model command that read pending state waits for its producer
to finish, then reevaluates even if that producer failed. Its own assertions determine its result; it does not inherit
another command's rejection. A validation failure against pending state is provisional too: partial changes are never
staged, and evaluation resumes after the discovered predecessors settle. This dependency coordination does not consume
or increase the configured conflict retry budget. External writers and newly discovered readsets still require atomic
conflict validation. Ordinary handlers that return results based on pending state retain their producer-success barrier;
they cannot publish a successful result from state that was rejected.

Injected and synchronous manually loaded Graph reads inside a Model mutation count: values/type/alias/revision reads protect Model heads; child collections (including empty
ones), parent navigation and indirect ancestor selection protect inspected relationships. Scans include rejected candidates.
Do not replace graph invariants with an extra guard Model solely to detect membership races on a matching post-RC8
SDK/Runtime. RETRY reevaluates on a fresh pinned boundary; FAIL rejects; ACCEPT retains only apply dependencies through
every rebase. Complete reads within evaluation, including joined parallel scans. Historical views, external search and
unrelated repository reads are not implicitly transactional. Types sharing a path share a conservative dependency;
remapped paths protect all source paths, and physical erasure invalidates older Graph reads namespace-wide.
Upgrade all Runtime instances first: older Runtimes reject the new relationship-aware wire request. Eligible reads at
the exact cached namespace boundary or across known contiguous head-only writes retain atomic-CAS planning; other older
reads need database validation. Unused or value-only Graph injection resolved directly by ID adds no membership proof/query;
indirect ancestor injection still protects the relationships used to select that ancestor. Writers still retain
evidence of removed/reparented relations, even without their own Graph injection, to protect concurrent readers.
Head-only writes remain batchable. Physical cleanup advances the namespace and an identity-free cleanup position. Stored Model
payload/history formats are unchanged. Custom repositories must return SDK views such as `Graphs.compose` for
transactional navigation; opaque custom Graphs fail explicitly, while ordinary custom reads remain supported.

## Deletion

- Returning `null` from `@Apply` is logical deletion and preserves history.
- Logical parent deletion recursively deletes children whose relevant `@Parent` keeps the default
  `deleteOnParentDeletion = true`. This follows pathless relations and shared descendants too; a shared descendant is
  deleted when any owning parent disappears. Moving a child away in the same atomic commit preserves it.
- `modelRepository().deleteModel(id, NONE)` physically erases that model's stream, current document, snapshots and
  cache state while leaving the global event log untouched.
- Physical descendant erasure remains a separate destructive operation and requires `planDeletion(...)` followed by
  confirmation/execution of that exact plan.
- Erasure fences prevent delayed document, snapshot or projection writes from resurrecting deleted data.
- Relations closed by parent deletion remain discoverable for later descendant erasure, including nested logical
  cascades. Earlier ordinary detachments or moves are not added back to the deleted tree.
- Always inspect the deletion plan before confirming it. Upgrading does not repair missing lineage markers written
  by older implementations; an already logically deleted tree needs separately verified scope before erasure.

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
For an event-sourced backfill, configure `PublishedEventModelMigration` with a stable name, isolated client, legacy
serializer/upcasters and the replacement Model packages or types. Run it without arguments for replay and as
`adopt <cutover-event-index>` for cutover. The SDK-owned consumer is always global, synchronous, single-tracker and
fail-fast; it completes each Model commit before advancing its durable position, and replicas with the same name
provide failover. Replay runs payload then Model `@Apply`, retains the original event index and message ID, does not
republish, and is idempotent. It does not recover legacy `STORE_ONLY` events. A listener application that gradually
moves legacy event handlers to Model/Graph injection should configure its owning repository with
`followPublishedEventMigration(theSameName)`. Mapped events stay on the ordinary read path; only a missing mapping waits
for the durable consumer and then retries exactly. Keep legacy Aggregates as the sole write owner during this read
phase, and do not let moved listeners apply changes back to them.
Document-backed Models are rebuilt in invisible staging; adoption through the owning `ModelRepository` upcasts and
compares every staged and production value, atomically adopts only unchanged equal results without rewriting existing
documents, and rebuilds declared materialized Graphs.
The accepted normalized source remains isolated from later staging until the first ordinary Model write, so resumed
legacy traffic can be caught up and re-adopted without using unverified document content in materialized Graph
composition. Repeat the plural operation to resume a partial cutover. Switch command ownership only after catch-up,
exact state and Graph comparisons, converted listeners and representative performance all report `GO`. The first
ordinary Model write makes recovery forward-only; durable Model commit history may feed an application-specific
emergency legacy projection, but there is no generic post-write rollback contract.
All new examples and implementations should use `@Model`.
