Use validation for structural payload checks, access annotations, response filtering, and sensitive-field handling. Use `@AssertLegal` for state-dependent business invariants that require Model state.

For concurrent Model invariants, use the binding and read-boundary example at `/docs/sdk/entities/assert-legal`.
Identify the Model values/relations, ID source and conflict policy; neither `@AssertLegal`, current reads nor RETRY
make arbitrary search or helper I/O transactional. Resolve an unclear binding before adding an application workaround.

Structural validation uses Jakarta annotations on command/query records and value objects. Put business detail fields in a dedicated value object and cascade with `@Valid`. For HTTP wrappers and nested collections, read request DTO validation and OpenAPI contracts: `@Valid` cascades into a present value but does not make the value, list, or item required.

```java
public record CreateUser(
    @NotNull UserId userId,
    @Valid @NotNull UserDetails details
) {
    @AssertTrue(message = "Username must differ from email")
    boolean usernameDiffersFromEmail() {
        return !details.username().equals(details.email());
    }
}
```

### Fields before method constraints

The default Fluxzero payload validator checks field constraints, including container-element constraints and
field-based `@Valid` cascades, before the containing object's method constraints. Pure constraint methods may
dereference values required by active field constraints without repeating null guards. For example:

```java
public record ConfigureReminder(@NotNull Duration delay) {
    @AssertTrue(message = "Choose a non-negative delay.")
    boolean hasNonNegativeDelay() {
        return !delay.isNegative();
    }
}
```

For collections, require both the container and its elements, for example
`@NotNull List<@NotNull @Valid ReminderDetails> reminders`. `@Valid` checks a present nested value; it does not
make a missing value or element invalid. Use `@NotNull` or the appropriate field constraint separately.
Optional values still need a null-aware rule. Conditionally required values need a combination rule.

This applies to automatic payload validation and `assertValid`/`checkValidity`/`isValid` with the default validator.
It is not a promise that a method is never called for invalid input: after finding field failures, the validator may
try method constraints again to collect additional violations, suppressing failures from that diagnostic pass.
Keep constraint methods pure. The raw `getConstraintViolations`/Jakarta `validate` APIs do not suppress such method
exceptions; use the normal payload-validation API for this contract.

Only constraints in the active groups and reached through enabled cascades establish these preconditions.
A requirement in a later group-sequence stage cannot protect a method in an earlier stage. Method-level
`@Valid` return values are not prevalidated fields. A replacement validator owns its own ordering and failure behavior.

Use `@ValidateWith` for validation groups when the same value object has different rules in draft and final contexts:

```java
public interface FinalOrder {
}

public record OrderDetails(
    @NotBlank String description,
    @NotNull(groups = FinalOrder.class) String paymentMethod
) {
}

@ValidateWith(FinalOrder.class)
public record SendOrder(@Valid OrderDetails details) {
}
```

Context-aware constraint methods may inject values resolved during handling, such as `User`, `Message`, `DeserializingMessage`, `Metadata`, or custom resolver values. If the parameter cannot be resolved for that validation run, Fluxzero skips that method, so keep always-required checks on fields or no-argument constraint methods.

Security annotations are checked before the handler runs:

- Prefer package-level `@RequiresUser` for protected domains.
- Use `@RequiresAnyRole` or a project-specific meta-annotation for coarse roles.
- Use `@NoUserRequired` only for public endpoints such as login, callbacks, health, or public docs.
- Most-specific annotation wins: method, class, package, then super-package.
- `throwIfUnauthorized = false` silently skips a handler, which is useful only when another handler intentionally serves the fallback.

Enable response/content filtering explicitly with `@FilterContent` on the handler method, class, or package. Filtering applies recursively to objects, collections, and maps. A filter method can inject the current user and the root object; returning `null` removes the object from the result.

When an annotated handler returns a `CompletableFuture`, its successful result is filtered in the original
request context with the original viewer. Failures and cancellation bypass filtering.

Use `@ProtectData` for fields that should not be stored in the normal message stream. Fluxzero stores protected values temporarily in KV and reinjects them for trusted handling. Nested protection works only when every segment of the nested path is annotated. Use `@DropProtectedData` only on the trusted handler that should consume the value for the last time. Deletion happens during restoration before the handler body runs and is not rolled back after handler failure. Read the protected-data lifecycle article before relying on ordering, retries, missing-data policy, or deletion tests.

Test each independent constraint with all other fields valid, and assert `ValidationException` plus the relevant `ViolationSummary.path()`. A payload that makes two required fields blank proves only that validation ran, not that either individual constraint is protected. Use the authorization and validation behavior-matrix article for exact-role, inherited-role, roleless, unauthenticated, and one-cause-per-scenario examples.
