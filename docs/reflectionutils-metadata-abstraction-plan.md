# Metadata Runtime Finalization Plan

## Goal

Keep one functional Fluxzero core.

Build/source time produces a Fluxzero application model. The JVM runtime should consume that model for Fluxzero app
semantics, while reflection remains only a JVM backend implementation detail where Java itself is the platform.

The active phase lists only open work. Completed slices are kept later in this file as historical evidence.

## Active Phase: Generated-Only Runtime Closure

Status: open.

Browser-local execution stays parked until generated-only JVM mode has no semantic reflection fallback left. The goal is
not zero JVM reflection everywhere; the goal is that existing JVM tests prove Fluxzero app semantics from the generated
application model, with reflection left only as an explicit platform/backend implementation detail.

### Slice 1: Backend Debt Ledger

Status: [x] implemented for this slice.

Goal: split the current "allowed JVM backend" list into real platform backends and temporary migration debt, so
generated-only green tests cannot be mistaken for semantic-complete tests.

- [x] Add a machine-readable status to `JvmBackendAccess` entries:
  - [x] `PLATFORM_BACKEND` for Java-only mechanics that may remain reflection-backed.
  - [x] `MIGRATION_DEBT` for app-semantic fallbacks that must be replaced by generated metadata/invocation/access
    plans.
- [x] Add a guard test that reports the migration-debt list and fails when new debt is added implicitly.
- [x] Update the runtime decision matrix so every `Hybrid` or `Allowed JVM backend` semantic row points to the slice
  that removes or justifies it.

Done when:

- [x] We can answer "what still blocks browser reuse of JVM tests?" from one code-backed ledger, not from memory.

Evidence:

- `JvmBackendAccess` now classifies generated-only backend access as `PLATFORM_BACKEND` or `MIGRATION_DEBT`.
- `ReflectionBoundaryTest.generatedOnlyBackendMigrationDebtIsExplicit` records the current 43 class-specific migration
  debt entries.
- `docs/metadata-runtime-decision-matrix.md` now has a `Closure` column, and `RuntimeDecisionMatrixTest` validates
  closure targets for unfinished backend rows.

### Slice 2: Handler Execution Without Semantic Fallback

Status: [x] implemented for this slice.

Goal: generated-only handler registration, matching, parameter binding, and invocation must not need JVM executable or
parameter reflection for Fluxzero semantics.

- [x] Make generated invocation plans the required path for generated-only handler execution.
  - [x] Registered non-web handlers now fail in generated-only mode when matching registry metadata exists but generated
    invocation plans are missing.
  - [x] Non-web handler matching in generated-only mode now rejects missing registry-backed generated matchers instead
    of falling through to `HandlerInspector`.
  - [x] Partially lowered registered handler metadata now fails when any matching executable lacks a generated
    invocation plan.
- [x] Remove the generated-only allowance for `HandlerInspector`/`DefaultHandlerFactory` semantic fallback where a
  generated plan exists.
- [x] Cover command, query, event, notification, error, metrics, result, custom, document, schedule, web, and socket
  handler execution with generated-only tests.

Done when:

- [x] Existing handler tests can run in generated-only mode without relying on reflective executable discovery or
  parameter semantics.

Current evidence:

- `DefaultHandlerFactory` now requires registry-backed generated matchers in generated-only mode and rejects missing or
  partially lowered generated invocations before any `HandlerInspector` compatibility fallback can run.
- `DefaultHandlerFactoryGeneratedOnlyMetadataTest.generatedOnlyModeRejectsRegisteredHandlerWithoutGeneratedInvocation`
  covers the new failure mode.
- `DefaultHandlerFactoryGeneratedOnlyMetadataTest.generatedOnlyModeRejectsHandlerInspectorFallbackWithoutRegistryMatcher`
  covers the missing-registry-matcher fallback boundary.
- `DefaultHandlerFactoryGeneratedOnlyMetadataTest.generatedOnlyModeRejectsPartiallyLoweredRegisteredHandlerInvocations`
  covers the partial-lowering boundary.
