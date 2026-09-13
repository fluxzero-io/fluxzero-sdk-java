# Models and state

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
5. Choose every model boundary by lifecycle first. State that can be created, changed, retained, deleted, or whose
   history matters independently is a separate `@Model`, even when it is normally placed in a parent's collection.
6. Treat a meaningful identity, separate retention, or independent updates as evidence for that boundary, not as
   competing criteria. A child without a globally unique functional ID can use `@EntityId(parentScoped = true)`.
7. Use `@Member` only when creation, every change, history, stream, document, cache, retention, and deletion all
   deliberately belong to the root. Collection shape, searchability, storage choice, and update frequency never make
   independently living state a member.
8. Use typed `Id<T>` values. The exact `Id.toString()` is the persisted model identity.

## Define a model

```java
@Model
public record Project(
        @EntityId ProjectId projectId,
        ProjectDetails details,
        UserId ownerId) {
}
```

Assume conventional typed `ProjectId` and `ProjectDetails` value types; do not expand obvious ID or details
definitions unless the user asks for them.

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
  - `{EVENT_SOURCED, DOCUMENT}`: reconstruct from events and also maintain a current document.
  - `{DOCUMENT}`: load authoritative current state from the current document.
- `ignoreUnknownEvents`: deliberately tolerates unhandled stored events during event-sourced reconstruction.
- `document`: optional `@DocumentProjection` configuration for the direct collection, timestamp paths, and public
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
- `graphProjection`: optional advanced `@GraphProjection` configuration; its collection defaults to the resolved direct
  Model collection plus `-graphs` when a direct document exists, or `<logical Model name>-graphs` otherwise, and
  materializes the complete finite graph without implicit size limits.

Persistence does not control event storage or publication. Those remain owned by `eventPublication`,
`publicationStrategy` and per-apply overrides. Internal Graph-component documents are also orthogonal: they neither
make an `EVENT_SOURCED` Model directly searchable nor change its load path. Event-sourcing-only options such as
`ignoreUnknownEvents`, snapshots and replay checkpoints are rejected on `DOCUMENT`-only Models.

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
    return Fluxzero.assertAndApplyAsync(command);
}
```

Fluxzero commits automatically. Only when a later step in the same handling context must force an already produced
automatic Model commit to durability, use `Fluxzero.commit()` and compose on its returned `CompletableFuture<Void>`.
It is a release of the existing commit, not another mutation path: repeated calls share its completion, automatic
commit remains enabled, and a context without pending changes completes without Runtime transport. Do not call or wait
on it inside `@Apply`; the apply has not returned its change yet.

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

## Batch-local command consistency

Automatic model commands in one tracking batch and ordered routing segment have read-your-writes. A later command sees
an earlier staged model update, including changed parent and ancestor relations, before the earlier commit completes.
When their read/write sets overlap, the later command waits for the predecessor's durable result and is then
reevaluated against canonical state before committing. Predecessor failure fails the dependent chain. Unrelated model
chains remain parallel.

Do not treat this as one transaction across commands: each command retains its own atomic commit, result and conflict
policy. Different consumers or routing segments have no implied ordering; configure a shared consumer and routing key
when that order is a domain requirement.

## Relationships

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

The child remains an independent Model because its lifecycle is independent; `@Parent` expresses graph placement and
default cascade ownership. Being displayed below or deleted with the parent does not make it a `@Member`.

- Updating `projectId` moves the task.
- The parent and siblings do not need to load for a task-only change.
- Typed `Id<Parent>` supplies the relation type. A role is only needed for untyped/ambiguous IDs.
- For one polymorphic typed relation, use `@Parent(types = {Project.class, Folder.class}, ...) Id<?> parentId`; the
  concrete typed ID selects one statically declared parent type. Use separate properties for distinct relation roles.
- `pathInParent` is a stable public graph-placement and serialization contract. A pathless relation remains available through
  typed `Graph` traversal and parent-deletion lifecycle handling, but is not emitted as a named JSON graph edge.
- A child is logically deleted by default when any parent referenced by that `@Parent` is finally deleted. Set
  `deleteOnParentDeletion = false` for deliberately detached or independently retained children.
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

Ordinary `loadGraph(...)` calls inside a handler inherit its coherent message or historical event boundary. Use
`loadCurrentGraph(...)` only after a synchronous nested command when later handler logic deliberately needs that
command's newer state. Do not use it as the default loading route.

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
then stay pinned. This is not a new transaction-level alias-mapping conflict dependency.
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

The implicit update policy is `RETRY` from defaults version `2026.09.09`, otherwise `ACCEPT`.
`fluxzero.model.conflictPolicy` and explicit builder/Model/Apply settings override it. Implicit first creations still
fail on conflict: the new default must not turn create-if-absent into an upsert. Explicit RETRY also reevaluates creation
and requires create-only assertions when appropriate. ACCEPT validates apply dependencies and writes, excluding
assertion-/interceptor-only reads; RETRY and FAIL validate the full evaluation readset. Conflict-free eligible Runtime
commits use the same cached-head/atomic-boundary optimization regardless of policy.

Injected Graph reads also count: values/type/alias/revision reads protect Model heads; child collections (including empty
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
