# Models and state

Use `@Model` for persisted domain state. Do not introduce `@Aggregate` in new code. Existing aggregate APIs remain the
compatibility boundary for already persisted aggregate state.

## Core rules

1. Implement model state as immutable data classes and value objects.
2. Put action-specific `@AssertLegal`, `@InterceptApply` and `@Apply` methods on the command/update payload by default.
3. Keep `@Apply` pure and deterministic. It is reused during event sourcing.
4. Do not load, search, publish or perform I/O from `@Apply`.
5. Choose every model boundary by lifecycle first. State that can be created, changed, retained, deleted, or whose
   history matters independently is a separate `@Model`, even when it is normally placed in a parent's collection.
6. Treat a meaningful identity, separate retention, or independent updates as evidence for that boundary, not as
   competing criteria. A child without a globally unique functional ID can use `@EntityId(parentScoped = true)`.
7. Use `@Member` only when creation, every change, history, stream, document, cache, retention, and deletion all
   deliberately belong to the root. Collection shape, searchability, storage choice, and update frequency never make
   independently living state a member.
8. Use typed `Id<T>` values. The exact `Id.toString()` is the persisted model identity.

## Define a model

```kotlin
@Model(persistence = [ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT])
data class Project(
    @EntityId val projectId: ProjectId,
    val details: ProjectDetails,
    val ownerId: UserId
)
```

Assume conventional typed `ProjectId` and `ProjectDetails` value types; do not expand obvious ID or details
definitions unless the user asks for them.

Important settings:

- `name`: durable logical Model type name; defaults to the concrete class's simple name. Keep an explicit value stable
  across Java/Kotlin class or package renames. It is separate from serializer payload types and has no aliases or FQN
  fallback. `fluxzero.model.namePrefix` is prepended literally for applications sharing a namespace (`billing` +
  `Invoice` = `billingInvoice`). Changing either value after data exists requires an application-managed data
  transition.
- `persistence`: selects a non-empty set of durable representations:
  - `[EVENT_SOURCED]` (default): reconstruct from Model events, without a direct document.
  - `[EVENT_SOURCED, DOCUMENT]`: reconstruct from events and also maintain a current document.
  - `[DOCUMENT]`: load authoritative current state from the current document.
- `ignoreUnknownEvents`: deliberately tolerates unhandled stored events during event-sourced reconstruction.
- `document`: optional `DocumentProjection` configuration for the direct collection, timestamp paths, and public
  searchability. It is valid only when `persistence` contains `DOCUMENT`; use `searchable = false` for a document that
  should remain available by Model ID, alias, parent relation and Graph composition without entering typed search.
- `eventPublication`: controls whether unchanged transitions create an event.
- `publicationStrategy`: `DEFAULT`, `STORE_AND_PUBLISH`, `STORE_ONLY` or `PUBLISH_ONLY`.
- `snapshotPeriod` and `maxSnapshotCount`: event-sourcing optimizations.
- `checkpointPeriod`: bounds repeated replay work within one reconstruction session.
- `cached` and `cachingDepth`: current and previous revisions retained in the SDK cache.
- `conflictPolicy`: `ACCEPT`, `RETRY`, `FAIL` or inherited `DEFAULT` for concurrent writes.
- `commitPolicy`: controls commit timing and completion-phase concurrency; normally keep `DEFAULT`.
- `automaticHandling`: opt out when an explicit command handler must call `Fluxzero.assertAndApply`.
- `materializeGraph`: enables the optional durable whole-tree read model.
- `graphProjection`: optional advanced `GraphProjection` configuration; its collection defaults to the resolved direct
  Model collection plus `-graphs` when a direct document exists, or `<logical Model name>-graphs` otherwise, and
  materializes the complete finite graph without implicit size limits.