- `TrackSelf` handler filtering now implements both `Executable` and metadata-only `ExecutableView` filter paths, fixing
  a view-first mismatch that reflection had hidden.
- `WebHandlerMatcher` now builds generated-only web routes from `HandlerRoute.webRoutes()`, `ExecutableView`, and
  registered generated invocations.
- Registry executable/parameter views project meta-annotations to the requested semantic annotation type, so
  `@HandleGet` can satisfy `@HandleWeb` without exposing a JVM `Method`.
- Generated-only handler tests now cover all non-web handler annotations, HTTP web execution, and socket-route
  execution through generated invocation functions.
- Focused handler/web suite passed after this Slice 2 expansion:
  `./mvnw -pl sdk-jvm -am -Dtest=DefaultHandlerFactoryGeneratedOnlyMetadataTest,HandleWebTest,WebUtilsTest,StaticFileHandlerGeneratedOnlyMetadataTest -Dsurefire.failIfNoSpecifiedTests=false test`.
- Full `sdk-jvm` suite passed after this Slice 2 checkpoint:
  `./mvnw -pl sdk-jvm -am test` with 1748 tests.

### Slice 3: Property And Constructor Access Plans

Status: [ ] open.

Goal: generated-only state/model/search/policy code should use generated property, constructor, and member access plans
instead of reflective JVM property access.

- [ ] Generate and consume property read/write plans for model ids, associations, routing keys, data protection,
  content filtering, document indexing, search facets, and web body extraction.
- [ ] Generate and consume constructor/factory plans for stateful entities and scheduled payload instantiation.
- [ ] Burn down the modeling, repository, search, data-protection, content-filtering, schedule, and web-body entries in
  the backend debt ledger.

Done when:

- [ ] Generated-only tests prove stateful/modeling/search/policy behavior without reflective property discovery.

Current evidence:

- Added `GeneratedPropertyAccesses` in `sdk-api`, a browser-safe registry for generated property readers and writers by
  component type and property name.
- `JvmComponentIntrospector.readProperty`, `hasProperty`, and `writeProperty` now prefer generated property accessors
  before falling back to the JVM property backend.
- `JvmComponentIntrospector.asInstance(Class)` now prefers a registered generated no-arg constructor invocation before
  using reflective default construction.
- `ModelMetadata.annotatedPropertyValues` now reads generated-only annotated property values through
  `PropertyDescriptor` metadata and generated property readers instead of reflective `AccessibleObject` reads.
- `DataProtectionInterceptor` now installs classpath generated property access before resolving generated-only
  protected field readers and writers.
- `SchedulingInterceptor` now installs classpath generated constructor invocations before generated-only periodic
  auto-start payload creation.
- The generated-only backend debt ledger no longer classifies `SchedulingInterceptor` as schedule instantiation debt;
  `PeriodicMetadata` remains as schedule metadata compatibility debt.
- Normal JVM compatibility fallbacks for model metadata, search/document id property reads, data protection,
  content filtering, and periodic metadata now go through `JvmCompatibilityBackend`, which refuses generated-only mode.
- Normal JVM compatibility fallbacks for handler document filtering, payload filtering, segment filtering, and trigger
  parameter metadata now also go through `JvmCompatibilityBackend`; stale handler filter/parameter debt entries without
  direct JVM backend access were removed.
- Normal JVM compatibility fallbacks for result error descriptions, consumer interceptor instantiation, mutable handler
  construction, and request response type resolution now go through `JvmCompatibilityBackend`.
- Stale direct-backend debt entries were removed for entity helpers, default tracking, expired request metadata, and
  default web request context after the boundary inventory showed no direct JVM introspector access in those classes.
- Normal JVM compatibility fallbacks for client helper annotations/topics, message routing-key reads, `Id`
  construction, and aggregate apply/entity id inference now go through `JvmCompatibilityBackend`.
