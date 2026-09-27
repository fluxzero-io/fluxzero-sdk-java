# Model state: persistence and protection boundaries

Use the Model APIs for ordinary atomic state transitions before building a separate persistence layer. Storage,
query visibility, authorization and protection of secrets are different decisions.

| Need | Existing capability | Boundary |
| --- | --- | --- |
| Create or change related state atomically | One Model action with multiple `@Apply` targets, including parent/child creation | External calls, schedules and unrelated repositories are not in that transaction |
| Load by identity | `Fluxzero.loadModel(id)` | Uses the Model's authoritative load path; identity knowledge is not authorization |
| Persist current state without Model events | `DOCUMENT` plus `eventPublication = NEVER` | No Model history or `previous()`; does not suppress incoming command/webrequest logs, results or application logs |
| Keep DOCUMENT state internal | `searchable = false`, outside a searchable ancestor scope | Identity reads remain possible; search and search-based parent/ancestor selection require effective searchability |
| Check concurrent changes | `RETRY` or `FAIL` and injected Models/Graphs | Use assertions within the action; a separate query/read followed by a write is not an atomic check |
| Delete from current state | Return `null` from `@Apply` | Does not erase retained history; follows configured parent ownership |
| Erase selected Model storage | `modelRepository().deleteModel(...)` or a confirmed deletion plan | Fences stale writes; global event logs, other copies and backups have separate lifecycles |

For example, current delivery status may need identity loads but neither replay nor unrestricted search:

```java
@Model(
    searchable = false,
    persistence = ModelPersistence.DOCUMENT,
    eventPublication = EventPublication.NEVER
)
record DeliveryProgress(@EntityId DeliveryProgressId progressId, boolean completed) {}
```

```kotlin
@Model(
    searchable = false,
    persistence = [ModelPersistence.DOCUMENT],
    eventPublication = EventPublication.NEVER
)
data class DeliveryProgress(@EntityId val progressId: DeliveryProgressId, val completed: Boolean)
```

Keep event sourcing when historical values or event-driven reactions are needed. An EVENT_SOURCED Model cannot
change state without storing the corresponding event. With DOCUMENT, `NEVER` must be the effective setting:
more specific apply/message configuration can override broader defaults. It suppresses the applied update's Model
event; it is not a general-purpose “do not log this request” switch.

DOCUMENT-only mutations retain document authority without replaying published history. Simple single-target writes
with built-in RETRY defer the extra namespace head read until an additional transactional dependency is requested.
That read verifies the original target revision before pinning; a mismatch restarts the whole evaluation within
the normal retry budget. More complex contexts verify eagerly, retrying head/document preparation races at most
eight times before user code runs. Once pinned, later Graph reads share the same boundary; an unavailable historical
document value fails explicitly rather than moving the snapshot. Keep EVENT_SOURCED for historical reconstruction.

Deletion/cascade preparation that loses a pinned DOCUMENT version fails with `ModelCommitConflictException` before
submission, without reevaluation even under RETRY or provisional batch dependencies. Its `readConflict` contains the
original commit ID, unavailable canonical Model ID and pinned read index; `result` is null because no storage response
exists. Ordinary storage conflicts instead have `result` and no `readConflict`, retaining normal conflict handling.

## Non-searchable is not private

A DOCUMENT Model keeps one internal current document. Searchability adds indexes to that canonical representation;
node queries, related-content predicates and Graph composition share it. There is no separately editable public Model
copy. Use Model operations for business changes and the guarded schema migration route for serializer upcasts.

A false value on the node means no independent search request. A composed searchable ancestor can still include that
node. Do not use collection names or searchability as authorization: another application with direct access to the
store can still read stored content. Enforce access through trusted handlers and namespace/access controls; an
injected Graph is not itself an authorization check.

`@ProtectData` redacts annotated fields in stored messages and restores retained values for handling. Protection on
an input does **not** carry over to values copied into Model state, documents, snapshots, results or application logs.
A result payload can declare its own protected fields, handled by the normal RESULT-dispatch interceptor; this is not
a blanket guarantee for WebResponse bodies or native HTTP. There is no general encryption-at-rest, KMS/key-rotation,
secret-store or backup guarantee. Verify each required protection separately; custom serialization is an extension
point, not automatic key management.

## Deletion, versions and rollout

Logical deletion is not physical erasure. Physical Model erasure removes the selected stream, snapshots, current
sources/projections and cache state, and fences delayed writes from restoring erased data. Descendant erasure requires
its own explicitly selected scope/plan. Shared event payloads can remain while another surviving Model references
them. The global event log is not removed by Model erasure. Copies in other stores, logs, exports and backups require
their own retention/deletion and restore policy.

Use a matching SDK and service implementation for the storage/wire capabilities you enable. Older separate public Model projections are not authoritative canonical nodes. Preserve DOCUMENT-only state,
choose the existing canonical collection deliberately and rebuild search indexes before changing writers. Recompile
Model contracts and coordinate writer rollout; do not let old writers restore obsolete projection definitions.

## Historical event blocks

Compact Model-event reads accept mixed historical MessagePack and binary records, including compressed blocks.
The SDK preserves event indices and requested state membership; applications need no custom format conversion.
Older SDK readers still require a Runtime that returns a representation they support.

The SDK advertises `Fluxzero-Supports-Mixed-Model-Event-Blocks: true` on every WebSocket connection.
A compatible Runtime can pass mixed blocks directly to this reader; clients without this capability receive
compatible responses prepared by the Runtime. Older runtimes may ignore the optional header.
