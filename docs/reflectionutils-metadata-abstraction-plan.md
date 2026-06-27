# ReflectionUtils Metadata Abstraction Plan

This note captures the remaining JVM reflection seams after the registry blueprint/property metadata slice.

Goal: keep one functional Fluxzero core. JVM execution may use reflection as an implementation detail, while browser
execution uses generated metadata and generated invocation. The core should consume an abstraction instead of calling
`ReflectionUtils` directly.

## Current Audit

The initial audit found 67 `sdk-jvm/src/main/java` files that referenced `ReflectionUtils`. The only known `sdk-api`
reference was Javadoc on browser-safe annotation/API surface.

The interesting conclusion is that these files are probably the map of future runtime seams: where JVM can have a
reflection-backed adapter and browser can have a registry/generated adapter.

## Phase 1 Progress

Implemented the first boundary slice:

- Added browser-safe `ComponentIntrospector`, `PropertyAccess`, and `ExecutableInvoker` contracts in `sdk-api`.
- Added `JvmComponentIntrospector` in `sdk-jvm` as the reflection-backed implementation.
- Migrated `PayloadFilter` and `ExpiredRequestDecorator` so handler metadata is read through the adapter instead of
  calling `ReflectionUtils` directly.
- Added focused tests for executable annotation descriptors, meta-annotation projection, property access, invocation,
  type specificity, allowedClasses filtering, and expired request behavior.

Current `sdk-jvm/src/main/java` count after this slice was 66 files still referencing `ReflectionUtils`. Two runtime
call sites moved behind one JVM adapter, so the count only dropped by one. That was intentional for this first slice:
the seam became explicit before the full mechanical migration.

## Phase 2 Progress

Centralized the JVM reflection backend behind `JvmComponentIntrospector`.

- Removed direct references to the old `ReflectionUtils` class from `sdk-jvm/src/main/java`, except inside
  `JvmComponentIntrospector`.
- Removed the temporary `ReflectionAccess` backend alias again so there is only one JVM adapter seam.
- Changed `JvmComponentIntrospector` to explicit delegation instead of subclassing `ReflectionUtils`, so the old
  reflection API is not inherited by the Fluxzero adapter surface.
- Added a guardrail test that fails when `sdk-jvm/src/main/java` reintroduces direct `ReflectionUtils` references outside
  `JvmComponentIntrospector`, or any `ReflectionAccess` references.
- Semantically migrated more runtime code to `JvmComponentIntrospector`:
  - `HandleCustomFilter`
  - `HandleDocumentFilter`
  - `SegmentFilter`
  - `TimestampParameterResolver`
  - `WebPayloadParameterResolver`
  - `ConsumerConfiguration`
  - `DefaultTracking`

Current backend counts:

- `ReflectionAccess`: 0 references in `sdk-jvm/src/main/java`.
- `ReflectionUtils`: only `JvmComponentIntrospector` references it in `sdk-jvm/src/main/java`.
- `JvmComponentIntrospector`: 67 files in `sdk-jvm/src/main/java` now consume the centralized seam.

This does not mean every operation is generated-metadata-backed yet. It does mean the functional JVM runtime no longer
sprays direct reflection-backend calls through its core. The next migration map is now inside
`JvmComponentIntrospector`: replace inherited reflection-style operations with metadata/generated implementations per
feature area.

## Proposed Boundary

Introduce a small metadata/introspection boundary before moving more logic to browser:

- `ComponentIntrospector`: reads type, package, method, constructor, parameter, field, record-component, and type-use
  metadata.
- `ExecutableInvoker`: invokes handler methods/constructors or generated handler entrypoints.
- `PropertyAccess`: reads/writes properties for modeling, data protection, serialization helper paths, and validation.
- `TypeModel`: exposes type hierarchy, record/class shape, generic names, allowed classes, and registered type metadata.
- `AnnotationModel`: exposes normalized annotation attributes from either reflection or generated registry metadata.

JVM implementation: wraps current `ReflectionUtils` and existing classpath scanning.

Browser implementation: consumes `ComponentRegistry`, `PropertyDescriptor`, `ExecutableDescriptor`,
`AnnotationDescriptor`, generated codecs, and generated invokers.