- `GeneratedInvocationPlanTest` proves virtual generated property read/write, nested generated property reads, and
  generated construction for a class without a JVM default constructor.
- `ModelMetadataTest.generatedOnlyModeReadsAnnotatedPropertyValuesThroughGeneratedAccessors` covers generated-only
  `@AssertLegal` property values without relying on the backing field value.
- `DataProtectionInterceptorTest.generatedOnlyModeInstallsClasspathGeneratedPropertyAccessForProtectedFields` covers
  generated-only protected-field handling without explicit runtime registry registration.
- `SchedulingInterceptorTest.generatedOnlyModeInstallsClasspathGeneratedConstructorForPeriodicHandlerMethods` covers
  generated-only periodic payload construction without explicit runtime registry registration.
- `ReflectionBoundaryTest.generatedOnlyBackendMigrationDebtIsExplicit` now tracks 14 migration-debt classes.

### Slice 4: Policy, Validation, And Web Binding Closure

Status: [ ] open.

Goal: app-facing policies and web bindings are enforced from generated metadata and generated binding plans.

- [ ] Ensure auth, role, validation, data-protection, content-filtering, timeout, routing, and consumer decisions are
  sourced only from registry metadata in generated-only mode.
- [ ] Keep Jakarta provider mechanics as a platform backend, but remove app-facing validation metadata fallback.
- [ ] Make web route, parameter, body, response mapper, and socket binding tests run against generated-only metadata.

Done when:

- [ ] Policy/web failures in generated-only mode point to missing generated metadata, not hidden JVM inspection.

Current evidence:

- `ComponentMetadataLookups.typeAnnotation`, `packageAnnotation`, and `annotations` now project matching
  meta-annotations to the requested semantic annotation type.
- `ComponentMetadataLookupTest.typeAnnotationProjectsMetaAnnotationToRequestedPolicyTypeInGeneratedOnlyMode` proves
  type-level policy metadata can be consumed as `@RequiresUser` from a composed annotation in generated-only mode.

### Slice 5: Type, Serialization, And Casting Boundary

Status: [ ] open.

Goal: generated-only mode should know Fluxzero type semantics from the registry while JVM serializers remain pure
encoding/decoding backends.

- [ ] Move payload type, request response type, routing type, registered type, upcast, and downcast decisions to
  generated metadata/plans.
- [ ] Keep Jackson/class resolution behind the JVM serialization backend only for object materialization and wire
  encoding.
- [ ] Add generated-only tests for request/response metadata, registered types, upcast/downcast chains, and serializer
  integration.

Done when:

- [ ] Serialization can remain JVM-specific without owning Fluxzero app-model decisions.

Current evidence:

- Caster discovery already uses registry metadata for `@Upcast`/`@Downcast` in generated-only mode.
- `UpcasterChainTest.generatedOnlyModeUsesGeneratedInvocationForCasterMethods` proves caster invocation uses registered
  generated executable invocation instead of the JVM method body.

### Slice 6: Strict Generated-Only Acceptance

Status: [ ] open.

Goal: turn generated-only JVM mode into the acceptance gate for browser-shared semantics.

- [x] Make `MIGRATION_DEBT` backend access fail in strict generated-only mode.
- [ ] Run the broad `sdk-jvm` test suite in strict generated-only phases.
- [ ] Run downstream Java/Kotlin compatibility tests against generated metadata defaults.
- [ ] Only after this passes, resume browser conformance as another executor of the same app model.

Done when:

- [ ] The JVM no longer proves behavior through semantic reflection fallback, so browser work can reuse JVM evidence.

Current evidence:

- Added `fluxzero.metadata.mode=strict-generated-only` support. Strict mode still counts as generated-only, but
  `JvmBackendAccess` rejects `MIGRATION_DEBT` backend categories while allowing platform backends.
- `ReflectionBoundaryTest.strictGeneratedOnlyModeRejectsMigrationDebtBackendCategories` proves the strict guard.

