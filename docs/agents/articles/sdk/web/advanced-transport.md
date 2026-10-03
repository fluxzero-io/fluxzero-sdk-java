Use this article for static application assets, multipart/form binding, binary responses, automatic HTTP helpers, or a
custom response policy. Keep ordinary JSON endpoints on the simpler web and routing paths.

## Static resources and SPA fallback

```java
@Component
@ServeStatic(
        value = "/",
        resourcePath = "classpath:/static",
        ignorePaths = "/api/*",
        fallbackFile = "index.html")
final class ConsoleUi {
}
```

`@ServeStatic` searches its configured classpath/file source. The default resource path is `/static`, the default API
ignore path is `/api/*`, and the default fallback is `index.html`. With `cleanUrls = true`, an extensionless path tries
the exact resource, `<path>.html`, `<path>/index.html`, then the fallback. Keep API paths excluded so an SPA fallback
cannot hide a missing endpoint. Use `classpath:` or `file:` to restrict the source intentionally.

Fingerprint immutable assets and configure caching deliberately; do not give HTML an immutable one-year cache when it
must point clients to newly deployed assets.

## Form and multipart parameters

Use `@FormParam` on handler parameters for URL-encoded and multipart parts. Bind text as `String`, file bytes as
`byte[]`/`InputStream`, or use `WebFormPart` when filename, content type, or part headers matter:

```java
@HandlePost("/api/uploads")
UploadReceipt upload(
        @FormParam("label") @NotBlank String label,
        @FormParam("file") WebFormPart file) {
    // Validate type and size before durable processing.
}
```

Use `@BodyParam` only to extract named JSON body fields; a plain DTO parameter is clearer for the complete body. Apply
validation to the injected parameter and independently bound nested values. Do not retain upload bytes in events,
metrics, logs, or search documents unless the product explicitly requires it.

## Automatic `HEAD` and `OPTIONS`

A matching GET route can answer `HEAD` when no explicit HEAD/ANY handler wins; the response keeps status/headers and
removes the body. Routes can contribute to automatic `OPTIONS` with `204` and `Allow` when no explicit OPTIONS/ANY
handler wins. Disable `autoHead` or `autoOptions` only when another application owns that helper for the same public
route. Add a routed test; method declaration order is not the selection contract.

## Response mapping and content negotiation

Automatically mapped objects support JSON, strings support `text/plain` and JSON string representation, and
`byte[]`/`InputStream` support `application/octet-stream`. Fluxzero chooses a supported representation from `Accept`
best-effort and leaves the normal default when none matches; do not promise `406` without an explicit application
policy. An explicit `WebResponse` or `Content-Type` wins.

Streamed responses are assembled as raw bytes by the SDK request gateway, including JSON content.
Use `getPayloadAs(...)` to convert the body. Async fixtures exercise this same path. For locally returned gzip
streams, conversion to a body value decodes the content; requesting `InputStream` or `Object` retains the original
stream without consuming it. Streams remain one-shot resources owned by their caller. Empty HEAD/304 bodies
may retain representation headers and must not be decompressed.

Use `FluxzeroBuilder.replaceWebResponseMapper(...)` only for an application-wide policy. Preserve safe error mapping,
HEAD body stripping, metadata/headers, and content negotiation in its tests. Prefer returning an explicit
`WebResponse` from the exceptional endpoint that needs a different status over changing every endpoint.

Programmatic `ApiDocExtractor`/`OpenApiRenderer` is an advanced documentation path for dynamic handler instances or a
custom document host. Generated annotation-processing output and served `@ApiDocInfo` endpoints remain the default;
do not create a second manually maintained operation inventory.

## Proxy response header buffers

The standalone and embedded SDK proxy accept `fluxzero.proxy.responseHeaderBufferSize`
(environment variable `FLUXZERO_PROXY_RESPONSE_HEADER_BUFFER_SIZE`), an initial HTTP/1 response **header**
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
continues to default to 1048576 bytes, so the existing incoming request and HTTP/1 response limits remain
unchanged. With the current Jetty configuration, a maximum configured below 16 KiB still retains
Jetty's 16 KiB HTTP/1 response ceiling, while the request limit follows the configured value. An explicit
initial capacity must be positive and no greater than the effective response ceiling.

For HTTP/2, set `fluxzero.proxy.http2MaxResponseHeaderSize=16384`
(`FLUXZERO_PROXY_HTTP2_MAX_RESPONSE_HEADER_SIZE=16384`) to cap response headers at 16 KiB.
This is the default from `fluxzero.defaults.version=2026.10.03`; older or absent defaults retain
the shared maximum. An explicit positive byte limit takes precedence regardless of the defaults
version. Set `1048576` to retain the previous default maximum. The effective shared response
maximum and the peer's advertised maximum can still lower the configured HTTP/2 limit.

This limit covers the complete uncompressed header list, including HTTP/2 accounting overhead
(32 bytes per field), rather than each individual header or just the compressed wire bytes.
Jetty allocates its HPACK output buffer against that ceiling, including for tiny responses;
16 KiB fits its buffer pool. Unlike HTTP/1, the initial response-buffer setting does not provide
small-buffer growth for HTTP/2 (see Jetty [#15872](https://github.com/jetty/jetty.project/issues/15872)).
An oversized response fails instead of being truncated or falling back to HTTP/1. Jetty 12.1.13
closes the HTTP/2 session after this HPACK encoding failure, so other streams on that connection
may be affected and subsequent requests need a new connection. Incoming request header limits
are unchanged. Choose a larger explicit limit if the application's response headers require it.

Jetty uses direct HTTP output buffers by default. Set
`fluxzero.proxy.useOutputDirectByteBuffers=false`
(`FLUXZERO_PROXY_USE_OUTPUT_DIRECT_BYTE_BUFFERS=false`) to allocate its HTTP/1.1 and HTTP/2 output
buffers on the Java heap. This setting applies to all proxy HTTP output, not only response headers
or health endpoints. Heap buffers share the configured heap budget and can increase copying and
garbage collection; direct buffers use a separate native-memory budget. Benchmark the complete
proxy workload when changing it.

These settings are resolved once at proxy startup through `ApplicationProperties`; restart after
changing them.

The built-in `/proxy/health` and `/proxy/ready` responses already have tiny fixed bodies and use
the same HTTP response generator as application responses. These options do not change their
payloads or readiness semantics and do not define the proxy's overall process-memory limit. The
same environment settings apply to Java and Kotlin applications.
