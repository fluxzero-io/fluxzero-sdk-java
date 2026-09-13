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
`@Parent(value = Project.class, pathInParent = "notes")`. The Project uses `EVENT_SOURCED`; the explicit path supplies
the Note component document. Use the same logical names and paths on both sides. Type aliases are additionally needed
if the example's old and new Java classes have different binary names. Close the reader **before** stopping the test
server. Closing a fixture's Fluxzero instance also shuts down its client; construct a new client, not a reused closed one.

The SDK's developer Model migration guide includes a runnable separate writer and a retained-store reader test.
Its normal build tests old **schema** with the candidate SDK on both sides. To claim an SDK upgrade, run the writer
with the pinned old SDK dependency classpath too. An in-memory test server does not prove a JDBC/database upgrade;
repeat against the supported persistent service and actually restart/upgrade that service when claiming that boundary.

## Search before and after migration

For this example, explicitly add `DOCUMENT` when a public direct Model search is required, and `materializeGraph = true`
when a stored whole-Graph projection is required. These are demonstration requirements, not the default for all Models.

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
    @HandleDocument(modelGraph = Project.class)
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

Do **not** copy that handler as `@HandleDocument Project migrate(Project p) { return p; }` for head-verified Model state.
Ordinary document writes do not preserve its trusted body/head proof. Direct/component Model documents are refreshed
through the Model commit path; this recipe does not supply a blanket in-place reindex API for them. An ordinary
application-owned search projection in a separate collection can use the normal document migration contract. Treat
these as distinct owners, and verify direct and whole-Graph searches separately.

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