## Completed Phase: JVM Metadata Runtime Foundation

Status: [x] implemented.

Browser-local execution was parked during this phase. The JVM was the proving ground because it already had the broadest
SDK behavior and test surface.

### Slice 1: Generated-Only Semantic Lock

Status: [x] implemented.

Goal: generated-only JVM mode must not answer Fluxzero app semantics through hidden classpath or reflection fallback.

Remaining work:

- [x] Add a generated-only runtime guard around the remaining JVM metadata/introspection backend so app-semantic use of
  `JvmComponentIntrospector` fails unless it is explicitly allowed.
- [x] Define the allowed JVM-only backend categories in this file, with examples and owning package areas.
- [x] Move every newly failing generated-only app-semantic path either to registry metadata/generated invocation or to
  the allowed JVM-only backend list.

Allowed JVM-only backend categories:

- Metadata producer / generated metadata bridge:
  `io.fluxzero.sdk.registry.*`, including `ClasspathComponentScanner`, `JvmComponentMetadataLookup`, generated
  registry loading, and metadata annotation projection.
- Current JVM executable invocation backend:
  `io.fluxzero.common.handling.HandlerInspector` and handler/modeling classes that still prepare JVM invocation
  handles until Slice 3 replaces app-level invocation decisions with generated invocation plans.
- JVM object/property backend:
  modeling, repository, document-store, data-protection, content-filtering, web-body, and stateful-handler code that
  still needs Java object construction or property access while the app semantics come from registry metadata.
- JVM serialization/type backend:
  serializer classes that need runtime classes for payload typing, chunked deserialization, Jackson conversion, and
  caster bridge logic.
- Jakarta validation provider backend:
  `io.fluxzero.sdk.tracking.handling.validation.jakarta.*` for constraint validator instantiation, provider metadata,
  composed constraints, value extractors, and override hierarchy mechanics until Slice 4 draws the final boundary.
- JVM integration backend:
  Spring parameter/config integration and small SDK utilities such as caller-scope memoization.

Done when:

- [x] Generated-only failures point to a missing metadata/invocation area, not to silent reflection drift.

Acceptance evidence:

- [x] `JvmComponentIntrospector` enforces the generated-only backend guard on every public method, including cached
  instances.
- [x] `ReflectionBoundaryTest` fails new direct `JvmComponentIntrospector` runtime sites unless they are classified by
  `JvmBackendAccess`.
- [x] `ComponentMetadataLookupTest` proves unclassified JVM introspection fails in generated-only mode.
- [x] Broad generated-only thematic suite passed:
  `./mvnw -pl sdk-jvm -am -Dtest=ApiDocExtractorTest,ClientUtilsTest,ComponentMetadataLookupTest,ConsumerConfigurationTest,ContentFilterInterceptorTest,DataProtectionInterceptorTest,DefaultAggregateRepositoryCommitPolicyTest,DefaultHandlerFactoryGeneratedOnlyMetadataTest,DefaultHandlerRepositoryGeneratedOnlyMetadataTest,DefaultValidatorTest,DocumentHandlerDecoratorTest,EntityParameterResolverTest,ExpiredRequestDecoratorTest,HandlerAssociationsTest,MessageRoutingInterceptorTest,ModelMetadataTest,OpenApiRendererTest,PayloadFilterTest,RegistryFilteringHandlerTest,SchedulingInterceptorTest,SearchTest,SocketSessionTest,StaticFileHandlerGeneratedOnlyMetadataTest,TriggerParameterResolverTest,UpcasterChainTest,ValidationUtilsTest,WebParamParameterResolverTest,WebUtilsTest -Dsurefire.failIfNoSpecifiedTests=false test`.

### Slice 2: Runtime Decision Matrix

Status: [x] implemented.

Goal: every Fluxzero runtime decision that belongs to app semantics has an explicit source in the generated app model.

Remaining work:

- [x] Add a runtime-decision matrix that maps each app-semantic decision to one of: consumed registry metadata,
  generated invocation plan, or allowed JVM-only backend implementation.
