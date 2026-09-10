Use this when a business key must be unique across aggregate roots, especially when the key that owns the invariant is not the aggregate ID being updated. Examples include one device serial across inventories, one email address across accounts, or one external reference across orders.

## Route by the invariant key

A check such as `Fluxzero.loadEntity(serial).isPresent()` followed by an update to `inventoryId` is a check-then-write boundary. If registration commands are routed by inventory ID—or have no stable routing key—two inventories can check the same device serial concurrently.

Put `@RoutingKey` on the business-unique key of the tracked ingress command:

```java
public record AssignDevice(
        @NotNull InventoryId inventoryId,
        @NotNull @RoutingKey DeviceSerial serial,
        @NotNull LocalDate receivedDate,
        @NotBlank String modelLabel) implements Request<Inventory> {
}

@Component
@Consumer(name = "device-assignment")
final class DeviceAssignmentHandler {
    @HandleCommand
    Inventory handle(AssignDevice command) {
        if (Fluxzero.loadEntity(command.serial()).isPresent()) {
            throw new IllegalCommandException(
                    "Device serial is already assigned: " + command.serial().getFunctionalId());
        }

        DeviceAssigned event = new DeviceAssigned(
                command.inventoryId(), command.serial(),
                command.receivedDate(), command.modelLabel());
        return Fluxzero.loadAggregate(command.inventoryId())
                .assertAndApply(event)
                .get();
    }
}
```

The payload-level routing key is computed before publication. Equal routing values map to one message segment; within one named consumer, one active tracker owns that segment and handles those messages in order. Assignments for the same `serial` are therefore serialized even when they update different `inventoryId` aggregates. Unrelated device serials can still use other segments.

Keep the existence check and the awaited aggregate mutation in the same tracked handler invocation. Do not check in one consumer and externally dispatch the write to another command: that reopens a gap between check and write.

## Derive the routing identity from one trusted representation

Do not expose both the source fields of an invariant and a separately caller-controlled derived `@RoutingKey` unless the
canonical constructor recomputes or validates the derived value. Fluxzero consumes the selected routing value; it does
not prove that the value represents the other message fields. If the same value later becomes an alias, document ID, or
lookup key, an inconsistent caller-supplied value can redirect every one of those boundaries together.

Prefer a computed zero-argument property when the public command naturally carries the source fields:

```java
public record ClaimArtifact(
        @NotNull TenantId tenantId,
        @NotNull ExternalReference reference) implements Request<Artifact> {

    @RoutingKey
    public ArtifactClaimKey claimKey() {
        return ArtifactClaimKey.of(tenantId, reference);
    }
}
```

Other safe shapes accept only the already-canonical key, or use a compact constructor that rejects a supplied key when
it differs from recomputation. Reuse one trusted construction path anywhere the identity influences routing, lookup,
aliasing, or persistence. Canonical construction and collision-safe scalar encoding are separate requirements: apply
both when the key has multiple components.

## Keep the serialization scope intact

Every command that can claim, release, rename, or otherwise change the same unique key must use the same invariant-key routing value and the same named consumer. The following do not protect a global key:

- routing by the target aggregate ID when the same unique key can occur in different aggregates;
- putting equal routing values in different consumer names, because consumers have independent positions and segment claims;
- a synchronous local/self handler that bypasses tracked production ingress;
- request-ID deduplication, which is not a permanent business uniqueness constraint;
- a preflight query in an endpoint followed by a separately tracked command.

`@RoutingKey` provides per-consumer processing order, not a database unique constraint or an atomic transaction across aggregate roots. A writer that bypasses this consumer remains outside the guarantee. At-least-once delivery also means the handler must repeat the lookup on retry and make the duplicate outcome deterministic.

If no stable partition key covers the invariant, `@Consumer(name = "global-registration", singleTracker = true)` can impose one global message order at the cost of throughput. A dedicated claim aggregate or staged workflow can make ownership explicit, but it is a workflow design: do not claim cross-aggregate atomicity merely because one command created a claim and then updated another aggregate.

## Prove the invariant boundary

Keep a fast domain matrix and add one tracked concurrency scenario:

| Scenario | Required observation |
| --- | --- |
| First assignment | Success; exactly one member is present |
| Same key, same aggregate | Duplicate is rejected; original member is unchanged |
| Same key, different aggregates | Exactly one aggregate receives the member |
| Two concurrently submitted commands with the same key | One success and one deterministic duplicate; never two members |
| Two different keys | Both succeed; the design does not impose unnecessary global serialization |
| Retry of the accepted request | No second member or side effect |

Run the concurrency row through `TestFixture.createAsync(...)` or a local runtime so the tracked consumer and serialized message segments participate. A synchronous fixture that invokes the handler locally proves the duplicate branch but not the production ordering boundary. Avoid timing sleeps: start both submissions behind a test barrier, await both results, then query every candidate aggregate and count the unique member globally.

Record which submission won and which one was rejected. Query every candidate primary ID and every alias that the
contenders could have created; assert the rejected identity is absent. Inspect the complete active-schedule, document,
and outbound-effect sets and prove that every retained effect belongs to the winner. A single global document count does
not identify the loser or prove that the rejected submission left no timed work under another ID.

When a public constructor necessarily accepts both source fields and a derived key, add an adversarial mismatch test.
The mismatch must be rejected before it can change routing, lookup, alias, aggregate, schedule, or projection state.

Also assert the routing contract structurally: the unique-key field carries `@RoutingKey`, every claimant is assigned to the same consumer name, and no alternate write path bypasses that boundary. Link this matrix to the behavior-matrix tests so missing, terminal, and rejected commands assert no event, metric, or side effect.

If the invariant key combines multiple independently supplied values, encode the tuple canonically before using it as
`@RoutingKey`. Per-key serialization protects only equal scalar routing values; it cannot repair a collision-prone
delimiter join that accidentally makes two different tuples equal. Use the composite-identities recipe and include its
shifted-delimiter pair in the concurrency matrix.
