# Validation And Protected Data

## Model event boundary

Durable Model events redact protected fields and restore retained values during reconstruction in this SDK version. Protection does not cover a secret copied into Model state, documents or snapshots. Applies must tolerate erased private values; vault-read failures must remain visible.

1. **Fail Fast**: Validation and security checks are performed **before** a message reaches its handler.
2. **Declarative Security**: Use annotations to define access rules on payload classes or handler methods.
3. **Invariants vs. Validation**: Use Jakarta annotations for structural validation and `@AssertLegal` for complex
   business invariants (see Entities: AssertLegal (`/docs/sdk/entities`)).
4. **Context-Aware Filtering**: Use `@FilterContent` to ensure users only see authorized data.
5. **Sensitive Data**: Use `@ProtectData` to isolate sensitive fields from the message stream.

---

<a name="payload-validation"></a>

## Structural validation

Fluxzero integrates with **Jakarta Validation** through the SDK's built-in validator. Annotate your Command and Query
records to enforce constraints. The supported SDK profile covers standard constraints, Fluxzero convenience
constraints, custom validators, groups, cascaded/container validation, executable parameter/return validation, and raw
constraint violations. Constrained payload methods such as `@AssertTrue` may declare parameters that the SDK's default
validator resolves from the same `ParameterResolver` set used for handler method injection. XML mappings,
`validation.xml`, CDI lifecycle integration, TraversableResolver reachability rules, and full Expression Language
message evaluation are intentionally not supported.

When validation fails while handling a web endpoint, Fluxzero raises `ValidationException` and
`DefaultWebResponseMapper` automatically returns `400 Bad Request`. Let the exception propagate; do not add endpoint
`try/catch` code just to create that response. See automatic HTTP result and exception mapping (`/docs/sdk/handlers`).

### Structural Validation

Extract business detail properties into a dedicated value object (e.g., `UserDetails`) and reference it in your
command/query using `@Valid`. Keep top-level primitive/scalar fields mainly for IDs and simple status/control
indicators.

[//]: # (@formatter:off)
```java
public record CreateUser(
    @NotNull UserId userId,
    @Valid @NotNull UserDetails details,
    @Min(18) int age
) {
    @AssertTrue(message = "Username must not be the same as email")
    public boolean isUsernameValid() {
        // NOTE: Null-checks are not needed here; Fluxzero ensures @NotBlank/@NotNull
        // fields in details are validated before this method is even called.
        return !details.username().equals(details.email());
    }
}
```
[//]: # (@formatter:on)

Context-aware method constraints can inject values such as `User`, `Message`, `DeserializingMessage`, `Metadata`, or
custom resolver values while a message is being handled:

[//]: # (@formatter:off)
```java
public record CreateUser(@NotBlank String userId) {
    @AssertTrue(message = "Only admins may create admin users")
    boolean allowedBy(User user) {
        return !userId.startsWith("admin-") || user != null && user.hasRole("admin");
    }
}
```
[//]: # (@formatter:on)

If a constrained method declares parameters that cannot be resolved for the current validation run, Fluxzero skips that
method instead of failing validation. Keep always-required checks on fields or no-argument constraint methods.

### @ValidateWith

Use `@ValidateWith` to reuse validation logic from nested objects or to activate specific validation groups. This is
useful when a field is optional in some contexts but mandatory in others.

[//]: # (@formatter:off)
```java
public record OrderDetails(
    @NotBlank String description,
    @NotNull(groups = FinalOrder.class) String paymentMethod // Only required for final orders
) {}

// Interface used as a validation group
public interface FinalOrder {}

// Save doesn't activate the FinalOrder group; paymentMethod can be null
public record SaveOrder(@Valid OrderDetails details) {}

// Send uses @ValidateWith to enforce the 'FinalOrder' group
@ValidateWith(FinalOrder.class)
public record SendOrder(@NotNull @Valid OrderDetails details) {}
```
[//]: # (@formatter:on)

---

<a name="rbac"></a>

## Protected data

Data protection isolates sensitive fields from the primary message and event streams.

<a name="protect-data"></a>

### @ProtectData

Fields annotated with `@ProtectData` are removed from the message payload before it is serialized. They are stored
temporarily in an external Key-Value (KV) store when the message is externally published.

When the message is eventually handled, the Fluxzero SDK **automatically re-injects** the retained value into the
payload, making it available to the handler.

For a message handled only by a local handler with `logMessage = false`, the original value remains in memory and is
passed directly to the handler without KV I/O. External fallback and `logMessage = true` use the KV-backed path;
`@LocalOnly` prevents external command dispatch, not durable domain writes performed by the handler.

Independent `@Model` updates also redact stored/published events, including local automatic commands, explicit
`assertAndApply`, and `@InterceptApply` replacements. Durable Model events require vault-backed references.
Unchanged restored values retain their references without recreating erased values. Reconstruction restores only
retained values; applies must tolerate erased (`null`) private data, while vault read failures fail reconstruction.
Protection does not extend to secrets copied into Model state, documents, or snapshots. JSON aliases and configured
property naming are respected; custom serializers must expose their serialized property paths.

`@ProtectData` protects the annotated field **as a whole** when its value is:

- a leaf value (see `ReflectionUtils.isLeafValue(...)`)
- a `JsonNode`
- a `Data<?>`
- an `Iterable`
- a `Map`
- a type that is itself annotated with `@ProtectData`

Nested protection is also supported, but only when **every segment of the path is annotated** with `@ProtectData`.
Fluxzero does not recursively scan arbitrary child fields.

[//]: # (@formatter:off)
```java
public record SubmitApplication(
    @NotNull ApplicationId id,
    @ProtectData String socialSecurityNumber
) {}

public record SubmitDetails(
    @ProtectData SensitiveDetails details
) {}

public record SensitiveDetails(
    @ProtectData String socialSecurityNumber,
    String displayName
) {}
```
[//]: # (@formatter:on)

In the nested example above, `details/socialSecurityNumber` is protected, while `details/displayName` remains part of
the regular payload.

<a name="drop-protected-data"></a>

### @DropProtectedData

Use `@DropProtectedData` on a handler or endpoint. The retained secret is restored and its reference is erased before the selected handler is invoked; an exception does not undo erasure. The handler can use the restored in-memory value.


```java
@Component
class ApplicationHandler {
    @HandleCommand
    @DropProtectedData // Restores the SSN and deletes its KV reference before invoking this handler
    void handle(SubmitApplication command) {
        // The SSN is automatically re-injected here
        verifySsn(command.socialSecurityNumber());
    }
}
```

If another handler receives the message after the protected value has been dropped, configure its behavior with
`onMissingProtectedData` on the `@Handle...` annotation or on `@Consumer`. Supported policies are `HANDLE` (silent
default), `WARN`, `SKIP`, and `FAIL`; `DEFAULT` inherits from broader configuration. The application fallback is set
with `FluxzeroBuilder.onMissingProtectedData(...)` or
`fluxzero.dataProtection.onMissingProtectedData`. Environment variables may use
`FLUXZERO_DATA_PROTECTION_ON_MISSING_PROTECTED_DATA` or `FLUXZERO_DATAPROTECTION_ONMISSINGPROTECTEDDATA`.
[//]: # (@formatter:on)
