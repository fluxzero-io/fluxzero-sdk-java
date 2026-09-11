# Atomic Multi-Model Updates

## Multi-model commits

One payload can read and update unrelated models:

```java
public record ReserveStock(
        OrderId orderId,
        InventoryId inventoryId,
        int quantity) {
    @AssertLegal
    void assertAvailable(Inventory inventory) {
        if (inventory.available() < quantity) {
            throw InventoryErrors.insufficientStock;
        }
    }

    @Apply
    Order apply(Order order) {
        return order.reserve(quantity);
    }

    @Apply
    Inventory apply(Inventory inventory) {
        return inventory.reserve(quantity);
    }
}
```

The SDK loads all targets at one state boundary and commits their events, direct documents, snapshots and relationship
deltas as one model commit. The event is globally published once.

Typed IDs resolve automatically. If two payload properties refer to the same model type, qualify the model parameter:

```java
@Apply
Account apply(@Association("sourceId") Account source) {
    return source.debit(amount);
}
```

## Conflict policy

Model commits default to `ModelConflictPolicy.DEFAULT`, resolved from apply/model, builder configuration or application
properties. Public policies are:

- `ACCEPT`: preserve the event once; rebase derived documents and relationships on current merged model state.
- `RETRY`: reload and rerun assertions/interceptors/applies.
- `FAIL`: return the conflict.

If multiple applies request different policies, the stricter applicable policy wins; failure is not weakened by retry.

The implicit update policy is `RETRY` from defaults version `2026.09.09`, otherwise `ACCEPT`.
`fluxzero.model.conflictPolicy` and explicit builder/Model/Apply settings override it. Implicit first creations still
fail on conflict: the new default must not turn create-if-absent into an upsert. Explicit RETRY also reevaluates creation
and requires create-only assertions when appropriate. ACCEPT validates apply dependencies and writes, excluding
assertion-/interceptor-only reads; RETRY and FAIL validate the full evaluation readset. Conflict-free eligible Runtime
commits use the same cached-head/atomic-boundary optimization regardless of policy.
