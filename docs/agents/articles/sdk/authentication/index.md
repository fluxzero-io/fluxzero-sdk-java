Use this when the app needs real users, browser login, API authorization, or role-based behavior. Authentication is optional for many first slices; add it when the user asks for accounts, ownership, sharing, permissions, teams, admin features, or a deployable demo with protected endpoints.

Fluxzero's recommended starter shape is a backend-for-frontend OIDC flow backed by Fluxzero IDP:

- The browser visits `/app/login`.
- The app redirects to the Fluxzero IDP tenant.
- The IDP redirects back to `/app/callback`.
- The backend validates tokens and creates an opaque HTTP-only app session cookie.
- Frontend code calls `/app/auth/session` to learn whether the browser is authenticated.
- Domain handlers receive an application-owned `Sender`, not raw token claims.

Required build additions:

- Runtime: `io.fluxzero.idp:client`.
- Local/test: `io.fluxzero.idp:test-support`.
- Keep the normal Fluxzero SDK and Spring Boot setup. The Fluxzero dev server supplies the standard local runtime,
  proxy, and managed IDP; do not add test-server/proxy boot code to the application.

Main application-owned pieces:

- `AppAuthEndpoint`: login, callback, logout, and session endpoints under `/app`.
- `AppAuthProperties`: resolves `fluxzero.auth.*` tenant/client settings.
- `AppSessionStore`: stores server-side sessions and writes opaque cookies.
- `SenderProvider`: maps browser sessions or bearer tokens to `Sender`.
- `Sender`, `Role`, `RequiresRole`, `AppUsers`: domain-specific user and permission model.

When a brief says a frontend caller is signed in, authenticated, or acts as itself, follow the authenticated-frontend recipe. Do not stop after adding `@RequiresUser`, roles, and `getUserById(...)`. Read the client credential and first-user lifecycle before choosing the mechanism: a validation-only bearer helper is incomplete unless a configured issuer gives the client a real acquisition path. Implement `UserProvider.fromMessage(...)` so proxy-forwarded request metadata becomes a validated application user, then read the production HTTP identity boundary before authorization.

Security defaults:

- Put `@RequiresUser` at the app or API package level.
- Add `@NoUserRequired` only to public endpoints such as login, callback, health, static docs, and public webhooks.
- Use coarse role annotations for broad gates and put state-dependent checks in commands, `@AssertLegal`, or endpoint adapters.
- Do not rely on frontend route guards as the only authorization layer.

Read client credential and first-user lifecycle first, then IDP BFF flow, production HTTP identity, local authentication, and authorization.
