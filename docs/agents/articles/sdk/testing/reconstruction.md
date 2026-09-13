Use reconstruction tests when behavior depends on Model events, legacy aggregate events, stateful documents, associations, aliases, or
schedules that are treated as durable inputs. Continuing with `.andThen()` on one fixture is useful multi-step behavior
coverage, but it does not cross a reconstruction boundary because the same registry, stores, and caches remain alive.

Choose the claim from the capability table before choosing fixture APIs. Words such as replay, reconstruction, fresh
fixture, restart, and recovery are not interchangeable evidence labels.

## Name the boundary you are proving

| Test boundary | What it proves | What it does not prove |
| --- | --- | --- |
| `.andThen()` on one fixture | Later behavior after earlier actions in the same in-memory application | Reconstruction or persistence restart |
| New default fixture plus direct durable Given APIs | Synthetic reconstruction from the recorded artifacts supplied by the test, with fresh in-memory caches | That a prior application stored those artifacts or that another instance can recover them |
| Fresh application/client connected to retained external storage, without reseeding | Persistence-backed application restart against that external boundary | Full deployed-process lifecycle or database upgrade unless actually exercised |
| Deployed process restart | Operational startup, connectivity, persistence, registration, and restored behavior together | Nothing beyond the environment and failure modes exercised |

The default `TestFixture.create(...)` and `createAsync(...)` factories create a new in-memory `LocalClient`, including
new event, schedule, key-value, and search stores. A new default fixture is therefore isolated from the old fixture's
durable state. If the test immediately supplies every recorded artifact through Given APIs, call the result **synthetic
reconstruction**, not a persistence restart.

Apply an assumption audit before naming the test: if `givenScheduledCommands(...)` supplies the same schedule ID,
payload, and deadline later asserted as “restored,” that assertion verifies the supplied seed. It does not verify that
the application derived, persisted, or recovered the schedule. Likewise, `givenAppliedEvents(...)` verifies aggregate
interpretation from supplied history, not that another application instance found that history in retained storage.
The same distinction applies to `givenModelEvents(...)`: it interprets supplied serialized events using the current SDK
and current `@Apply` methods, then creates new commits/documents/relations. It is not an import of untouched old storage.
For persistence-backed restart, instance B must observe the artifacts written by instance A without either Given call.

## Start synthetic reconstruction with a new fixture

Run the first phase, capture only durable artifacts, then create a new `TestFixture` instance. Register only the handlers
needed to reconstruct state first; add integration observers after seeding when the fixture/API shape permits it. If
observers must already be registered, explicitly account for and assert every Given-phase publication, index update,
document notification, schedule, and outbound effect.

## Model reconstruction: the default v2 route

Use `givenModelEvents(Id<?>, Object...)` or `givenModelEvents(String, Class<?>, Object...)` for independent Models.
Supply the **historical event payload**, not a serialized Model state and not a command that only resembles it.
For an automatic command persisted as its own `@Apply` event, these happen to be the same payload type.

```java
@Model
record Project(@EntityId String projectId, ProjectDetails details) {}
record ProjectDetails(String name) {}

@Revision(2)
record CreateProject(String projectId, ProjectDetails details) {
    @Apply Project apply() { return new Project(projectId, details); }
}
record RenameProject(String projectId, String name) {
    @Apply Project apply(Project current) {
        return new Project(projectId, new ProjectDetails(name));
    }
}

class CreateProjectUpcaster {
    @Upcast(type = "com.example.CreateProject", revision = 1)
    JsonNode fromRevision1(ObjectNode payload) {
        JsonNode name = payload.remove("name");
        if (name == null || !name.isTextual()) {
            throw new IllegalArgumentException("Revision 1 requires a textual name");
        }
        payload.putObject("details").set("name", name);
        return payload;
    }
}

@Test
void reconstructsProjectHistory() {
    TestFixture.create().registerCasters(new CreateProjectUpcaster())
            .givenModelEvents("project-1", Project.class,
                    "/project/create-project-rev1.json", new RenameProject("project-1", "Renamed"))
            .whenExecuting(fc -> {
                var model = Fluxzero.loadModel("project-1", Project.class);
                assertEquals("Renamed", model.get().details().name());
                assertEquals("Legacy name", model.previous().get().details().name());
            }).expectNoEvents().expectNoErrors();
}
```

Save this resource under `src/test/resources/project/create-project-rev1.json`. Put the example types in package
`com.example`; if nesting them in a test class, use that class's binary name, including `$`, in both type strings.
Use `io.fluxzero.common.serialization.Revision` and `io.fluxzero.sdk.common.serialization.casting.Upcast`.

```json
{
  "@class": "com.example.CreateProject",
  "@revision": 1,
  "projectId": "project-1",
  "name": "Legacy name"
}
```

Kotlin uses the same serialized fixture and explicit registration:

```kotlin
TestFixture.create().registerCasters(CreateProjectUpcaster())
    .givenModelEvents("project-1", Project::class.java,
        "/project/create-project-rev1.json", RenameProject("project-1", "Renamed"))
    .whenExecuting {
        val model = Fluxzero.loadModel("project-1", Project::class.java)
        assertEquals("Renamed", model.get().details.name)
        assertEquals("Legacy name", model.previous().get().details.name)
    }.expectNoEvents().expectNoErrors()
```

