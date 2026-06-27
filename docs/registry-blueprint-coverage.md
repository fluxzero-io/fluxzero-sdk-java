# Fluxzero Registry Blueprint Coverage

Temporary coverage gate for the build-time/source-time Fluxzero app model. "Yes" means the registry can discover and
serialize the metadata without making it the final runtime implementation for that feature.

| Area | Source scanner | Build-time processor | Classpath fallback | JSON artifact | Markdown blueprint | Status |
| --- | --- | --- | --- | --- | --- | --- |
| Package metadata: explicit package-info, ancestor package-info and implicit component packages | Yes | Yes | Yes | Yes | Yes | Yes |
| Component identity: kind, package, class, source file, package-info source and direct supertypes | Yes | Yes | Yes | Yes | Yes | Yes |
| Raw annotations: package, type, property, record component, method, constructor and parameter annotations with string, boolean, class, enum and array values | Yes | Yes | Yes | Yes | Yes | Yes |
| Property metadata: field/record component name, erased type, generic type and annotations | Yes | Yes | Yes | Yes | Yes | Yes |
| Executables: methods, constructors, executable name, return type, parameter names and parameter types | Yes | Yes | Yes | Yes | Yes | Yes |
| Handler routes: command, event, notification, query, result, schedule, error, metrics, web request, web response, document and custom | Yes | Yes | Yes | Yes | Yes | Yes |
| Handler route semantics: disabled, passive, skipExpiredRequests, allowedClasses and likely payload type names | Yes | Yes | Yes | Yes | Yes | Yes |
| Dispatch semantics: self-handling payloads, TrackSelf, LocalHandler and package/type/method precedence for local/tracked routes | Yes | Yes | Yes | Yes | Yes | Yes |
| Consumer metadata: package and type @Consumer attributes and effective component consumer | Yes | Yes | Yes | Yes | Yes | Yes |
| Web metadata: path composition, HTTP methods, autoHead, autoOptions, web parameter annotations and socket handler routes | Yes | Yes | Yes | Yes | Yes | Yes |
| Registered types: package/type @RegisterType root, contains filters and discovered candidate type names | Yes | Yes | Yes | Yes | Yes | Yes |
| Policy metadata: auth annotations, validation annotations, routing keys, timeouts, data protection and content filtering | Yes | Yes | Yes | Yes | Yes | Yes |
| Modeling metadata: TrackSelf, Stateful, Association, Aggregate, EntityId, Apply and InterceptApply | Yes | Yes | Yes | Yes | Yes | Yes |
| Search/document metadata: Searchable, SearchInclude, SearchExclude, Facet, Sortable, Revision and document handler routes | Yes | Yes | Yes | Yes | Yes | Yes |
| Casting/serialization metadata: RegisterType, FilterContent, Cast, Upcast and Downcast annotations | Yes | Yes | Yes | Yes | Yes | Yes |
| Builder extension components: dispatch, batch and handler interceptors, decorators, response mappers, validators, parameter resolvers, serializers, document serializers, correlation providers, identity providers, user providers, caches, task schedulers and property sources | Yes | Yes | Yes | Yes | Yes | Yes |
| Runtime loading contract: generated registry resources are loaded at startup; Markdown blueprint writes only when property/env configuration is present | Yes | Yes | Yes | Yes | Yes | Yes |

## Scope

This file is about the registry blueprint layer only. It does not claim browser runtime parity, JVM runtime rewiring, or
complete policy enforcement from metadata. Those are downstream consumers of the same app model.

## Current Proof

| Proof | Status |
| --- | --- |
| Source-only scan creates no class files and no on-demand compile cache output | Yes |
| Source scanner covers all concrete Fluxzero handler annotations | Yes |
| Source scanner covers property and record component annotations used by modeling, data protection and associations | Yes |
| Source scanner resolves common Fluxzero simple-name annotations for wildcard-import style code | Yes |
| Classpath scanner covers all concrete Fluxzero handler annotations | Yes |
| Classpath scanner covers property and record component annotations used by modeling, data protection and associations | Yes |
| Javac registry processor covers all concrete Fluxzero handler annotations | Yes |
| Javac registry processor covers property and record component annotations used by modeling, data protection and associations | Yes |
| Registry JSON round-trips all descriptor records | Yes |
| Blueprint output uses simple names, local source paths, implicit packages, consumers, registered types and Mermaid graphs | Yes |
