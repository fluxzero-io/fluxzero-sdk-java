Fluxzero apps usually run as Spring Boot applications on Java 21 or newer. If the project does not already have Fluxzero in the build, read project setup first and add the Maven/Gradle dependencies, BOM, annotation processing, and test runtime before writing domain code.

```java
@SpringBootApplication
@Import(FluxzeroSpringConfig.class)
public class MyApp {
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }
}
```

Recent SDKs can load `FluxzeroSpringConfig` through Spring Boot auto-configuration. Keep the explicit `@Import` only when auto-configuration is disabled or the app uses a custom Spring context.

Property precedence is:

1. Environment variables such as `FLUXZERO_BASE_URL`.
2. System properties such as `-Dfluxzero.base-url=...`.
3. Extra locations from `FLUXZERO_CONFIG_LOCATIONS` or `fluxzero.config.locations`.
4. `application-{environment}.properties` selected by `ENVIRONMENT`.
5. `application.properties`.
6. Fluxzero defaults from classpath `fluxzero.json` and `fluxzero.properties`.
7. Spring Environment as the final fallback when Spring Boot is active.

`FLUXZERO_CONFIG_LOCATIONS` accepts comma-separated `file:/path/to/config.properties`, plain file paths, and `classpath:config.properties` locations. Prefix a location with `optional:` when a missing file should be ignored; later listed locations override earlier ones.

Core SDK properties include `FLUXZERO_BASE_URL`, `FLUXZERO_APPLICATION_NAME`, `FLUXZERO_NAMESPACE`,
`FLUXZERO_APPLICATION_ID`, `FLUXZERO_TASK_ID`, `FLUXZERO_CLIENT_ID`, `ENCRYPTION_KEY`, and `fluxzero.defaults.version`.
The task ID identifies the platform task and supplies `$taskId` correlation metadata; the client ID uniquely identifies
a process/client instance. By default the latter contains a task-ID prefix and a UUID, or just a random UUID when
there is no task ID. Do not use task identity as an exact client-readiness identity.

Use `fluxzero.serialization.typeAliases` or `FLUXZERO_SERIALIZATION_TYPE_ALIASES` for compatible serialized
class/package renames. Configure the complete list through the usual property sources; read type aliases for ordering
with upcasters and programmatic overrides.

`FLUXZERO_NAMESPACE` is app-wide by default. It scopes runtime interactions across messaging, tracking, event store, documents, and search unless a specific operation overrides it.

Spring client selection prefers a user-provided `Client`, then a `WebSocketClient.ClientConfig`, then URL/name properties such as `FLUXZERO_BASE_URL` and `FLUXZERO_APPLICATION_NAME`; otherwise it falls back to the in-memory local client.

Spring auto-configuration registers annotated handlers, upcasters, downcasters, user providers, and other SDK integration beans in normal Spring Boot apps. Use a `FluxzeroCustomizer` when the app needs to adjust the default client/builder instead of forking setup code.

`DefaultFluxzero.builder()` is for infrastructure-level tuning: custom property resolvers, validator, consumer defaults, replay behavior, secondary behavior, correlation, host metrics, web request forwarding, and fetch byte limits. Keep these settings centralized near application bootstrap.

Use `application.properties`, environment variables, and runtime configuration for deploy-specific values. Access them through `ApplicationProperties.getProperty(...)`, its default-value overload, or `ApplicationProperties.requireProperty(...)`. The required accessor is `requireProperty`, not `require`. Read the focused property-access article for a typed configuration boundary and `TestFixture.withProperty(...)` scenarios.

Avoid reading environment variables deep inside domain code. Pass configuration into handlers or service boundaries where it can be tested.

For local development, use the Fluxzero dev server. It starts the local runtime and proxy, launches the production
Spring main class with the correct runtime ports/properties, and replaces it after source changes. Keep stable
environment choices in `.fluxzero/dev.yaml`; use the manual test-classpath runtime only as an explicit fallback.

When the app needs users, keep Fluxzero IDP settings under `fluxzero.auth.*` and use the authentication docs. Production deployments provide a real tenant, while local tests can import `FluxzeroIdpStub` and get test-support defaults.

The runtime exposes WebSocket endpoints for commands, queries, events, event sourcing, key-value, search, scheduling, and optional admin operations. Treat that as operational context; application code should use SDK APIs instead of building protocol clients by hand.

Runtime namespace defaults to `public` when no namespace is supplied and normalizes namespace values to lowercase. Use explicit namespaces only when the deployment model needs isolation.

Runtime health and readiness are different: `/health` means the process is up, while `/ready` includes availability/database readiness and should be used for deployment readiness checks.
