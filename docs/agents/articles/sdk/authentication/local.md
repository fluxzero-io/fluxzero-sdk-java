Use this when the app should support login locally without a real cloud tenant. Local auth should exercise the same app endpoints as production: `/app/login`, `/app/callback`, `/app/logout`, and `/app/auth/session`.

Add test dependency:

```xml
<dependency>
  <groupId>io.fluxzero.idp</groupId>
  <artifactId>test-support</artifactId>
  <version>${fluxzero-idp.version}</version>
  <scope>test</scope>
</dependency>
```

Gradle equivalent:

```kotlin
testImplementation("io.fluxzero.idp:test-support:$fluxzeroIdpVersion")
```

The test-support dependency remains useful for focused `TestFixture` authentication tests. Add
`src/test/resources/application.properties` only when those tests must override its defaults.

For the running application, the Fluxzero dev server starts and registers `FluxzeroIdpStub` as a managed service and
injects the matching `fluxzero.auth.*` properties into each app instance. Start or reuse the environment:

```bash
fz dev
```

An installed coding-agent plugin reaches that same environment through `fz mcp` and its explicit `start_dev` tool. Do not import the
stub into a test-classpath `TestApp` or start `TestServer`/`ProxyServer` beside it. Set `idp: external` in
`.fluxzero/dev.yaml` only when the application must use separately supplied tenant configuration. The manual runtime
article describes the exceptional test-classpath fallback.

Then open:

```text
http://localhost:8080/app/login?returnTo=/app/auth/session
```

Expected flow:

1. `/app/login` redirects to the local stub IDP.
2. The local IDP login form redirects to `/app/callback`.
3. `/app/callback` creates an app session cookie.
4. `/app/auth/session` returns JSON with `authenticated: true`.
5. A raw request carrying that cookie reaches one actor-sensitive protected business operation and acts as the same user; `/app/auth/session` or `/me` alone is not enough.

Recommended test shape:

- Use `TestFixture.create(DefaultFluxzero.builder().registerUserProvider(new BrowserSessionSenderProvider()), AppAuthEndpoint.class, FluxzeroIdpStub.class)`.
- Define `BrowserSessionSenderProvider extends SenderProvider` in the test and override `getSystemUser()` to return `null`, matching the starter example, so browser requests without a session are not silently treated as the system user.
- Follow redirects through login, local IDP form post, callback, and session endpoint.
- Assert that `/app/auth/session` contains the expected subject.
- Reuse the returned cookie on a protected API request without a `...ByUser` helper, and assert actor-owned state or a user-specific query result.
- Reset `AppSessionStore`, `FluxzeroIdpStub`, and active fixtures between tests.

`TestFixture` wraps user providers with a test provider by default, and that wrapper falls back to `getSystemUser()` when no active user exists. For unauthenticated browser-flow assertions, either use the browser-session test subclass above or call `withProductionUserProvider()` only when the intended provider is installed as process-wide `UserProvider.defaultUserProvider`. That switch does not preserve an arbitrary provider registered only on the fixture builder.

Keep local browser login separate from CLI or Maven-plugin login. CLI tooling uses its own OAuth/PKCE token store path; app tests should prove the BFF endpoints, production HTTP identity mapping, and one actor-sensitive protected business operation. A successful `/app/auth/session` response alone does not prove protected requests become a `Sender`.
