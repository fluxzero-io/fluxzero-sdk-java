# Protected Data At A Model Boundary

Model event serialization also protects annotated properties for automatic commands, explicit applications and interceptor replacements. Reconstruction restores retained values and preserves erased references. Do not copy raw secrets into Model state, documents or snapshots, which are outside this protection.

Use a protected incoming command only at a trusted processing boundary. The handler derives the value permitted in durable state and performs a separate Model update:

```java
public record OpenSupportTicket(TicketId ticketId, @ProtectData String rawContactAddress) {
}

@Component
final class TicketOpeningHandler {
    @HandleCommand(logMessage = true)
    @DropProtectedData
    void handle(OpenSupportTicket command) {
        String pseudonym = pseudonym(command.rawContactAddress());
        Fluxzero.assertAndApply(new TicketOpened(command.ticketId(), pseudonym));
    }
}
```

`TicketOpened` is an application-owned update with a pure `@Apply` method for the ticket Model. It contains only the safe derived value. Never log, return, publish or copy the original contact address into another durable value.

## Erasure and failure

On the vault-backed path the ordering is KV read, restore the handler payload, delete the KV reference, then invoke the selected handler. Erasure is not conditional on handler success. A later retry can receive `null`, or follow the configured `MissingProtectedDataPolicy`. Put `@DropProtectedData` only on the trusted final consumer, and design retries around a safe idempotent result instead of recovering an already erased secret.

## Verify the boundary

Keep `logMessage = true` in a KV-erasure scenario. Capture the field-to-key map from the handler's `DeserializingMessage` metadata under `DataProtectionInterceptor.METADATA_KEY`, then assert that `fixture`'s public `keyValueStore()` no longer contains the captured key. Separately capture the restored handler value and inspect the stored/public update: restoration, sanitization and erasure are different assertions.

Repeat with a handler that throws after restoration and verify that the key remains absent. A second ordinary command handler is not a later observer: requests select one result handler. Use an explicitly passive observer for that scenario and configure its missing-data policy deliberately.

Purely local handling with `logMessage = false` retains protected values in memory without KV access. External fallback and logged messages use KV. `@LocalOnly` controls external command dispatch; durable Model writes inside the handler still need vault-backed references.
