`@AssertLegal` validates whether a command may be applied. Use it for business invariants and ownership checks that depend on current state or sender context.

```java
@AssertLegal
void assertOwner(Project project, Sender sender) {
    if (!project.ownerId().equals(sender.userId())) {
        throw ProjectErrors.unauthorized;
    }
}
```

Assertions may load or query data when needed, but they must not perform updates. Keep error types grouped in a domain error interface so tests can assert the exact rule that failed.

Assertion methods can inject the current entity, ancestors, the payload, metadata, the full message, and user context. Use nullable parameters when absent entities are legal for a create path.

If an assertion returns a non-null object, Fluxzero inspects that object for further `@AssertLegal` methods. Use this only when it makes nested legality reusable; otherwise return `void`.

Use `priority` and `afterHandler` only when assertion ordering changes behavior. Most domains should keep assertions independent enough that ordering does not matter.

Throw domain errors built from Fluxzero `FunctionalException` helpers/constants so callers receive functional failures instead of infrastructure errors.

Security annotations are good coarse gates. Put state-dependent authorization, ownership, quota, and cross-aggregate legality in `@AssertLegal` so the rule is tested with the domain behavior.

Use Jakarta validation for payload shape, nullability, and scalar constraints. Use `@AssertLegal` for rules that depend on existing state, user context, other aggregates, or current search/query results.

For Models, injected state and actual Graph navigation participate in the evaluation read set. Use `RETRY` or `FAIL`
for assertion invariants; `ACCEPT` retains apply dependencies only. Empty child scopes count, whereas arbitrary
search/query results are not automatically protected. Read Model and Graph conflicts for the precise boundaries.

## A separately addressed Model invariant

An independent Product must remain active when a Reservation is committed. Bind its ID explicitly in the command;
it does not need a parent relation to the Reservation:

```java
@Model record Product(@EntityId String productId, boolean active) {}
@Model record Reservation(@EntityId String reservationId) {}

record Reserve(String reservationId, String productId) {
    @AssertLegal
    void check(@jakarta.annotation.Nullable Product product) {
        if (product == null || !product.active()) {
            throw new IllegalCommandException("Product is not active");
        }
    }

    @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
    Reservation apply() { return new Reservation(reservationId); }
}
```

In Kotlin, the same binding uses a nullable Model parameter:

```kotlin
@Model data class Product(@EntityId val productId: String, val active: Boolean)
@Model data class Reservation(@EntityId val reservationId: String)

data class Reserve(val reservationId: String, val productId: String) {
    @AssertLegal
    fun check(product: Product?) {
        if (product?.active != true) throw IllegalCommandException("Product is not active")
    }

    @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
    fun apply() = Reservation(reservationId)
}
```

`productId` matches the Product's `@EntityId` property. `@Nullable` permits an absent Product to reach the explicit
business rejection; it does not make the rule optional. A typed `Id<Product>` or an explicit `@Association` can
disambiguate a different property. With `@Association("product")`, trusted message metadata can supply `product`
even when the payload has no such property. Metadata wins unless `excludeMetadata = true`; never trust caller-owned
metadata for authorization. Binding must be available before handler invocation, including prefetch and retry:
a value that a custom resolver invents only during the method body is not automatically an identified Model target.

The same rule can inject `Graph<Product>` and check `get()`. A returned validation object may carry its own
`productId`; nested `Fluxzero.assertLegal(new ProductGuard(productId))` inside the mutation joins that mutation's
reads too. Independent calls made outside a mutation remain read-only checks, not reservations of future writes.

If Product changes after a successful read but before commit, RETRY reevaluates and rejects an inactive Product;
FAIL reports the conflict. A cached starting state is allowed. An assertion that already rejects is final: no
freshness request or retry is made merely to see whether newer state might permit it.

## Read boundaries are not interchangeable

| Read during a Model mutation | Snapshot and commit protection |
| --- | --- |
| Injected `T` / `Graph<T>` | Attempt snapshot; injected values and inspected relationships are dependencies |
| Synchronous `loadGraph`, `loadCurrentGraph`, `graph.current()` on its repository/namespace | Same attempt snapshot and staged state; inspected values/relationships join its readset, including empty collections |
| Arbitrary `loadModel` in a helper | Authoritative load and applicable handler/cache boundary; does not itself register a new dependency: inject the Model or read its Graph |
| Search / `loadCurrentModelState` | Search/document read contract, not a commit dependency or an event-bound Graph |
| Historical `previous()` / `at...` | Historical view, not a current invariant dependency |

Outside mutations, ordinary Model-event reads use the event's boundary; explicit current Graph reads open a fresh
storage-verified view. Inside mutations, current shortcuts never create a second snapshot. Do not hand detached
Graphs from a separate query or earlier operation to an invariant and assume their old reads become protected.
Canonical Model IDs are the invariant binding; alias lookups do not add transaction-level alias-mapping protection.

SDK rc.13 and earlier do not attach manual Graph loads or separately invoked assertion helpers to the active
readset; use direct injection there. The corrected behavior above applies after that fix. Reproduce invariants with
active/inactive/missing state, warm caches and a second writer between read and commit, not just sequential commands.

Before adding a workaround, identify the values and relationships that carry the invariant, their ID binding,
read boundary and conflict policy. `@AssertLegal`, a current read or RETRY alone does not make arbitrary I/O
transactional. The query guide at `/docs/sdk/entities/graph-search` covers document/search consistency separately.
