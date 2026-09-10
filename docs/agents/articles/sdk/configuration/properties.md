Use `ApplicationProperties` at the application configuration boundary for deploy-specific URLs, timeouts, feature switches, and credentials. Import the SDK class from exactly this package:

```java
import io.fluxzero.sdk.configuration.ApplicationProperties;
```

The supported symbol is `io.fluxzero.sdk.configuration.ApplicationProperties`. Reject the observed wrong imports `io.fluxzero.common.ApplicationProperties` and `io.fluxzero.common.application.ApplicationProperties`, as well as other guessed SDK, Spring, or application-local lookalikes; they are not substitutes for the SDK property source used by `TestFixture.withProperty(...)`.

Resolve raw strings into a small typed settings object, then inject that object into handlers or gateways. Do not read environment variables or application properties from `@Apply` methods or throughout domain code. For processor integrations, also require an absolute HTTP(S) URI before building a one-way outbound request.

## Choose the accessor deliberately

```java
String optionalLabel = ApplicationProperties.getProperty("processor.label");
String timeout = ApplicationProperties.getProperty("processor.timeout", "PT5S");
String captionUrl = ApplicationProperties.requireProperty("processor.caption.base-url");
```

- `getProperty(name)` returns `null` when the property is absent.
- `getProperty(name, defaultValue)` uses the default only when the property is absent.
- `requireProperty(name)` throws `IllegalStateException` when the property is absent.
- The required accessor is `requireProperty`, not `require`.

An empty string is present: neither the default-value overload nor `requireProperty` rejects it. Validate non-blank text and parse URLs, durations, and numbers at the configuration boundary so startup fails with a useful error. Typed helpers such as `getBooleanProperty(...)` and `getIntegerProperty(...)` are available when their conversion rules match the contract.

Property-style keys also map to conventional environment-variable names. For example, `processor.caption.base-url` can be supplied as `PROCESSOR_CAPTION_BASE_URL`; the normal precedence rules from the configuration overview still apply.

## Inject typed settings

Keep parsing in one place:

```java
public record ProcessorSettings(
        URI captionBaseUrl,
        URI artworkBaseUrl,
        Duration timeout) {

    public static ProcessorSettings load() {
        return new ProcessorSettings(
                requiredUri("processor.caption.base-url"),
                requiredUri("processor.artwork.base-url"),
                Duration.parse(ApplicationProperties.getProperty(
                        "processor.timeout", "PT5S")));
    }

    private static URI requiredUri(String name) {
        String value = ApplicationProperties.requireProperty(name);
        if (value.isBlank()) {
            throw new IllegalStateException("Property for %s is blank".formatted(name));
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Property for %s is not a valid URI".formatted(name), e);
        }
        String scheme = uri.getScheme();
        boolean http = "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme);
        if (!uri.isAbsolute() || uri.isOpaque() || !http
                || uri.getRawAuthority() == null
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException(
                    ("Property for %s must be an absolute HTTP(S) base URL "
                    + "with a host and without a query or fragment").formatted(name));
        }
        return uri;
    }
}
```

This processor setting deliberately accepts a base path such as `https://processor.test/api`, but rejects relative,
opaque, or hostless values and rejects query/fragment suffixes because operation paths are appended later. If a
different setting represents a complete request URI, define and test that separate contract rather than weakening this
base-URL parser.

Create this value near bootstrap and use normal constructor injection:

```java
@Configuration
class ProcessorConfiguration {
    @Bean
    ProcessorSettings processorSettings() {
        return ProcessorSettings.load();
    }
}

@Component
final class ProcessorGateway {
    private final ProcessorSettings settings;

    ProcessorGateway(ProcessorSettings settings) {
        this.settings = settings;
    }
}
```

This keeps domain behavior deterministic and makes gateway tests independent of process-wide environment state.

## Override properties in TestFixture

`TestFixture.withProperty(name, value)` adds a fixture-scoped string property. A non-null value is converted with `toString()`; passing `null` removes the fixture override and may reveal a lower-precedence source instead of masking it.

Test the mapping from raw properties to typed settings:

```java
TestFixture.create()
        .withProperty("processor.caption.base-url", "https://caption.test")
        .withProperty("processor.artwork.base-url", "https://artwork.test")
        .withProperty("processor.timeout", "PT1S")
        .whenApplying(fc -> ProcessorSettings.load())
        .expectResult(new ProcessorSettings(
                URI.create("https://caption.test"),
                URI.create("https://artwork.test"),
                Duration.ofSeconds(1)));
```

Keep the missing-required case independently falsifiable:

```java
TestFixture.create()
        .whenApplying(fc -> ApplicationProperties.requireProperty(
                "example.missing.required-property"))
        .expectExceptionalResult(IllegalStateException.class);
```

Also reject each malformed URL shape independently:

```java
for (String invalid : List.of(
        "/relative",
        "http:jobs",                        // opaque
        "http:/jobs",                       // no authority/host
        "ftp://processor.test/api",           // unsupported scheme
        "https://processor.test/api?tenant=a",// query not allowed on a base
        "https://processor.test/api#section")) {
    TestFixture.create()
            .withProperty("processor.caption.base-url", invalid)
            .withProperty("processor.artwork.base-url", "https://artwork.test")
            .whenApplying(fc -> ProcessorSettings.load())
            .expectExceptionalResult(IllegalStateException.class);
}
```

Test handler behavior separately by constructing the handler with an explicit `ProcessorSettings`. The property test proves resolution and parsing; the handler test proves business or integration behavior without depending on ambient configuration.
## Shared properties and module packaging

All classpath `application.properties` resources are merged. Put shared defaults in a common module and let executable
modules inherit them. Conflicting values for a key depend on class-loader order and cause a warning; intentional
overrides belong in a higher-priority source, such as environment/system properties or configured extra locations.

Always resolve feature configuration through `ApplicationProperties`, or the configured `PropertySource` at a builder
or configuration boundary. Do not add a feature-specific property helper or read environment/system/file values
directly. This preserves shared precedence, normalization, placeholders, decryption and test overrides.

Separate classpath resources and nested JARs retain module properties. A custom uber-JAR that collapses resources with
the same name must merge overlapping properties itself. Spring integration cannot recover a file discarded during
packaging. Verify the packaged application when its runtime configuration depends on defaults from multiple modules.
