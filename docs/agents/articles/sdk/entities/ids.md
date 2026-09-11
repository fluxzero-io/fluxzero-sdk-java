Use typed IDs instead of raw strings at domain boundaries.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

```java
public final class ProjectId extends Id<Project> {
    public ProjectId(String id) {
        super(id);
    }
}
```

`Id<T>` serializes as its string value and deserializes through a single-string constructor on the concrete ID type. Keep the concrete ID class stable and avoid passing raw strings across domain boundaries.

Mark aggregate and member identity fields with `@EntityId`. Put the relevant ID on command payloads so Fluxzero can route member updates. Use `@RoutingKey` on self-handling update interfaces to keep related commands ordered.

Generate IDs with `Fluxzero.generateId(ProjectId.class)` outside `@Apply`, usually in an endpoint or command factory.

Use `@Member` for nested routed entities and `@Alias` for alternate lookup IDs. `Entity#getEntity` and aggregate loading helpers can then resolve the target without raw database access.

When an aggregate has aliases, use the `Id(String functionalId, String prefix)` constructor to put the root in a disjoint internal repository namespace, for example `super(publicAssetJobId, "asset-job-id-")`. The functional ID remains the public serialized value while `toString()` becomes the prefixed repository/routing value. Keep every alias family on a different prefix and read disjoint identity namespaces; alias prefixes alone cannot prevent root precedence from stealing a valid lookup.

ID equality includes the concrete ID class/type context, so two different ID classes with the same string value are not interchangeable. Case sensitivity depends on the constructor behavior of the concrete ID class; do not assume automatic normalization.

When one routing key, alias, document ID, or idempotency key combines multiple opaque values, use a canonical injective
encoding. A delimiter join such as `left + "|" + right` can map distinct tuples to the same scalar value. Read composite
identities for encoding choices and adversarial tests; keep its within-family encoding separate from repository prefixes
that isolate different identity families.
