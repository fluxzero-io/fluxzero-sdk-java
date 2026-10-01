Use this when a Model has both a public primary identifier and one or more `@Alias` families. Post-load equality guards prevent mutation of a wrong root, but they cannot recover a valid alias after root-ID precedence selected another Model.

## Prefix the repository ID, not the public value

Suppose a processor decision loads `"caption-ref-" + raw`. If another Model's repository ID is exactly that text, `Fluxzero.loadModel(alias, AssetJob.class)` returns that root before considering aliases. A field-equality guard rejects the wrong root, but lookup does not fall back to the intended alias. The valid processor decision is silently dropped.

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

Keep the Model root and aliases in disjoint namespaces:

```java
@Model
public record AssetJob(
        @EntityId AssetJobId assetJobId,
        @Alias(prefix = "caption-ref-") String captionReference,
        @Alias(prefix = "artwork-ref-") String artworkReference,
        AssetJobStatus status) {
}
```

The internal root key always begins `asset-job-id-`; aliases begin `caption-ref-` or `artwork-ref-`. Because these prefixes are disjoint and always added internally, an unrestricted public ID cannot occupy an alias repository key.

## Use typed primary keys and explicit alias lookup

Primary commands carry `AssetJobId`; automatic Model applies use its complete repository identity. Direct primary
reads use `Fluxzero.loadModel(new AssetJobId(publicValue))`. Alias reads use the declared prefix and type:

```java
AssetJob job = Fluxzero.loadModel("caption-ref-" + rawReference, AssetJob.class).get();
```

A Model's current aliases must be globally unique. The primary identity takes precedence over an equal alias, which
is why namespaces still matter. When an alias changes or its Model is deleted, the old alias stops resolving after
commit. Keep a persisted-field equality check when interpreting an external callback so the callback's component
and current reference agree. Qualify duplicate references and concurrent alias changes explicitly.

## Adversarial proof

Start job A with caption reference `abc`. Start job B whose public ID is exactly `caption-ref-abc`. Assert their repository identities are effectively `caption-ref-abc` for A's alias and `asset-job-id-caption-ref-abc` for B's root. Then deliver A's caption decision and prove:

- A changes and B does not;
- no valid decision is dropped;
- exact lookup of B still uses its public ID;
- the equality guard succeeds only for A's persisted caption reference;
- after synthetic reconstruction in a new fixture, the same alias and primary routes still work.

Repeat for the artwork alias family and for two workflows with interleaved decisions. Prefixing only aliases, or rejecting public IDs that resemble aliases, is not a collision-safe identity model.
