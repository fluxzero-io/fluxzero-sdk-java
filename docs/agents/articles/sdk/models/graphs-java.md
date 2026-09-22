# Model relationships and lazy Graphs (Java)

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
  The standard serializer writes `{"name":"project","id":"owner-a"}` for such a property (using the actual logical
  Model name). Each allowed Model declares its concrete ID class as `@EntityId`; `types` is also the read allowlist.
  No additional `@JsonTypeInfo` is needed. Unknown, ambiguous or conflicting names fail rather than guessing.
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

## Selective Graph navigation across applications

Use `children(path)`, `children(path, modelName)`, or `namedChildren(modelName)` for metadata-first
selection returning `List<Graph<?>>`. The corresponding `descendants`/`namedDescendants` methods traverse
deeper placements. Each accepts a final `boolean knownOnly`, defaulting to `true`: this selects only locally
known Model types, not necessarily every stored child. Pass `false` when a quota or inventory must include
unknown types:

```java
var reservations = graph.namedChildren("stock-reservation", false);
int count = reservations.size(); // No child values or historical events are loaded.
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

Selections share a pinned boundary and preserve mutation Graph relationship dependencies, including empty
results, add/remove/reparent and retries. Values are reconstructed only when requested at that boundary; no
automatic current-state fallback or unknown-event skipping occurs. Full Graph materialization/serialization
still requires the contracts for the values being materialized. Custom repositories without metadata navigation
retain their existing loading behavior and cannot promise unknown-type-safe selection.

The default repository resolves lazy root aliases from head metadata without replay. Initial lookup uses the
current alias table even for historical Model reads; the selected canonical ID or absence then remains fixed
with the value/relationship boundary. Alias reassignment cannot redirect that Graph. Inside a Model mutation,
consumed alias lookups also participate in commit conflict detection, including absent aliases, `id()` and
relation-only access. Exact-ID reads remain independent of alias mappings. Root lookup errors are not empty child
collections. This does not add historical alias reconstruction; the initial lookup still uses the current mapping.
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

Read the central decision guide at `/docs/sdk/entities/graph-search` before selecting storage or query APIs. Its matrix covers plain event-sourced Models, explicit component paths, direct documents, reference-only
documents and materialized Graphs, with executable Java/Kotlin query examples and state guarantees.

Start with `@Model`. An explicit `@Parent(pathInParent = "...")` maintains an indexed internal component and supports
relationship-scoped search without `DOCUMENT`. Use a direct public document for unrestricted typed Model lists.
With DOCUMENT, that public projection is separate: parent/ancestor queries return it, while related-content
predicates and live Graph composition use the internal Model source. Public reindexing never rewrites that source.
Reindex internal schemas with `@HandleDocument(modelState = T.class)` (Kotlin: `T::class`), public projections with
`documentClass`, and whole-Graph projections with `modelGraph`; see `/docs/sdk/models/migration-testing`.
Identity-based Graph navigation needs neither document nor composition path. Search Graphs include explicit paths
only and are document-backed; they do not inherit an event handler's historical boundary or transaction readset.
Materialized Graphs may lag, whereas live composition can require broad candidate work before filtering/pagination.
Relation queries and live Graph composition do not support the statistics-based `count()` terminal.
