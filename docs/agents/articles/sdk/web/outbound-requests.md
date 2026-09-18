Use `WebRequestGateway` for outbound HTTP integrations. Keep requests in Fluxzero's normal message/proxy route by
default: it preserves auditability and correlation, supports configured transport retries, and lets `TestFixture`
assert requests and supply remote responses. Do not build a separate JDK, Spring or third-party HTTP client for an
ordinary integration. External writes that depend on a domain change belong after its commit; keep all HTTP calls out
of replayable Model applies.

## Give each external interaction a local command or query

Use a named command for an external action and a typed query (`Request<T>`) for an external read. Put the actual
`WebRequestGateway` call in the payload's `@HandleCommand` or `@HandleQuery` method. Other handlers invoke the
operation through `Fluxzero.sendCommandAndWait(...)` or `Fluxzero.queryAndWait(...)`, just like other application
behavior. Choose command versus query by the operation's meaning, not only its HTTP verb.

These self-handlers run locally by default. They need no `@TrackSelf`, `@Consumer`, `@Component`, extra
`@LocalHandler`, injected API-service bean, or explicit handler registration. Dispatch the payload; do not call its
`handle()` method directly.

The example partner API returns an order as JSON, with HTTP 200 for lookup and 201 for creation:

```java
public record GetPartnerOrder(@NotNull UUID orderId) implements Request<PartnerOrder> {
    @HandleQuery
    PartnerOrder handle() {
        var request = WebRequest.get("https://partner.example/api/orders/" + orderId)
                .header("Authorization", "Bearer " + ApplicationProperties.requireProperty("partner.api.token"))
                .build();
        var response = Fluxzero.sendWebRequestAndWait(request);
        if (response.getStatus() != 200) {
            throw new IllegalStateException("Order lookup returned HTTP " + response.getStatus());
        }
        return response.getPayloadAs(PartnerOrder.class);
    }
}

public record PlacePartnerOrder(@NotNull @Valid OrderDetails details) implements Request<PartnerOrder> {
    @HandleCommand
    PartnerOrder handle() {
        var request = WebRequest.post("https://partner.example/api/orders")
                .header("Authorization", "Bearer " + ApplicationProperties.requireProperty("partner.api.token"))
                .contentType("application/json")
                .body(details)
                .build();
        var response = Fluxzero.sendWebRequestAndWait(request);
        if (response.getStatus() != 201) {
            throw new IllegalStateException("Order placement returned HTTP " + response.getStatus());
        }
        return response.getPayloadAs(PartnerOrder.class);
    }
}

public record OrderDetails(@NotBlank String productCode, @Positive int quantity) {}
public record PartnerOrder(UUID orderId, String status) {}
```

```kotlin
data class GetPartnerOrder(@field:NotNull val orderId: UUID) : Request<PartnerOrder> {
    @HandleQuery
    fun handle(): PartnerOrder {
        val request = WebRequest.get("https://partner.example/api/orders/$orderId")
            .header("Authorization", "Bearer " + ApplicationProperties.requireProperty("partner.api.token"))
            .build()
        val response = Fluxzero.sendWebRequestAndWait(request)
        check(response.status == 200) { "Order lookup returned HTTP ${response.status}" }
        return response.getPayloadAs(PartnerOrder::class.java)
    }
}

data class PlacePartnerOrder(@field:NotNull @field:Valid val details: OrderDetails) : Request<PartnerOrder> {
    @HandleCommand
    fun handle(): PartnerOrder {
        val request = WebRequest.post("https://partner.example/api/orders")
            .header("Authorization", "Bearer " + ApplicationProperties.requireProperty("partner.api.token"))
            .contentType("application/json")
            .body(details)
            .build()
        val response = Fluxzero.sendWebRequestAndWait(request)
        check(response.status == 201) { "Order placement returned HTTP ${response.status}" }
        return response.getPayloadAs(PartnerOrder::class.java)
    }
}

data class OrderDetails(@field:NotBlank val productCode: String, @field:Positive val quantity: Int)
data class PartnerOrder(val orderId: UUID, val status: String)
```

Call them from application code in an active Fluxzero context:

```java
PartnerOrder existing = Fluxzero.queryAndWait(new GetPartnerOrder(orderId));
PartnerOrder placed = Fluxzero.sendCommandAndWait(
        new PlacePartnerOrder(new OrderDetails("book", 2)));
```

```kotlin
val existing: PartnerOrder = Fluxzero.queryAndWait(GetPartnerOrder(orderId))
val placed: PartnerOrder = Fluxzero.sendCommandAndWait(
    PlacePartnerOrder(OrderDetails("book", 2)))
```

Shared URL construction, headers, settings and response mapping may live in a small helper, interface or base class.
Keep each operation recognizable as its own command/query; do not replace them with a generic HTTP-command envelope
or require callers to inject an API service. Resolve configuration through `ApplicationProperties` at this integration
boundary. If settings need parsing, a typed settings value can be loaded here without making it a bean. The fixed
example URLs stand in for a configured, validated endpoint; see the URL guidance below.

