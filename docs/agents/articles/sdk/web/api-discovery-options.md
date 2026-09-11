Use these options after the ordinary generated API-discovery path works and the product needs exclusions, reusable
responses, a different OpenAPI dialect, or a browser reference renderer. Keep handlers and their annotations as the
single operation inventory.

## Exclude documentation without changing runtime behavior

`@ApiDocExclude` can exclude a package, handler type or method, parameter, field, record component, or type use from
generated documentation. It does not disable a route or remove a runtime field. Do not use it to hide an endpoint that
should be inaccessible; enforce authentication/authorization or remove the handler.

## Describe additional and reusable responses

Fluxzero infers the normal response from the handler return type. Add repeatable `@ApiDocResponse` entries for status
or error responses that cannot be inferred:

```java
@ApiDocResponse(status = 400, description = "Invalid request")
@ApiDocResponse(status = 409, ref = "conflict")
@HandlePost("/api/actions")
ActionResult perform(ActionRequest request) {
    // ordinary endpoint mapping
}
```

A bare `ref` resolves below `#/components/responses/`; define that component through `@ApiDocInfo.components`. When
`ref` is set, response description, type, and content type are ignored because an OpenAPI Reference Object cannot have
response-object siblings. Test that every reference resolves in the served document.

Use `@ApiDoc` itself for dependency-free schema hints that inference cannot supply: `type`, `format`, `example`,
`defaultValue`, `minimum`, `maximum`, `allowableValues`, `required`, `implementation`, `oneOf`, and
`additionalProperties`. It can annotate fields,
parameters, record components, and type arguments. Prefer it over adding a second Swagger-specific annotation model;
none of these documentation hints replaces runtime validation.

Use `additionalProperties = ApiDoc.AdditionalProperties.DENY` only when runtime deserialization rejects unknown
fields. Close each alternative of a `oneOf` union separately. `@Size` maps to text length, array/collection item count,
or map property count according to the annotated type.

Required metadata on a body or body/form parameter sets `requestBody.required: true`; without it, the body remains
optional. Map-value type-use constraints describe `additionalProperties`. OpenAPI 3.1 emits supported string-compatible
map-key constraints as `propertyNames`; OpenAPI 3.0 omits that unsupported keyword. Jackson polymorphism with a real
type property preserves discriminator/mapping; `Id.DEDUCTION` emits `oneOf` alternatives without a synthetic discriminator.

## Select document and reference rendering

`ApiDocCatalog` is the format-neutral extracted model. `OpenApiRenderer` turns it into OpenAPI using
`OpenApiOptions`; the default OpenAPI document version is `3.0.1`, while `openApiVersion = "3.1.0"` selects the 3.1
shape. Annotation processing accepts matching `fluxzero.openapi.*` processor options for title, version, servers,
enabled state, and document version. Prefer `@ApiDocInfo` for application-owned metadata and processor options only
for build-wide defaults.

The exact processor keys are `fluxzero.openapi.enabled`, `fluxzero.openapi.output`,
`fluxzero.openapi.specVersion`, `fluxzero.openapi.title`, `fluxzero.openapi.version`,
`fluxzero.openapi.description`, and `fluxzero.openapi.servers`. Pass them as compiler `-A` options. Do not disable
generation in one build profile while tests or packaging still promise the generated resource.

`@ApiDocInfo(serveApiReference = true)` also serves the OpenAPI document used by the page. Choose
`ApiReferenceRenderer.REDOC`, `SCALAR`, or `SWAGGER_UI`, and override script/stylesheet URLs only when the application's
content-security and asset-hosting policy requires it. A browser page is presentation; the served JSON contract
remains the machine-readable source.

## Programmatic extraction is exceptional

`ApiDocExtractor.extract(handlerInstance)` supports dynamically supplied handler instances.
`OpenApiRenderer.render(...)`, `renderJson(...)`, or `renderPrettyJson(...)` produces a document for a custom endpoint;
the selected `ApiReferenceRenderer` configures the accompanying browser page. Use this only when generated
annotation-processing output and automatic `@ApiDocInfo` serving cannot express the runtime handler set.

For any custom path, retain the structural tests from generated API discovery: exact paths/methods, stable operation
IDs, required fields, every response/reference, security requirement plus scheme, selected OpenAPI version, serving
content type, and one routed behavior test per advertised operation.
