Use this when protected HTTP or WebSocket operations must work for real signed-in clients. Credential establishment, application-user lookup, role/ownership enforcement, and API discovery are separate proof obligations.

| Test input | What it proves | What it bypasses |
| --- | --- | --- |
| `whenGetByUser(sender, ...)` | Routing and authorization for that ready-made user | Credential extraction and `getUserById(...)` |
| `whenGetByUser(userId, ...)` | User lookup plus routing/authorization | Cookie or bearer extraction and validation |
| Raw `whenWebRequest(...)` with real cookie/header | `fromMessage(...)`, route, lookup, propagation, and authorization | Nothing at the HTTP identity boundary only when the exact provider participates and the fixture system fallback is neutralized |
| Raw missing/invalid credential | Anonymous/invalid rejection through the production provider or proxy | Do not let the default fixture system fallback mask this row |

The `...ByUser` helpers add `$user` to message metadata. They are convenient authorization tests, but they do not exercise the credential a browser or API client sends.

## Test a raw bearer or cookie

Use deterministic validators, not a live IDP, for focused tests. Build the same raw metadata the proxy forwards and do not call a `...ByUser` helper. Make the representative success operation actor-sensitive; a request to `/me` proves lookup but not that the credential becomes the actor in domain behavior.

A normal `TestFixture` wraps a builder-registered provider in `TestUserProvider`. That wrapper can add the provider's system user to a raw request before `fromMessage(...)` reads the cookie/header. Register a test-only delegating wrapper that preserves the production provider's `fromMessage(...)`, lookup, and refresh behavior, keeps the current handler user available for nested dispatch, and disables only the system fallback:

```java
var exactBoundaryProvider = new DelegatingUserProvider(productionLikeProvider) {
    @Override public User getActiveUser() { return User.getCurrent(); }
    @Override public User getSystemUser() { return null; }
};

var validRequest = WebRequest.post("/api/orders/order-1/claim")
        .header("Authorization", "Bearer valid-member-token")
        .build();

TestFixture.create(
                DefaultFluxzero.builder()
                        .registerUserProvider(exactBoundaryProvider),
                OrderEndpoint.class)
        .givenCommandsByUser(owner, createMember, createOrder)
        .whenWebRequest(validRequest)
        .expectWebResult(response -> response.getStatus() == 200
                && viewerId.equals(
                        response.<OrderView>getPayloadAs(OrderView.class).claimedBy()));
```

The success row should cross raw request → endpoint → protected command/query → persisted or queried actor. Returning `User.getCurrent()` above is what lets nested dispatch inherit the user authenticated for the web handler; returning `null` would lose that actor. Use that same `exactBoundaryProvider` setup for the missing, malformed, expired, wrong-issuer, wrong-audience, and unknown-subject raw requests. Assert that the deterministic authenticator was invoked, the request is rejected, and no business state changed. Never make `Bearer <user-id>` itself a valid token format.

For a BFF, drive login redirect, stub login, callback validation, response cookie, then a raw request to an actor-sensitive protected business operation with that cookie. Assert actor-owned persisted or queried state so identity propagation is observable. Testing only `/app/auth/session` or `/me` is insufficient. Read the client credential and first-user lifecycle for the clean-deployment sequence; internally constructing the token used by this focused validator test does not prove acquisition.

Do not call `withProductionUserProvider()` on a fixture with a builder-only provider and then claim that provider handled the request. The switch installs process-wide `UserProvider.defaultUserProvider` and discards the builder-only provider. It is valid only when the intended provider is installed through the process-wide SPI. Otherwise use the no-system delegating wrapper above or run a local proxy smoke request. Record and, where useful, count which authenticator actually participates in every valid and invalid row.

Repeat the credential on a WebSocket open request and prove the established session acts as the same user on later frames. Also parse the served OpenAPI and assert that its security requirement points to a matching cookie or bearer scheme. `@RequiresUser`, a successful `when...ByUser` test, and a named OpenAPI scheme cannot substitute for the raw credential scenario.
