# Fluxzero Metadata Runtime Decision Matrix

This matrix tracks where JVM runtime decisions get their Fluxzero app semantics while the runtime moves from
reflection-backed compatibility to generated metadata and generated invocation.

Allowed source values:

- `Registry metadata`: `ComponentRegistry`, descriptors, generated registry resources, or active source registry.
- `Generated invocation plan`: generated executable/property/binding plans to be introduced in the next slice.
- `Allowed JVM backend`: Java-specific mechanics that may use reflection behind `JvmBackendAccess`.
- `Hybrid`: registry metadata already provides the semantic decision, but a JVM backend still performs Java mechanics.

| ID | Runtime decision | Current source | Final source | Boundary |
| --- | --- | --- | --- | --- |
| handler.registration | Which component routes are registered for command/query/event/notification/error/metrics/result/schedule/document/custom/web messages | Registry metadata | Registry metadata | `HandlerRoute` descriptors and `ComponentCapability.HANDLER` |
| handler.discovery | Which executable is a handler method or constructor | Hybrid | Generated invocation plan | Registry-backed matcher when generated invocations are registered |
| handler.annotation-kind | Which concrete handler annotation applies, including composed/meta annotations | Registry metadata | Registry metadata | `MetadataExecutableAnnotationResolver` |
| handler.disabled-passive-expiry | Disabled, passive, and skipExpiredRequests behavior | Registry metadata | Registry metadata | `HandlerRoute` and metadata annotation projection |
| handler.payload-filter | Payload type names, likely payload parameter, and allowedClasses filtering | Registry metadata | Registry metadata | `HandlerRoute.payloadTypeNames` and `allowedClassNames` |
| handler.local-tracked | Local/tracked semantics and method/type/package precedence | Registry metadata | Registry metadata | `HandlerRoute.local` and `tracked` |
| handler.consumer | Consumer name/group/segment/batch/passive configuration | Registry metadata | Registry metadata | `ConsumerDescriptor` from package/type metadata |
| handler.parameter-binding | Binding payload, message, metadata, trigger, user, entity, web, and custom parameters | Hybrid | Generated invocation plan | Parameter metadata plus JVM/web parameter resolver mechanics |
| handler.invocation | Calling handler methods and constructors | Hybrid | Generated invocation plan | Registry-backed matcher can expose only `ExecutableView`; reflection-shaped matcher remains fallback |
| tracking.gateway-locality | Local handler and self-tracking gateway decisions | Registry metadata | Registry metadata | `ClientUtils` metadata lookups |
| routing.message-key | Routing key from payload type/property/metadata | Registry metadata | Registry metadata | `HasMessage` and routing interceptor metadata lookup |
| timeout.request | Request timeout metadata from payload type | Registry metadata | Registry metadata | `DefaultGenericGateway` and `SocketSession` metadata lookup |
| validation.validate-with | `@ValidateWith` and custom validator selection | Registry metadata | Registry metadata | `ValidationUtils` metadata lookup |
| validation.auth-policy | Requires/forbids user/role policy metadata | Registry metadata | Registry metadata | `ValidationUtils` metadata lookup |
| validation.jakarta-elements | Bean/executable/type-use constraints, `@Valid`, groups, and conversions | Registry metadata | Registry metadata | Generated element and `TypeUseDescriptor` metadata consumed by Jakarta bridge |
| validation.jakarta-provider | Constraint validator construction, composed constraints, provider metadata views, and value extractors | Allowed JVM backend | Allowed JVM backend | `JakartaValidationBackend` |
| policy.data-protection | Protect/drop data policy on type/property/method | Registry metadata | Registry metadata | `DataProtectionInterceptor` metadata lookup |
| policy.content-filter | Filter content policy on handler/type/package/property | Registry metadata | Registry metadata | `ContentFilterInterceptor` metadata lookup |
| modeling.stateful | Stateful handler type and repository behavior | Registry metadata | Registry metadata | `DefaultHandlerFactory` and repository metadata lookup |
| modeling.association-member | Association, member, routing-key, and entity-id metadata | Registry metadata | Registry metadata | `HandlerAssociations`, `StatefulHandler`, repositories |
| modeling.apply-assert | `@Apply`, `@AssertLegal`, and trigger filters | Hybrid | Generated invocation plan | Metadata filters now, generated invocation in Slice 3 |
| modeling.property-access | Reading/writing JVM object properties and constructing entities | Allowed JVM backend | Generated invocation plan | `AnnotatedEntityHolder` and property backend until Slice 3 |
| search.document-indexing | Searchable collections, document ids, revisions, facets, sorting, includes/excludes | Registry metadata | Registry metadata | Search/document metadata lookups |
| casting.route-discovery | `@Upcast`, `@Downcast`, and cast parameter discovery | Registry metadata | Registry metadata | Caster executable descriptors |
| casting.invocation | Calling upcaster/downcaster methods | Hybrid | Generated invocation plan | Caster invocation uses the shared JVM invocation backend, which now prefers generated invokers when present |
| serialization.registered-types | `@RegisterType` roots, contains filters, and candidate type names | Registry metadata | Registry metadata | Registry registered type descriptors |
| serialization.payload-type | Runtime payload class names and serializer-specific type handling | Allowed JVM backend | Allowed JVM backend | JVM serializer/type backend |
| web.route | Web path/method/autoHead/autoOptions/static/socket/API-doc route metadata | Registry metadata | Registry metadata | Web route descriptors and route registry |
| web.parameter-binding | Path/query/header/cookie/form/body parameter metadata and dynamic path roots | Hybrid | Generated invocation plan | Web parameter metadata plus JVM/web context mechanics |
| web.response-mapping | Web response mapper selection and API-doc response metadata | Registry metadata | Registry metadata | Registry metadata plus JVM mapper execution |
| schedule.periodic | Periodic schedule metadata and handler schedule registration | Registry metadata | Registry metadata | `SchedulingInterceptor` and `MessageScheduler` metadata lookup |
| schedule.payload-instantiation | Constructing schedule payloads for JVM periodic handlers | Allowed JVM backend | Generated invocation plan | JVM constructor backend until Slice 3 |
| extension.builder-components | Builder-facing user components such as interceptors, decorators, mappers, validators, serializers, providers, caches, schedulers, and property sources | Registry metadata | Registry metadata | Component capabilities and builder registry merge |
| source.lifecycle | Add/edit/delete source components under `src/.../fluxzero` | Registry metadata | Registry metadata | `OnDemandExecution.refresh()` diff/register lifecycle |
