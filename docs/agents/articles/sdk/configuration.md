# Configuration

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

[//]: # (@formatter:off)
```java
String name = ApplicationProperties.getProperty("app.name", "DefaultApp");
boolean enabled = ApplicationProperties.getBooleanProperty("feature.toggle", true);
int maxItems = ApplicationProperties.getIntegerProperty("limit.items", 100);
```
[//]: # (@formatter:on)

---

<a name="serialization-type-aliases"></a>

## Core properties

The following properties are used by the SDK to configure its connection and behavior.

| Property                    | Description                                                        | Default                      |
|:----------------------------|:-------------------------------------------------------------------|:-----------------------------|
| `FLUXZERO_BASE_URL`         | Base URL of the Fluxzero Runtime (e.g., `wss://flux.example.com`). | `null` (falls back to local) |
| `FLUXZERO_APPLICATION_NAME` | The logical name of your application.                              | `inMemory` (local)           |
| `FLUXZERO_NAMESPACE`        | The project or tenant namespace.                                   | `public`                    |
| `FLUXZERO_APPLICATION_ID`   | A unique identifier for the application deployment.                | `null`                       |
| `FLUXZERO_TASK_ID`          | Platform task/pod ID; published as authoritative `$taskId` correlation metadata and used as the generated client-ID prefix. | `null` |
| `FLUXZERO_CLIENT_ID`        | Optional explicitly unique client/process instance ID.            | Task-ID-prefixed UUID or random UUID |
| `ENCRYPTION_KEY`            | The key used for automatic decryption of `encrypted` values.       | `null`                       |

`FLUXZERO_NAMESPACE` sets the app-wide default namespace (not just one consumer). It applies to runtime interactions
across messaging/tracking/event store/documents/search unless explicitly overridden on a specific operation/consumer.

---

<a name="client-configuration"></a>
