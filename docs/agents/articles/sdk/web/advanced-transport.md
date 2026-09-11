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

Use `FluxzeroBuilder.replaceWebResponseMapper(...)` only for an application-wide policy. Preserve safe error mapping,
HEAD body stripping, metadata/headers, and content negotiation in its tests. Prefer returning an explicit
`WebResponse` from the exceptional endpoint that needs a different status over changing every endpoint.

Programmatic `ApiDocExtractor`/`OpenApiRenderer` is an advanced documentation path for dynamic handler instances or a
custom document host. Generated annotation-processing output and served `@ApiDocInfo` endpoints remain the default;
do not create a second manually maintained operation inventory.
