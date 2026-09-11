Use durable correlation when later messages identify a workflow by processor reference, payment reference, or another secondary key instead of its primary domain ID.

The API-sensitive examples use these Fluxzero imports:

```java
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.AggregateEventRouting;
import io.fluxzero.sdk.modeling.Alias;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.EventPublication;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.Request;
```

In particular, `Apply`, `RoutingKey`, and `Metadata` are not in the modeling, tracking, and SDK-common packages that their simple names may suggest. Read the Java SDK import reference instead of guessing.

## Choose `@Alias` or `@Association`

| State owner | Secondary-key mechanism | Lookup behavior |
| --- | --- | --- |
| Aggregate/member | `@Alias` on persisted entity state | Explicitly load with `Fluxzero.loadEntity(prefixedAlias)` |
| `@Stateful` workflow | `@Association` on persisted handler state | Incoming payload properties select matching stored instances |

`@Alias` is an alternate entity ID. `@Association` matches both property name and value unless an explicit mapping overrides the name. More than one stateful instance can match an association, so do not use a supposedly unique business key without testing collision behavior.

## Generate service-owned references after acceptance

Keep the public command limited to its public contract. If it contains six caller-owned fields, do not add two caller-controlled processor references. Generate those references only after validation and duplicate detection, outside `@Apply`, then persist them in an internal applied update.

```java
@TrackSelf
@Consumer(name = "asset-job-transitions")
public record StartAssetJob(
        @NotNull @RoutingKey AssetJobId assetJobId,
        @NotBlank String assetKey,
        @NotBlank String sourceVersion,
        @NotBlank String captionProfile,
        @NotBlank String artworkProfile,
        @NotNull OutputFormat outputFormat) implements Request<AssetJob> {

    @HandleCommand
    AssetJob handle() {
        Entity<AssetJob> current = Fluxzero.loadAggregate(assetJobId);
        if (current.isPresent()) {
            return current.get();                 // first accepted start wins
        }
        String captionReference = Fluxzero.generateId();
        String artworkReference = Fluxzero.generateId();
        return current.assertAndApply(new RecordAssetJob(
                assetJobId, assetKey, sourceVersion, captionProfile, artworkProfile,
                outputFormat, captionReference, artworkReference)).get();
    }
}

record RecordAssetJob(
        AssetJobId assetJobId, String assetKey, String sourceVersion,
        String captionProfile, String artworkProfile,
        OutputFormat outputFormat,
        String captionReference, String artworkReference) {
    @Apply
    AssetJob apply(Instant timestamp) {
        return AssetJob.start(this, timestamp);
    }
}
```

Validation runs before the handler. The duplicate branch runs before generation, so it cannot replace references or repeat start side effects. Route all starts by the primary ID so concurrent duplicates are ordered together. An endpoint or deserializer is not a trusted generation boundary merely because it constructs the public command.

## Namespace aliases, then verify the loaded component

Prefix each alias family and put aggregate roots in a separate internal repository namespace. Prefixes separate caption and artwork alias keys that have the same raw text, but alias prefixes alone are insufficient. If a root repository ID can equal the fully-prefixed lookup key, `loadEntity("caption-ref-" + raw)` prioritizes that root and does not fall back to the intended alias. A field guard prevents wrong mutation but drops the valid decision. Use a typed primary `Id` whose constructor adds a disjoint internal prefix such as `asset-job-id-`, while retaining the unrestricted public functional ID. Read disjoint identity namespaces for the complete pattern and adversarial proof.

```java
@Aggregate(
        eventPublication = EventPublication.IF_MODIFIED,
        eventRouting = AggregateEventRouting.AGGREGATE_ID)
public record AssetJob(
        @EntityId AssetJobId assetJobId,
        @Alias(prefix = "caption-ref-") String captionReference,
        @Alias(prefix = "artwork-ref-") String artworkReference,
        AssetJobStatus status,
        ComponentStatus captionStatus,
        ComponentStatus artworkStatus) {
}
```

`eventRouting = AggregateEventRouting.AGGREGATE_ID` is needed here because this workflow publishes applied intent events to an ordered post-commit effect consumer. Event-sourced storage belongs to the aggregate, but published-event message routing defaults to the applied payload's `@RoutingKey`; it does not default to the aggregate ID. If every applied payload instead declares the same primary-ID `@RoutingKey`, the default routing mode can be used, but enforce that invariant across every intent type.

The load key must include the same prefix. Keep decision payloads component-specific and, after every secondary-key load, compare the persisted component field with the raw incoming reference before doing anything else:

```java
@TrackSelf
@Consumer(name = "processor-decision")
public record CaptionCompleted(
        @NotBlank String captionJobReference) {

    @HandleCommand
    void handle() {
        Entity<AssetJob> job = Fluxzero.loadEntity(
                "caption-ref-" + captionJobReference);
        if (job.isPresent()
                && captionJobReference.equals(
                        job.get().captionReference())) {
            // Target accepted. Publish the durable primary-ID transition below.
        }                                      // unknown or wrong target: ignore
    }
}
```

