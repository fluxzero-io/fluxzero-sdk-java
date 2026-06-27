# ReflectionUtils Metadata Abstraction Plan

## Goal

Keep one functional Fluxzero core.

JVM execution may use reflection as an implementation detail. Browser execution should use generated metadata and
generated invocation. The core should consume metadata-shaped abstractions instead of calling `ReflectionUtils` or a
JVM-shaped introspector directly.

The target architecture is:

- build/source time produces a Fluxzero application model,
- JVM runtime and browser runtime consume the same semantic model,
- JVM and browser differ in metadata/invocation backends, not in Fluxzero behavior,
- the only real black box left in an app is what handler code does as a side effect.

Legend: `[x]` means implemented and verified for the current target scope. `[ ]` means still open.

Overall status: [ ] in progress.

Migration rule: move broad layers, not narrow vertical features. Fluxzero should not ask projects to run a
half-reflection, half-blueprint semantic model.

## Phase 1: Foundation And Registry Parity

Status: [x] implemented.

### Step 1: Browser-Safe Metadata Boundary

Status: [x] implemented.

Context: the first step was making the JVM/browser seam explicit before trying to remove reflection-backed behavior.
This proved that handler metadata, property access, and invocation can be described by SDK contracts instead of direct
`ReflectionUtils` calls.

Done:

- [x] Added browser-safe `ComponentIntrospector`, `PropertyAccess`, and `ExecutableInvoker` contracts in `sdk-api`.
- [x] Added `JvmComponentIntrospector` in `sdk-jvm` as the reflection-backed implementation.
- [x] Migrated `PayloadFilter` and `ExpiredRequestDecorator` to read handler metadata through the adapter.
- [x] Added focused tests for annotation descriptors, meta-annotation projection, property access, invocation, type
  specificity, allowedClasses filtering, and expired request behavior.

Outcome: the seam became explicit. The `sdk-jvm/src/main/java` `ReflectionUtils` count only dropped from 67 to 66 at
this point, because the phase was about introducing the boundary before the full mechanical migration.

### Step 2: Centralized JVM Reflection Backend

Status: [x] implemented.

Context: the JVM runtime can still use reflection internally, but the functional SDK core should no longer scatter raw
reflection calls through many components. This makes the remaining JVM/browser difference visible as one backend seam.

Done:

- [x] Removed direct `ReflectionUtils` references from `sdk-jvm/src/main/java`, except inside
  `JvmComponentIntrospector`.
- [x] Removed the temporary `ReflectionAccess` alias.
- [x] Changed `JvmComponentIntrospector` to explicit delegation instead of subclassing `ReflectionUtils`.
- [x] Added a guardrail test that fails on new direct `ReflectionUtils` usage outside the adapter.
- [x] Semantically migrated more runtime code to `JvmComponentIntrospector`, including handler filters, timestamp and
  web payload parameter resolution, consumer configuration, and tracking.

Current backend counts:

- [x] `ReflectionAccess`: 0 references in `sdk-jvm/src/main/java`.
- [x] `ReflectionUtils`: only `JvmComponentIntrospector` references it in `sdk-jvm/src/main/java`.
- [x] `JvmComponentIntrospector`: 67 files in `sdk-jvm/src/main/java` consume the centralized seam.

Outcome: the functional JVM runtime no longer sprays direct reflection-backend calls through its core.

### Slice 1: Build-Time Registry Artifact Is The App Model

Status: [x] implemented for the current registry producers.

Context: the registry artifact should be the Fluxzero application model, not a side effect of on-demand execution or
browser generation. This is the source of truth that can later drive JVM runtime behavior, browser generation,
dashboard rendering, and agent-readable blueprints.

Goal: generate and load `ComponentRegistry` metadata at build/source time without requiring runtime source scans.

Done:

- [x] Annotation processor writes `META-INF/fluxzero/component-registry.json` from javac's source model.
- [x] Source generator writes the same registry JSON shape from `src/main/fluxzero` and `src/test/fluxzero` without
  javac, classloading, or annotation processors.
- [x] Runtime can load generated registry resources from the classpath/classloader.
- [x] Missing `src/.../fluxzero` roots are not startup/runtime failures.
- [x] Markdown blueprint output remains explicit and opt-in.

Acceptance evidence:

- [x] Generated JSON round-trips through `ComponentRegistryJson`.
- [x] Generated registry resources are loadable from a classpath/classloader.
- [x] Build-time metadata exists without runtime source scanning.

Nearby follow-up, not required for this slice to count as done:

- [ ] Wire the registry artifact into standard CLI/template defaults.

### Slice 2: Broad Source/ClassPath Parity Harness

Status: [x] implemented as a broad semantic parity harness.

Context: this is the "did we lose magic?" layer. Before runtime behavior moves to metadata, source/build metadata must
match the current JVM classpath/reflection view for Fluxzero semantics.

