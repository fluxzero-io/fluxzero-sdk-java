Use this to connect validated identity to application permissions. Authentication says who the caller is; authorization decides what that caller may do in this domain.

Create an application-owned `Sender`:

```java
public record Sender(UserId userId, Role userRole) implements User {
    public static final Sender system =
            new Sender(new UserId("system"), Role.OWNER);

    @Override
    public String getName() {
        return userId.toString();
    }

    @Override
    public boolean hasRole(String role) {
        if (role == null) {
            return false;
        }
        try {
            return hasRole(Role.valueOf(role));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public boolean hasRole(Role role) {
        return role.matches(userRole);
    }

    public boolean isAuthorizedFor(UserId userId) {
        return hasRole(Role.OWNER) || this.userId.equals(userId);
    }
}
```

Create roles in the app, not in the IDP client:

```java
public enum Role {
    VIEWER,
    EDITOR(VIEWER),
    OWNER(EDITOR);

    private final Role[] assumedRoles;

    Role(Role... assumedRoles) {
        this.assumedRoles = assumedRoles;
    }

    public boolean matches(Role userRole) {
        if (userRole == null) {
            return false;
        }
        if (this == userRole) {
            return true;
        }
        for (Role assumedRole : userRole.assumedRoles) {
            if (matches(assumedRole)) {
                return true;
            }
        }
        return false;
    }
}
```

Use the authorization and validation behavior-matrix article to turn the hierarchy into exact-role, inherited-role, adjacent-denial, roleless, and unauthenticated scenarios. `@RequiresUser` proves identity only; add a base-role requirement when every authenticated user is not automatically a member of that domain role.

Create a role annotation when the app needs coarse-grained role checks:

```java
@Target({TYPE, METHOD, PACKAGE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@RequiresAnyRole
public @interface RequiresRole {
    Role[] value();
}
```

Fluxzero security annotations can be applied at package, type, constructor, and method level. Package-level `@RequiresUser` is the recommended default; `@NoUserRequired` permits anonymous access and overrides broader user requirements for explicit public surfaces.

Precedence is method, then class, then package, then super-package. Put broad defaults high in the package tree and explicit exceptions only at the endpoint or payload that needs them.

`throwIfUnauthorized` belongs on auth annotations such as `@RequiresUser` and `@RequiresAnyRole`, not on `@HandleQuery`. Use it only when a handler should be silently skipped so another eligible handler can process the same message.

Register a `SenderProvider`. The code fragment below maps an already validated identity to application roles; it is deliberately not a complete production HTTP provider. `getUserById(...)` alone is not HTTP authentication. For a real frontend, follow the authenticated-frontend recipe and implement the cookie/bearer `fromMessage(...)` boundary described by production HTTP identity:

- For web requests, first respect any explicit Fluxzero user already present.
- Then read the BFF session cookie through `AppSessionStore.sender(metadata)`.
- Optionally accept `Authorization: Bearer ...` for API-style calls.
- Validate bearer tokens with `TokenValidators` and the same `OidcTenantConfig`.
- Refresh the user by loading the app's user aggregate/read model so role changes take effect. The application owns this mapping; the IDP client does not own domain roles.
- Return a system user for trusted internal commands.

If the application creates domain users, the provider is incomplete until `getUserById(...)` resolves those users from application state. A provider that recognizes only the system user and returns `null` for every created user makes successful user-creation commands useless to real signed-in requests.

```java
@Component
final class SenderProvider extends AbstractUserProvider
        implements RefreshingUserProvider<Sender> {

    SenderProvider() {
        super(Sender.class);
    }

    @Override
    public User getUserById(Object rawUserId) {
        UserId userId = rawUserId instanceof UserId typed
                ? typed : new UserId(rawUserId.toString());
        UserProfile profile = Fluxzero.loadAggregate(userId, UserProfile.class).get();
        return profile == null ? null : new Sender(profile.userId(), profile.role());
    }

    @Override
    public Sender refreshUser(Sender sender, HasMessage message) {
        return sender == null ? null : (Sender) getUserById(sender.userId());
    }

    @Override
    public User getSystemUser() {
        return Sender.system;
    }
}
```

