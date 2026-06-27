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

## Current Backlog: Generated Metadata Runtime Parity

Status: [ ] open.

Context: browser conformance is parked as a breadth exercise until the JVM runtime can prove the same generated app
model. The browser should not need to prove that Fluxzero was reimplemented; it should only prove that another backend
can execute the same generated metadata and generated invocation form.

Acceptance criteria:

- [ ] `ComponentRegistry` is complete enough for all Fluxzero runtime decisions.
- [x] JVM runtime has a central generated-only metadata resolver mode.
- [ ] In generated-only mode, reflection fallback is forbidden for app semantics.
- [ ] Existing `sdk-jvm` tests run green in generated-only mode, first by thematic clusters and then broadly.
- [ ] Browser generator uses the same metadata and generated invocation shape after the JVM proves the model.

Work slices:

- [x] Slice A: Generated-only metadata resolver mode.
  - [x] Add an explicit runtime/property switch for generated-only metadata.
  - [x] Make the central JVM metadata resolver refuse classpath/reflection fallback in that mode.
  - [x] Add focused tests proving registry-backed lookup still works and reflection-backed fallback is absent.
- [x] Slice B: Direct JVM scanner-call inventory and guardrail.
  - [x] Treat direct `JvmComponentMetadataLookup.scan(...)`, `scanIfScannable(...)`, and
    `new ClasspathComponentScanner().scan(...)` calls in runtime code as backlog debt.
  - [x] Add a focused guardrail that can be tightened as each thematic cluster moves through the central/generated
    resolver.
  - [x] Current known debt: 0 direct runtime scan/fallback sites outside the central metadata lookup backends.
