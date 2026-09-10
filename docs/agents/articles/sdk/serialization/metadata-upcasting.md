Use this when a stored message's metadata must evolve during deserialization. Keep payload/type/format changes in an `ObjectNode` or `Data<T>` upcaster; metadata has its own immutable parameter-and-result contract.

## Return replacement metadata

An `@Upcast` method may inject the intermediate payload, its `Data<T>`, the source `SerializedMessage`, and the message `Metadata` in any order. To change only metadata, return `Metadata`:

```java
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import org.springframework.stereotype.Component;

@Component
final class CreateProjectMetadataUpcaster {
    @Upcast(type = "com.example.project.api.CreateProject", revision = 1)
    Metadata addLegacyTenant(Metadata metadata, SerializedMessage source) {
        return metadata.with("tenant", "legacy")
                .with("upcasted-message-id", source.getMessageId());
    }
}
```

Returning `Metadata` replaces the complete message metadata, leaves the payload unchanged, and advances the revision by one. `metadata.with(...)` retains existing entries while adding or replacing the named entries. Returning `Metadata.of(...)` instead intentionally drops every entry you do not copy. The source `SerializedMessage`, including its metadata, payload, and message ID, remains unchanged; upcasters must be deterministic and side-effect-free.

Do not call a setter on the injected `SerializedMessage`. The message is context, not an object to mutate. A metadata-returning upcaster must return a non-null `Metadata` value.

## Separate payload and metadata revisions

`Data<T>` represents the serialized payload value, type, revision, and format; it does not own message metadata. When both payload and metadata must change, use explicit successive steps. The payload-value/`ObjectNode` and `Metadata` return steps below each advance one revision. A returned `Data<T>` is installed with the revision it contains, so that method must set its target revision explicitly. Only one caster may own a `(type, revision)` pair:

```java
@Upcast(type = "com.example.project.api.CreateProject", revision = 0)
ObjectNode renameOwner(ObjectNode payload) {
    payload.set("ownerId", payload.remove("userId"));
    return payload; // revision 0 -> 1
}

@Upcast(type = "com.example.project.api.CreateProject", revision = 1)
Metadata addTenant(Metadata metadata) {
    return metadata.with("tenant", "legacy"); // revision 1 -> 2
}
```

Set the current stored type's `@Revision` to `2`. Do not register separate payload and metadata methods for the same input revision; that is ambiguous rather than a two-part transaction.

## Message input versus reusable data input

Messages provide `Metadata` and `SerializedMessage` context. Snapshots, documents, key-value entries, and other raw serialized objects do not. A required `Metadata` parameter therefore makes an upcaster message-only: applying it to non-message data fails.

If one payload upcaster deliberately supports both contexts, make metadata nullable:

```java
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.api.Metadata;
import org.jspecify.annotations.Nullable;

@Upcast(type = "com.example.shared.LegacyValue", revision = 0)
ObjectNode normalize(ObjectNode payload, @Nullable Metadata metadata) {
    if (metadata != null && metadata.containsKey("tenant")) {
        payload.put("tenant", metadata.get("tenant"));
    }
    return payload;
}
```

Fluxzero recognizes a runtime parameter annotation whose simple name is `Nullable`; Kotlin nullable parameter types are also recognized. The parameter receives `null` for non-message input. This only makes metadata injection optional. A method that returns `Metadata` still requires message input because a raw serialized object has no metadata envelope to replace.

## Test the envelope, absence, and failures

Keep payload-only shape tests with `TestFixture.whenUpcasting(...)`. Add message-envelope tests against the registered serializer/caster chain or through durable reconstruction of a legacy `SerializedMessage`. Prove all of these separately:

- existing metadata remains and the new entry is present;
- payload bytes/value, type, message ID, and unrelated envelope fields are preserved while revision advances exactly one;
- the original input message and its metadata are unchanged;
- a nullable metadata parameter receives `null` for non-message input;
- a required metadata parameter on non-message input fails with `DeserializationException`;
- a metadata-returning caster on non-message input fails;
- returning `null` metadata fails instead of deleting or silently preserving metadata.

When a payload step and metadata step are chained, test the intermediate and final revisions so a missing or duplicate `(type, revision)` caster cannot be hidden by the final object assertion.
