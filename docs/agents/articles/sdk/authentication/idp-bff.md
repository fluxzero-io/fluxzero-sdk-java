Use this to add browser login with Fluxzero IDP after choosing the client and first-user lifecycle. The app owns the BFF endpoints; Fluxzero IDP client helpers own OIDC URL creation, token exchange, token validation, and login-state encoding.

Add dependencies:

```xml
<dependency>
  <groupId>io.fluxzero.idp</groupId>
  <artifactId>client</artifactId>
  <version>${fluxzero-idp.version}</version>
</dependency>
```

Gradle equivalent:

```kotlin
implementation("io.fluxzero.idp:client:$fluxzeroIdpVersion")
```

Expose public BFF endpoints:

```java
@Component
@Path("/app")
@NoUserRequired
public class AppAuthEndpoint {
    @HandleGet("/login")
    WebResponse login(@QueryParam("returnTo") String returnTo) { ... }

    @HandleGet("/callback")
    WebResponse callback(WebRequest request,
                         @QueryParam("code") String code,
                         @QueryParam("state") String state,
                         @QueryParam("error") String error) { ... }

    @HandleGet("/logout")
    WebResponse logout(WebRequest request) { ... }

    @HandleGet("/auth/session")
    WebResponse session(WebRequest request) { ... }
}
```

The login endpoint should:

- Create `OidcLoginState` with a safe app-relative `returnTo`.
- Reject or normalize unsafe absolute `returnTo` values.
- Store encoded login state in an HTTP-only cookie scoped to `/app`.
- Redirect to `OidcClient.authorizationUrl(loginState)`.

The callback endpoint should:

- Reject missing `code` or `state`.
- Decode the login-state cookie with `OidcLoginStateCodec`.
- Verify that the returned state matches.
- Exchange the code through `OidcClient.exchangeCode`.
- Validate the ID token using `TokenValidators.validate(TokenValidationRequest.idToken(...))`.
- Map claims to a domain `Sender`.
- Create an opaque backend session and set an HTTP-only session cookie.

Keep tokens server-side. The browser should receive only an opaque session cookie and JSON from `/app/auth/session`.

Configuration keys:

```properties
fluxzero.auth.external-base-url=<public application base URL>
fluxzero.auth.oidc.issuer=<Fluxzero IDP tenant issuer URL>
fluxzero.auth.oidc.client-id=<Fluxzero IDP client ID>
fluxzero.auth.oidc.redirect-uri=<public application base URL>/app/callback
fluxzero.auth.oidc.resource-audience=<public application base URL>/api
fluxzero.auth.oidc.scope=openid profile email
fluxzero.auth.oidc.login-state-secret=<shared 32+ character random secret>
fluxzero.auth.oidc.token-endpoint-auth-method=private_key_jwt
fluxzero.auth.oidc.client-private-jwk=<RSA private JWK registered on the IDP client>
fluxzero.auth.oidc.token-endpoint-audience=<optional token endpoint audience>
```

For local development, do not hard-code local values in production properties. Use the local IDP stub from the local authentication page.

Proxy namespace selection is transport plumbing, not app authorization. Signed `Fluxzero-Namespace` handling can select a namespace from a verified JWT subject, but domain access still belongs in `SenderProvider`, security annotations, and `@AssertLegal`.