## Category Map

### Handler discovery, filtering, and invocation

- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/DefaultHandlerFactory.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/MutableHandler.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/StatefulHandler.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/PayloadFilter.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/PayloadParameterResolver.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/RequestTypeResolver.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/TimestampParameterResolver.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/TriggerParameterResolver.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/DocumentHandlerDecorator.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/ExpiredRequestDecorator.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/HandleCustomFilter.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/HandleDocumentFilter.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/HandlerAssociations.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/SegmentFilter.java`

### Consumer, tracking, and gateway configuration

- `sdk-jvm/src/main/java/io/fluxzero/sdk/Fluxzero.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/common/ClientUtils.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/common/HasMessage.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/ConsumerConfiguration.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/DefaultTracking.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/publishing/DefaultResultGateway.java`

### Modeling, aggregates, entities, and repositories

- `sdk-jvm/src/main/java/io/fluxzero/sdk/modeling/AnnotatedEntityHolder.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/modeling/DefaultEntityHelper.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/modeling/DefaultHandlerRepository.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/modeling/Entity.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/modeling/EntityParameterResolver.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/modeling/Id.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/modeling/ImmutableEntity.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/persisting/repository/DefaultAggregateRepository.java`

### Serialization, casting, search, and document helpers

- `sdk-jvm/src/main/java/io/fluxzero/sdk/common/serialization/AbstractSerializer.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/common/serialization/ChunkedDeserializingMessage.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/common/serialization/DeserializingObject.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/common/serialization/casting/CastInspector.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/common/serialization/jackson/JacksonSerializer.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/persisting/search/DefaultIndexOperation.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/persisting/search/DocumentStore.java`

### Policies: data protection, content filtering, auth-related validation

- `sdk-jvm/src/main/java/io/fluxzero/sdk/publishing/dataprotection/DataProtectionInterceptor.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/contentfiltering/ContentFilterInterceptor.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/ValidationUtils.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta/BeanValidationMetadata.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta/ConstraintMeta.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta/ConstraintValidators.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta/DefaultJakartaValidator.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta/DefaultValidationMetadata.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta/ExecutableValidationMetadata.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta/TypeUseValidationMetadata.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta/ValidationAnnotationUtils.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta/ValidationRun.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta/ValidationSettings.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/tracking/handling/validation/jakarta/ValueExtractorRegistry.java`

### Scheduling

- `sdk-jvm/src/main/java/io/fluxzero/sdk/scheduling/MessageScheduler.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/scheduling/SchedulingInterceptor.java`

### Web, socket, and API-document metadata

- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/ApiDocExtractor.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/ApiReferenceEndpoint.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/DefaultWebRequestContext.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/OpenApiDocumentEndpoint.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/OpenApiRenderer.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/SocketEndpointHandler.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/SocketSession.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/StaticFileHandler.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/WebHandlerMatcher.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/WebParamParameterResolver.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/WebPayloadParameterResolver.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/WebUtils.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/web/WebsocketHandlerDecorator.java`

### JVM-only integrations and metadata producers

- `sdk-jvm/src/main/java/io/fluxzero/sdk/configuration/spring/FluxzeroSpringConfig.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/configuration/spring/SpringBeanParameterResolver.java`
- `sdk-jvm/src/main/java/io/fluxzero/sdk/registry/ClasspathComponentScanner.java`

## Migration Order

1. Create the abstraction in `sdk-api` or a small shared module without browser-forbidden dependencies.
2. Implement a JVM adapter that delegates to existing `ReflectionUtils` behavior.
3. Implement a registry/generated adapter that reads `ComponentRegistry` descriptors.
4. Move low-risk metadata consumers first: handler filters, web matching, scheduling, consumer config, API docs.
5. Move property-sensitive policies next: validation metadata, content filtering, data protection, search indexing.
6. Move modeling/entity graph logic after property metadata parity is proven.
7. Keep raw method invocation and mutable property writes behind the adapter until generated invokers/codecs exist.

## Guardrail

Any future browser implementation should fail if it copies functional behavior instead of consuming the same core through
the metadata abstraction. The JVM and browser should differ in metadata/invocation backends, not in Fluxzero semantics.
