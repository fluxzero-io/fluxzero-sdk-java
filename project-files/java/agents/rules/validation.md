# Validation & Security

Fluxzero provides a multi-layered approach to security and validation, ensuring that business logic is protected and
data is filtered according to user permissions before it ever reaches the network or the client.

---

## Quick Navigation

- [Core Principles](#core-rules)
- [Payload Validation](#payload-validation)
- [Access Control (RBAC)](#rbac)
    - [@RequiresAnyRole & @RequiresUser](#requires-role)
    - [@NoUserRequired](#no-user-required)
    - [Security Precedence](#security-precedence)
    - [External Integration (JWT/Session)](#security-integration)
- [Content Filtering (@FilterContent)](#content-filtering)
- [Data Protection](#data-protection)
    - [@ProtectData](#protect-data)
    - [@DropProtectedData](#drop-protected-data)

---

<a name="core-rules"></a>

## Core Rules

1. **Fail Fast**: Validation and security checks are performed **before** a message reaches its handler.
2. **Declarative Security**: Use annotations to define access rules on payload classes or handler methods.
3. **Invariants vs. Validation**: Use Jakarta annotations for structural validation and `@AssertLegal` for complex
   business invariants (see [Entities: AssertLegal](entities.md#assertlegal)).
4. **Context-Aware Filtering**: Use `@FilterContent` to ensure users only see authorized data.
5. **Sensitive Data**: Use `@ProtectData` to isolate sensitive fields from the message stream.

---

<a name="payload-validation"></a>

## Payload Validation

Fluxzero integrates with **Jakarta Validation**. Annotate your Command and Query records to enforce constraints.

### Structural Validation

It is recommended to extract detail properties into a dedicated value object (e.g., `UserDetails`) and reference it in
your command using `@Valid`.

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

## Access Control (RBAC)

### @RequiresAnyRole & @RequiresUser

- **@RequiresUser**: The message sender must be authenticated.
- **@RequiresAnyRole**: The sender must have at least one of the specified roles.

**Package-level Security**:
It is strongly recommended to add `@RequiresUser` at the domain package level (in `package-info.java`). This ensures
that all message payloads within that domain are protected by default.

[//]: # (@formatter:off)
```java
@RequiresUser
package io.fluxzero.app.orders;

import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
```
[//]: # (@formatter:on)

> **Tip**: In most projects, a custom `@RequiresRole(Role[])` annotation is created and meta-annotated with
`@RequiresAnyRole` to provide type-safety with a `Role` enum.

**Precedence & Scope**:
Security annotations follow a "most specific wins" rule: **Method > Class > Package > Super Package**.
An annotation on a package automatically covers all classes within it and its sub-packages, unless overridden.

**Silent Exclusion**:
To silently skip a handler without throwing an error if the user lacks permissions, combine security annotations with
`throwIfUnauthorized = false`. This is typically used to provide different handlers for the same message.

[//]: # (@formatter:off)
```java
@Component
class LogHandler {
    // Only admins can see admin logs
    @HandleQuery(throwIfUnauthorized = false)
    @RequiresAnyRole("admin")
    public List<Log> handleAdminQuery(GetLogs query) { ... }

    // Standard users are forbidden from admin logs and see this handler instead
    @HandleQuery
    @ForbidsAnyRole("admin")
    public List<Log> handleStandardQuery(GetLogs query) { ... }
}
```
[//]: # (@formatter:on)

<a name="no-user-required"></a>

### @NoUserRequired

Explicitly allows unauthenticated access. Useful for login or registration endpoints when a package-level
`@RequiresUser` is active.

---

<a name="security-precedence"></a>

## Security Precedence

Security annotations follow a "most specific wins" rule:

1. **Method Level**: Overrides everything else.
2. **Class Level**: Overrides package level.
3. **Package Level**: Default for the domain.
4. **Super Package**: Global default.

**Combining with @AssertLegal**:
Annotations are checked **before** the handler is invoked. Use `@AssertLegal` for rules that require domain state (
e.g., "only the project owner can close it"). Role-based checks (`@RequiresRole`) should be used for coarse-grained
access control (e.g., "only admins can delete projects").

---

<a name="security-integration"></a>

## External Integration (JWT/Session)

Fluxzero applications typically integrate with external identity providers (like Auth0 or standard JWT) via a
`UserProvider`.

- **UserProvider**: A Spring bean that resolves the current user from the message metadata or thread context.
- **JWT**: In a typical setup, a gateway or proxy extracts the JWT, verifies it, and attaches the user information to
  the message `Metadata` before forwarding it to the Fluxzero application.
- **Local Identity**: For local testing, the `TestFixture` uses a system user by default but can be configured with
  specific users using `when...ByUser`.

---

<a name="content-filtering"></a>

## Content Filtering (@FilterContent)

Filtering allows objects to dynamically adjust their exposed content based on who is viewing them.

### Enabling Filtering

Content filtering is not automatic. You must add `@FilterContent` to the **handler method, class, or package** to enable
it for a specific flow.

[//]: # (@formatter:off)
```java
@Component
public class UserQueryHandler {
    @HandleQuery
    @FilterContent // Enables recursive filtering for the result
    public UserProfile handle(GetUserProfile query) {
        return Fluxzero.loadAggregate(query.userId()).get();
    }
}
```
[//]: # (@formatter:on)

### Implementing Filter Logic

Filtering is applied recursively to objects, collections, and maps. The annotated method can inject the current `User`
and the top-level **root object** for context. Returning `null` removes the object from the result.

Practical recursion behavior:

- If a filtered list element returns `null`, it is removed from the list.
- If a filtered map value returns `null`, the key is removed from the map.
- Root-context injection (`filter(User, RootType)`) is useful when child visibility depends on parent state.

[//]: # (@formatter:off)
```java
public record Order(OrderId id, List<LineItem> items, boolean isSecret) {
    @FilterContent
    public Order filter(User user) {
        // Hide the entire order if secret and user is not admin
        return isSecret && !user.hasRole("admin") ? null : this;
    }
}

public record LineItem(String product, double price, boolean isTaxFree) {
    @FilterContent
    public LineItem filter(User user, Order root) {
        // Injecting the root Order for context
        // Mask price for non-admins if the order is not yet finalized
        if (!user.hasRole("admin") && !root.isFinalized()) {
            return new LineItem(product, 0.0, isTaxFree);
        }
        return this;
    }
}
```
[//]: # (@formatter:on)

---

<a name="data-protection"></a>

## Data Protection

Data protection isolates sensitive fields from the primary message and event streams.

<a name="protect-data"></a>

### @ProtectData

Fields annotated with `@ProtectData` are removed from the message payload before it is serialized and are stored
temporarily in an external Key-Value (KV) store.

When the message is eventually handled, the Fluxzero SDK **automatically re-injects** the value from the KV store back
into the payload, making it available to the handler.

[//]: # (@formatter:off)
```java
public record SubmitApplication(
    @NotNull ApplicationId id,
    @ProtectData String socialSecurityNumber
) {}
```
[//]: # (@formatter:on)

<a name="drop-protected-data"></a>

### @DropProtectedData

Use this annotation on a handler or endpoint to permanently delete the sensitive values from the KV store. This ensures
that once the trusted processing is complete, the data is no longer accessible.

[//]: # (@formatter:off)
```java
@Component
class ApplicationHandler {
    @HandleCommand
    @DropProtectedData // SSN is deleted from KV store after this handler completes
    void handle(SubmitApplication command) {
        // The SSN is automatically re-injected here
        verifySsn(command.socialSecurityNumber());
    }
}
```
[//]: # (@formatter:on)
