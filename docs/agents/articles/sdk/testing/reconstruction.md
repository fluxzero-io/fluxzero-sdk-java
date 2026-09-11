Use reconstruction tests when behavior depends on aggregate events, stateful documents, associations, aliases, or

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
schedules that are treated as durable inputs. Continuing with `.andThen()` on one fixture is useful multi-step behavior
coverage, but it does not cross a reconstruction boundary because the same registry, stores, and caches remain alive.

Choose the claim from the capability table before choosing fixture APIs. Words such as replay, reconstruction, fresh
fixture, restart, and recovery are not interchangeable evidence labels.

## Name the boundary you are proving

| Test boundary | What it proves | What it does not prove |
| --- | --- | --- |
| `.andThen()` on one fixture | Later behavior after earlier actions in the same in-memory application | Reconstruction or persistence restart |
| New default fixture plus direct durable Given APIs | Synthetic reconstruction from the recorded artifacts supplied by the test, with fresh in-memory caches | That a prior application stored those artifacts or that another instance can recover them |
| Fresh application using a retained external `Client`/runtime, without reseeding | Persistence-backed application restart against that external boundary | Full deployed-process lifecycle unless the test actually starts it |
| Deployed process restart | Operational startup, connectivity, persistence, registration, and restored behavior together | Nothing beyond the environment and failure modes exercised |

The default `TestFixture.create(...)` and `createAsync(...)` factories create a new in-memory `LocalClient`, including
new event, schedule, key-value, and search stores. A new default fixture is therefore isolated from the old fixture's
durable state. If the test immediately supplies every recorded artifact through Given APIs, call the result **synthetic
reconstruction**, not a persistence restart.

Apply an assumption audit before naming the test: if `givenScheduledCommands(...)` supplies the same schedule ID,
payload, and deadline later asserted as “restored,” that assertion verifies the supplied seed. It does not verify that
the application derived, persisted, or recovered the schedule. Likewise, `givenAppliedEvents(...)` verifies aggregate
interpretation from supplied history, not that another application instance found that history in retained storage.
For persistence-backed restart, instance B must observe the artifacts written by instance A without either Given call.

## Start synthetic reconstruction with a new fixture

Run the first phase, capture only durable artifacts, then create a new `TestFixture` instance. Register only the handlers
needed to reconstruct state first; add integration observers after seeding when the fixture/API shape permits it. If
observers must already be registered, explicitly account for and assert every Given-phase publication, index update,
document notification, schedule, and outbound effect.

For event-sourced aggregates:

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

For aggregates, exact lookup uses `Fluxzero.loadAggregate(primaryId)`, while aliases use `Fluxzero.loadEntity(prefixedAlias)`. For stateful documents, exact lookup uses `Fluxzero.getDocument(primaryId, ProcessType.class)` and correlation uses `@Association` routing. Do not prove only one secondary key and infer that all others were reconstructed.

## Avoid replay-side-effect false positives

This preference applies specifically to a synthetic reconstruction boundary. In an ordinary behavior test, prefer
`givenCommands(...)` when setup should pass through the real business handlers and their resulting events or effects.
`givenCommands(...)` and `givenEvents(...)` run registered handlers fully as Given work. That is useful when setup
behavior matters, but at a reconstruction boundary it can recreate network requests or schedules that do not result
from passive production loading. Prefer direct durable setup APIs (`givenAppliedEvents`, `givenStateful`,
`givenSchedules`, `givenScheduledCommands`) for synthetic reconstruction, while remembering that aggregate application
and document indexing can still wake registered observers. Stage observer registration where possible; otherwise
capture or assert setup effects before making one new When action. These APIs prove application behavior from supplied
artifacts; they do not prove that the artifacts survived outside the fixture.

This fixture behavior is different from passive production aggregate loading. `givenAppliedEvents(...)` applies and
commits fixture updates, so already-registered event consumers may run during Given; those Given effects are not
collected by the next Then phase. Production loading reconstructs state from stored events without recommitting them or
intentionally republishing transition metrics. For a negative replay-metrics test, store serialized history, register
the production observer, load or query the aggregate in When, and assert no matching custom metric. If using
`givenAppliedEvents(...)`, seed first and register the observer afterward so hidden setup publications do not masquerade
as production replay.

## Add persistence-backed restart evidence when required

`TestFixture.createAsync(FluxzeroBuilder, Client, Object...)` accepts an externally managed client. Use that seam only
when the retained runtime/store is itself part of the test: application instance A writes the durable state, instance A
is closed, and a separately constructed instance B reconnects through the retained client without calling
`givenAppliedEvents(...)`, `givenStateful(...)`, `givenSchedules(...)`, or `givenScheduledCommands(...)`. Drive a public
query, correlated message, and due schedule through instance B. If no retained runtime/store participates, keep the
claim at synthetic reconstruction.

For a full restart contract, run the packaged application or process twice against the supported persistent services
and verify startup/registration as well as restored behavior. Keep reconstruction and restart tests deterministic: use
recorded timestamps/deadlines, controlled time movement, and no timing sleeps.
