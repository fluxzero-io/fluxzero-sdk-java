Use this when a routing key, alias, document ID, idempotency key, or global-invariant key contains more than one opaque

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
value. Fluxzero converts a `@RoutingKey` value with `toString()`, and `Id` stores a scalar string. The application must
therefore make the mapping from component tuple to string unambiguous and stable.

## Do not join unrestricted values directly

This is collision-prone when either input may contain the delimiter:

```java
String key = left + "|" + right;
```

For example, `("a|b", "c")` and `("a", "b|c")` produce the same string. A rare control character is still unsafe
unless validation explicitly excludes it from every component for the lifetime of the data.

Choose one of these contracts:

- Use one existing opaque business identifier when it already names the invariant.
- Constrain every component to a documented alphabet and reject the separator in runtime validation.
- Encode arbitrary UTF-8 components independently with an alphabet that excludes the separator.
- Use a length-prefixed canonical encoding, with the length unit fixed in the contract.

An independent base64url encoding keeps arbitrary values reversible and makes `.` a safe separator:

```java
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

static String canonicalCompositeKey(String... parts) {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    return Stream.of(parts)
            .map(part -> encoder.encodeToString(
                    part.getBytes(StandardCharsets.UTF_8)))
            .collect(joining("."));
}
```

Wrap the result in a dedicated type and give that type a stable `toString()` when it is used under `@RoutingKey`.
Reuse the same canonical function for an alias, document ID, or idempotency key only when those concepts intentionally
share identity. Keep repository prefixes for different identity families disjoint; canonical tuple encoding solves
within-family collisions, while prefixes solve cross-family collisions.

## Keep one trusted construction path

An injective encoder does not help if callers may independently supply both its inputs and a conflicting encoded value.
Fluxzero routes on the selected `@RoutingKey` value and does not compare it with other message properties. Prefer to
derive the key inside the message:

```java
public record ClaimArtifact(TenantId tenantId, ExternalReference reference)
        implements Request<Artifact> {

    @RoutingKey
    public ArtifactClaimKey claimKey() {
        return ArtifactClaimKey.of(tenantId, reference);
    }
}
```

Alternatively, accept only `ArtifactClaimKey`, or validate an unavoidable duplicate representation in the canonical
constructor:

```java
public record ClaimArtifact(
        TenantId tenantId,
        ExternalReference reference,
        @RoutingKey ArtifactClaimKey claimKey) implements Request<Artifact> {

    public ClaimArtifact {
        ArtifactClaimKey expected = ArtifactClaimKey.of(tenantId, reference);
        if (!expected.equals(claimKey)) {
            throw new IllegalArgumentException("claimKey does not match its components");
        }
    }
}
```

Use that same trusted result for routing, exact lookup, aliases, and persisted document IDs when those concepts really
share identity. Do not let an adapter be the only place that derives the value: other callers can still invoke the
public message constructor directly.

## Prove injectivity at the boundary

Test ordinary values, empty values if allowed, Unicode, delimiter-containing values, and the classic shifted-delimiter
pair. Assert that different tuples produce different keys and that reconstructing or deserializing the dedicated key
preserves its canonical value. Then run the real routed duplicate/concurrency scenario: one identical tuple must collide,
while two adversarial but distinct tuples must both remain valid.

Also invoke every public construction path with a deliberately mismatched derived value. Prove rejection before routing
and prove that no aggregate, alias, document, schedule, or outbound effect exists under either the source-derived or
supplied identity.

Do not rely on a record's generated `toString()` as a durable encoding. Renaming the record or its components changes
that representation and can move messages to different routing segments or make persisted aliases unreachable.