- [ ] Slice C: Thematic generated-only JVM clusters.
  - [ ] Handler discovery, filtering, and invocation.
    - [x] `RegistryFilteringHandler` uses the central metadata resolver and generated-only mode no longer scans
      handler routes through the JVM classpath fallback.
    - [x] `DefaultFluxzero` and Spring handler registration route component registry production through the central
      metadata helper; generated-only mode no longer scans registered handlers through direct classpath fallback.
  - [ ] Consumer, local/tracked, gateway, and tracking configuration.
    - [x] `ClientUtils` local handler and self-tracking metadata checks use the central resolver and respect
      generated-only mode.
    - [x] `ConsumerConfiguration` uses the central resolver for package/type `@Consumer`; generated-only mode no
      longer derives consumer configuration from JVM annotation fallback.
    - [x] `DefaultHandlerFactory` uses metadata-backed type/package `@TrackSelf` resolution; generated-only mode no
      longer derives self-handling factory behavior through JVM annotation fallback.
  - [ ] Modeling, aggregates, entities, repositories, and property access.
    - [x] `DefaultHandlerFactory` uses metadata-backed `@Stateful` resolution for handler discovery and stateful
      handler creation; generated-only mode no longer derives stateful factory behavior through JVM annotation
      fallback.
    - [x] `HandlerAssociations` and `StatefulHandler` use the central resolver for association, member,
      routing-key, and entity-id metadata; generated-only mode no longer derives those semantics through direct
      scanner fallback.
    - [x] Aggregate repository, index operations, and document store entity-id/apply-factory metadata use the central
      resolver; generated-only mode no longer derives those semantics through direct scanner fallback.
    - [x] `DefaultHandlerRepository` uses metadata-backed `@Stateful` commit/timestamp policy; generated-only mode no
      longer derives repository behavior through JVM annotation fallback.
    - [x] `DefaultEntityHelper` uses metadata-backed `@Aggregate` root policy; generated-only mode no longer derives
      aggregate commit/search/event policy through JVM annotation fallback.
    - [x] `ClientUtils.getSearchParameters` uses metadata-backed `@Searchable` projection, including `@Aggregate`
      meta-annotation attributes; generated-only mode no longer derives search collection/timestamp policy through
      JVM annotation fallback.
    - [x] `ModelMetadata` uses metadata-backed member, alias, and annotated-property discovery; generated-only mode no
      longer derives model property semantics through JVM annotation fallback.
  - [ ] Serialization, casting, data protection, content filtering, auth, validation, web, sockets, scheduling, stores.
    - [x] Registry annotations preserve nested annotation-valued attributes across source scanning, classpath scanning,
      annotation processing, JSON round-tripping, and metadata annotation projection.
    - [x] `ContentFilterInterceptor` uses the central metadata resolver and generated-only mode no longer applies
      `@FilterContent` through JVM annotation fallback.
    - [x] `ValidationUtils` uses the central metadata resolver for `@ValidateWith` and auth rules; generated-only
      mode no longer enforces validation/auth semantics from JVM annotation fallback.
    - [x] `DataProtectionInterceptor` uses the central metadata resolver for `@ProtectData` and
      `@DropProtectedData`; generated-only mode no longer protects or drops data through JVM annotation fallback.
    - [x] Web route pattern metadata uses the central resolver; generated-only mode no longer derives web patterns
      through direct scanner fallback.
    - [x] Dynamic web `@Path` roots and web parameter binding use metadata-backed property/parameter annotations;
      generated-only mode no longer derives those web semantics through JVM annotation fallback.
    - [x] `SchedulingInterceptor` and `MessageScheduler` use metadata-backed `@Periodic` resolution for handler
      methods and payload types; generated-only mode no longer derives scheduling semantics through JVM annotation
      fallback.
    - [x] `DefaultGenericGateway` and `SocketSession` use metadata-backed `@Timeout` resolution for request payload
      types; generated-only mode no longer derives timeout semantics through JVM annotation fallback.
    - [x] `HasMessage` and message routing use metadata-backed `@RoutingKey` type/property resolution; generated-only
      mode no longer derives publication routing keys through JVM annotation fallback.
    - [x] `DefaultHandlerFactory` and `SocketEndpointHandler` use metadata-backed `@SocketEndpoint` resolution,
      including nested `aliveCheck` configuration; generated-only mode no longer discovers socket endpoint semantics
      through JVM annotation fallback.
    - [x] `StaticFileHandler` uses metadata-backed type/package `@ServeStatic` resolution; generated-only mode no
      longer discovers static file handlers through JVM type or package annotation fallback.
- [ ] Slice D: Generated invocation parity.
  - [x] Route JVM handler, entity apply, assertion, authorization helper, and caster invocation through the explicit
    `ExecutableInvocationBackend`/`JvmComponentIntrospector` seam instead of scattered direct member invokers.
  - [x] Add a guardrail that prevents direct `DefaultMemberInvoker` usage from reappearing in `sdk-jvm` runtime code
    outside the JVM backend.
  - [x] Make `HandlerConfiguration` resolve handler annotations through an explicit executable annotation resolver.
  - [x] Add a JVM metadata-backed executable annotation resolver that synthesizes annotation views from
    `AnnotationDescriptor` and refuses reflection fallback in generated-only mode.
  - [x] Let the executable annotation resolver return concrete composed annotation views and all matching executable
    annotations, so meta-annotation lookups keep the same handler-kind semantics as the JVM path.
  - [x] Prove generated-only handler discovery, disabled handlers, and passive handler metadata through registry
    metadata.
  - [x] Route payload `allowedClasses` filtering through the same metadata-backed annotation resolver and prove
    generated-only behavior with and without registry metadata.
  - [x] Route `skipExpiredRequests` through the metadata-backed annotation resolver and prove generated-only request
    expiry behavior.
  - [x] Route document handler update/delete decoration and entity-parameter handler-kind matching through the
    metadata-backed annotation resolver.
  - [x] Route document/custom handler topic discovery through the metadata-backed annotation resolver.
  - [x] Route method-level `@Trigger`, `@Apply`, and `@AssertLegal` runtime filters through the metadata-backed
    annotation resolver.
  - [ ] Make JVM capable of using generated invocation metadata for app semantics where possible.
  - [ ] Replace handler discovery/matching reflection with registry-shaped executable metadata where generated
    invocation plans are available.
  - [ ] Keep unavoidable JVM-only reflection behind an explicit backend seam.