Goal: compare the semantics discovered from source/build metadata against the current JVM classpath scanner.

Done:

- [x] Source scanner and classpath scanner have semantic parity coverage.
- [x] Annotation processor output and classpath scanner have semantic parity coverage.
- [x] Shared assertions cover packages, components, handler routes, properties, executables, consumers, registered types,
  web routes, local/tracked semantics, payload type names, `allowedClasses`, and capabilities.
- [x] Source-only and classpath-only discovery-mode capabilities are kept out of semantic equality.
- [x] Build-time parameter names are treated as richer metadata, not reflection parity; parity compares parameter types
  and annotations.
- [x] Non-Fluxzero platform annotations such as `java.lang.FunctionalInterface` are ignored when they do not affect
  Fluxzero semantics.

Acceptance evidence:

- [x] Source scanner and classpath scanner produce equivalent semantic registries for the curated fixture.
- [x] Annotation processor output and classpath scanner produce equivalent semantic registries for the same curated
  fixture wherever javac can observe the same information.
- [x] Parity failures point at exact package/component/route/property/executable fields.

Nearby follow-up, not required for this slice to count as done:

- [ ] Add a larger real-app parity corpus beyond the curated registry fixtures.

## Phase 2: Metadata Runtime Migration

Status: [ ] in progress.

### Slice 3: Metadata Lookup Facade

Status: [x] implemented.

Context: the runtime currently consumes `JvmComponentIntrospector` directly. That is better than direct
`ReflectionUtils`, but still too JVM-shaped. The next layer should let the core ask metadata-shaped questions that can
be answered by either reflection-backed JVM metadata or generated registry metadata.

Goal: introduce the complete runtime-facing metadata lookup surface for handlers, packages, types, methods,
constructors, parameters, properties, annotations, policies, and routes.

Work:

- [x] Define the runtime-facing lookup interfaces.
- [x] Add the reflection-backed JVM implementation behind the facade.
- [x] Add the registry-backed/generated implementation skeleton.
- [x] Keep the surface broad enough for the whole app model before migrating one subsystem deeply.

Done when:

- [x] Runtime code can depend on metadata-shaped interfaces without knowing whether metadata came from reflection or
  generated registry descriptors.

### Slice 4: JVM Runtime Consumes Metadata Lookup

Status: [ ] in progress.

Context: after the facade exists, the existing JVM runtime should move horizontally from direct introspector calls to
metadata lookup calls. Reflection may still answer behind the facade at first; the important change is that Fluxzero
semantics become metadata-driven.

Goal: replace direct calls to `JvmComponentIntrospector` across runtime semantics with the metadata lookup facade.

Work:

- [ ] Handler/package/type/method annotation reads.
  - [x] Payload type/package authorization reads use `ComponentMetadataLookup`.
  - [x] Handler method/type/package authorization reads use `ComponentMetadataLookup` before JVM fallback.
  - [x] `@ValidateWith` type metadata reads use `ComponentMetadataLookup` before JVM fallback.
  - [x] Local/self-tracking reads use metadata descriptors projected to the existing annotation-shaped API.
  - [x] Content-filter handler method/type/package marker reads use metadata descriptors before JVM fallback.
  - [ ] Custom/meta-annotation relationships are still completed by JVM fallback until meta-annotation metadata is
    explicitly modeled.
- [ ] Route, consumer, local/tracked, and web metadata reads.
  - [x] `@Consumer` package/type metadata drives `ConsumerConfiguration` creation.
  - [x] Class-literal metadata resolving handles deep nested source names such as `Outer.Inner.Component`.
  - [x] Handler association and routing-key metadata reads use executable/property/parameter descriptors before JVM
    fallback.
  - [x] Local/tracked decisions use descriptor metadata through `ClientUtils`.
  - [ ] Web route runtime consumption remains on the existing JVM path until source/classpath/processor producers all
    preserve full package/type/method `@Path` stacking, blank path defaults, and absolute URL reset semantics.
- [ ] Property metadata reads for modeling, validation, content filtering, data protection, and search.
  - [x] Association property selection reads descriptor property metadata before JVM fallback.
  - [x] Content-filter policy marker reads descriptor metadata before JVM fallback.
  - [x] Data-protection property and method policy markers use descriptor metadata before JVM fallback.
  - [x] Search/document indexing `@EntityId` property discovery uses descriptor metadata before JVM fallback.
  - [x] Stateful handler `@EntityId`, `@Member`, and `@RoutingKey` policy discovery uses descriptor metadata before
    JVM access fallback.
  - [x] Aggregate repository `@EntityId` property and `@Apply` factory-method discovery use descriptor metadata before
    JVM fallback.
  - [ ] Recursive entity-helper/entity traversal for `@Member`, `@Alias`, `@AssertLegal`, and routing-key properties
    still uses the JVM backend directly.