A local command/query and its outgoing HTTP request have separate delivery rules. Local self-handling does **not**
select native HTTP: the nested `WebRequest` still uses the auditable proxy route and configured transport retries.
The local command/query itself is not a persisted job and has no independent tracker retry. Add `@TrackSelf` only
when that operation itself must arrive through the Runtime, have a durable consumer position, or be replayed/retried
independently. Add `@Consumer` only for a required tracking configuration. Neither is needed merely to call an API.

For an external write that depends on committed Model state, a registered post-commit event handler can dispatch the
local command and wait for its outcome. Let failures reach that caller's error/retry policy. Local dispatch does not
make HTTP part of the Model transaction: do not perform external I/O in `@Apply` or send the write from a mutation
handler while its changes are still pending. Read-only external queries may run from ordinary query/orchestration
handlers; their results are not replayable Model state until explicitly recorded.

## Credentials remain hidden in visible audit logs

Fluxzero Auditlog automatically replaces standard credential-header values such as `Authorization`,
`Proxy-Authorization`, `X-Api-Key`, `Cookie` and `Set-Cookie` with `<value scrambled>` in visible web request/response
logs. Header matching is case-insensitive. The same filtering applies to downloads through Auditlog. A bearer token
or API key in a standard header is therefore not a reason to bypass the auditable gateway or select native HTTP.

Resolve credentials through `ApplicationProperties` and send them in the external API's required header. This masking
belongs to Auditlog's projections and responses; the HTTP transport still receives the original credentials. It is
not a promise that secrets placed in arbitrary URLs, bodies, custom fields or application-written logs are removed.

## Await a third-party response

Use `sendAndWait` when the application needs the returned status or data, for example when reading an external
snapshot. `send` provides the corresponding future. Both use the same gateway; waiting is not a transport choice.

For example, configure the read handler's gateway call with an explicit retry budget:

```java
WebRequestSettings settings = WebRequestSettings.builder()
        .timeout(Duration.ofSeconds(5))
        .maxRetries(2)
        .retryDelay(Duration.ofMillis(250))
        .retryableStatusCodes(Set.of(502, 503, 504))
        .build();
WebResponse response = Fluxzero.sendWebRequestAndWait(request, settings);
```

```kotlin
val settings = WebRequestSettings.builder()
    .timeout(Duration.ofSeconds(5))
    .maxRetries(2)
    .retryDelay(Duration.ofMillis(250))
    .retryableStatusCodes(setOf(502, 503, 504))
    .build()
val response = Fluxzero.sendWebRequestAndWait(request, settings)
```

Configure `partner.api.token` through the normal property sources (`PARTNER_API_TOKEN` is the conventional environment
variable). Inspect the response status before mapping its body to the external contract. An HTTP success is a
transport/service outcome; it is not automatically a later business or physical-device confirmation.

`maxRetries` counts additional attempts and defaults to zero. Transport failures and configured HTTP statuses can
trigger retries within the overall `timeout`; `retryDelay` consumes that same budget. Retry writes only when the
operation is idempotent or the external API supplies an appropriate idempotency contract. Keep domain polling or
reconciliation separate from these transport retries.

## Choose native transport only deliberately

`WebRequestSettings.builder().useNativeHttpClient(true)` keeps the SDK API but executes an absolute HTTP(S) request
from the application instead of publishing it to the proxy. It bypasses the WebRequest/WebResponse message audit
route, local web handlers, dispatch interceptors and consumer isolation. It is not needed merely because the API
uses a bearer token. Choose it only for an explicit transport requirement that accepts those differences.

`TestFixture` can still route native-configured requests to registered remote handlers; it retains retry counts and
retryable statuses without real retry delays. Use the same production gateway in tests. Ordinary integration tests
should assert exact requests and use absolute `@HandleGet`/`@HandlePost` stubs; reserve real-network tests for wire,
TLS or proxy behavior that the fixture does not exercise. See `/docs/sdk/testing/external-backends` and
`/docs/sdk/tracking`. Give a remote stub its own consumer when the caller waits for its response; do not change
production consumer defaults only to make a test pass.

For request/response calls, the SDK message gateway decompresses gzip before typed deserialization. Use
`response.getPayloadAs(MyReply.class)` normally, including in async fixtures. Decoded responses omit
`Content-Encoding` and update any `Content-Length` to the uncompressed byte length; wire responses are unchanged.

## Build an exact one-way POST

The proxy forwards only absolute `http://` or `https://` URLs. Validate required base URLs at configuration startup, resolve the operation path, and preserve the typed body contract.

