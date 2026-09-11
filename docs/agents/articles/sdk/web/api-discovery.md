# Generated API Discovery

Fluxzero can extract a format-neutral `ApiDocCatalog` from web handlers and render it as OpenAPI 3.0.1 JSON.
OpenAPI 3.1 can be enabled with `OpenApiOptions` or `-Afluxzero.openapi.specVersion=3.1.0`.

- Prefer automatic inference from `@Handle...`, `@Path`, and web parameter annotations.
- Generated API docs are opt-in: only endpoints with `@ApiDoc` on a super-package/package, handler class, or handler
  method are included. Empty `@ApiDoc` is enough when all endpoint metadata can be inferred.
- Use `@ApiDoc` only for summaries, descriptions, operation ids, tags, operation security requirements, deprecation
  metadata, or schema hints that cannot be inferred.
  It may also document fields, parameters, record components, and type arguments such as
  `List<@ApiDoc(description = "Connection item") Connection>`; prefer this over OpenAPI-specific array annotations.
  For dependency-free schema metadata, use its optional `type`, `format`, `example`, `defaultValue`, `minimum`,
  `maximum`, `allowableValues`, `required`, `exclude`, `implementation`, `oneOf`, and `additionalProperties` attributes instead
  of Swagger `@Schema`. Use `additionalProperties = ApiDoc.AdditionalProperties.DENY` only when runtime
  deserialization rejects unknown fields, and close every alternative of a `oneOf` union separately.
- Use repeatable `@ApiDocResponse` annotations for additional status/error responses, or to describe an inferred
  response without repeating its body type. Use `ref = "error"` to reference `#/components/responses/error`.
- For a composed independent-model graph returned as `JsonNode`, select its root with
  `@ApiDocResponse(status = 200, modelGraph = RootModel.class)`. Add
  `apiDoc = @ApiDoc(...)` next to each child model's `@Parent(pathInParent = ...)`; the final path segment is documented as
  a list of that child model and slash-separated prefixes become nested objects. Array and collection return types
  remain arrays whose items are complete model graphs. An empty `modelGraphPaths` selection includes every relation
  except those with `@Parent(apiDoc = @ApiDoc(exclude = true))`. Use
  `modelGraphPaths = {"children/grandchildren"}` only for endpoint-specific subgraphs; ancestors are implicit, siblings
  and deeper descendants are not. `type` and `modelGraph` are mutually exclusive. Runtime-served docs include
  registered child models from other modules.
- Use `@ApiDocExclude` or `@ApiDoc(exclude = true)` to exclude package/class/method endpoints or model
  fields/record components/parameters from generated docs only. The `exclude` attribute also works in nested
  `@Parent.apiDoc`; neither form changes runtime handling or returned graph data.
- Use `@ApiDocInfo` on a package or handler type for document-level metadata such as title, version, description,
  contact, logo, servers, top-level security requirements, shared components via `@ApiDocComponent`, and top-level
  vendor extensions. Prefer this over external Swagger configuration files.
- Set `@ApiDocInfo(serveOpenApi = true)` to expose the generated spec through an internal `@NoUserRequired` web
  endpoint. The default `openApiPath` is `openapi.json`, resolved relative to the `@Path` value on the same package or
  handler type; use an absolute path to serve from the application root.
- Set `@ApiDocInfo(serveApiReference = true)` to expose a small HTML API reference page for the same document. The
  default `apiReferencePath` is `docs`, resolved relative to the same `@Path`; enabling this also serves the OpenAPI
  JSON document. The default renderer is Redoc; `ApiReferenceRenderer.SCALAR` and `SWAGGER_UI` are also available.
  Renderer assets are referenced by URL and are not bundled by the SDK; use `apiReferenceScriptUrl` and
  `apiReferenceStylesheetUrl` for self-hosted assets.
- Jakarta validation annotations on endpoint parameters and model fields/record components are reflected in schemas
  where possible, including required flags, numeric bounds, sizes, patterns, and email format. `@Size` maps to
  length constraints for text, item constraints for arrays/collections, and property constraints for maps.
- Required metadata on a body or body/form parameter sets `requestBody.required: true`; a body without such metadata
  remains optional. Map-value type-use constraints apply to `additionalProperties`. OpenAPI 3.1 also emits supported
  string-compatible map-key constraints as `propertyNames`; OpenAPI 3.0 omits that unsupported keyword.
- Jackson polymorphism with a real type property retains its discriminator and mapping. `Id.DEDUCTION` is represented
  only by its `oneOf` alternatives and never by a synthetic discriminator.
- Array properties in response models are required by default; array properties in input models must be made required
  explicitly with validation or `@ApiDoc(required = true)`.
- Render JSON with `OpenApiRenderer.render(...)`, `renderJson(...)`, or `renderPrettyJson(...)` and configure global
  title/version/servers with `OpenApiOptions`.
- When annotation processing is enabled, `OpenApiProcessor` generates `META-INF/fluxzero/openapi.json` for modules that
  contain web handlers opted in with `@ApiDoc`. Configure it with javac options like `-Afluxzero.openapi.title=...`,
  `-Afluxzero.openapi.version=...`, `-Afluxzero.openapi.servers=...`, `-Afluxzero.openapi.specVersion=3.1.0`, or
  disable it with `-Afluxzero.openapi.enabled=false`.
- The automatic endpoint discovers every `META-INF/fluxzero/openapi.json` visible to the handler classloader and merges
  compatible paths, components, and metadata in stable resource order. Exact duplicates are accepted; conflicting
  values and duplicate operation ids fail during handler registration with source and JSON-path context. A manual
  document at the same path follows these rules. Spring Boot nested JARs are discovered normally. A classic shaded JAR
  must preserve overlapping resources itself; an application-configured Maven Shade `AppendingTransformer` is
  supported because consecutive JSON documents are read separately.
- If route paths depend on runtime `@Path` properties, use `ApiDocExtractor.extract(handlerInstance)` for exact runtime
  docs; the compile-time processor can only see static annotation values.

---

<a name="http-mapping"></a>