Persistence does not control event storage or publication. Those remain owned by `eventPublication`,
`publicationStrategy` and per-apply overrides. Internal Graph-component documents are also orthogonal: they neither
make an `EVENT_SOURCED` Model directly searchable nor change its load path. Event-sourcing-only options such as
`ignoreUnknownEvents`, snapshots and replay checkpoints are rejected on `DOCUMENT` Models.

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
@Model
data class Task(
    @EntityId val taskId: TaskId,
    @Parent(pathInParent = "tasks")
    val projectId: ProjectId,
    val details: TaskDetails,
    val completed: Boolean
)
```

The child remains an independent Model because its lifecycle is independent; `@Parent` expresses graph placement and
default cascade ownership. Being displayed below or deleted with the parent does not make it a `@Member`.

- Updating `projectId` moves the task.
- The parent and siblings do not need to load for a task-only change.
- Typed `Id<Parent>` supplies the relation type. A role is only needed for untyped/ambiguous IDs.
- For one polymorphic typed relation, use
  `@Parent(types = [Project::class, Folder::class], ...) val parentId: Id<*>`; the concrete typed ID selects one
  statically declared parent type. Use separate properties for distinct relation roles.
- `pathInParent` is a stable public graph-placement and serialization contract. A pathless relation remains available through
  typed `Graph` traversal and parent-deletion lifecycle handling, but is not emitted as a named JSON graph edge.
- A child is logically deleted by default when any parent referenced by that `@Parent` is finally deleted. Set
  `deleteOnParentDeletion = false` for deliberately detached or independently retained children.
- Relationships are temporal; graph reconstruction can pin a `stateIndex`.
- Same-type recursion is supported. A `Folder` may hold
  `@Parent(pathInParent = "folders") val parentFolderId: FolderId?`; Fluxzero accepts an arbitrarily deep tree and atomically
  rejects a concrete cycle. This remains a relation between independent Models, not an embedded recursive object.

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

Every root and descendant in a materialized Graph retains its own serialized type and `@Revision`. The ordinary
serializer upcasts nodes independently and lazily; do not create a Graph-wide upcaster. Use
`@HandleDocument(modelGraph = Root::class)` and return the complete Graph only when evolved node JSON must be persisted
back into the derived projection. That operation must preserve the root, state boundary, nodes and placements and does
not modify direct Models, histories or relationships.

## Embedded members

`@Model` plus `@Member` is the intentional shared-stream option:

```kotlin
@Model
data class Invoice(
    @EntityId val invoiceId: InvoiceId,
    @Member val lines: List<InvoiceLine>
)

data class InvoiceLine(
    @EntityId val lineId: LineId,
    val amount: BigDecimal
)
```

Choose this only if each line has no meaningful lifecycle outside its invoice: creation, every change, history,
retention and deletion all belong to the root. If any of those concerns can diverge, use a separate `@Model` plus
`@Parent`. A list-shaped field, frequent updates, or convenient whole-document storage is never sufficient reason to
use `@Member`.

## Loading and event parameters

```kotlin
val project = Fluxzero.loadModel(projectId).get()

val graph: Graph<Project> = Fluxzero.loadGraph(projectId)
val sameProject = graph.get()
val publicId = graph.functionalId()
val tasks = graph.childModels("tasks", Task::class.java)
val previous = graph.previous()
```

Prefer direct `T` injection when only the current value is needed. Inject `Graph<T>` for parents, children,
descendants, history or staged updates. Resolving the graph itself costs the same model load as direct value injection;
relationships are fetched only when traversed. Typed ancestor lookup follows relationship identities first and loads
only the selected ancestor value. Every child remains a graph with `parent()`, `root()`, `previous()`,
`atStateIndex(...)`, `apply(...)` and `assertAndApply(...)`.

`id()` is the collision-safe repository identity; `functionalId()` is the public ID from the current or last present
model value and omits repository affixes or parent scope. `stateIndex()` pins the complete graph read, while
`revisionStateIndex()` reports when the selected node revision became current.

Ordinary `loadGraph(...)` calls inside a handler inherit its coherent message or historical event boundary. Use
`loadCurrentGraph(...)` only after a synchronous nested command when later handler logic deliberately needs that
command's newer state. Do not use it as the default loading route.

Use `graph.delete()` to stage logical deletion of a selected node; return or explicitly commit that resulting graph
according to the surrounding handler contract.

Use `optional()`, `map(...)`, `mapIfPresent(...)` or `filterPresent()` for wrapper/value handling without relationship
loads. `stream()` walks every placement lazily in deterministic order; `find(idOrAlias)` and
`find(idOrAlias, ModelType::class.java)` search primary IDs and `@Alias` values without hard-coded paths. For
event-driven before/after logic, use `previous()`, `hasChanged(selector)`, `previousValue(selector)` or `revisions()`.
Normal application handlers therefore need `T` or `Graph<T>`, not the persistence-oriented `Entity<T>` wrapper.

Returning a `Graph<T>` from a handler serializes the current model plus all explicitly named relationship paths.
Pathless relations remain queryable through the typed graph API but are absent from that JSON shape. Use
`selectPaths(...)`, `filterNodes(...)` or ancestor-preserving `filterBranches(...)` for immutable response views;
accepted model values are shared. Annotate
a model method with `@GraphProperty` when a serialized property is derived from the current graph or a typed ancestor
graph. It runs only during graph serialization and reuses the graph already in memory.
For one response-wide lookup that several nodes consume, attach the already-batched result once with
`graph.withContext(value)` and read it inside the property method with `graph.context(ValueType::class.java)`; graph
context is immutable, shared across the view and never persisted as Model state.

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
    graph: Graph<Project>
) {
    // Exact state after this event.
}
```

