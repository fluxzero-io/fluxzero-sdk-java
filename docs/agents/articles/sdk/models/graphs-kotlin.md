# Model relationships and lazy Graphs (Kotlin)

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

## Selective Graph navigation across applications

Use `children(path)`, `children(path, modelName)`, or `namedChildren(modelName)` for metadata-first
selection returning `List<Graph<?>>`. The corresponding `descendants`/`namedDescendants` methods traverse
deeper placements. Each accepts a final `boolean knownOnly`, defaulting to `true`: this selects only locally
known Model types, not necessarily every stored child. Pass `false` when a quota or inventory must include
unknown types:

```kotlin
val reservations = graph.namedChildren("stock-reservation", false)
val count = reservations.size // No child values or historical events are loaded.
```

Names are exact resolved logical Model names, including any configured prefix, not serializer aliases or
Java supertypes. The same name in two applications denotes the same shared contract, not separate ownership.
Counting remains metadata-only even when that name is known locally but historical event classes are unavailable.
`modelName()` exposes the logical name; `knownType()` is empty for a locally unknown Model. Such nodes retain
IDs and relationship navigation, but reading their type/value/history or updating them fails explicitly.
Unknown does not mean absent or deleted. A known class likewise does not suppress replay or application errors.

Class-based selection matches locally known assignable types only, including when querying `Object.class`.
Unknown intermediate nodes do not hide known descendants. Direct child paths are exact (`null` means pathless);
descendant paths are root-relative slash-separated paths (`null` means all paths). Counts count placements:
a shared Model reached through different graph paths can occur more than once.

Selections share a pinned boundary and preserve injected Graph relationship dependencies, including empty
results, add/remove/reparent and retries. Values are reconstructed only when requested at that boundary; no
automatic current-state fallback or unknown-event skipping occurs. Full Graph materialization/serialization
still requires the contracts for the values being materialized. Custom repositories without metadata navigation
retain their existing loading behavior and cannot promise unknown-type-safe selection.

An unresolved `@Alias`-capable root can still require root replay to resolve its identity, even with a
canonical-looking String. Use an already resolved root or
`Graphs.lazyRepositoryId(canonicalId, Root::class.java, repository)` when the exact persisted ID is known.
Root lookup errors are not empty child collections.

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