A missing aggregate is represented by an empty `Entity`, and `Entity.get()` returns `null`; the `profile == null` branch above is the normal absence path. Do not wrap `Fluxzero.loadAggregate(...).get()` in `catch (RuntimeException)` and translate every failure to `null`. That would erase the distinction between normal absence and storage, serialization, `TechnicalException`, or programming failures. Preserve the original failure in provider code and instrument that boundary. During initial request authentication, the current `AuthenticatingInterceptor` converts an exception thrown by `fromMessage(...)` to no user, so do not claim that the HTTP caller will observe the technical exception; test and monitor provider failures separately. If malformed raw identifiers are deliberately treated as unknown callers, catch only the exact identifier-conversion failure before loading application state.

Spring registers a `UserProvider` bean with the Fluxzero builder. Outside Spring, register it explicitly with `DefaultFluxzero.builder().registerUserProvider(...)`. Use `RefreshingUserProvider` when persisted role or profile changes must affect later requests instead of trusting stale role data embedded in an earlier session.

Map validated IDP claims to existing application state according to the client credential and first-user lifecycle and an explicit provisioning policy. Successful external authentication does not itself authorize creation of a domain user. Choose pre-provisioning, administrator invitation, or explicit self-registration as the product requires, and make that choice idempotent and auditable. Do not use the system user to send an admin-only create-user command merely because an authenticated subject is missing; reserve system authority for a named, deliberate bootstrap, migration, or infrastructure operation.

Protect by default:

```java
@RequiresUser
@Path("/api")
package com.example.app;
```

Add `@NoUserRequired` only where public access is intentional:

- `/app/login`
- `/app/callback`
- public health/docs/static routes
- public webhooks that verify signatures themselves

Put fine-grained checks near the behavior:

- Use `Sender` injection in command handlers or `@AssertLegal` methods.
- When actor identity becomes persisted state, inject `Sender` directly into `@Apply`; Fluxzero resolves it from stored message metadata during replay. Do not call `User.getCurrent()` inside `@Apply` and do not accept an actor ID from an untrusted client payload.
- Keep object ownership checks in domain rules.
- Keep elevated-role checks in command annotations or legal assertions.
- Do not trust frontend route guards as the only authorization layer.

Test ownership with `whenCommandByUser` or the matching web helper, then load/query the state in a later fixture step and attempt the protected action as both the original actor and another user. This proves dispatch metadata, replayed ownership, and authorization together; directly calling `apply(...)` does not.

Also test the configured lookup path whenever created users must be able to sign in. Register the real provider on the builder, keep the fixture's normal provider wrapper, create the profile as setup, and pass the user ID rather than a constructed `Sender` to a `...ByUser` operation:

```java
TestFixture.create(
                DefaultFluxzero.builder().registerUserProvider(new SenderProvider()),
                CreateUser.class, ProfileEndpoint.class)
        .givenCommands(new CreateUser(viewerId, "Viewer", Role.VIEWER))
        .whenGetByUser(viewerId, "/api/users/me")
        .expectWebResult(response -> response.getStatus() == 200);
```

The normal fixture wraps the builder-configured provider with `TestUserProvider`; ID lookup still delegates to that configured provider. Passing a ready-made `Sender` proves authorization for that object, but it bypasses `getUserById(...)` and cannot prove that a runtime request can resolve a created user.

Both forms bypass real HTTP credential extraction: `...ByUser` puts `$user` on the message after accepting or resolving the supplied identity. Add the raw cookie/bearer scenarios from HTTP authentication boundary testing before claiming a proxy-facing sign-in path works.

`withProductionUserProvider()` has a different purpose: it disables the fixture's system-user fallback and switches to the process-wide `UserProvider.defaultUserProvider`, which is useful for unauthenticated behavior. It does not preserve an arbitrary provider registered only on the fixture builder. Use it in a separate anonymous/no-fallback scenario, not in the configured-provider lookup test above.