Register casters **before** Given. `@Component` is not Spring discovery inside a standalone fixture. A Model-state
caster is needed separately when old snapshots/documents of that Model also changed shape. `whenUpcasting` proves the
conversion only; `givenModelEvents` additionally proves current event application, bypassing command assertions and
interceptors. It can still publish events and notify observers while seeding. `expectNoEvents` above covers passive
loading in **When**, not those Given effects. Repeat with `createAsync()` when consumer behavior matters.

Keep `EVENT_SOURCED` and reconstructible history for historical `previous()` values. `DOCUMENT` alone is current-state
storage, not a version archive, even if some events are also stored/published by its event policy.

The Model migration article supplies the stronger separate-writer/retained-store recipe and before/after index checks.

## Existing aggregate and stateful workflows

For event-sourced aggregates already in use:

```java
TestFixture reconstructed = TestFixture.createAsync(AssetJobUpdate.class, DeadlineHandler.class)
        .givenAppliedEvents(assetJobId,
                recordedStart,
                recordedCaptionConfirmation)
        .givenScheduledCommands(new Schedule(
                new ExpireAssetJob(assetJobId), deadlineId, deadline));
```

Use `givenAppliedEvents(...)` for aggregate history. It skips the original command/creation handler, but applying the recorded events can still publish aggregate events, update indexes, and notify registered observers. Use
`givenScheduledCommands(...)` when the persisted item is dispatched as a command at its deadline; use
`givenSchedules(...)` for ordinary `@HandleSchedule` payloads. Do not interchange those APIs.

When the recorded input is historical serialized data, pass a revision-old `SerializedMessage` directly to
`givenAppliedEvents(...)` after registering the caster. That exercises the event deserializer and caster chain before
the current event is applied. `whenUpcasting(...)`, manual deserialization, and applying an already-current Java object
are useful narrower checks, but do not prove serialized aggregate reconstruction.

For `@Stateful` workflows:

```java
TestFixture reconstructed = TestFixture.createAsync(AssetJobProcess.class, DecisionHandlers.class)
        .givenStateful(recordedProcess)
        .givenSchedules(new Schedule(
                new ExpireAssetJob(assetJobId), deadlineId, deadline));
```

`givenStateful(...)` stores the stateful document directly without invoking the original creation handler. That document write can still update the index and notify registered `@HandleDocument` or other document observers. Direct seeding
therefore narrows setup behavior; it does not guarantee a side-effect-free Given phase.

## Prove all supplied durable routes

On the reconstructed fixture, verify independently:

- exact lookup by primary ID;
- a message using the first alias or association;
- a separate message using the second alias or association;
- the still-active deadline before and at its due time;
- a stale deadline after terminal state;
- terminal state and compensation flags;
- no startup re-emission of processing, compensation, or notification requests.

For Models use `Fluxzero.loadModel(id)` and `Fluxzero.loadGraph(id)`; check the actual alias and typed/path-based child
selection the application uses as separate assertions. For existing aggregates, exact lookup uses
`Fluxzero.loadAggregate(primaryId)`, while aliases use `Fluxzero.loadEntity(prefixedAlias)`. For stateful documents,
exact lookup uses `Fluxzero.getDocument(primaryId, ProcessType.class)` and correlation uses `@Association` routing.
Do not prove only one secondary key and infer that all others were reconstructed.

## Avoid replay-side-effect false positives

This preference applies specifically to a synthetic reconstruction boundary. In an ordinary behavior test, prefer
`givenCommands(...)` when setup should pass through the real business handlers and their resulting events or effects.
`givenCommands(...)` and `givenEvents(...)` run registered handlers fully as Given work. That is useful when setup
behavior matters, but at a reconstruction boundary it can recreate network requests or schedules that do not result
from passive production loading. Prefer direct durable setup APIs (`givenAppliedEvents`, `givenStateful`,
`givenSchedules`, `givenScheduledCommands`) for synthetic reconstruction, while remembering that aggregate application
and document indexing can still wake registered observers. Stage observer registration where possible; otherwise
capture or assert setup effects before making one new When action. Include `givenModelEvents` in this same caution.
These APIs prove application behavior from supplied
artifacts; they do not prove that the artifacts survived outside the fixture.

This fixture behavior is different from passive production aggregate loading. `givenAppliedEvents(...)` applies and
commits fixture updates, so already-registered event consumers may run during Given; those Given effects are not
collected by the next Then phase. Production loading reconstructs state from stored events without recommitting them or
intentionally republishing transition metrics. For a negative replay-metrics test, store serialized history, register
the production observer, load or query the aggregate in When, and assert no matching custom metric. If using
`givenAppliedEvents(...)`, seed first and register the observer afterward so hidden setup publications do not masquerade
as production replay.

## Add persistence-backed restart evidence when required

`TestFixture.createAsync(FluxzeroBuilder, Client, Object...)` accepts a supplied client. The fixture's Fluxzero instance
owns shutdown of that client. Use that seam only
when the retained runtime/store is itself part of the test: application instance A writes the durable state, instance A
is closed, and a separately constructed instance B reconnects through a **new client to the same retained store and
namespace** without calling `givenModelEvents(...)`, `givenAppliedEvents(...)`, `givenStateful(...)`,
`givenSchedules(...)`, or `givenScheduledCommands(...)`. Do not reuse a closed client. Drive a public
query, correlated message, and due schedule through instance B. If no retained runtime/store participates, keep the
claim at synthetic reconstruction.

For a full restart contract, run the packaged application or process twice against the supported persistent services
and verify startup/registration as well as restored behavior. Keep reconstruction and restart tests deterministic: use
recorded timestamps/deadlines, controlled time movement, and no timing sleeps.