Do not call `loadEntity(rawReference)` after declaring a prefix: that key is different. Do not accept `job.isPresent()` as sufficient evidence. Keep the equality guard even after root and alias repositories are disjoint. Do not strip the component type into one generic decision payload unless the resolver still chooses and verifies the correct persisted component field. `Fluxzero.loadEntity(...)` can also select the most recently associated entity when aliases collide, so unique generated references, disjoint repository namespaces, post-load equality, and adversarial collision tests are correctness requirements.

## Resolve secondary keys, serialize mutations by primary ID

Do not mutate the aggregate directly from independently tracked caption and artwork correlation consumers. Those messages route by different secondary keys and may execute concurrently. Both handlers can load the same old job, derive cancellation or compensation intent from it, and then apply competing transitions. Aggregate conflict handling can replay events already produced by a handler, but it does not rerun the handler's earlier derived-intent decision. A transition or logical-once effect can therefore be lost.

Use a read-only correlation resolver followed by a durable internal command:

```java
@TrackSelf
@Consumer(name = "processor-correlation")
public record CaptionCompleted(
        @NotBlank @RoutingKey String captionJobReference) {

    @HandleCommand
    void handle() {
        String raw = captionJobReference;
        Entity<AssetJob> match = Fluxzero.loadEntity("caption-ref-" + raw);
        if (match.isPresent() && raw.equals(match.get().captionReference())) {
            AssetJob job = match.get();
            Fluxzero.sendAndForgetCommand(
                    new RecordProcessorDecision(
                            job.assetJobId(), Component.CAPTION, raw, Decision.CONFIRMED),
                    Metadata.empty(), Guarantee.STORED);
        }
    }
}

@TrackSelf
@Consumer(name = "asset-job-transitions")
record RecordProcessorDecision(
        @NotNull @RoutingKey AssetJobId assetJobId,
        @NotNull Component component,
        @NotBlank String expectedReference,
        @NotNull Decision decision) {

    @HandleCommand
    void handle() {
        Entity<AssetJob> job = Fluxzero.loadAggregate(assetJobId);
        if (!job.isPresent() || !referenceStillMatches(job.get())) {
            return;
        }
        job.assertAndApply(this);
    }

    private boolean referenceStillMatches(AssetJob job) {
        return switch (component) {
            case CAPTION -> expectedReference.equals(job.captionReference());
            case ARTWORK -> expectedReference.equals(job.artworkReference());
        };
    }

    @Apply
    AssetJob apply(AssetJob job) {
        return job.recordFirstDecision(component, decision);
    }
}
```

The artwork resolver mirrors the caption resolver but loads `"artwork-ref-" + raw` and verifies `artworkReference()`. `Guarantee.STORED` makes the handoff durable before the resolver completes. The internal command carries `@RoutingKey AssetJobId`; it reloads by the exact primary ID and rechecks the expected reference before applying.

Put every command that mutates this workflow—accepted start, resolved caption/artwork decision, cancellation, and scheduled expiry—under the same named `@Consumer(name = "asset-job-transitions")` and give each the same primary-ID routing key. Messages for one job then share one consumer segment and transition in order, while unrelated jobs can still use other segments and trackers. A separate consumer name defeats this ordering even when the routing-key value is identical.

`@Consumer(name = "asset-job-transitions", singleTracker = true)` is a simpler global-order alternative when partitioning cannot be made safe. It gives one tracker all segments and strict global index order, but sacrifices per-job parallelism and throughput. Prefer primary-ID partitioning when workflows are independent; use `singleTracker = true` only when the business invariant truly needs one global sequence.

Exact primary lookup remains separate and available in terminal states. Expose it as a dedicated `Request<AssetJobView>` query that calls `Fluxzero.loadAggregate(assetJobId)`, maps the aggregate to its public view, and returns `null` for an unknown ID. Do not use alias lookup or a search projection for this exact path. Read exact aggregate lookup and query result contracts for the complete implementation and absence rules.

For a `@Stateful` alternative, persist the workflow primary ID with `@EntityId` and each independent reference with `@Association`. Incoming messages correlate when their property name and value match; use `@Association("captionJobReference")` when names differ. Exact primary lookup of stateful state uses its document identity, for example `Fluxzero.getDocument(assetJobId, AssetJobProcess.class)`, not aggregate loading. Returning `null` from a saga-compatible instance handler deletes that stateful document. If terminal lookup, late correlation, or history is required, return and persist the terminal `AssetJobProcess` copy instead of returning `null` on completion.

## Required correlation checks

Verify every route independently:

- both components, in both arrival orders;
- interleaved messages for at least two workflow IDs;
- unknown raw reference, root ID used as a reference, and wrong-component reference;
- same raw text in different alias namespaces;
- attempted cross-workflow collision, including a primary ID equal to the fully-prefixed alias lookup key;
- concurrent or opposite-order component decisions through separate resolvers, proving one primary-ID transition stream;
- duplicate start preserving the first references;
- exact primary lookup in every terminal state;
- each alias or association again after reconstruction.

Assert which component changed, not merely that some workflow became terminal.
