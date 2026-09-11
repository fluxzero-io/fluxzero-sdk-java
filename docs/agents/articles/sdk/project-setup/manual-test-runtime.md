Use this only when the Fluxzero dev server is unavailable and an explicitly manual test-classpath runtime is required.
Do not run it beside an active `fz dev` environment.

The manual stack uses:

- `TestServer` for an in-memory Fluxzero runtime. Its port is chosen from `FLUXZERO_PORT`, `FLUX_PORT`, `port`, then
  default `8888`.
- `ProxyServer` for HTTP. Its port is chosen from `FLUXZERO_PROXY_PORT`, `PROXY_PORT`, then default `8080`.
- `FLUXZERO_BASE_URL`, `FLUX_BASE_URL`, or `FLUX_URL` to connect the proxy/runtime.
- Spring profile `main`, `FLUX_BASE_URL=ws://localhost:8888`, and a stable `FLUX_APPLICATION_NAME`.
- `FluxzeroIdpStub` in the test app only when local authenticated login is required.

Keep this boot code under `src/test`, never in the production application:

```java
@SpringBootApplication
public class TestApp {
    public static void main(String[] args) {
        System.setProperty("FLUX_PORT", "8888");
        System.setProperty("FLUX_BASE_URL", "ws://localhost:8888");
        TestServer.startServer();

        System.setProperty("PROXY_PORT", "8080");
        ProxyServer.start();

        System.setProperty("FLUX_APPLICATION_NAME", "LocalApp");
        SpringApplication app = new SpringApplication(TestApp.class);
        app.setAdditionalProfiles("main");
        app.run(args);
    }
}
```

The Kotlin shape is equivalent. A Gradle `JavaExec` task or Maven `exec-maven-plugin` can run the test-classpath main.
If a port is occupied, reuse the intended stack or select different runtime/proxy ports. Use `TestFixture` for ordinary
behavior tests; this stack is for real-network or browser smoke checks.

Database, runtime-service, and container-platform administration remain outside this application fallback. Prefer the
dev-server-owned environment or an explicitly supplied managed environment rather than reconstructing platform internals.
