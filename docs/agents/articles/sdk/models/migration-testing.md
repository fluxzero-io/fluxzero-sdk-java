Use this recipe when an existing Model moves `name` into `ProjectDetails.name`. Keep the stored logical `@Model(name)`
and identity stable; change the schema revision, not the identity. The goal is to preserve old values and qualify both
replay and query behavior, not to recreate old commands through today's validation rules.

## Separate the three tests

1. **Conversion:** `TestFixture.create().registerCasters(new ProjectUpcaster()).whenUpcasting(oldJson)` must retain
   the old name, ID and unaffected fields. Match the old resource's `@revision` to the caster's input revision.
2. **Synthetic Model reconstruction:** register historical event casters first, then
   `givenModelEvents(id, oldSerializedEvent, ...)` or `givenModelEvents(rawId, Project.class, ...)`, followed by
   `Fluxzero.loadModel(...)` / `loadGraph(...)` in When. This interprets supplied history and makes new commits using
   the current SDK. It is not a byte-preserving old-store import.
3. **Retained-storage migration:** run a writer built with the actual old application contracts and pinned old SDK;
   close it; run a fresh reader built with the candidate contracts/SDK against the **same retained test store and
   namespace**, with no Given reseeding. Verify the old writer's ID, values and relations through public read APIs.
   No manually assembled `CommitModels` requests are required.

The reconstruction article contains a complete `givenModelEvents` example. Its event is `CreateProject` revision 1
with `projectId` and `name`; the revision-2 event contains `projectId` and `details`. The Model-state schema needs its
own caster if snapshots/current documents embed the old state. An unchanged `RenameProject(id, name)` event may remain
unchanged: current replay logic wraps its name in details. Add casters for every changed top-level serialized type,
not a caster solely on the nested `ProjectDetails` value object.

## An old writer and a new reader

Build two separate application artifacts or run two source sets/JVMs, so only one generation of a logical Model type
exists in each application's catalog. The old writer performs ordinary creation, rename and child-creation commands.
It waits for commit completion and closes its client. Keep the test server/database alive.

In the reader, register casters **before building or reading**:

```java
var builder = DefaultFluxzero.builder();
builder.serializer().registerCasters(new ProjectUpcaster(), new CreateProjectUpcaster());
var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
        .runtimeBaseUrl(testUrl).namespace(testNamespace).name("migration-reader").build());
var fixture = TestFixture.createAsync(builder, client);

fixture.whenExecuting(fc -> {
    var model = Fluxzero.loadModel("project-1", Project.class);
    assertEquals("Renamed", model.get().details().name());
    assertEquals("Legacy name", model.previous().get().details().name());
    assertEquals("note-1", Fluxzero.loadCurrentGraph("project-1", Project.class)
            .children(Note.class).getFirst().get().noteId());
}).expectNoErrors();
```

Here the old writer created `project-1` as `Legacy name`, renamed it to `Renamed`, and created `note-1` with
`@Parent(value = Project.class, pathInParent = "notes")`. The Project uses `EVENT_SOURCED` and `searchable = true`;
its search scope maintains the Note index. The path only controls placement. Use the same logical names and paths on both sides. Type aliases are additionally needed
if the example's old and new Java classes have different binary names. Close the reader **before** stopping the test
server. Closing a fixture's Fluxzero instance also shuts down its client; construct a new client, not a reused closed one.

The SDK's developer Model migration guide includes a runnable separate writer and a retained-store reader test.
Its normal build tests old **schema** with the candidate SDK on both sides. To claim an SDK upgrade, run the writer
with the pinned old SDK dependency classpath too. An in-memory test server does not prove a JDBC/database upgrade;
repeat against the supported persistent service and actually restart/upgrade that service when claiming that boundary.

## Search before and after migration

For this example, set `searchable = true` on Project to index it and its composed descendants. Set
`graphProjection = @GraphProjection(mode = ASYNC)` only when a stored whole-Graph projection is required;
`NONE` uses the same indexed nodes and composes them when read. `DOCUMENT` alone does not activate search.

| Operation after registering casters, before reindexing | Result for the old stored representation |
| --- | --- |
| `loadModel(id)` | Replays events through their casters/current applies; current state has `details.name` |
| `loadCurrentModelState(id)` | Upcast current document, if maintained and verifiably at the current head; never falls back to replay |
| `loadGraph(id).children(Note.class)` | Relationship navigation, independent of the `name` field's index path |
| `search(Project.class).match("Renamed", "name")` | Selects on the **old stored path**, then upcasts returned values |
| `search(Project.class).match("Renamed", "details/name")` | Does not match an old document merely because the reader has a caster |
| `searchGraph(Project.class).match("Renamed", "details/name")` | Does not match an old materialized Graph until its stored projection is migrated |