- [ ] Invocation and mutable property access behind a platform backend seam.

Done when:

- [ ] JVM tests prove existing server-side behavior is unchanged while the core asks metadata-shaped questions.
  - [x] Focused auth, validation, consumer, association, stateful, local-handler, web, and content-filter tests are
    green for the migrated clusters.
  - [x] Full `sdk-jvm` suite is green after the current migrated clusters.
  - [ ] Remaining Slice 4 clusters are migrated and covered without relying on direct runtime reflection calls.

### Slice 5: Generated Registry Backend

Status: [ ] planned.

Context: once the JVM runtime consumes the metadata lookup facade, a generated-registry backend can prove the same
functional core no longer requires reflection for application semantics. This is the bridge from "metadata exists" to
"metadata actually runs the app".

Goal: add a registry/generated metadata backend for the lookup facade and run the JVM test suite against it where
possible.

Work:

- [ ] Implement lookup answers from `ComponentRegistry`, descriptors, generated property metadata, and generated
  executable metadata.
- [ ] Run relevant JVM tests against the registry-backed metadata backend.
- [ ] Make browser execution consume the same backend shape.

Done when:

- [ ] Reflection-backed and registry-backed JVM runs agree for the covered semantics.

### Slice 6: Browser Runtime Parity

Status: [ ] planned.

Context: browser execution should be a backend problem, not a copied Fluxzero implementation. The browser can have
generated invokers, generated codecs, browser stores, and browser-safe IO, but the Fluxzero rules should be shared with
the JVM core.

Goal: make browser execution consume the same metadata-shaped core as JVM execution.

Work:

- [ ] Generated invokers and codecs.
- [ ] Browser stores, scheduler, and browser-safe IO.
- [ ] Shared Fluxzero semantics with backend-only divergence.

Done when:

- [ ] Browser conformance proves app-level SDK behavior using the shared metadata-driven core.

## Phase 3: Hardening After This Plan

Status: [ ] planned.

Context: these are adoption and confidence steps after the metadata/runtime direction is proven.

Work:

- [ ] Wire the registry artifact into standard CLI/template defaults.
- [ ] Add a larger real-app parity corpus beyond the curated registry fixtures.
- [ ] Rerun full `./mvnw -B install` after the current parity changes.

## Recent Verification

- [x] Focused `sdk-jvm` registry/reflection tests passed.
- [x] Slice 3 focused metadata lookup and registry/reflection tests passed with `-Dmaven.compiler.proc=full`.
- [x] Full `sdk-jvm` test suite passed with `-Dmaven.compiler.proc=full` after Slice 3.
- [x] Slice 4 focused auth/validation/stateful/consumer/local/content-filter/web safety tests passed with
  `-Dmaven.compiler.proc=full`.
- [x] Full `sdk-jvm` test suite passed with `-Dmaven.compiler.proc=full` after the current Slice 4 checkpoint.
- [x] Attempted web route runtime metadata consumption was reverted after tests exposed missing producer parity for
  stacked `@Path` semantics; existing web behavior is preserved.
- [x] Slice 4 property-policy tests for data protection, search, stateful members, and metadata lookup passed with
  `-Dmaven.compiler.proc=full`.
- [x] Full `sdk-jvm` test suite passed with `-Dmaven.compiler.proc=full` after property-policy migration.
- [x] Aggregate repository/modeling focused tests passed with `-Dmaven.compiler.proc=full` after aggregate metadata
  discovery migration.
- [x] Full `sdk-jvm` test suite passed with `-Dmaven.compiler.proc=full` after aggregate metadata discovery migration.
- [ ] Full multi-module install has not been rerun after the current parity changes.

## Reference: Boundary Shape

The proposed boundary is intentionally small and runtime-facing:

- `ComponentIntrospector`: reads type, package, method, constructor, parameter, field, record-component, and type-use
  metadata.
- `ExecutableInvoker`: invokes handler methods/constructors or generated handler entrypoints.
- `PropertyAccess`: reads/writes properties for modeling, data protection, serialization helper paths, and validation.
- `TypeModel`: exposes type hierarchy, record/class shape, generic names, allowed classes, and registered type metadata.
- `AnnotationModel`: exposes normalized annotation attributes from either reflection or generated registry metadata.

JVM implementation: wraps current `ReflectionUtils` and existing classpath scanning.

Browser implementation: consumes `ComponentRegistry`, `PropertyDescriptor`, `ExecutableDescriptor`,
`AnnotationDescriptor`, generated codecs, and generated invokers.

## Reference: Category Map

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

## Guardrail

Any future browser implementation should fail if it copies functional behavior instead of consuming the same core through
the metadata abstraction. The JVM and browser should differ in metadata/invocation backends, not in Fluxzero semantics.
