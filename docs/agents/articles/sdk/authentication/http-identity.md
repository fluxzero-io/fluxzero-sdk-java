Use this when a real HTTP or WebSocket client must establish the `User` consumed by `@RequiresUser`, role annotations, and `Sender` injection. Keep four provider responsibilities distinct:

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

| Method | Responsibility |
| --- | --- |
| `fromMessage(...)` | Authenticate an incoming request or consume an already trusted Fluxzero `$user` |
| `getUserById(...)` | Resolve an already identified user, primarily for fixture `...ByUser(id, ...)` helpers and application lookup |
| `refreshUser(...)` | Reload roles/profile for an identity that was already established |
| `getSystemUser()` | Supply the trusted internal identity for non-human work |

`getUserById(...)` is lookup, not HTTP authentication. Extending `AbstractUserProvider` without overriding `fromMessage(...)` only reads a complete user already stored in `$user` metadata. `ProxyServer` forwards request headers and cookies as `WebRequest` metadata; it does not validate them or turn them into `$user`. Likewise, `@RequiresUser` rejects or permits an identity but does not establish one.

## Choose one mechanism or deliberately support both

For browser applications, prefer the BFF flow: validate the IDP callback on the server, store tokens server-side, and send the browser an opaque HTTP-only session cookie. For API clients, validate an `Authorization: Bearer ...` access token with the configured issuer, audience, signature keys, expected token use when the issuer supplies/requires it, `iat`, `nbf`, expiry, and tenant rules. Never treat a raw header, cookie value, query parameter, or client-supplied user ID as an authenticated subject. Validation does not tell a client how to obtain the credential; read the client credential and first-user lifecycle before implementing this boundary.

After validation, map the trusted subject to the application-owned user profile and roles. A provider can make that boundary explicit with application adapters:

```java
interface BearerAuthenticator {
    Optional<UserId> verifyBearerToken(String token);
}

@Component
final class SenderProvider extends AbstractUserProvider
        implements RefreshingUserProvider<Sender> {
    private final BearerAuthenticator tokens;

    SenderProvider(BearerAuthenticator tokens) {
        super(Sender.class);
        this.tokens = tokens;
    }

    @Override
    public User fromMessage(HasMessage message) {
        User alreadyTrusted = super.fromMessage(message);
        if (alreadyTrusted != null) {
            return alreadyTrusted;
        }

        if (!(message.toMessage() instanceof WebRequest)) {
            return null;
        }

        Metadata metadata = message.getMetadata();
        Optional<Sender> sessionSender = AppSessionStore.sender(metadata);
        if (sessionSender.isPresent()) {
            return refreshUser(sessionSender.get(), message);
        }

        Optional<UserId> userId = WebRequest.getHeader(metadata, "Authorization")
                .flatMap(this::verifyBearerHeader);
        return userId.map(this::getUserById).orElse(null);
    }

    private Optional<UserId> verifyBearerHeader(String header) {
        String[] parts = header.strip().split("\\s+", 2);
        return parts.length == 2
               && parts[0].equalsIgnoreCase("Bearer")
               && !parts[1].isBlank()
                ? tokens.verifyBearerToken(parts[1]) : Optional.empty();
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

The snippet is intentionally dual-mode because some applications accept both their BFF session cookie and API bearer tokens. For a cookie-only application, remove `BearerAuthenticator` and the header branch. For a bearer-only application, remove the `AppSessionStore.sender(metadata)` branch. If an application uses a different opaque cookie store, read it with `WebRequest.getCookie(...)` and validate that opaque value server-side.

`BearerAuthenticator` is an application security boundary, not permission to implement token validation by string splitting. Splitting the authorization scheme from its credentials is only header parsing; the authenticator must still verify the token cryptographically and semantically with `TokenValidators` and the configured `OidcTenantConfig`. First respect `super.fromMessage(...)` so trusted identity propagated from a parent Fluxzero message is not re-authenticated as if it were a new HTTP request. Then require an actual `WebRequest` before inspecting raw headers or cookies; otherwise a command or event carrying similarly named metadata could establish an HTTP identity.

For WebSockets, authenticate the opening handshake through the same cookie or bearer path. Established frames reuse the session identity and `RefreshingUserProvider` can refresh application roles; document which handshake header or cookie the client must send.

## Completion checklist

- The app chose BFF cookie, validated bearer behavior, or an intentional dual-mode combination and includes only the matching dependencies and configuration.
- The client has a documented login or configured external-issuer acquisition path; an internal token helper is not that path.
- BFF login/callback/session endpoints are explicitly public with `@NoUserRequired`; protected API packages use `@RequiresUser`.
- `fromMessage(...)` handles the chosen real request credential, while `getUserById(...)` and `refreshUser(...)` map it to current application state.
- Local/test startup includes the matching IDP stub or deterministic validator.
- OpenAPI declares the actual cookie/bearer scheme and WebSocket documentation describes handshake credentials.
- Raw credential, missing credential, invalid credential, lookup, role, and ownership behavior are tested as separate boundaries.

A provider with only `getUserById(...)` can pass every `when...ByUser(...)` authorization test and still reject every external proxy request. Read HTTP authentication boundary testing before claiming the frontend sign-in path works.