- [ ] Slice E: Browser resumes only after JVM generated-only evidence.
  - [ ] Browser generator consumes the same metadata/invocation contracts proven by JVM tests.
  - [ ] Browser-conformance checks backend compatibility, not a copied Fluxzero implementation.

Module shape checkpoint:

- [ ] Preserve the current customer-facing Java SDK artifact/module shape before customer adoption. The existing SDK
  should remain the JVM cornerstone; a thin compatibility artifact is not a satisfying long-term answer.
- [ ] Re-evaluate whether `sdk-jvm` should remain a public cornerstone module or be folded back into the existing
  SDK artifact before this branch becomes customer-facing.
- [ ] Keep edge/tooling modules only where they buy clear isolation: `sdk-browser-generator` and
  `browser-conformance` are acceptable as tooling/test edges; cornerstone modules need a higher bar.
- [ ] Re-evaluate `common-api` and `sdk-api` after generated-only JVM parity. They are justified only if they remain
  genuinely browser-safe/shared contracts rather than architecture-for-architecture's-sake.

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

Status: [x] implemented for the current JVM and browser metadata runtime targets.

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

Status: [x] implemented.

Context: after the facade exists, the existing JVM runtime should move horizontally from direct introspector calls to
metadata lookup calls. Reflection may still answer behind the facade at first; the important change is that Fluxzero
semantics become metadata-driven.

Goal: replace direct calls to `JvmComponentIntrospector` across runtime semantics with the metadata lookup facade.

Work:

- [x] Handler/package/type/method annotation reads.
  - [x] Payload type/package authorization reads use `ComponentMetadataLookup`.
  - [x] Handler method/type/package authorization reads use `ComponentMetadataLookup` before JVM fallback.
  - [x] `@ValidateWith` type metadata reads use `ComponentMetadataLookup` before JVM fallback.
  - [x] Local/self-tracking reads use metadata descriptors projected to the existing annotation-shaped API.
  - [x] Content-filter handler method/type/package marker reads use metadata descriptors before JVM fallback.
  - [x] Custom/meta-annotation relationships are explicitly modeled in `AnnotationDescriptor` metadata and consumed by
    source/classpath/processor registry lookups.
- [x] Route, consumer, local/tracked, and web metadata reads.
  - [x] `@Consumer` package/type metadata drives `ConsumerConfiguration` creation.
  - [x] Class-literal metadata resolving handles deep nested source names such as `Outer.Inner.Component`.
  - [x] Handler association and routing-key metadata reads use executable/property/parameter descriptors before JVM
    fallback.
  - [x] Local/tracked decisions use descriptor metadata through `ClientUtils`.
  - [x] Source/classpath/processor producers preserve full package/type/method `@Path` stacking, blank path defaults,
    handler annotation values, and absolute `@Path` reset semantics.
  - [x] HTTP web route runtime consumption uses registry `HandlerRoute.webRoutes()` before JVM fallback.
  - [x] WebSocket endpoint route consumption uses registry `HandlerRoute.webRoutes()` before JVM fallback, including
    enclosing type `@Path` semantics for classpath and annotation-processor metadata.