- [x] Cover runtime consumers rather than producers: handler matching, handler filters, parameter binding, validation,
  data protection, content filtering, routing, casting, scheduling, web/socket/document/custom routes, and registered
  types.
- [x] Add a lightweight maintenance guard for the matrix where practical, so new app-semantic runtime code must declare
  its metadata source.

Done when:

- [x] There is no undocumented "Fluxzero magic" left outside the generated model or the allowed JVM-only backend list.

Acceptance evidence:

- [x] Runtime decisions are mapped in `docs/metadata-runtime-decision-matrix.md`.
- [x] `RuntimeDecisionMatrixTest` requires coverage for all current app-semantic consumer areas and restricts source
  values to `Registry metadata`, `Generated invocation plan`, `Allowed JVM backend`, or `Hybrid`.
- [x] Guard command passed:
  `./mvnw -pl sdk-jvm -am -Dtest=RuntimeDecisionMatrixTest,ReflectionBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test`.

### Slice 3: Generated Invocation Plan

Status: [x] implemented.

Goal: the JVM runtime can use generated executable plans for app-level invocation decisions.

Remaining work:

- [x] Add invocation-plan descriptors on top of the existing executable/parameter metadata, including target component,
  executable id, binding plan, property access plan, and generated codec hooks where needed.
- [x] Generate invocation plans from build-time/source-time registry producers.
- [x] Make JVM handler invocation prefer generated plans when present.
- [x] Make JVM modeling/caster invocation prefer generated plans for `@Apply`, `@AssertLegal`, upcasters, and
  downcasters.
- [x] Keep reflection invocation only behind the explicit JVM backend seam for missing plans or intentionally JVM-only
  mechanics.
- [ ] Replace handler discovery/matching dependence on JVM `Executable`/`Parameter` with generated plan/view contracts.
  - [x] Add `ExecutableView` and `ParameterView` contracts in `common`.
  - [x] Add compatibility bridges on `HandlerDescriptor`, `HandlerMatcher`, `ParameterResolver`,
    `PreparedParameterResolver`, `HandlerFilter`, `MessageFilter`, and `MethodInvocationValidator`.
  - [x] Make `HandlerInspector` use executable/parameter views internally for message filtering, parameter resolving,
    specificity, and validation calls.
  - [x] Move `InputParameterResolver`, `MessageParameterResolver`, `PayloadParameterResolver`, `PayloadFilter`,
    `TriggerParameterResolver`, and `WebParamParameterResolver` onto view overrides with JVM fallback.
  - [x] Move `TypedParameterResolver` subclasses, `SegmentFilter`, `HandleCustomFilter`, `HandleDocumentFilter`,
    `JsonPayloadParameterResolver`, and `WebPayloadParameterResolver` onto view overrides where type metadata is
    sufficient.
  - [x] Add a generated matcher that can be built from registry invocation plans without enumerating JVM executables.
  - [x] Move built-in SDK resolvers/filters/decorators to override the view APIs where they still inspect
    `Executable`/`Parameter` directly.
- [x] Add generated-only JVM tests that exercise generated invocation across the main handler/modeling/casting paths
  without relying on `HandlerInspector` reflection-shaped matching/binding.

Done when:

- [x] Existing JVM behavior is preserved while handler matching, parameter binding, and app-level invocation decisions
  are driven by generated plans.

Current evidence:

- [x] `InvocationPlanDescriptor`, `ParameterBindingDescriptor`, and `PropertyAccessPlanDescriptor` describe executable,
  parameter, and property access plans without JVM reflection objects.
- [x] `ComponentMetadataLookup.invocationPlans(...)` derives invocation plans from existing registry descriptors, so
  source, classpath, and build-time registry producers expose the same plan shape.
- [x] `GeneratedExecutableInvocations` lets generated code register direct invocation functions by stable executable id.
- [x] `JvmComponentIntrospector.prepareInvocation(...)` resolves the active registry invocation plan first and prefers
  a generated invoker before falling back to JVM member invocation.
