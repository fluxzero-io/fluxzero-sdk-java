Use typed IDs instead of raw strings at domain boundaries.

```java
public final class ProjectId extends Id<Project> {
    public ProjectId(String id) {
        super(id);
    }
}
```

`Id<T>` serializes as its string value and deserializes through a single-string constructor on the concrete ID type. Keep the concrete ID class stable and avoid passing raw strings across domain boundaries.

Mark each Model identity with `@EntityId`. Put its typed ID on command payloads so Fluxzero can address the Model automatically. `@Apply` returns the updated Model; no routing interface or explicit load is needed for an ordinary update.

Generate IDs with `Fluxzero.generateId(ProjectId.class)` outside `@Apply`, usually in an endpoint or command factory.

Connect independently identified Models with `@Parent`. Use `@Alias` for alternate lookup IDs and the Model loading helpers to resolve them without raw database access.

When a Model has aliases, use the `Id(String functionalId, String prefix)` constructor to put the root in a disjoint internal repository namespace, for example `super(publicAssetJobId, "asset-job-id-")`. The functional ID remains the public serialized value while `toString()` becomes the prefixed repository/routing value. Keep every alias family on a different prefix and read disjoint identity namespaces; alias prefixes alone cannot prevent root precedence from stealing a valid lookup.

ID equality includes the concrete ID class/type context, so two different ID classes with the same string value are not interchangeable. Case sensitivity depends on the constructor behavior of the concrete ID class; do not assume automatic normalization.

When one routing key, alias, document ID, or idempotency key combines multiple opaque values, use a canonical injective
encoding. A delimiter join such as `left + "|" + right` can map distinct tuples to the same scalar value. Read composite
identities for encoding choices and adversarial tests; keep its within-family encoding separate from repository prefixes
that isolate different identity families.
