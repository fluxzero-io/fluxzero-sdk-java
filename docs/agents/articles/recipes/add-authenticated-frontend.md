Use this recipe when a product brief says a browser or API caller is signed in, authenticated, acts as itself, or opens a protected live connection. Finish the entire credential-to-domain path; authorization after a ready-made `Sender` is only one step.

## 1. Choose the external credential

Choose one production mechanism before writing protected endpoints:

| Client | Recommended boundary |
| --- | --- |
| Browser | BFF login with an opaque HTTP-only application session cookie |
| API client | Bearer token validated for signature, issuer, audience, expiry, and tenant |
| Both | Deliberate dual-mode provider with one authoritative validation path per credential |

Do not accept a user ID, role, raw cookie value, query parameter, or unverified bearer string as proof of identity. If a deployment contract says an upstream host supplies a trusted Fluxzero `$user`, document and test that contract explicitly; do not silently assume the proxy creates it.

Read the client credential and first-user lifecycle before implementing this choice. For a signed-in browser/frontend with no explicit direct-token requirement, default to the BFF flow. A bearer verifier is complete only when a configured external issuer gives the client a documented acquisition path. Do not invent an internal HMAC token format or expose a signing secret merely to make the header testable.

## 2. Establish and resolve the application user

Implement all applicable provider responsibilities:

1. `UserProvider.fromMessage(...)` consumes a real `WebRequest` credential or an already trusted Fluxzero user.
2. `getUserById(...)` loads the application profile after a subject is known.
3. `refreshUser(...)` reloads mutable roles or profile data when an already established user is refreshed for a message or session.
4. `getSystemUser()` remains limited to trusted internal work.

The production HTTP identity article contains the cookie/bearer provider example and validation boundary. A provider containing only `getUserById(...)` is a lookup fragment. It can pass every `...ByUser(userId, ...)` test while every external protected request still fails.

External authentication also does not automatically authorize creation of a domain user. Choose pre-provisioning, administrator invitation, or explicit self-registration according to the product rules. Do not send an admin-only create-user command as the system user merely because an authenticated subject is missing.

## 3. Protect the application surface

Put `@RequiresUser` on the protected API/package default and use role or ownership checks for domain permissions. Mark only deliberate public surfaces such as login, callback, health, public docs, or verified webhooks with `@NoUserRequired`.

Use the same credential path for the WebSocket opening handshake. Later frames reuse the established session identity; do not invent a second client-supplied actor field.

## 4. Publish the matching client contract

Generated OpenAPI must name the mechanism the application actually accepts:

- BFF cookie: an `apiKey` scheme with `in: cookie` and the exact configured cookie name.
- Bearer API: an HTTP bearer scheme and matching security requirement.

`@RequiresUser` does not generate this scheme. Keep generated OpenAPI authoritative for HTTP operations and add a small live-protocol section that states the WebSocket route, handshake cookie/header, initial snapshot, updates, errors, and reconnect behavior.

## 5. Prove each boundary separately

Keep these scenarios distinct:

| Scenario | Required input and observation |
| --- | --- |
| Credential success | Raw `whenWebRequest(...)` with the real cookie/header reaches an actor-sensitive protected business operation as the expected persisted user; `/me` alone is not enough |
| Missing/invalid/forged credential | Raw requests are rejected without fixture-injected `$user` |
| Lookup and refresh | A user ID resolves from application state and a role/profile change affects a later request |
| Authorization | Exact, inherited, adjacent-denied, ownership, and unauthenticated cases |
| WebSocket handshake | Raw authenticated open establishes the same user for later frames |
| Discovery | Served OpenAPI security requirement resolves to the matching scheme |

The `...ByUser` helpers remain useful for authorization after identity exists, but they do not replace the raw credential rows. Read HTTP authentication boundary testing for a deterministic fixture example and generated API discovery for structural security assertions.

## 6. Hand off a usable client path

Before finishing, make the README or API reference answer:

- where login starts or how an API obtains a bearer token;
- the exact cookie or header sent on protected HTTP calls;
- how the WebSocket handshake carries that same credential;
- logout/expiry/reconnect behavior;
- which OpenAPI/reference URL describes the protected operations.

Also name the first-user procedure. “Infrastructure bootstrap” or “send a signed token” without a concrete operator/client path is not a usable handoff. Prove the clean-deployment sequence from first-admin provisioning through credential acquisition and one actor-sensitive business action.

If the client must guess what “authenticated” means, the signed-in frontend path is incomplete.
