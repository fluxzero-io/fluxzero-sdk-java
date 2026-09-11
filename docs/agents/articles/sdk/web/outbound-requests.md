Use `WebRequestGateway` for auditable outbound HTTP through the Fluxzero runtime proxy. One-way processor submissions and compensations are messages; they are not inbound endpoint handlers.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

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
