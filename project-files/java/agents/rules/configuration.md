# Configuration

Setting up and tuning your Fluxzero application is straightforward. Most configuration is handled automatically, but
you can fine-tune your application using properties, environment variables, or programmatic builders.

---

## Quick Navigation

- [Property Resolution](#property-resolution)
- [Core Properties](#core-properties)
- [Client Configuration](#client-configuration)
    - [In-Memory (LocalClient)](#local-client)
    - [Connecting to Runtime (WebSocketClient)](#websocket-client)
- [Spring Integration](#spring-integration)
- [Security & Encryption](#security-encryption)

---

<a name="property-resolution"></a>

## Property Resolution

Fluxzero provides a layered configuration system via `ApplicationProperties`. Properties are resolved in the following
order of precedence:

1. **Environment Variables**: e.g., `export FLUXZERO_BASE_URL=...`
2. **System Properties**: e.g., `-Dfluxzero.base-url=...`
3. **Environment-Specific Properties**: `application-{environment}.properties` (set `ENVIRONMENT` variable)
4. **Base Properties**: `application.properties`
5. **Spring Environment**: (If Spring is active)

### Typed Access

You can access properties in your code using the static `ApplicationProperties` utility:

[//]: # (@formatter:off)
```java
String name = ApplicationProperties.getProperty("app.name", "DefaultApp");
boolean enabled = ApplicationProperties.getBooleanProperty("feature.toggle", true);
int maxItems = ApplicationProperties.getIntegerProperty("limit.items", 100);
```
[//]: # (@formatter:on)

---

<a name="core-properties"></a>

## Core Properties

The following properties are used by the SDK to configure its connection and behavior.

| Property                    | Description                                                        | Default                      |
|:----------------------------|:-------------------------------------------------------------------|:-----------------------------|
| `FLUXZERO_BASE_URL`         | Base URL of the Fluxzero Runtime (e.g., `wss://flux.example.com`). | `null` (falls back to local) |
| `FLUXZERO_APPLICATION_NAME` | The logical name of your application.                              | `inMemory` (local)           |
| `FLUXZERO_NAMESPACE`        | The project or tenant namespace.                                   | `default`                    |
| `FLUXZERO_APPLICATION_ID`   | A unique identifier for the application deployment.                | `null`                       |
| `FLUXZERO_TASK_ID`          | A unique ID for the specific client instance (for tracking).       | Random UUID                  |
| `ENCRYPTION_KEY`            | The key used for automatic decryption of `encrypted` values.       | `null`                       |

`FLUXZERO_NAMESPACE` sets the app-wide default namespace (not just one consumer). It applies to runtime interactions
across messaging/tracking/event store/documents/search unless explicitly overridden on a specific operation/consumer.

---

<a name="client-configuration"></a>

## Client Configuration

The `Client` interface defines how the SDK interacts with the Fluxzero subsystems (Event Sourcing, Search, Scheduling,
etc.).

<a name="local-client"></a>

### In-Memory (LocalClient)

Used for local development and unit tests. All data is stored in-memory and lost on restart.

[//]: # (@formatter:off)
```java
Fluxzero fluxzero = DefaultFluxzero.builder()
    .build(LocalClient.newInstance());
```
[//]: # (@formatter:on)

<a name="websocket-client"></a>

### Connecting to Runtime (WebSocketClient)

Used for production and shared environments. It connects to a remote Fluxzero Runtime via WebSockets.

[//]: # (@formatter:off)
```java
var config = WebSocketClient.ClientConfig.builder()
    .runtimeBaseUrl("wss://flux.your-domain.com")
    .name("my-service")
    .namespace("production")
    .build();

Fluxzero fluxzero = DefaultFluxzero.builder()
    .build(WebSocketClient.newInstance(config));
```
[//]: # (@formatter:on)

<a name="advanced-builder-patterns"></a>

### Advanced Builder Patterns

Use these only when default behavior is not sufficient:

- **Predicate-based consumer grouping**: configure extra consumers via builder rules to group handlers by fitness
  predicates.
- **Custom parameter injection**: register custom `ParameterResolver`s via `.addParameterResolver(...)` for contextual
  handler arguments.
- **Selective runtime toggles**: use targeted toggles (for example metrics/correlation/protection toggles) only when you
  have an explicit operational reason.

Typical patterns:

1. **Replay + Live split**: Add an additional consumer configuration for replay handlers while the primary consumer keeps
   processing live traffic.
2. **Domain grouping by predicate**: Route a subset of handlers into a dedicated consumer (for example billing-heavy
   handlers) for independent scaling/tuning.
3. **Context injection**: Use `addParameterResolver(...)` when standard payload/metadata/sender/entity injection is not
   enough.

```java
FluxzeroBuilder builder = DefaultFluxzero.builder()
    .addParameterResolver(new CustomResolver());
```

Example patterns:

```java
FluxzeroBuilder builder = DefaultFluxzero.builder()
    // 1) Tune the default command consumer
    .configureDefaultConsumer(MessageType.COMMAND, c -> c.toBuilder()
        .name("commands-default")
        .threads(4)
        .build())
    // 2) Add a dedicated replay/secondary consumer for selected handlers
    .addConsumerConfiguration(ConsumerConfiguration.builder()
        .name("replay-billing")
        .handlerFilter(h -> h.getClass().getSimpleName().contains("Billing"))
        .exclusive(false)
        .build(), MessageType.COMMAND)
    // 3) Extend correlation metadata behavior
    .replaceCorrelationDataProvider(existing -> existing.andThen((client, msg, type) -> Map.of("tenant", "acme")))
    // 4) Enable host metrics (or disable tracking metrics explicitly when needed)
    .enableHostMetrics()
    // 5) Compatibility bridge: forward Fluxzero web requests to an existing local HTTP server
    //    (for example Spring Web), typically when migrating or using unsupported web features
    .forwardWebRequestsToLocalServer(8080);
```

`forwardWebRequestsToLocalServer(...)` is an advanced compatibility path and is rarely needed in Fluxzero-first
applications.

Use advanced toggles conservatively:

- Disabling correlation/metrics/protection can affect diagnostics, observability, or security assumptions.
- Prefer default behavior unless the user explicitly asks for a different operational profile.

---

<a name="spring-integration"></a>

## Spring Integration

Fluxzero provides seamless integration with Spring Boot.

### Setup

Annotate your configuration or application class:

[//]: # (@formatter:off)
```java
@SpringBootApplication
public class MyApplication {
}
```
[//]: # (@formatter:on)

### Auto-Configuration

When `FluxzeroSpringConfig` is imported:

- All `@Component` beans with `@Handle...` methods are automatically registered.
- `Fluxzero`, `CommandGateway`, `QueryGateway`, etc., are available for injection.
- Spring's `Environment` is automatically added as a property source.
- Upcasters and downcasters are auto-detected.

### Customization

Implement `FluxzeroCustomizer` to tune the `FluxzeroBuilder` before the instance is created:

[//]: # (@formatter:off)
```java
@Component
public class MyCustomizer implements FluxzeroCustomizer {
    @Override
    public FluxzeroBuilder customize(FluxzeroBuilder builder) {
        return builder.replaceCache(new MyCustomCache());
    }
}
```
[//]: # (@formatter:on)

---

<a name="security-encryption"></a>

## Security & Encryption

Fluxzero supports transparent decryption of sensitive properties.

### 1. Set the Encryption Key

Set the `ENCRYPTION_KEY` environment variable. You can generate a new key using:
`DefaultEncryption.generateNewEncryptionKey()`.

### 2. Use Encrypted Values

Add encrypted values to your `application.properties` using `ApplicationProperties.encryptValue("secret-api-key")` to get a
ciphertext:

```properties
google.apikey=encrypted|ChaCha20|mm8yeY8TXtNpdrwO:REdej56zvFXc:b7oQdmnpQpUzagKtma9JLQ==
```

### 3. Usage

Access them normally; decryption is automatic and transparent:

```java
String apiKey = ApplicationProperties.getProperty("google.apikey");
```