Relation-scoped content predicates have the same rule: `search(Note.class).whereParent(projectId).match(value, path)`
filters the **stored Note document** before deserialization. Graph traversal can remain correct while an old index
does not yet support a new field path. During migration, either keep old predicates, deliberately support both paths,
or gate new queries on migration completion. Do not present silently incomplete matches as a complete invariant.

To rewrite a **materialized Graph projection**, bump each changed node's `@Revision`, register its caster, and return
the complete Graph from a dedicated replaying document consumer:

```java
@Consumer(name = "project-graph-schema-2", minIndex = 0)
class RematerializeProjects {
    @HandleDocument(graphMigration = GraphMigrationTarget.PROJECTION)
    Graph<Project> migrate(Graph<Project> graph) { return graph; }
}
```

Register a type alias when a node's Java type name changes, including when an upcaster already returns a `Data` envelope
with that new type. The Graph replacement guard requires that content-independent rename mapping.
For embedded TestServer, initialize the collection's lazy document tracking log before the old writer runs; the
executable repository test demonstrates this setup. A retained document is not proof that its update log was active.

Await/observe this consumer's catch-up before asserting the new path matches. This operation replaces the derived
Graph when a node's serialized type or revision changed; bump the revision for a JSON-shape migration, because a
JSON-only difference at the same type/revision is not sufficient. It does not change
Model event history, direct/component documents or Model heads. Its manifest comparison prevents a
delayed rewrite from overwriting a newer Graph. A projection rebuild from unchanged old component documents is not a
substitute for running node upcasters and rewriting the result. Keep the migration consumer for the required rollout
window so old components cannot reintroduce old paths unnoticed on subsequent projections.

## Reindex canonical nodes and optional stored Graphs

The projection-only walkthrough above opts into the earlier behavior. By default, returning the unchanged injected
Graph migrates only evolved canonical nodes, including at NONE. The SDK upcasts each verified **current** source;
it never copies stale Graph business values into it. Full-head/proof checks and bounded re-reads guard concurrent
updates and deletion. In-place value changes and topology edits are rejected. Shared nodes are migrated once.
Node storage completes before handling completes, while affected projections follow durably. AWAIT retains its
ordinary Model-commit guarantee and is not a schema-migration barrier. Test current queries after catch-up, stale
Graphs against newer state, retry after partial progress, and unchanged unrelated roots.


A searchable Model has one canonical indexed node document. DOCUMENT persistence can maintain that state without
searchability. ASYNC/AWAIT additionally store the composed Graph. Schema rewrites do not advance Model history.

| Handler selection | Reads/writes | Query paths it updates |
| --- | --- | --- |
| `@HandleDocument` with `Project` | Canonical searchable node | Node searches, live composition and related-content predicates |
| `@HandleDocument(source = DocumentSource.MODEL_STATE)` with `Project` | Maintained internal state, including non-searchable DOCUMENT Models | The same canonical source; does not activate search |
| `@HandleDocument` with `Graph<Project>` | Logical Graph updates in every mode | Return migrates evolved canonical nodes, then affected stored Graphs or NONE markers follow durably |
| `@HandleDocument(graphMigration = GraphMigrationTarget.PROJECTION)` with `Graph<Project>` | Handled projection only | Stored ASYNC/AWAIT composition; NONE returns are observational |

After registering the value-preserving upcasters above and raising the Model schema revision, use explicit consumers:

```java
@Consumer(name = "project-source-schema-2", minIndex = 0)
class ReindexProjectSources {
    @HandleDocument(source = DocumentSource.MODEL_STATE)
    Project migrate(Project project) { return project; }
}

```

Kotlin has the same contract:

```kotlin
@Consumer(name = "project-source-schema-2", minIndex = 0)
class ReindexProjectSources {
    @HandleDocument(source = DocumentSource.MODEL_STATE)
    fun migrate(project: Project): Project = project
}
```

The source handler receives the upcast value. Return it unchanged: identity, type and logical serialized state must
remain equal, including for mutable objects. Returning `null`, splitting/dropping the upcast state or changing business
data is rejected; use commands/`@Apply` for state changes or deletion. A higher schema revision is required for a
rewrite. The built-in Jackson serializer captures an immutable logical snapshot; a custom `DocumentSerializer` must
explicitly implement `modelStateSnapshot` to support this new route. Existing ordinary document handling is unaffected.