```java
record ProcessingSubmission(
        String componentType,
        String assetJobId,
        String assetKey,
        String sourceVersion,
        String profile,
        String processingRequestReference,
        String outputFormat) {
}

public record SubmitProcessing(@NotNull @Valid ProcessingSubmission details) {
    @HandleCommand
    void handle() {
        URI baseUrl = requiredAbsoluteUri("processor.base-url");
        WebRequest request = WebRequest.post(appendPath(baseUrl, "jobs"))
                .contentType("application/json")
                .body(details)
                .build();

        Fluxzero.get().webRequestGateway()
                .sendAndForget(Guarantee.STORED, request);
    }
}

static String appendPath(URI base, String operation) {
    String value = base.toString();
    while (value.endsWith("/")) {
        value = value.substring(0, value.length() - 1);
    }
    return value + "/" + operation;
}
```

Here `processor.base-url` (environment variable `PROCESSOR_BASE_URL`) is required configuration. The static helpers
illustrate shared request construction; they can live in the same enclosing class or a small integration helper.
Dispatch `SubmitProcessing` through `Fluxzero.sendCommandAndWait(...)` from the post-commit caller.

Do not use `base.resolve("jobs")` blindly: when the configured base is `https://processor.test/api` without a trailing slash, URI resolution replaces `api` and produces `https://processor.test/jobs`. Normalize or append deliberately so the configured base path is preserved.

For an object payload, `WebRequest` does not infer JSON content type. Set `application/json` explicitly. Use a dedicated body type so the serializer cannot accidentally include workflow state, internal flags, or the other component's fields.

`sendAndForget` means the caller does not register a pending response callback or await/return the HTTP result. Web
request processing still normally produces and appends a `WebResponse` to the WebResponse log. The returned
`CompletableFuture<Void>` tracks only the requested delivery guarantee for the `WebRequest`:

- `Guarantee.SENT` confirms local delivery or successful sending to the runtime.
- `Guarantee.STORED` waits for durable runtime storage and is the safer default when a tracked business workflow must not advance its consumer position before the request is durably recorded.

`Guarantee.STORED` does not commit an aggregate and does not make state plus HTTP publication atomic. Never call this gateway immediately after `assertAndApply(...).get()` in the same aggregate handler. Persist the request intent first, then publish from a registered tracked post-commit consumer so correlation aliases exist before a fast response can arrive; read aggregate commit and effect boundaries.

Tracked consumers await send-and-forget futures started during a batch by default (`awaitSendAndForgetFutures = true`). Keep that default unless independent completion is intentional.

Use `sendAndWait` when the caller needs to inspect the HTTP acknowledgement or detect an immediate rejection. Use
`sendAndForget` when it deliberately does not await that outcome. Neither makes a `2xx` processor response a later
processing confirmation unless the processor contract says so; model that later decision as its own correlated message.

Delivery is at-least-once. Use the persisted processing or compensation reference as the processor's stable idempotency key, and keep the logical-once decision in workflow state.

## Validate configured URLs

Keep required URL parsing at the integration/configuration boundary. The local command can call this helper; no
settings bean is required. For startup validation, use the same parser from application bootstrap:

```java
static URI requiredAbsoluteUri(String property) {
    String value = ApplicationProperties.requireProperty(property);
    if (value.isBlank()) {
        throw new IllegalStateException(property + " must not be blank");
    }
    URI uri;
    try {
        uri = URI.create(value);
    } catch (IllegalArgumentException e) {
        throw new IllegalStateException(property + " is not a valid URI", e);
    }
    String scheme = uri.getScheme();
    boolean http = "http".equalsIgnoreCase(scheme)
            || "https".equalsIgnoreCase(scheme);
    if (!uri.isAbsolute() || uri.isOpaque() || !http
            || uri.getRawAuthority() == null
            || uri.getHost() == null || uri.getHost().isBlank()
            || uri.getRawQuery() != null || uri.getRawFragment() != null) {
        throw new IllegalStateException(property
                + " must be an absolute HTTP(S) base URL with a host "
                + "and without a query or fragment");
    }
    return uri;
}
```

`URI.isAbsolute()` alone is insufficient: `http:jobs` is absolute but opaque, and `http:/jobs` has no
host. This `appendPath` contract allows a configured path such as `https://processor.test/api`, but rejects a query or
fragment because string path appending after either would build the wrong target. Reject at least `/relative`,
`http:jobs`, `http:/jobs`, `ftp://processor.test/api`,
`https://processor.test/api?tenant=a`, and `https://processor.test/api#section` in configuration tests. Do not silently
substitute a production-looking default for a missing processor URL.

## Assert the published request

TestFixture records outbound requests without contacting the processor:

```java
fixture.withProperty("processor.caption.base-url", "https://caption.test/api/")
        .whenCommand(startAssetJob)
        .expectWebRequest(request ->
                request.getMethod().equals("POST")
                && request.getPath().equals("https://caption.test/api/jobs")
                && "application/json".equals(request.getContentType())
                && request.<ProcessingSubmission>getPayloadAs(ProcessingSubmission.class)
                        .equals(expectedCaptionBody));
```

Assert URL, method, content type, and exact typed body for every processing and compensation component. Also test
missing, blank, relative, opaque, hostless, unsupported-scheme, query-bearing, and fragment-bearing configuration
independently.
