Use `fluxzero.modelRepository().diagnostics()` for an explicit dev/CI comparison of stored Model/event metadata with
that application's local catalog and serializer. Select `.forNamespace(namespace)` first for a different namespace.
No automatic imports, replay or handler registration occurs. A custom repository must opt in; its default method
throws `UnsupportedOperationException`.

```java
var limits = new ModelDiagnostics.Limits(32, 8, 256 * 1024, 3);
var diagnostics = fluxzero.modelRepository().diagnostics();
var report = diagnostics.inspectStreams(List.of("project-1"), ModelReadBoundary.current(), limits);
var graph = diagnostics.inspectGraph("project-1", ModelReadBoundary.at(report.stateIndex()), limits);
```

```kotlin
val limits = ModelDiagnostics.Limits(32, 8, 256 * 1024L, 3)
val diagnostics = fluxzero.modelRepository().diagnostics()
val report = diagnostics.inspectStreams(listOf("project-1"), ModelReadBoundary.current(), limits)
val graph = diagnostics.inspectGraph("project-1", ModelReadBoundary.at(report.stateIndex()), limits)
```

`ModelDiagnostics` is in `io.fluxzero.sdk.persisting.repository`; `ModelReadBoundary` is in
`io.fluxzero.common.api.modeling`. One call makes one request; no pagination follows. Bounds are 256 Models,
1,024 events each, 4,096 total memberships, positive byte budget at most 16 MiB and depth 0–64. Defaults are
64/16/1 MiB/4. Existing oversized events/packed blocks can exceed the soft byte budget. Packed envelope metadata is
expanded, but payload deserializers and structural upcasters do not run. Class lookup may initialize local classes;
custom lookup implementations retain their own behavior. Reports retain no payloads.

Inspect `headPresent`, stored `modelType`, nullable `localModelType`, `historyComplete`, `eventsComplete`, `stateIndex`
and `exactBoundary` separately. `eventsComplete` requires all sequences zero through the head and retained history;
it is not a replay guarantee. `graphSample` never claims full closure. `observedTypes` contains original
identifier/revision/format and `TypeInspection`: `KNOWN`, `UNKNOWN` or `UNAVAILABLE`, alias-resolved identifier and
local revision. An unknown input can still upcast into a known type; equal revisions do not prove compatibility.
`Serializer.inspectType` exposes that metadata-only lookup, defaulting to unavailable for custom serializers.

`ModelReadException` remains an `EventSourcingException`. Its kind distinguishes missing Model contracts, serialized
types or replay handlers from invalid stored data, catalog failures, application replay failures and ambiguous decoding
failures. Context includes operation, Model/root when known, logical/local Model type, original serialized type/revision,
registration and failing membership state index. `unresolvedType`/`unresolvedRevision` identify the unknown post-upcast
or nested-envelope representation. Null means unavailable. Preserve the cause; it may contain application/decoder text,
although the diagnostic message itself only adds bounded identifiers. Not every transport/verification/storage failure
is converted. Corrupt-syntax attribution requires a failure-only scan of original JSON at most 1 MiB using the built-in Jackson serializer’s configured parser; custom
serializer/mapper implementations remain ambiguous. Application parser
errors or larger/ambiguous inputs stay decoding failures. Optional bad-snapshot deletion and replay remain unchanged.

Include shared indexed Model contracts, aliases and historical event/state upcasters as appropriate; qualify actual
cold replay to exercise application handlers. Do not suppress unknown history or switch to documents implicitly.
Verified current-state reads require their maintained source, and scoped Graph partiality/ownership remains explicit.
Use existing synchronous and asynchronous `TestFixture` workflows. `givenModelEvents` creates synthetic current-SDK
commits; it is not a retained-store import or a cold-reader proof. A separate old writer and fresh candidate reader in
the same retained namespace qualify the historical boundary. Metadata inspection alone proves none of replay logic,
snapshot compatibility, complete Graph closure or a persistent-service upgrade.
