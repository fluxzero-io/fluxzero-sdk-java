Use this for a public query that retrieves one event-sourced Model by its primary ID. This is not a search/list query and does not require a searchable projection.

## Load the Model by its primary ID

Declare the unwrapped public view type and map persisted Model state without applying an update or publishing an effect:

```java
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.Request;

public record GetAssetJob(AssetJobId assetJobId) implements Request<AssetJobView> {
    @HandleQuery
    AssetJobView handle() {
        Entity<AssetJob> loaded = Fluxzero.loadModel(assetJobId);
        AssetJob job = loaded.get();
        return job == null ? null : AssetJobView.from(job);
    }
}
```

`Fluxzero.loadModel(assetJobId)` is the exact primary-ID path. When the Model also has aliases, make `AssetJobId` extend `Id<AssetJob>` with a disjoint internal repository prefix such as `asset-job-id-`. Constructing `AssetJobId` from the public value then performs the translation consistently for start, get, cancel, expiry, and internal transitions. Do not concatenate repository prefixes at query call sites. Do not use `Fluxzero.loadModel(aliasKey)` for this query; that API deliberately accepts aliases and can therefore select by a secondary key. Do not substitute `Fluxzero.getDocument(...)` unless the state owner is actually a `@Stateful` document or an intentionally indexed projection.

Keep `Request<AssetJobView>`, even though the handler can return `null` for an unknown ID. `Request<Optional<AssetJobView>>` is invalid because Fluxzero unwraps handler `Optional` values before validating the request result type. Read query result contracts for the equivalent `Optional<AssetJobView>` handler form.

The view should expose only the promised public fields. For a durable coordinator, include its accepted request values, creation/deadline timestamps, stable component references and statuses, terminal status, and compensation-requested flags. A terminal Model remains loadable only when its terminal `@Apply` transitions retain a non-null Model. Recorded events alone do not guarantee a present Model: replaying an `@Apply` method that returns `null` yields an empty entity. Do not return `null` from a terminal transition when the public contract requires later lookup.

## Prove present, absent, terminal, and reconstructed lookup

```java
fixture.givenModelEvents(assetJobId, recordedStart, recordedTerminalDecision)
        .whenQuery(new GetAssetJob(assetJobId))
        .expectResult((AssetJobView view) ->
                view.assetJobId().equals(assetJobId)
                && view.status() == AssetJobStatus.FAILED
                && view.artworkCancellationRequested());

TestFixture.create()
        .whenQuery(new GetAssetJob(new AssetJobId("missing")))
        .expectNoResult();
```

Use a new default fixture for the synthetic reconstruction row and seed the recorded Model events before querying.
That proves lookup from supplied history, not persistence survival. Test every terminal outcome that the public contract
must retain. `expectNoResult()` is ordinary successful absence; a handler exception belongs under
`expectExceptionalResult(...)`.