- [x] `HandlerInspector` now routes the common handling hot path through `ExecutableView`/`ParameterView` while
  preserving existing `Executable`/`Parameter` extension points through default bridges.
- [x] Core payload, message, trigger, web parameter, metadata/user/timestamp, custom/document, and segment matching now
  have view-first overrides with legacy JVM fallbacks.
- [x] `GeneratedInvocationPlanTest` proves generated-only handler invocation can use registry metadata plus a generated
  invoker without calling the JVM method body.
- [x] Common handling tests prove view-based parameter resolvers can run without the legacy `Parameter` methods.
- [x] `DefaultHandlerFactoryGeneratedOnlyMetadataTest` proves a registry-backed handler matcher can run in
  generated-only mode with no matching JVM `Executable` exposed to the Fluxzero handler invoker.
- [x] Method-level metadata consumers now use `ExecutableView` where generated invokers may not expose a JVM
  executable, including local/tracked decisions, auth policy, data protection, content filtering, document handling,
  expired-request handling, periodic metadata, handler metrics, and stateful routing-key checks.
- [x] Generated-only acceptance covers a methodless generated handler invoker with method-level `@LocalHandler` and
  `@RequiresUser` metadata.
- [x] Broad generated-only thematic suite passed after this change:
  `./mvnw -pl sdk-jvm -am -Dtest=ApiDocExtractorTest,ClientUtilsTest,ComponentMetadataLookupTest,ConsumerConfigurationTest,ContentFilterInterceptorTest,DataProtectionInterceptorTest,DefaultAggregateRepositoryCommitPolicyTest,DefaultHandlerFactoryGeneratedOnlyMetadataTest,DefaultHandlerRepositoryGeneratedOnlyMetadataTest,DefaultValidatorTest,DocumentHandlerDecoratorTest,EntityParameterResolverTest,ExpiredRequestDecoratorTest,GeneratedInvocationPlanTest,HandlerAssociationsTest,MessageRoutingInterceptorTest,ModelMetadataTest,OpenApiRendererTest,PayloadFilterTest,RegistryFilteringHandlerTest,SchedulingInterceptorTest,SearchTest,SocketSessionTest,StaticFileHandlerGeneratedOnlyMetadataTest,TriggerParameterResolverTest,UpcasterChainTest,ValidationUtilsTest,WebParamParameterResolverTest,WebUtilsTest -Dsurefire.failIfNoSpecifiedTests=false test`.

Remaining architectural boundary:

- Jakarta executable validation still needs an explicit JVM-only provider/backend boundary for return-value and
  type-use mechanics. That work is tracked in Slice 4.

### Slice 4: Validation And Policy Gaps

Goal: close the remaining validation and policy areas that still need JVM-specific metadata or explicit boundaries.

Status: [x] implemented for this slice.

- [x] Model type-use validation metadata where Fluxzero semantics need it, including collection, optional, generic
  element, and array paths.
- [x] Define the JVM-only Jakarta backend boundary for constraint validator instantiation, `@Constraint` definitions,
  composed constraints, `ValueExtractor`s, and provider metadata views.
- [x] Move those JVM-only validation mechanics behind the backend boundary instead of letting them look like generic app
  semantic reflection.
- [x] Add generated-only tests for the remaining type-use validation and Jakarta backend-boundary cases.

Done when:

- [x] Generated-only JVM tests prove app-facing validation and policy behavior without undisclosed reflection fallback.

Evidence:

- Added `TypeUseDescriptor` to the registry model and JSON format so fields, parameters, and return values can carry
  nested type-use annotations from classpath and annotation-processor producers.
- Added `JakartaValidationBackend` as the only direct JVM introspection site inside the Jakarta validation package.
- Added `ReflectionBoundaryTest.jakartaValidationUsesOnlyItsJvmBackendForDirectIntrospection`.
- Added `DefaultValidatorTest.generatedOnlyModeUsesRegisteredJakartaTypeUseValidationMetadata`, proving
  `List<@NotBlank ...>` and `Optional<@Valid ...>` are enforced from registered metadata while unregistered
  generated-only mode does not reflectively infer them.