Use `Graph<T>` to observe an absent model after logical deletion or compare `get()` with `previous()`. Ordinary events
without model-commit metadata may inject directly addressed Models at one current pinned boundary. If a migration has
linked that global event to a Model commit, the same injection resolves its exact historical state instead. Such an
event is not implicitly a complete graph-change subscription; that requires the durable Model commit metadata.

## Complete graph-change handlers

Use an unqualified `Graph<T>` as the sole handler parameter to subscribe to every durable change of that root or one
of its descendants:

```kotlin
@HandleEvent
fun projectChanged(graph: Graph<Project>) {
    val before = graph.previous()
}
```

Creation has no previous graph; deletion supplies an empty current graph and the complete deleted graph through
`previous()`; moving a child invokes both old and new roots. The previous graph is commit-exact and does not depend on
cache depth. One handler object may declare several such methods for distinct root types. Adding an explicit event
payload turns the method back into ordinary payload handling with direct/ancestor Graph injection.

## Search and graph composition

```kotlin
val open = Fluxzero.search(Task::class.java)
    .match(false, "completed")
    .fetchAll()

val related = Fluxzero.search(Task::class.java)
    .whereAncestor(
        Project::class.java,
        MatchConstraint.match("active", "status")
    )
    .fetchAll()
```

Use `.whereParent(projectId)` or `.whereAncestor(organisationId)` when the related typed ID is known. This traverses
durable relationships directly and needs no parent or ancestor document. Use the ID-plus-Model-class overload for
untyped functional IDs and a loaded `Graph` for parent-scoped identities. The returned target must still have either a
public document or a relation-scoped current component document maintained for Graph participation; standalone event-sourced targets
without one should be loaded by ID.

Use the class-and-constraint `whereParent`, `whereAncestor`, `whereChild` and `whereDescendant` overloads when related
IDs must first be selected by current Model content. They use that Model's own public document or independently
maintained Graph-component document. A reference-only `DOCUMENT` projection without such a Graph role does
not add content, facet or sortable indexes. `materializeGraph = true` supplies an internal root document but does not
make the whole Graph projection the related predicate source. Prefer
`searchGraph(Root::class.java).whereDescendant(Child::class.java, constraint)` over a broad
forced-live nested-path filter when the child type is known. Use
`searchGraph(Root::class.java).stream()` for complete typed lazy `Graph<Root>` results without a cast or type witness.
It reads a configured `@GraphProjection` by default and otherwise stitches the applicable current documents live;
pass `true` as the second argument to force live composition. Use `fetch(..., ObjectNode::class.java)` for explicit raw
JSON. Enable materialization with
`@Model(materializeGraph = true)`. Include `DOCUMENT` separately only when the Model itself needs a current document;
set `DocumentProjection.searchable = false` when that document must not be publicly searchable. Without a separate
Graph role its payload remains reference-loadable but its summary/reversary, facets and sortables are not indexed. A
Graph-component role retains its independently required indexes; shape those explicitly with `@SearchExclude`, `@Facet` and
`@Sortable`. A blank projection collection
appends `-graphs` to the direct Model collection when one exists, or to the
logical root-Model name otherwise; explicit lower-level composition limits fail rather than returning a partial graph.

## Conflict policy

Model commits default to `ModelConflictPolicy.DEFAULT`, resolved from apply/model, builder configuration or application
properties. Public policies are:

- `ACCEPT`: preserve the event once; rebase derived documents and relationships on current merged state.
- `RETRY`: reload and rerun assertions/interceptors/applies.
- `FAIL`: return the conflict.

If multiple applies request different policies, the stricter applicable policy wins; failure is not weakened by retry.

## Deletion

- Returning `null` from `@Apply` is logical deletion and preserves history.
- Logical parent deletion recursively deletes children whose relevant `@Parent` keeps the default
  `deleteOnParentDeletion = true`. This follows pathless relations and shared descendants too; a shared descendant is
  deleted when any owning parent disappears. Moving a child away in the same atomic commit preserves it.
- `modelRepository().deleteModel(id, NONE)` physically erases that Model's stream, current document, snapshots and
  cache state while leaving the global event log untouched.
- Physical descendant erasure remains a separate destructive operation and requires `planDeletion(...)` followed by
  confirmation/execution of that exact plan.
- Erasure fences prevent delayed document, snapshot or projection writes from resurrecting deleted data.
- Detached descendants remain discoverable through deleted-parent lineage for later GDPR/lifecycle erasure.

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
