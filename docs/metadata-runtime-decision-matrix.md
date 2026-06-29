# Fluxzero Metadata Runtime Decision Matrix

This matrix tracks where JVM runtime decisions get their Fluxzero app semantics while the runtime moves from
reflection-backed compatibility to generated metadata and generated invocation.

Allowed source values:

- `Registry metadata`: `ComponentRegistry`, descriptors, generated registry resources, or active source registry.
- `Generated invocation plan`: generated executable/property/binding plans used for app-level invocation and access.
- `Allowed JVM backend`: Java-specific mechanics that may use reflection behind `JvmBackendAccess`.
- `Hybrid`: registry metadata already provides the semantic decision, but a JVM backend still performs Java mechanics.

| ID | Runtime decision | Current source | Final source | Boundary | Closure |
| --- | --- | --- | --- | --- | --- |
| handler.registration | Which component routes are registered for command/query/event/notification/error/metrics/result/schedule/document/custom/web messages | Registry metadata | Registry metadata | `HandlerRoute` descriptors and `ComponentCapability.HANDLER` | Done |
| handler.discovery | Which executable is a handler method or constructor | Generated invocation plan | Generated invocation plan | Generated-only registry matcher requires generated invocations; explicit JVM compatibility mode keeps the reflection fallback | Done |
| handler.annotation-kind | Which concrete handler annotation applies, including composed/meta annotations | Registry metadata | Registry metadata | `MetadataExecutableAnnotationResolver`, `RegistryExecutableViews`, and metadata annotation projection | Done |
| handler.disabled-passive-expiry | Disabled, passive, and skipExpiredRequests behavior | Registry metadata | Registry metadata | `HandlerRoute` and metadata annotation projection | Done |
| handler.payload-filter | Payload type names, likely payload parameter, and allowedClasses filtering | Registry metadata | Registry metadata | `HandlerRoute.payloadTypeNames` and `allowedClassNames` | Done |
| handler.local-tracked | Local/tracked semantics and method/type/package precedence | Registry metadata | Registry metadata | `HandlerRoute.local` and `tracked` | Done |
| handler.consumer | Consumer name/group/segment/batch/passive configuration | Registry metadata | Registry metadata | `ConsumerDescriptor` from package/type metadata | Done |
| handler.parameter-binding | Binding payload, message, metadata, trigger, user, entity, web, and custom parameters | Generated invocation plan | Generated invocation plan | `ExecutableView`/`ParameterView` metadata plus generated invocation-backed resolver mechanics | Done |
| handler.invocation | Calling handler methods and constructors | Generated invocation plan | Generated invocation plan | Registry-backed matcher exposes `ExecutableView` and generated invocations; browser conformance scans customer source in-browser and emits direct source calls for source-accessible handlers; reflection-shaped matcher remains an explicit JVM compatibility fallback | Done |
| tracking.gateway-locality | Local handler and self-tracking gateway decisions | Registry metadata | Registry metadata | `ClientUtils` metadata lookups | Done |
| routing.message-key | Routing key from payload type/property/metadata | Registry metadata | Registry metadata | `HasMessage` and routing interceptor metadata lookup | Done |
| timeout.request | Request timeout metadata from payload type | Registry metadata | Registry metadata | `DefaultGenericGateway` and `SocketSession` metadata lookup | Done |
| validation.validate-with | `@ValidateWith` and custom validator selection | Registry metadata | Registry metadata | `ValidationUtils` metadata lookup | Done |
| validation.auth-policy | Requires/forbids user/role policy metadata | Registry metadata | Registry metadata | `ValidationUtils` metadata lookup | Done |
| validation.jakarta-elements | Bean/executable/type-use constraints, `@Valid`, groups, and conversions | Registry metadata | Registry metadata | Generated element and `TypeUseDescriptor` metadata consumed by Jakarta bridge | Done |
| validation.jakarta-provider | Constraint validator construction, composed constraints, provider metadata views, and value extractors | Allowed JVM backend | Allowed JVM backend | `JakartaValidationBackend` | Platform backend |
| policy.data-protection | Protect/drop data policy on type/property/method | Registry metadata | Registry metadata | `DataProtectionInterceptor` metadata lookup | Done |
| policy.content-filter | Filter content policy on handler/type/package/property | Registry metadata | Registry metadata | `ContentFilterInterceptor` metadata lookup | Done |
| modeling.stateful | Stateful handler type and repository behavior | Registry metadata | Registry metadata | `DefaultHandlerFactory` and repository metadata lookup | Done |
| modeling.association-member | Association, member, routing-key, and entity-id metadata | Registry metadata | Registry metadata | `HandlerAssociations`, `StatefulHandler`, repositories | Done |
| modeling.apply-assert | `@Apply`, `@AssertLegal`, and trigger filters | Generated invocation plan | Generated invocation plan | Metadata filters plus generated invocation backend; explicit JVM compatibility fallback is outside generated-only mode | Done |
| modeling.property-access | Reading/writing JVM object properties and constructing entities | Generated invocation plan | Generated invocation plan | `GeneratedPropertyAccesses` and generated constructor invocations; explicit JVM compatibility property backend is outside generated-only mode | Done |
| search.document-indexing | Searchable collections, document ids, revisions, facets, sorting, includes/excludes | Registry metadata | Registry metadata | Search/document metadata lookups | Done |
| casting.route-discovery | `@Upcast`, `@Downcast`, and cast parameter discovery | Registry metadata | Registry metadata | Caster executable descriptors | Done |
| casting.invocation | Calling upcaster/downcaster methods | Generated invocation plan | Generated invocation plan | Caster invocation uses registered generated executable invocations in generated-only mode | Done |
| serialization.registered-types | `@RegisterType` roots, contains filters, and candidate type names | Registry metadata | Registry metadata | Registry registered type descriptors | Done |
| serialization.payload-type | Runtime payload class names and serializer-specific type handling | Allowed JVM backend | Allowed JVM backend | JVM serializer/type backend | Platform backend |
| web.route | Web path/method/autoHead/autoOptions/static/socket/API-doc route metadata | Registry metadata | Registry metadata | Web route descriptors and route registry | Done |
| web.parameter-binding | Path/query/header/cookie/form/body parameter metadata and dynamic path roots | Generated invocation plan | Generated invocation plan | `ExecutableView`/`ParameterView` metadata plus web parameter/body binding descriptors; browser conformance binds parameters from in-browser scanned source into generated direct web calls; JVM keeps web context mechanics behind the platform backend | Done |
| web.response-mapping | Web response mapper selection and API-doc response metadata | Registry metadata | Registry metadata | Registry metadata plus JVM mapper execution | Done |
| schedule.periodic | Periodic schedule metadata and handler schedule registration | Registry metadata | Registry metadata | `SchedulingInterceptor` and `MessageScheduler` metadata lookup | Done |
| schedule.payload-instantiation | Constructing schedule payloads for JVM periodic handlers | Generated invocation plan | Generated invocation plan | Generated-only periodic startup installs and uses generated constructors; explicit JVM compatibility mode keeps constructor fallback | Done |
| extension.builder-components | Builder-facing user components such as interceptors, decorators, mappers, validators, serializers, providers, caches, schedulers, and property sources | Registry metadata | Registry metadata | Component capabilities and builder registry merge | Done |
| source.lifecycle | Add/edit/delete source components under `src/.../fluxzero` | Registry metadata | Registry metadata | `OnDemandExecution.refresh()` diff/register lifecycle | Done |
