Use validation for structural payload checks, access annotations, response filtering, and sensitive-field handling. Use `@AssertLegal` for state-dependent business invariants that require aggregate data.

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

Use `@ProtectData` for fields that should not be stored in the normal message stream. Fluxzero stores protected values temporarily in KV and reinjects them for trusted handling. Nested protection works only when every segment of the nested path is annotated. Use `@DropProtectedData` only on the trusted handler that should consume the value for the last time. Deletion happens during restoration before the handler body runs and is not rolled back after handler failure. Read the protected-data lifecycle article before relying on ordering, retries, missing-data policy, or deletion tests.

Test each independent constraint with all other fields valid, and assert `ValidationException` plus the relevant `ViolationSummary.path()`. A payload that makes two required fields blank proves only that validation ran, not that either individual constraint is protected. Use the authorization and validation behavior-matrix article for exact-role, inherited-role, roleless, unauthenticated, and one-cause-per-scenario examples.