Before invoking the handler the SDK inspects the verified current source. A delayed message whose upcast state no
longer matches that source is not written. The eventual compare-and-set checks the full durable head, old proof and
actual stored body atomically with the source/index/proof replacement. A concurrent update, deletion, recreation,
untrusted document overwrite or prior schema rewrite makes the old request a successful no-op. No new Model version
or domain event is produced. This guards the materialized source boundary; it is not a cross-database atomic snapshot
of all Models. A void handler observes only and performs no rewrite.

Ordinary read-model documents retain their revision-aware return/deletion semantics. A typed Model handler uses the
schema-only guard, whether selected through SEARCH or MODEL_STATE. Use commands for business state changes; direct
indexing cannot certify Model state. For an independently transformed view, define a separate read model.

Migrate internal sources before rebuilding Graph projections, observe consumer catch-up, and test old/new paths
separately for nodes and stored Graphs. Reindex each affected child's source too: rewriting only the root does not migrate
child predicates. Verify a fresh reader can still load current state and historical `previous()` values afterward.

Storage/configuration changes are separate migrations. Schema upcasters do not rename collections, change
persistence/name/path settings. For missing canonical sources use the explicit operation below; collection or identity
changes still require a separate migration.


## Explicit current-state reindexing

Call `graph.reindex()` to rebuild the canonical search source of **that Model node** with the current document
serializer, indexed fields, exclusions and summary. This can create a missing source from complete event-sourced
history. It preserves the Model head, events, aliases, relationships and history. A historical or event-bound Graph
selects the identity only: reconstruction uses the latest committed state, never that view's old values or staged
updates. Reindex each selected child separately; the call does not enumerate descendants or select Models for you.
DOCUMENT-only state still requires its existing verified source. Missing/deleted Models are no-ops; erased Models
cannot be resurrected. Untrusted source bytes and incomplete reconstruction fail instead of creating provenance.

The customer owns selection, replay and progress. A bounded event consumer can invoke this operation repeatedly:

```java
// Replace this example with the fixed cutover time for this migration, in millis << 16.
@Consumer(name = "project-search-reindex", minIndex = 0,
          maxIndexExclusive = 1791058000000L << 16, exclusiveAfterMaxIndex = false)
class ReindexProjects {
    @HandleEvent
    void reindex(Object event, Graph<Project> project) { project.reindex(); }
}
```

```kotlin
@Consumer(name = "project-search-reindex", minIndex = 0,
          maxIndexExclusive = 117378777088000000L, exclusiveAfterMaxIndex = false)
class ReindexProjects {
    @HandleEvent
    fun reindex(event: Any, project: Graph<Project>) { project.reindex() }
}
```

Use one fixed, positive `maxIndexExclusive` time boundary for the run, after stopping old writers **and draining
pending old materializations**, and before starting writers with the new search configuration. Keep consumer and
storage clocks comparable. A later configuration change needs a new boundary. This is a coordinated cutover, not
mixed-writer migration. Source storage records an atomic durable storage-time index, distinct from Model state,
functional timestamps and document tracking indexes. A verified source stored on or after the cutoff skips before
Model replay and any write; ordinary writes under the new configuration count too. Old sources without this evidence
are refreshed. Future or nonpositive cutoffs fail. Without a bounded consumer, each call refreshes unconditionally.
A restarted replay must retain its original cutoff; do not calculate a new cutoff for every event.

The operation retries bounded head/proof conflicts. An exhausted conflict or storage failure may be retried by the
customer. Concurrent writes, deletion and erasure retain their normal fences. Completion confirms the source write
and durable targeted invalidation. Affected NONE notifications and ASYNC/AWAIT compositions follow through the
existing worker; this operation is not an AWAIT projection-completion barrier. Observe projection catch-up separately.
No new Model event or automatic migration job is produced. Event replay can select only retained published events;
use another authoritative ID inventory for Models absent from that log.

## Preserve historical meaning and name the remaining limits

After migration and a fresh cache/application, assert that old `previous()` values still say `Legacy name`, while current
state says `Renamed`. Keep `EVENT_SOURCED` and reconstructible history; `DOCUMENT` alone is not a history archive even
when its event policy also stores/publishes events. Upcasting changes the read representation, not the historical
business value. `loadCurrentGraph` deliberately asks for newer state; an injected/event-bound Graph retains its event
boundary. Search returns current/projection state, not event-bound history.

Expand the retained-store matrix to the application's actual snapshots, aliases, child paths, document-only models,
encrypted/KV references, materialized projections, namespaces and old/new writer overlap. A projection consumer replay
requires its source documents/history still to exist. Missing historical inputs or unknown types must fail explicitly;
skipping them is not a migration strategy. Neither JSON conversion nor one happy-path restart proves all these cases.