### Slice 5: On-Demand Source Lifecycle

Goal: `src/.../fluxzero` behaves like a live local development source root, not only an index captured at registration.

Status: [x] implemented for this slice.

- [x] Refresh or watch source roots so newly added and deleted Java files update the indexed registry without
  restarting the app.
- [x] Diff refreshed registries and safely register or unregister lazy routes at runtime.
- [x] Update the active component registry visible to runtime metadata lookup after a refresh.
- [x] Invalidate compile-cache entries and close classloaders for removed or replaced source units.
- [x] Add tests for added handlers, removed handlers, added payload/model components, and removed payload/model
  components. Source-defined infrastructure remains startup material in this slice.

Done when:

- [x] A local JVM app can add, edit, and delete on-demand handlers and payload/model components, then see the route/type
  changes without restarting.

Evidence:

- Added explicit `OnDemandExecution.refresh()` for deterministic local/IDE/tooling refreshes without a background
  watcher.
- Refresh re-scans source roots when source scanning is enabled, diffs message-handler groups, cancels/re-registers
  changed groups, updates the active `ComponentRegistry`, and closes units for removed or descriptor-changed source
  components.
- Source type resolution now treats every scanned source component as loadable, not only handlers or route payloads.
- `OnDemandExecutionTest` covers added handlers, deleted handlers, added source payload components, deleted source
  payload components, and existing edit/hot-recompile behavior.

### Slice 6: JVM Adoption Shape And Final Verification

Goal: make the JVM-first model boring to adopt before browser work resumes.

Adoption decision:

- Existing JVM customers keep depending on `io.fluxzero:sdk`. That compatibility artifact remains the customer-facing
  Java SDK entry point and depends on the split API/JVM implementation artifacts internally.
- `io.fluxzero:sdk-jvm` is the JVM runtime implementation artifact, not a required new mental model for ordinary
  customer projects. Templates may use it explicitly only when the project wants to be clear about the runtime
  implementation, but this is not required for backward compatibility.
- `io.fluxzero:sdk-api` and `io.fluxzero:common-api` stay as cornerstone boundary artifacts because browser/local
  source execution needs the handler annotations, registry descriptors, gateway contracts, and protocol types without
  dragging in Jackson, Spring, filesystem, websocket, or JVM reflection/runtime dependencies.
- `io.fluxzero:sdk-browser`, `io.fluxzero:sdk-browser-generator`, and `browser-conformance` stay as edge/tooling/test
  modules. They must not become part of the default JVM adoption story.
- Generated-only metadata mode remains opt-in/test-only until the full JVM verification set proves it can be made the
  normal runtime path without changing Fluxzero semantics.

Remaining work:

- [x] Decide the customer-facing artifact/module shape before adoption. The existing `fluxzero-sdk-java` identity
  should remain the JVM cornerstone unless there is a concrete migration reason.
- [x] Fold, rename, or justify cornerstone modules introduced for the split. Edge/tooling/test modules may stay
  separate when the boundary is clearly useful.
- [x] Run generated-only thematic suites, then the full `sdk-jvm` suite.
- [x] Run downstream Java and Kotlin projects with generated registry lifecycle defaults.
- [x] Run the on-demand comparison benchmark with old/new paths and add/edit/delete source lifecycle scenarios.
- [x] Run full release-style verification after the JVM slices are complete.

Done when:

- [x] JVM customer projects can adopt the generated-model/source workflow without learning different Fluxzero semantics
  or a surprising module story.

Evidence:

- Focused generated-only/on-demand/registry suite passed:
  `./mvnw -pl sdk-jvm -am -Dtest=ApiDocExtractorTest,ClientUtilsTest,ComponentMetadataLookupTest,ComponentRegistryJsonTest,ConsumerConfigurationTest,ContentFilterInterceptorTest,DataProtectionInterceptorTest,DefaultAggregateRepositoryCommitPolicyTest,DefaultHandlerFactoryGeneratedOnlyMetadataTest,DefaultHandlerRepositoryGeneratedOnlyMetadataTest,DefaultValidatorTest,DocumentHandlerDecoratorTest,EntityParameterResolverTest,ExpiredRequestDecoratorTest,GeneratedInvocationPlanTest,HandlerAssociationsTest,MessageRoutingInterceptorTest,ModelMetadataTest,OpenApiRendererTest,PayloadFilterTest,ReflectionBoundaryTest,RegistryFilteringHandlerTest,RuntimeDecisionMatrixTest,SchedulingInterceptorTest,SearchTest,SocketSessionTest,SourceComponentScannerTest,StaticFileHandlerGeneratedOnlyMetadataTest,TriggerParameterResolverTest,UpcasterChainTest,ValidationUtilsTest,WebParamParameterResolverTest,WebUtilsTest,OnDemandExecutionTest,OnDemandSemanticParityTest,FluxzeroComponentRegistryTest,SourceClasspathRegistryParityTest -Dsurefire.failIfNoSpecifiedTests=false test`.
- Full `sdk-jvm` suite passed:
  `./mvnw -pl sdk-jvm -am test`.
- Downstream Java and Kotlin suites passed:
  `./mvnw -pl java-downstream-project -am test` and
  `./mvnw -pl kotlin-downstream-project -am test`.
- Manual benchmark sanity passed with a small scale:
  `./mvnw -pl java-downstream-project -DskipTests test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=io.fluxzero.downstream.benchmark.OnDemandComparisonBenchmark -Dfluxzero.benchmark.handlerCounts=1 -Dfluxzero.benchmark.flowIterations=5 -Dfluxzero.benchmark.consumers=1`.
  Reports were written under
  `java-downstream-project/target/on-demand-comparison/report.md` and
  `java-downstream-project/target/on-demand-comparison/report.json`.
- Full release-style verification passed:
  `./mvnw -B install`.
- Verification also hardened two edge cases: annotation metadata now ignores null attribute values before consumers call
  `firstValue`, and `ProxyServerTest` CORS checks now create their own proxy with the CORS property set before handler
  construction.

## Parked Browser Backlog

Status: parked until the active JVM phase is complete.

Browser is still strategically important, but it should consume the same generated app model and invocation shape after
the JVM has proved them.

Remaining browser work:

- [ ] Publish or package a browser-safe Fluxzero SDK plus browser `LocalClient` artifact that is compiled ahead of
  customer app code.
- [ ] Keep browser-local execution scoped to `LocalClient`; do not model this as a connection to a remote Fluxzero
  runtime.
- [ ] Productize the existing `teavm-javac` browser-source spike so customer Java production sources compile and
  execute on the fly in the browser without an external JVM, Maven, javac, TeaVM CLI, or server-side app compile.
- [ ] Add a JUnit-compatible browser test runner for common Java test semantics, with Fluxzero `TestFixture` available
  as a normal library/DSL inside those tests.
- [ ] Define browser-local consumers as local execution contexts, with Web Workers only as an optional backend.
- [ ] Support browser hot reload for edited, added, and removed source files by recompiling/lowering sources in the
  browser, diffing the registry, and swapping generated functions safely.

## Completed History

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

## Phase 2: Metadata Runtime Migration

Status: historical; final gaps are tracked in the active phase above.

Context: Slices 3 through 6 below describe completed foundation/runtime-migration work from the previous plan. They
are kept for history and evidence. The stricter final-state work is tracked in the active JVM phase above.

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

Status: [x] implemented for the previous browser conformance target.

Current caveat: this proved the browser path can execute registry-lowered metadata, but it is not the final
generated-only shared runtime parity target. That stricter target is parked in B1 through B4.

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