- [x] Property metadata reads for modeling, validation, content filtering, data protection, and search.
  - [x] Association property selection reads descriptor property metadata before JVM fallback.
  - [x] Content-filter policy marker reads descriptor metadata before JVM fallback.
  - [x] Data-protection property and method policy markers use descriptor metadata before JVM fallback.
  - [x] Search/document indexing `@EntityId` property discovery uses descriptor metadata before JVM fallback.
  - [x] Stateful handler `@EntityId`, `@Member`, and `@RoutingKey` policy discovery uses descriptor metadata before
    JVM access fallback.
  - [x] Aggregate repository `@EntityId` property and `@Apply` factory-method discovery use descriptor metadata before
    JVM fallback.
  - [x] Recursive entity-helper/entity traversal for `@Member`, `@Alias`, `@AssertLegal`, and routing-key properties
    uses descriptor metadata before JVM access fallback.
  - [x] Modeling, data-protection, search, and indexing property reads/writes use the `PropertyAccess` backend seam for
    property paths and property handles.
  - [x] Annotation attribute projection for modeling annotations such as `@Member`, `@Alias`, and `@Apply` uses
    metadata config descriptors before JVM annotation fallback.
- [x] Invocation and mutable property access behind a platform backend seam.
  - [x] `PropertyAccess` covers annotated property discovery, property names, property path reads/writes,
    property-handle reads, raw property types, and collection element types.
  - [x] Handler invocation uses a configurable `ExecutableInvocationBackend`, while constructor/wither invocation and
    mutable entity reconstruction use the `ExecutableInvoker`/`PropertyAccess` backend seams.

Done when:

- [x] JVM tests prove existing server-side behavior is unchanged while the core asks metadata-shaped questions.
  - [x] Focused auth, validation, consumer, association, stateful, local-handler, web, and content-filter tests are
    green for the migrated clusters.
  - [x] Full `sdk-jvm` suite is green after the current migrated clusters.
  - [x] Focused aggregate/modeling tests are green after recursive entity traversal migration.
  - [x] Custom/meta-annotation relationships are explicitly modeled and covered without relying on direct runtime
    reflection calls.

### Slice 5: Generated Registry Backend

Status: [x] implemented for the current registry-backed metadata surface.

Context: once the JVM runtime consumes the metadata lookup facade, a generated-registry backend can prove the same
functional core no longer requires reflection for application semantics. This is the bridge from "metadata exists" to
"metadata actually runs the app".

Goal: add a registry/generated metadata backend for the lookup facade and run the JVM test suite against it where
possible.

Work:

- [x] Implement lookup answers from `ComponentRegistry`, descriptors, generated property metadata, and generated
  executable metadata.
- [x] Run relevant JVM tests against the registry-backed metadata backend.
- [x] Make browser generation consume the same backend shape.
  - [x] `BrowserApplicationGenerator` accepts `ComponentMetadataLookup` directly.
  - [x] Registry-backed lookup remains the dependency-free backend shared by generated/browser-oriented code.
  - [ ] Full browser runtime parity remains Slice 6.

Done when:

- [x] Reflection-backed and registry-backed JVM runs agree for the covered semantics.

Acceptance evidence:

- [x] JVM runtime metadata resolution prefers active/generated `ComponentRegistry` metadata and falls back to
  reflection-backed classpath scanning only when registry metadata is unavailable.
- [x] Generated registry resources are loadable through the metadata lookup resolver.
- [x] Modeling metadata reads for annotated properties and `@Apply` executable metadata run through the shared lookup
  resolver.
- [x] Browser generation can start from the same `ComponentMetadataLookup` facade instead of directly owning a separate
  metadata model.

### Slice 6: Browser Runtime Parity

Status: [x] implemented for the current browser conformance target.

Context: browser execution should be a backend problem, not a copied Fluxzero implementation. The browser can have
generated invokers, generated codecs, browser stores, and browser-safe IO, but the Fluxzero rules should be shared with
the JVM core.

Goal: make browser execution consume the same metadata-shaped core as JVM execution.

Work:

- [x] Generated invokers and codecs.
  - [x] Browser generation emits generated route handlers and generated codec/upcast/downcast hooks.
  - [x] Browser execution avoids runtime reflection and explicit `Object#getClass()` payload discovery.
