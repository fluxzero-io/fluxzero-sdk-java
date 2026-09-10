Use this when a browser, frontend, or API client must obtain the credential that a protected Fluxzero application validates. Token validation is downstream of credential acquisition. An application that only validates or internally mints a token has not given a client a supported authentication path.

## Completion invariant

A client credential lifecycle is complete only when a legitimate caller can:

1. start the chosen login or OAuth/OIDC acquisition flow without application signing secrets;
2. present the resulting cookie or access token to the application;
3. resolve the trusted subject to the current application-owned user and roles;
4. invoke a protected, actor-sensitive business operation;
5. expire, log out, or otherwise end the credential according to the published contract.

A `TokenService.issue(...)` method reachable only from tests, together with README instructions to “send a signed token,” proves a verifier or test helper—not a usable client lifecycle. A public endpoint that mints a token solely from a caller-supplied user ID, without independently verified credential proof, is an impersonation endpoint.

## Choose the client boundary

| Product boundary | Client acquisition | Application responsibility |
| --- | --- | --- |
| Signed-in browser or frontend | BFF authorization-code flow with PKCE | Own `/app/login`, callback, opaque server session, logout, and session-status endpoints |
| API/resource server | Access token from a configured OAuth/OIDC issuer | Validate signature, exact issuer, resource audience, expected token use when the issuer supplies/requires it, `iat`, `nbf`, and `exp`; map subject to application state |
| Trusted upstream Fluxzero identity | Credential is established by a named, verified upstream contract | Consume trusted `$user`, document the deployment boundary, and test it explicitly |
| Application-owned issuer | Only when the product explicitly requires operating an identity system | Treat issuance, credential proof, rotation, revocation, recovery, abuse protection, and audit as separate security requirements |

For a browser/frontend brief with signed-in humans and no explicit direct-token requirement, default to the supported BFF flow. Do not invent a proprietary bearer format merely because protected handlers accept an `Authorization` header.

### Browser/BFF acquisition

Use this concrete handoff:

```text
GET /app/login
  -> OidcClient.authorizationUrl(...) and browser redirect
  -> IDP login and callback code
  -> OidcClient.exchangeCode(...)
  -> TokenValidators.validate(...)
  -> application-user lookup/provisioning policy
  -> opaque HTTP-only application session cookie
  -> protected HTTP request and WebSocket opening handshake
```

The IDP client performs authorization-code-with-PKCE protocol mechanics. The application owns its backend session, cookie, subject-to-user mapping, and permissions. Keep ID and access tokens server-side for the BFF flow. Read the IDP BFF and local-authentication articles for the endpoint and local-stub details.

### Bearer resource-server acquisition

A bearer resource server accepts access tokens from a configured OAuth/OIDC issuer; it is not automatically an issuer. The client handoff must identify the issuer's supported acquisition flow, client registration, authorization entry point or token endpoint, resource audience, and required scopes. The application then validates the access token with `TokenValidationRequest.accessToken(...)` and `TokenValidators`, including expected token use when the issuer supplies or requires that claim, not an ad hoc user-ID-and-expiry HMAC format.

Do not expose a signing secret to a browser, add an ad hoc HMAC token endpoint, or make the domain application its own issuer merely to satisfy `Authorization: Bearer`. If no issuer/acquisition contract exists, use the BFF flow for a browser or report the missing external dependency; do not invent a bearer format.

## Provision the first application user without changing policy

Authentication proves an external subject; it does not guarantee that a matching application profile exists. Choose pre-provisioning, administrator invitation, or explicit self-registration according to the product rules.

“Provisioned out of band” is not an implementation. Name the deployment artifact or operator procedure, its subject-to-`UserId` mapping, how it is triggered, and how idempotency and auditability are verified. When only administrators may create users, the first-admin path must not turn ordinary first login into system-authorized self-provisioning.

A deliberate bootstrap may use system authority only as a separately configured infrastructure operation. It should accept a preapproved external subject, create the matching first admin at most once, expose no anonymous mint/create endpoint, and leave ordinary user creation on the admin-authorized path. The local IDP stub emits the login name as the subject, so local tests should pre-create or deliberately bootstrap the application user with that same ID.

## Clean-deployment acceptance sequence

Prove the selected lifecycle from a clean deployment with empty application state:

1. Execute the named first-admin pre-provisioning, invitation, or bootstrap step.
2. Complete the real login/acquisition flow without calling an internal token helper.
3. Reuse the returned cookie/token on a protected admin operation such as user creation.
4. Authenticate the created user through the same external subject mapping.
5. Reuse that credential on an actor-sensitive business action such as claiming, approving, archiving, or modifying an owned resource.
6. Assert persisted/query state that identifies the credential's subject as the domain actor.
7. Test unknown subject, missing/invalid/expired credential, logout or expiry, and role refresh as separate cases.
8. Repeat the credential on the WebSocket opening handshake when the product has protected live updates.

Deterministic tokens remain appropriate for a focused validator test. Calling the application's own internal `issue(...)` helper does not prove acquisition. Pair validator tests with the BFF/local-stub flow, the configured external-issuer contract, or an explicit network smoke test.

## Client handoff

The product-facing reference must state:

- the exact login URL or external issuer/acquisition entry point;
- required client registration, redirect URI, audience, and scopes without exposing secrets;
- the cookie name or bearer header used on HTTP and the WebSocket handshake;
- first-user/unknown-subject behavior;
- logout, expiry, reconnect, and role-refresh behavior;
- the OpenAPI URL and separate live-protocol reference.

If a clean client cannot acquire a credential and reach a protected business action by following that reference, the authentication lifecycle is incomplete.
