# Configuration

Setting up and tuning your Fluxzero application is straightforward. Most configuration is handled automatically, but
you can fine-tune your application using properties, environment variables, or programmatic builders.

A builder initializes its default task scheduler only on `taskScheduler()` access or `build(...)`.
Installing `replaceTaskScheduler(...)` first avoids starting an unused default scheduler; the built Fluxzero instance
shuts down the selected scheduler when it closes.

This manual covers application and SDK configuration. For local environment orchestration and `.fluxzero/dev.yaml`,
use [Local Development](development.md) and obtain the current schema with `fz dev config`.

---

## Quick Navigation

- [Property Resolution](#property-resolution)
- [Serialization Type Aliases](#serialization-type-aliases)
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
3. **Additional Config Locations**: files configured with `FLUXZERO_CONFIG_LOCATIONS`
4. **Environment-Specific Properties**: `application-{environment}.properties` (set `ENVIRONMENT` variable)
5. **Base Properties**: `application.properties`
6. **Fluxzero SDK Defaults**: `fluxzero.properties` or `fluxzero.json`
7. **Spring Environment**: (If Spring is active)

All classpath `application.properties` resources are merged. Put shared defaults in one common module and let dependent
executables inherit them; do not copy the same key into every executable. If different modules define conflicting
values for one key, resolution depends on class-loader order and Fluxzero logs a warning. Use a higher-priority source
for intentional overrides. Always resolve feature configuration through `ApplicationProperties` or, at a builder or
configuration boundary, the component's configured `PropertySource`; never read environment variables, system
properties, or files directly and never introduce a feature-specific property utility.
Custom uber-JAR packaging that collapses equal resource names must merge overlapping `application.properties` files
itself; Spring integration cannot recover a resource removed during packaging.

### Typed Access

You can access properties in your code using the static `ApplicationProperties` utility:

```kotlin
val name: String = ApplicationProperties.getProperty("app.name", "DefaultApp")
val enabled: Boolean = ApplicationProperties.getBooleanProperty("feature.toggle", true)
val maxItems: Int = ApplicationProperties.getIntegerProperty("limit.items", 100)
```

---

<a name="serialization-type-aliases"></a>

## Serialization Type Aliases

Prefer application or deployment configuration for serialized class and package renames. Put all aliases in one
property value:

```properties
fluxzero.serialization.typeAliases=host.example.LegacyCommand=io.example.CurrentCommand,host.example.events.*=io.example.events.*
```

Or use the conventional environment variable:

```bash
export FLUXZERO_SERIALIZATION_TYPE_ALIASES='host.example.LegacyCommand=io.example.CurrentCommand,host.example.events.*=io.example.events.*'
```

`ApplicationProperties` also accepts the compact `FLUXZERO_SERIALIZATION_TYPEALIASES` spelling. The selected property
source supplies the complete comma-, semicolon-, or newline-separated list; package aliases use `.*` on both sides.
Use builder methods only for aliases intentionally owned by application code or tests. See
[Serialization](serialization.md#type-aliases) for precedence, upcaster ordering, and fixture behavior.

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
| `FLUXZERO_TASK_ID`          | Platform task/pod ID; published as authoritative `$taskId` correlation metadata and used as the generated client-ID prefix. | `null` |
| `FLUXZERO_CLIENT_ID`        | Optional explicitly unique client/process instance ID.            | Task-ID-prefixed UUID or random UUID |
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

```kotlin
val fluxzero = DefaultFluxzero.builder()
    .build(LocalClient.newInstance())
```

<a name="websocket-client"></a>

### Connecting to Runtime (WebSocketClient)

Used for production and shared environments. It connects to a remote Fluxzero Runtime via WebSockets.

The SDK uses ZSTD for default WebSocket compression and document serialization and requires a ZSTD-capable Runtime.
Document and legacy message encoding uses bounds-checked Java array operations. Existing MessagePack storage
and transport representations remain unchanged; applications need no migration or JVM-wide MessagePack property.
The upstream MessagePack library is used only as a test reference and is no longer a transitive runtime dependency.
Applications that directly use `org.msgpack` classes must declare `org.msgpack:msgpack-core` explicitly.

The SDK also reads historical LZ4 documents using bounds-checked Java compression and decompression, without Unsafe or
native LZ4. SDK connections advertise `Fluxzero-Supported-Document-Compression: ZSTD,LZ4,NONE`, independently of
outer WebSocket compression. A Runtime supporting this header preserves stored document bytes whenever the client
supports their codec and converts only unsupported formats. No bulk storage migration is needed. Explicit LZ4,
GZIP and NONE WebSocket configurations remain available. These compression defaults are not gated by
`fluxzero.defaults.version`.

```kotlin
val config = WebSocketClient.ClientConfig.builder()
    .runtimeBaseUrl("wss://flux.your-domain.com")
    .name("my-service")
    .namespace("production")
    .build()

val fluxzero = DefaultFluxzero.builder()
    .build(WebSocketClient.newInstance(config))
```

Compatibility mode retries failed WebSocket connections every second. Enable capped exponential equal-jitter retry
with `fluxzero.websocket.reconnectBackoff.enabled=true` or `fluxzero.defaults.version >= 2026.09.09`; use the explicit
property with `false` to retain fixed retries. The environment-variable form is
`FLUXZERO_WEBSOCKET_RECONNECT_BACKOFF_ENABLED`. Transport diagnostics are single-flight per client on a dedicated,
timeboxed worker, so metric failure cannot build a queue on result-completion workers.

<a name="advanced-builder-patterns"></a>

### Advanced Builder Patterns

Use these only when default behavior is not sufficient:

- **Predicate-based consumer grouping**: configure extra consumers via builder rules to group handlers by fitness
  predicates.
- **Custom parameter injection**: register custom `ParameterResolver`s via `.addParameterResolver(...)` for contextual
  handler arguments.
- **Custom validation**: replace the configured validator via `.replaceValidator(...)`; `ValidationUtils` convenience
  methods use the validator from the active `Fluxzero` instance and fall back to the SDK default outside that context.
- **Selective runtime toggles**: use targeted toggles (for example metrics/correlation/protection toggles) only when you
  have an explicit operational reason.

Typical patterns:

1. **Replay + Live split**: Add an additional consumer configuration for replay handlers while the primary consumer keeps
   processing live traffic.
2. **Domain grouping by predicate**: Route a subset of handlers into a dedicated consumer (for example billing-heavy
   handlers) for independent scaling/tuning.
3. **Context injection**: Use `addParameterResolver(...)` when standard payload/metadata/sender/entity injection is not
   enough for handler methods.

```kotlin
val builder = DefaultFluxzero.builder()
    .addParameterResolver(CustomResolver())
```

Example patterns:

```kotlin
val builder = DefaultFluxzero.builder()
    // 1) Tune the default command consumer/template
    .configureDefaultConsumer(MessageType.COMMAND) { c ->
        c.toBuilder()
            .name("commands-default")
            .threads(4)
            .build()
    }
    // 2) Add a dedicated replay/secondary consumer for selected handlers
    .addConsumerConfiguration(
        ConsumerConfiguration.builder()
            .name("replay-billing")
            .handlerFilter { h -> h::class.java.simpleName.contains("Billing") }
            .exclusive(false)
            .build(),
        MessageType.COMMAND
    )
    // 3) Extend correlation metadata behavior
    .replaceCorrelationDataProvider { existing ->
        existing.andThen { _, _, _ -> mapOf("tenant" to "acme") }
    }
    // 4) Enable host metrics (or disable tracking metrics explicitly when needed)
    .enableHostMetrics()
    // 5) Compatibility bridge: forward Fluxzero web requests to an existing local HTTP server
    //    (for example Spring Web), typically when migrating or using unsupported web features
    .forwardWebRequestsToLocalServer(8080)
```

`forwardWebRequestsToLocalServer(...)` is an advanced compatibility path and is rarely needed in Fluxzero-first
applications.

Use advanced toggles conservatively:

- Disabling correlation/metrics/protection can affect diagnostics, observability, or security assumptions.
- Prefer default behavior unless the user explicitly asks for a different operational profile.
- `fluxzero.tracking.maxFetchBytes` changes the default serialized payload byte limit per consumer fetch. Use bytes,
  for example `104857600` for 100 MiB; omit a consumer's `maxFetchBytes` or set it to `-1` to inherit that default,
  and set it to `0` only when an unbounded fetch is intentional.
- `fluxzero.eventsourcing.maxFetchBytes` bounds serialized event payload per aggregate-history page. Compatibility mode
  is count-only; `fluxzero.defaults.version >= 2026.09.10` selects 100 MiB. Use `0` to retain count-only pages. One
  oversized event is still returned so aggregate loading cannot stall. Older Runtimes ignore the optional byte limit.

---

<a name="spring-integration"></a>

## Spring Integration

Fluxzero provides seamless integration with Spring Boot.

### Setup

Annotate your configuration or application class:

```kotlin
@SpringBootApplication
class MyApplication
```

### Auto-Configuration

When `FluxzeroSpringConfig` is imported:

- All `@Component` beans with `@Handle...` methods are automatically registered.
- `Fluxzero`, `CommandGateway`, `QueryGateway`, etc., are available for injection.
- Spring's `Environment` is automatically added as a property source.
- Upcasters and downcasters are auto-detected.

### Customization

Implement `FluxzeroCustomizer` to tune the `FluxzeroBuilder` before the instance is created:

```kotlin
@Component
class MyCustomizer : FluxzeroCustomizer {
    override fun customize(builder: FluxzeroBuilder): FluxzeroBuilder {
        return builder.replaceCache(MyCustomCache())
    }
}
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

Access them normally; decryption is automatic:

```kotlin
val apiKey: String = ApplicationProperties.getProperty("google.apikey")
```

## Proxy response header buffers

The standalone and embedded SDK proxy accept `fluxzero.proxy.responseHeaderBufferSize`
(environment variable `FLUXZERO_PROXY_RESPONSE_HEADER_BUFFER_SIZE`), an initial response **header**
buffer capacity in bytes. For example, `FLUXZERO_PROXY_RESPONSE_HEADER_BUFFER_SIZE=8192` starts
with 8 KiB. Larger headers use Jetty's existing overflow path to the effective maximum; large
response bodies do not require a larger header buffer. Frequent large headers can therefore make
an undersized initial buffer more expensive through extra allocation and header generation.

Jetty [#15840](https://github.com/jetty/jetty.project/issues/15840) tracks a known HTTP/1.1
limitation during header growth: a previously determined connection-close decision can be lost.
The proxy preserves explicit request `Connection: close`; other Jetty close decisions require the
upstream fix. Setting the initial capacity to the effective response maximum avoids this growth
path, at the cost of larger buffers for small responses. HTTP/2 does not use this retry path.

Without this property the initial capacity is 8192 bytes. `FLUXZERO_PROXY_MAX_HEADER_SIZE`
continues to default to 1048576 bytes, so the existing request and response limits remain
unchanged. With the current Jetty configuration, a maximum configured below 16 KiB still retains
Jetty's 16 KiB response ceiling, while the request limit follows the configured value. An explicit
initial capacity must be positive and no greater than the effective response ceiling.

Jetty uses direct HTTP output buffers by default. Set
`fluxzero.proxy.useOutputDirectByteBuffers=false`
(`FLUXZERO_PROXY_USE_OUTPUT_DIRECT_BYTE_BUFFERS=false`) to allocate its HTTP/1.1 and HTTP/2 output
buffers on the Java heap. This setting applies to all proxy HTTP output, not only response headers
or health endpoints. Heap buffers share the configured heap budget and can increase copying and
garbage collection; direct buffers use a separate native-memory budget. Benchmark the complete
proxy workload when changing it.

Both settings are resolved once at proxy startup through `ApplicationProperties`; restart after
changing them.

The built-in `/proxy/health` and `/proxy/ready` responses already have tiny fixed bodies and use
the same HTTP response generator as application responses. These options do not change their
payloads or readiness semantics and do not define the proxy's overall process-memory limit. The
same environment settings apply to Java and Kotlin applications.

### Packed Model substeps

The SDK automatically advertises its supported packed Model membership versions during the WebSocket handshake.
A supporting Runtime can use lossless v8 for non-zero substeps; older peers retain the existing representation.
This is protocol capability negotiation, independent of application properties and `fluxzero.defaults.version`.

A supporting Runtime compacts full memberships only for capable receiving sessions. Old clients retain the lossless
fallback, and new clients continue to read old replies. No stored data changes or migration are involved. Updating
only the SDK cannot repair an old Runtime that omits substeps. Zero-only responses and Graph-embedded event pages
retain their existing representation. Request count and payload-byte limits retain their existing meaning; metadata
still contributes to total response size.

## Tracking shutdown

When tracking closes, incomplete chunked payloads fail so their handlers cannot remain blocked waiting for missing
input. A fully received body remains readable, and ordinary asynchronous handler results retain their shutdown grace.

### Publication delivery default

Configure `fluxzero.publishing.defaultGuarantee` (`FLUXZERO_PUBLISHING_DEFAULT_GUARANTEE`) before building the
application: `NONE`, `SENT`, or `STORED`. Without this override, `fluxzero.defaults.version >= 2026.09.25` selects
`STORED`; older or absent versions keep `NONE`. This governs `Guarantee.DEFAULT` in publication/send-and-forget
APIs. It does not change explicit concrete guarantees or operation-specific persistence/telemetry defaults.
See the sending rules for consumer-position and asynchronous completion boundaries.
