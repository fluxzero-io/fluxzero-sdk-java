Use `@ProtectData` when a field must stay out of the ordinary serialized payload. Fluxzero stores the value separately
in KV, puts only a field-to-key reference in message metadata, serializes the field as `null`, and restores it for
handling. Put `@DropProtectedData` only on the trusted handler that should consume the value for the last time.

## Independent Model events

Independent `@Model` updates also redact stored/published events, including local automatic commands, explicit
`assertAndApply`, and `@InterceptApply` replacements. Durable Model events require vault-backed references.
Unchanged restored values retain their references without recreating erased values. Reconstruction restores only
retained values; applies must tolerate erased (`null`) private data, while vault read failures fail reconstruction.
Protection does not extend to secrets copied into Model state, documents, or snapshots. JSON aliases and configured
property naming are respected; custom serializers must expose their serialized property paths.

The aggregate example below remains a compatibility example. For new Model state, a trusted explicit handler can
derive the permitted value and call `Fluxzero.assertAndApply(new TicketOpened(...))` instead. The update's pure
`@Apply` must persist only that safe derived value. `@LocalOnly` prevents external command dispatch, not durable Model
writes inside the handler; those events still require protected references.

## Deletion happens before handler invocation

The interceptor order for a dropping handler is:

```text
KV read -> inject protected value into a handler payload -> delete KV entry -> invoke handler
```

Deletion is therefore not conditional on handler success. If validation, an invariant, or the handler
body fails after restoration, the protected value remains deleted. A retry or later handler may receive `null`.
Do not claim that deletion happens after the handler completes, and do not design retry correctness around recovering
the raw value after a failed dropping handler.

## Model the trusted boundary

```java
public record OpenSupportTicket(
        TicketId ticketId,
        @ProtectData String rawContactAddress) {
}

@Component
final class TicketOpeningHandler {
    @HandleCommand
    @DropProtectedData
    void handle(OpenSupportTicket command) {
        String pseudonym = pseudonym(command.rawContactAddress());
        Fluxzero.<SupportTicket>loadAggregate(command.ticketId())
                .assertAndApply(new TicketOpened(command.ticketId(), pseudonym));
    }
}
```

`assertAndApply(...)` is an `Entity` operation. Do not call it on `TicketOpened` or another event record. The explicit `loadAggregate(...)` above supplies the aggregate target, and `@Component` makes this standalone handler discoverable in a Spring application. Outside Spring, register the handler instance with the configured `Fluxzero.registerHandlers(...)` API.

The durable update, aggregate state, logs, results, events, and metrics must contain only a derived safe value, never the
raw protected value. Nested protection is not an arbitrary recursive scan: every segment of a nested path must itself
be annotated with `@ProtectData`.

Choose the dropping handler carefully. Another independent handler for the same message may run after it and see the
protected field as `null`. Missing protected data is controlled by `MissingProtectedDataPolicy`: handler-level
`onMissingProtectedData` overrides the consumer setting, which overrides the application default. Use `FAIL` when a
missing value must stop handling, `SKIP` when the handler should not run, `WARN` for a null-tolerant warned path, and
`HANDLE` only when passing `null` is intentional.

## Prove sanitization, restoration, and deletion separately

A durable-event assertion with the sensitive field set to `null` proves sanitization. A capture in the trusted handler
proves restoration. Neither proves that KV was deleted.

For a deterministic deletion assertion, capture the generated KV key from the handler's `DeserializingMessage`
metadata, then inspect the fixture's public `keyValueStore()` after the action:

```java
AtomicReference<String> protectedKey = new AtomicReference<>();

@HandleCommand
@DropProtectedData
void handle(OpenSupportTicket command, DeserializingMessage message) {
    Map<String, String> references = message.getMetadata().get(
            DataProtectionInterceptor.METADATA_KEY, Map.class);
    protectedKey.set(references.get("rawContactAddress"));
    // derive and apply only safe state
}

fixture.whenCommand(new OpenSupportTicket(ticketId, "person@example.test"))
        .expectThat(fc -> assertNull(fc.keyValueStore().get(protectedKey.get())));
```

Add a second scenario whose dropping handler captures the same key and then throws. The post-action KV
assertion is still `null`; that test records the pre-handler-deletion limitation instead of accidentally promising
success-conditioned deletion.

## Prove later-handler visibility with the right message shape

A request such as a command selects one non-passive result handler. Registering a second ordinary `@HandleCommand`
method therefore does not prove later visibility: that second handler is not invoked. For the original command type,
make the later observer explicitly passive and allow it to handle missing protected data:

```java
AtomicReference<String> trustedValue = new AtomicReference<>();
AtomicReference<String> laterValue = new AtomicReference<>("not-called");

final class TrustedTicketHandler {
    @HandleCommand
    @DropProtectedData
    void handle(OpenSupportTicket command) {
        trustedValue.set(command.rawContactAddress());
    }
}

final class LaterPassiveObserver {
    @HandleCommand(
            passive = true,
            onMissingProtectedData = MissingProtectedDataPolicy.HANDLE)
    void observe(OpenSupportTicket command) {
        laterValue.set(command.rawContactAddress());
    }
}

fixture.registerHandlers(new TrustedTicketHandler(), new LaterPassiveObserver())
        .whenCommand(new OpenSupportTicket(ticketId, "person@example.test"))
        .expectThat(fc -> {
            assertEquals("person@example.test", trustedValue.get());
            assertNull(laterValue.get());
        });
```

Registration order is intentional in this local fixture: the dropping result handler runs before the passive observer.
This proves that a genuinely invoked later command observer cannot restore the deleted value. It is not a production
ordering guarantee between independent named consumers, which track and claim segments separately.

For the smallest framework-level proof, use a fan-out event instead. Register an `@HandleEvent` dropping handler
before a second `@HandleEvent` handler for the same protected event; events can invoke both ordinary handlers. Assert
that the first sees the raw value and the second sees `null`. This is the shape used by the SDK regression test.

Either later-handler pattern proves single-use visibility only. It does not by itself prove direct KV deletion,
pre-handler deletion timing, or failure behavior, so keep the direct KV and throwing-handler scenarios too.
