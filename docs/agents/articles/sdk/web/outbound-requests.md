Use `WebRequestGateway` for auditable outbound HTTP through the Fluxzero runtime proxy. One-way processor submissions and compensations are messages; they are not inbound endpoint handlers.

For request/response calls, the SDK message gateway decompresses gzip before typed deserialization. Use
`response.getPayloadAs(MyReply.class)` normally, including in async fixtures. Decoded responses omit
`Content-Encoding` and update any `Content-Length` to the uncompressed byte length; wire responses are unchanged.

## Build an exact one-way POST

Buffered request bodies retain their serialized byte length in both the proxy and native transports. For HTTP/1.1,
the client supplies `Content-Length` automatically; do not add it manually. An endpoint that requires a known body
length can use the ordinary proxy route, including its WebRequest/WebResponse audit trail.

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

void submit(URI processorBaseUrl, ProcessingSubmission body) {
    WebRequest request = WebRequest.post(
                    appendPath(processorBaseUrl, "jobs"))
            .contentType("application/json")
            .body(body)
            .build();

    Fluxzero.get().webRequestGateway()
            .sendAndForget(Guarantee.STORED, request);
}

static String appendPath(URI base, String operation) {
    String value = base.toString();
    while (value.endsWith("/")) {
        value = value.substring(0, value.length() - 1);
    }
    return value + "/" + operation;
}
```

Do not use `base.resolve("jobs")` blindly: when the configured base is `https://processor.test/api` without a trailing slash, URI resolution replaces `api` and produces `https://processor.test/jobs`. Normalize or append deliberately so the configured base path is preserved.

For an object payload, `WebRequest` does not infer JSON content type. Set `application/json` explicitly. Use a dedicated body type so the serializer cannot accidentally include workflow state, internal flags, or the other component's fields.

`sendAndForget` means the caller does not register a pending response callback or await/return the HTTP result. Web
request processing still normally produces and appends a `WebResponse` to the WebResponse log. The returned
`CompletableFuture<Void>` tracks only the requested delivery guarantee for the `WebRequest`:

- `Guarantee.SENT` confirms local delivery or successful sending to the runtime.
- `Guarantee.STORED` waits for durable runtime storage and is the safer default when a tracked business workflow must not advance its consumer position before the request is durably recorded.

`Guarantee.STORED` does not commit an aggregate and does not make state plus HTTP publication atomic. Never call this gateway immediately after `assertAndApply(...).get()` in the same aggregate handler. Persist the request intent first, then publish from a registered tracked post-commit consumer so correlation aliases exist before a fast response can arrive; read aggregate commit and effect boundaries.

Tracked consumers await send-and-forget futures started during a batch by default (`awaitSendAndForgetFutures = true`). Keep that default unless independent completion is intentional.

Do not use `sendAndWait` when the HTTP response is only transport acknowledgement. A `2xx` processor response is not a processing confirmation unless the processor contract explicitly says so. Model the later business decision as its own correlated message.

Delivery is at-least-once. Use the persisted processing or compensation reference as the processor's stable idempotency key, and keep the logical-once decision in workflow state.

## Validate configured URLs

Resolve required properties into typed settings once:

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

## Isolate outbound failures without changing existing configurations

The additional resilience controls are opt-in on both supported SDK lines. Existing applications retain
`retryPolicy(LEGACY)`, fixed retry delays and no per-origin concurrency limit; no defaults-version threshold changes
these settings.

Use `retryPolicy(RetryPolicy.SAFE)` for configured retries of GET, HEAD, PUT, DELETE, OPTIONS and TRACE, or of a
request with exactly one nonblank `Idempotency-Key` header. For POST/PATCH, use a stable key supported by the upstream's
deduplication contract. The SDK reuses the same serialized body and headers across attempts; the presence of a key
alone cannot guarantee that an upstream deduplicates it. `RetryPolicy.IDEMPOTENT` is an explicit application guarantee
for operations with proven idempotent semantics. `LEGACY` preserves the previous method-independent retry behavior.
None of these choices enables retries unless `maxRetries` is positive.

```java
WebRequestSettings settings = WebRequestSettings.builder()
        .timeout(Duration.ofSeconds(5)).maxRetries(2)
        .retryPolicy(RetryPolicy.SAFE)
        .retryDelay(Duration.ofMillis(250)).retryJitter(true)
        .build();
```

```kotlin
val settings = WebRequestSettings.builder()
    .timeout(Duration.ofSeconds(5)).maxRetries(2)
    .retryPolicy(RetryPolicy.SAFE)
    .retryDelay(Duration.ofMillis(250)).retryJitter(true)
    .build()
```

With jitter enabled, each retry waits a randomized duration between half and all of `retryDelay`. All attempts,
redirects and retry waits still consume the same timeout budget; retries do not reset it. Fixture retries enforce
the same safety policy without actual retry waits. A new SDK publishes `maxRetries=0` for disallowed SAFE retries,
so an older proxy also refrains from retrying them. Jitter requires an updated proxy on the proxy route.

Configure concurrency limits through the normal property infrastructure:

| Transport owner | Property | Environment variable | Default |
| --- | --- | --- | --- |
| Proxy process | `fluxzero.proxy.forward.maxConcurrentPerOrigin` | `FLUXZERO_PROXY_FORWARD_MAX_CONCURRENT_PER_ORIGIN` | `0` (disabled) |
| Native application client | `fluxzero.web.native.maxConcurrentPerOrigin` | `FLUXZERO_WEB_NATIVE_MAX_CONCURRENT_PER_ORIGIN` | `0` (disabled) |

A positive value limits outstanding executions per original HTTP origin: lowercase scheme and hostname plus the
effective port, without DNS resolution. Paths, credentials and query strings do not create separate origins.
Limits are local to the owning client/process, not cluster-wide. Namespace views of one native client share its
limit. Idle origins are removed from bookkeeping; no permanent hostname cache or additional unbounded queue is kept.

The proxy admits requests within its existing bounded queue and selects eligible segment heads before reserving a
global execution slot. Set the per-origin limit below the existing global concurrency limit to leave execution
capacity for other origins. Requests in one segment remain ordered, including response publication. The global
outstanding bound and backpressure remain in force: a completely full queue or a preceding request in the same
segment can still delay another origin. The limit is held across retries and response publication and is released
on completion or shutdown handoff. Waiting in the proxy queue consumes the existing request deadline.

Native execution has no added waiting queue: exceeding its limit returns HTTP 503 immediately, without an HTTP
attempt or automatic transport retry of that local response. The permit is held across retries and released on
completion, failure or cancellation; closing the client cancels its limited in-flight requests and retry waits.

Redirects retain the original request's permit and existing redirect policy. Multiple original origins redirecting
to the same destination are not a shared destination bulkhead. Use `SAME_ORIGIN` or `NEVER` when that distinction
matters. These local limits also do not deduplicate replayed requests or replace domain idempotency.
