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

```java
// @formatter:off
String name = ApplicationProperties.getProperty("app.name", "DefaultApp");
boolean enabled = ApplicationProperties.getBooleanProperty("feature.toggle", true);
int maxItems = ApplicationProperties.getIntegerProperty("limit.items", 100);
// @formatter:on
```

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
| `ENCRYPTION_KEY`            | The key used for automatic decryption of `encrypted                | ...` values.                 | `null` |

---

<a name="client-configuration"></a>

## Client Configuration

The `Client` interface defines how the SDK interacts with the Fluxzero subsystems (Event Sourcing, Search, Scheduling,
etc.).

<a name="local-client"></a>

### In-Memory (LocalClient)

Used for local development and unit tests. All data is stored in-memory and lost on restart.

```java
// @formatter:off
Fluxzero fluxzero = DefaultFluxzero.builder()
    .build(LocalClient.newInstance());
// @formatter:on
```

<a name="websocket-client"></a>

### Connecting to Runtime (WebSocketClient)

Used for production and shared environments. It connects to a remote Fluxzero Runtime via WebSockets.

```java
// @formatter:off
var config = WebSocketClient.ClientConfig.builder()
    .runtimeBaseUrl("wss://flux.your-domain.com")
    .name("my-service")
    .namespace("production")
    .build();

Fluxzero fluxzero = DefaultFluxzero.builder()
    .build(WebSocketClient.newInstance(config));
// @formatter:on
```

---

<a name="spring-integration"></a>

## Spring Integration

Fluxzero provides seamless integration with Spring Boot.

### Setup

Annotate your configuration or application class:

```java
// @formatter:off
@SpringBootApplication
public class MyApplication {
}
// @formatter:on
```

### Auto-Configuration

When `FluxzeroSpringConfig` is imported:

- All `@Component` beans with `@Handle...` methods are automatically registered.
- `Fluxzero`, `CommandGateway`, `QueryGateway`, etc., are available for injection.
- Spring's `Environment` is automatically added as a property source.
- Upcasters and downcasters are auto-detected.

### Customization

Implement `FluxzeroCustomizer` to tune the `FluxzeroBuilder` before the instance is created:

```java
// @formatter:off
@Component
public class MyCustomizer implements FluxzeroCustomizer {
    @Override
    public FluxzeroBuilder customize(FluxzeroBuilder builder) {
        return builder.replaceCache(new MyCustomCache());
    }
}
// @formatter:on
```

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
