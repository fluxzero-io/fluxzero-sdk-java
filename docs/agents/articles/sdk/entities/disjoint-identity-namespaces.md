Use this when an aggregate has both a public primary identifier and one or more `@Alias` families. Post-load equality guards prevent mutation of a wrong root, but they cannot recover a valid alias after root-ID precedence selected another aggregate.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

## Prefix the repository ID, not the public value

Suppose a processor decision loads `"caption-ref-" + raw`. If another aggregate's repository ID is exactly that text, `Fluxzero.loadEntity(...)` returns that root before considering aliases. A field-equality guard rejects the wrong root, but lookup does not fall back to the intended alias. The valid processor decision is silently dropped.

Do not forbid a harmless public job ID such as `caption-ref-abc`. Give the typed primary ID its own internal repository prefix:

```java
import io.fluxzero.sdk.modeling.Id;

public final class AssetJobId extends Id<AssetJob> {
    public AssetJobId(String publicId) {
        super(publicId, "asset-job-id-");
    }
}
```

`Id.getId()` and JSON serialization retain the public functional value. `Id.toString()` returns the repository value, here `asset-job-id-<publicId>`. The public one-argument constructor is also the deserialization path, so every reconstructed `AssetJobId` gets the same repository namespace.

Keep the aggregate root and aliases in disjoint namespaces:

```java
@Aggregate(eventPublication = EventPublication.IF_MODIFIED)
public record AssetJob(
        @EntityId AssetJobId assetJobId,
        @Alias(prefix = "caption-ref-") String captionReference,
        @Alias(prefix = "artwork-ref-") String artworkReference,
        AssetJobStatus status) {
}
```

The internal root key always begins `asset-job-id-`; aliases begin `caption-ref-` or `artwork-ref-`. Because these prefixes are disjoint and always added internally, an unrestricted public ID cannot occupy an alias repository key.

## Use the same typed key on every primary transition

Public start, get, cancel, and expiry payloads accept or reconstruct `AssetJobId`; never load these aggregates by the raw `String`. Every mutation that shares the primary transition consumer exposes the same typed value as its routing key:

```java
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;

@TrackSelf
@Consumer(name = "asset-job-transitions")
public record CancelAssetJob(@RoutingKey AssetJobId assetJobId) {
    @HandleCommand
    void handle() {
        Fluxzero.<AssetJob>loadAggregate(assetJobId).assertAndApply(this);
    }
}

@TrackSelf
@Consumer(name = "asset-job-transitions")
record RecordProcessorDecision(
        @RoutingKey AssetJobId assetJobId,
        Component component,
        String expectedReference,
        Decision decision) {
    @HandleCommand
    void handle() {
        Fluxzero.<AssetJob>loadAggregate(assetJobId).assertAndApply(this);
    }
}

@TrackSelf
@Consumer(name = "asset-job-transitions")
record ExpireAssetJob(@RoutingKey AssetJobId assetJobId) {
    @HandleCommand
    void handle() {
        Fluxzero.<AssetJob>loadAggregate(assetJobId).assertAndApply(this);
    }
}
```

`StartAssetJob`, `CancelAssetJob`, `RecordProcessorDecision`, and `ExpireAssetJob` must use the same named transition consumer. Self-handling command records need both `@TrackSelf` and `@Consumer(name = "asset-job-transitions")`; without `@TrackSelf`, their handler methods execute locally and bypass tracked ordering. If `StartAssetJob` uses a separate registered handler class instead, put that handler class on the same named `@Consumer`. Their `AssetJobId.toString()` routing value is the same `asset-job-id-...` key, so they share one segment. Exact public lookup constructs `new AssetJobId(publicValue)` and calls `Fluxzero.loadAggregate(assetJobId)`; it does not concatenate the prefix at each call site.

Secondary resolvers still load `"caption-ref-" + raw` or `"artwork-ref-" + raw` and must retain the persisted-field equality guard as defense in depth. Disjoint namespaces prevent root precedence from stealing the lookup; the guard rejects a wrong-family or otherwise mismatched-field resolution. It cannot distinguish two aggregates that persist the same alias-family value, because both fields pass equality. Generate and enforce unique component references to prevent that duplicate-alias ambiguity.

## Adversarial proof

Start job A with caption reference `abc`. Start job B whose public ID is exactly `caption-ref-abc`. Assert their repository identities are effectively `caption-ref-abc` for A's alias and `asset-job-id-caption-ref-abc` for B's root. Then deliver A's caption decision and prove:

- A changes and B does not;
- no valid decision is dropped;
- exact lookup of B still uses its public ID;
- the equality guard succeeds only for A's persisted caption reference;
- after synthetic reconstruction in a new fixture, the same alias and primary routes still work.

Repeat for the artwork alias family and for two workflows with interleaved decisions. Prefixing only aliases, or rejecting public IDs that resemble aliases, is not a collision-safe identity model.
