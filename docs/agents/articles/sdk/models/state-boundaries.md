# Model state: persistence and protection boundaries

Use the Model APIs for ordinary atomic state transitions before building a separate persistence layer. Storage,
query visibility, authorization and protection of secrets are different decisions.

| Need | Existing capability | Boundary |
| --- | --- | --- |
| Create or change related state atomically | One Model action with multiple `@Apply` targets, including parent/child creation | External calls, schedules and unrelated repositories are not in that transaction |
| Load by identity | `Fluxzero.loadModel(id)` | Uses the Model's authoritative load path; identity knowledge is not authorization |
| Persist current state without Model events | `DOCUMENT` plus `eventPublication = NEVER` | No Model history or `previous()`; does not suppress incoming command/webrequest logs, results or application logs |
| Exclude unrestricted typed Model search | `@DocumentProjection(searchable = false)` | Exact parent/ancestor searches and identity reads remain possible; not a security boundary |
| Check concurrent changes | `RETRY` or `FAIL` and injected Models/Graphs | Use assertions within the action; a separate query/read followed by a write is not an atomic check |
| Delete from current state | Return `null` from `@Apply` | Does not erase retained history; follows configured parent ownership |
| Erase selected Model storage | `modelRepository().deleteModel(...)` or a confirmed deletion plan | Fences stale writes; global event logs, other copies and backups have separate lifecycles |

For example, current delivery status may need identity loads but neither replay nor unrestricted search:

```java
@Model(persistence = ModelPersistence.DOCUMENT,
       eventPublication = EventPublication.NEVER,
       document = @DocumentProjection(searchable = false))
record DeliveryProgress(@EntityId DeliveryProgressId progressId, boolean completed) {}
```

```kotlin
@Model(persistence = [ModelPersistence.DOCUMENT],
       eventPublication = EventPublication.NEVER,
       document = DocumentProjection(searchable = false))
data class DeliveryProgress(@EntityId val progressId: DeliveryProgressId, val completed: Boolean)
```

Keep event sourcing when historical values or event-driven reactions are needed. An EVENT_SOURCED Model cannot
change state without storing the corresponding event. With DOCUMENT, `NEVER` must be the effective setting:
more specific apply/message configuration can override broader defaults. It suppresses the applied update's Model
event; it is not a general-purpose “do not log this request” switch.

## Non-searchable is not private

The internal current Model source and the optional public DOCUMENT projection are independent stored roles.
DOCUMENT-only loads use the internal source; direct typed searches use the public projection. Model commits maintain
both. Public document indexing or `@HandleDocument(documentClass = ...)` does not change authoritative Model state.

A Model used in Graph composition may require its own internal content indexes even when its public projection is
not searchable. `whereChild`/`whereDescendant` predicates use that internal source; exact parent/ancestor searches
can still retrieve a non-searchable public projection. Do not use collection names or `searchable = false` to hide
values from another application that can read the same store. Enforce access through trusted handlers and the
deployment's actual namespace/access controls; an injected Graph is not itself an authorization check.

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

Use a matching SDK and service implementation for the storage/wire capabilities you enable. Older shared
DOCUMENT storage is not automatically equivalent to independent internal sources and public projections:
preserve DOCUMENT-only state and follow the explicit migration/rebuild procedure before switching writers.
Do not let a mixed set of unsupported writers replace newly separated state or silently drop conflict dependencies.
