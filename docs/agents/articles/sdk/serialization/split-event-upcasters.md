Use this for reconstruction from a serialized historical event, whether one old event becomes one current event or

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
several. Keep the caster pure and test the registered event deserialization path with a legacy `SerializedMessage`, not
only an already-current Java object.

For this TestFixture setup, `givenAppliedEvents(..., SerializedMessage)` and `givenEvents(SerializedMessage)` deserialize the input as an event stream and flatten every result from a one-to-many upcaster.

## Choose the proof boundary

`whenUpcasting(...)` directly verifies a caster result. It does not prove that serialized legacy event data passes
through the registered caster chain and reconstructs the aggregate. Manually calling `serializer().deserialize(...)`
and then applying the returned current Java object is also narrower because the fixture did not receive the historical
event envelope.

For a one-to-one change, use an ordinary `ObjectNode` or `Data<JsonNode>` upcaster and pass a revision-old
`SerializedMessage` to `givenAppliedEvents(...)`. The fixture deserializes it as `MessageType.EVENT`, runs the registered
caster chain, and applies the single current result. The same path flattens an ordered `Stream<Data<?>>` for the split
case described below.

## For a split, return complete target data

Each split output is a separate serialized object. Give every output its current type, target revision, and format; do not return several payload shapes under the legacy type:

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.api.Data;
import io.fluxzero.sdk.common.serialization.casting.Upcast;

import java.util.List;
import java.util.stream.Stream;

final class LegacyAssetJobUpcaster {
    @Upcast(type = "com.example.media.LegacyAssetJobCreated", revision = 0)
    Stream<Data<JsonNode>> split(Data<JsonNode> input) {
        ObjectNode started = ((ObjectNode) input.getValue()).deepCopy();
        started.remove("tag");

        ObjectNode tagged = ((ObjectNode) input.getValue()).deepCopy();
        tagged.remove(List.of("assetKey"));

        return Stream.of(
                new Data<>(started, AssetJobStarted.class.getName(), 1, input.getFormat()),
                new Data<>(tagged, AssetJobTagged.class.getName(), 1, input.getFormat()));
    }
}
```

The target classes in this example declare `@Revision(1)`. If one target's current revision is higher, its returned `Data<?>` must enter at the revision expected by that target's next caster or at its final current revision. The output stream order is the event order, so make it deliberate and test the resulting state rather than merely counting outputs.

## Build a real legacy event envelope

Use a `SerializedMessage` to exercise event-stream fan-out. Its `Data<byte[]>` must contain the historical type and revision rather than a current Java object that bypasses the legacy boundary:

```java
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.JsonUtils;

import java.util.Map;

static SerializedMessage legacyAssetJobCreated() {
    Data<byte[]> legacy = new Data<>(
            JsonUtils.asBytes(Map.of(
                    "assetJobId", "asset-job-42",
                    "assetKey", "asset-7",
                    "tag", "priority")),
            "com.example.media.LegacyAssetJobCreated",
            0,
            Data.JSON_FORMAT);
    return new SerializedMessage(legacy, Metadata.empty(), "legacy-asset-job-42", 0L);
}
```

Register the caster before entering the Given phase. Passing raw `Data<?>` or a current plain object is useful for other fixture setup, but it does not prove this `SerializedMessage` event-stream flattening path.

## Prove aggregate reconstruction for one or many results

`givenAppliedEvents` applies every split result to the selected aggregate. Query or load the reconstructed aggregate after the Given phase and assert the combined state:

```java
TestFixture.create(AssetJob.class)
        .registerCasters(new LegacyAssetJobUpcaster())
        .givenAppliedEvents(new AssetJobId("asset-job-42"), legacyAssetJobCreated())
        .whenQuery(new GetAssetJob(new AssetJobId("asset-job-42")))
        .verifyResult(result -> {
            assertEquals("asset-7", result.assetKey());
            assertEquals(List.of("priority"), result.tags());
        });
```

For a one-to-one caster, use the same fixture shape and assert the current state produced by the single current event.
For a split caster, this proves more than `whenUpcasting(...)` alone: every resulting event must deserialize, retain its
order, target the aggregate, and combine into the expected current state.

## Prove published-event fan-out

`givenEvents` publishes every split result through the event gateway. Register a handler or projection and assert what it recorded after setup:

```java
TestFixture.create(new AssetJobEventProjection())
        .registerCasters(new LegacyAssetJobUpcaster())
        .givenEvents(legacyAssetJobCreated())
        .whenQuery(new GetSeenAssetJobEvents())
        .expectResult(List.of(
                new AssetJobStarted("asset-job-42", "asset-7"),
                new AssetJobTagged("asset-job-42", "priority")));
```

Do not use a When-phase `expectEvents(...)` assertion for these historical Given events. Assert reconstructed aggregate state for `givenAppliedEvents`, or downstream handler/projection state for `givenEvents`. Keep a focused caster unit test over `split(...)` that asserts the ordered output `Data<?>` types and revisions; the two fixture shapes above prove their integration effects. For a negative row, make an output deliberately incompatible with its target and assert the exact deserialization failure; a chain that merely stops at another revision can still deserialize when that payload already fits the target shape.