- [x] Browser stores, scheduler, and browser-safe IO.
  - [x] In-memory browser key-value, event/snapshot, document/search, scheduler, web router, and socket simulator are
    exercised by conformance.
- [x] Shared Fluxzero semantics with backend-only divergence.
  - [x] Build-time `ComponentRegistry` remains the source model.
  - [x] Browser generation lowers that registry into a TeaVM-safe `BrowserComponentRegistry`.
  - [x] Browser runtime registers generated handlers from lowered route metadata, not ad hoc handler strings.
  - [x] JVM runtime continues to consume the broader `ComponentMetadataLookup` facade.

Done when:

- [x] Browser conformance proves app-level SDK behavior using the shared metadata-driven core.

Acceptance evidence:

- [x] Browser unit tests prove registry-lowered route metadata can register and dispatch generated handlers.
- [x] Browser generator tests prove generated source includes a lowered runtime registry and uses metadata-backed
  `core.register(...)`.
- [x] Playwright/TeaVM E2E compiles the generated browser app to WebAssembly and passes the browser-native conformance
  report.
- [x] E2E report includes metadata-backed handler evidence (`metadataHandlers` and `metadataSnapshot`).

## Phase 3: Hardening After This Plan

Status: [x] implemented.

Context: these are adoption and confidence steps after the metadata/runtime direction is proven.

Work:

- [x] Wire the registry artifact into standard CLI/template defaults.
  - [x] `ComponentRegistryGenerator` supports explicit `--merge-existing` output.
  - [x] Java downstream/template Maven lifecycle generates main registry metadata in `process-classes`.
  - [x] Java downstream/template Maven lifecycle generates test registry metadata in `process-test-classes`.
  - [x] The downstream fixture proves compiled classpath metadata and `src/.../fluxzero` source metadata are merged in
    classpath registry resources.
  - [x] The manual comparison harness generated Maven apps use the same registry lifecycle wiring.
  - [x] Docs and Java agent rules describe lifecycle generation as the default path and manual generation as fallback.
- [x] Add a larger real-app parity corpus beyond the curated registry fixtures.
  - [x] Added a checked-in mini app with package metadata, consumers, registered types, self-handling payloads,
    modeling properties, all route families, web/socket routes, and infrastructure capabilities.
  - [x] Source and classpath scanners now compare that mini app through the shared semantic parity assertions.
  - [x] Parity assertions now fail per package/component, so future corpus failures point at the exact semantic drift.
  - [x] Fixed source scanning so lower `package-info.java` files inherit ancestor `@Consumer` and `@LocalHandler`
    semantics while still preserving lower package web-path metadata.
- [x] Rerun full `./mvnw -B install` after the current parity changes.
  - [x] First full install exposed a transient proxy upload assertion failure.
  - [x] Full `ProxyServerTest` rerun passed before retrying the reactor.
  - [x] Second full install passed across all modules.

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
- [x] Focused modeling/search/data-protection/property-access tests passed with `-Dmaven.compiler.proc=full`.
- [x] Full `sdk-jvm` test suite passed with `-Dmaven.compiler.proc=full` after property-access backend seam migration.
- [x] Focused source/classpath/processor web route parity tests passed with `-Dmaven.compiler.proc=full`.
- [x] Full `sdk-jvm` test suite passed with `-Dmaven.compiler.proc=full` after web route producer parity migration.
- [x] Full `sdk-jvm`, `sdk-browser`, `sdk-browser-generator`, and `browser-conformance` suites passed under
  `-Pbrowser-conformance`, including Playwright/TeaVM E2E.
- [x] Phase 3 registry lifecycle focused tests passed for `ComponentRegistryGeneratorTest` and `DownstreamProjectTest`.
- [x] Phase 3 real-app registry corpus and focused registry cluster passed.
- [x] Full multi-module install passed after the current Phase 3 parity changes.

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
