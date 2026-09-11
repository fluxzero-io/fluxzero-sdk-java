Use this when a query can return no matching value or when a fixture result assertion is unclear. The generic argument of `Request<R>` is the unwrapped public result contract.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

## Declare the value type, not `Optional`

For a query whose successful value is `AssetJob`, declare `Request<AssetJob>`. A handler may return `AssetJob`, `null`, or `Optional<AssetJob>`; Fluxzero validates the handler's unwrapped type against `AssetJob` and unwraps `Optional.empty()` to a `null` result.

The direct nullable form is:

```java
public record GetAssetJob(AssetJobId assetJobId) implements Request<AssetJob> {
    @HandleQuery
    AssetJob handle() {
        return Fluxzero.<AssetJob>loadAggregate(assetJobId).orElse(null);
    }
}
```

The equivalent optional-producing handler still declares `Request<AssetJob>`:

```java
public record FindAssetJob(AssetJobId assetJobId) implements Request<AssetJob> {
    @HandleQuery
    Optional<AssetJob> handle() {
        return Optional.ofNullable(Fluxzero.<AssetJob>loadAggregate(assetJobId).get());
    }
}
```

For local handling, `LocalHandlerRegistry` converts a returned `Optional<AssetJob>` to `AssetJob` or `null`. The annotation processor likewise unwraps `Optional<R>` (and `Future<R>`) before comparing the handler type with `Request<R>`.

Assert present and absent normal outcomes as:

```java
fixture.whenQuery(new GetAssetJob(existingId))
        .expectResult(AssetJob.class);

fixture.whenQuery(new GetAssetJob(missingId))
        .expectNoResult();
```

`expectNoResult()` means the unwrapped handler result is `null`. It does not mean an empty collection or an exception.

## Reject a wrapped request contract

Do not put `Optional` in the request generic:

```java
public record FindAssetJob(AssetJobId assetJobId) implements Request<Optional<AssetJob>> {
    @HandleQuery
    Optional<AssetJob> handle() {                  // invalid request contract
        return Optional.ofNullable(Fluxzero.<AssetJob>loadAggregate(assetJobId).get());
    }
}
```

Fluxzero unwraps the handler return to `AssetJob` and then compares it with the declared `Optional<AssetJob>`, so this contract is rejected. `Request<Optional<AssetJob>>` with a handler returning `AssetJob` is invalid for the same reason. Do not weaken annotation processing to make either mixed form compile.

The annotation-processor diagnostic may say `Return type of request handler is invalid. Should be
java.util.Optional<...>`. In this situation, do not mechanically change the handler to return another nested
`Optional`. Inspect the request declaration first: it usually means `Request<Optional<R>>` made the declared result
contract optional while the handler's return was unwrapped to `R`. Declare `Request<R>` and keep `Optional<R>` only as
the handler implementation's normal absence mechanism.

Keep the unwrapped value type consistent across self-handling and standalone handlers, `Fluxzero.queryAndWait(...)`, fixture assertions, and endpoint response mapping. A handler exception is an exceptional result and belongs under `expectExceptionalResult(...)`; it is not an absent normal value.
