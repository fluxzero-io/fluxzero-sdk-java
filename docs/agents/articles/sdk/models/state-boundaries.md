# Model state: persistence and protection boundaries

Use the Model APIs for ordinary atomic state transitions before building a separate persistence layer. Storage,
query visibility, authorization and protection of secrets are different decisions.

| Need | Existing capability | Boundary |
| --- | --- | --- |
| Create or change related state atomically | One Model action with multiple `@Apply` targets, including parent/child creation | External calls, schedules and unrelated repositories are not in that transaction |
| Load by identity | `Fluxzero.loadModel(id)` | Uses the Model's authoritative load path; identity knowledge is not authorization |
| Persist current state without Model events | `DOCUMENT` plus `eventPublication = NEVER` | No Model history or `previous()`; does not suppress incoming command/webrequest logs, results or application logs |
| Keep DOCUMENT state internal | No effective searchability (the default unless an ancestor activates it) | Identity reads remain possible; search and search-based parent/ancestor selection require effective searchability |
| Check concurrent changes | `RETRY` or `FAIL` and injected Models/Graphs | Use assertions within the action; a separate query/read followed by a write is not an atomic check |
| Delete from current state | Return `null` from `@Apply` | Does not erase retained history; follows configured parent ownership |
| Erase selected Model storage | `modelRepository().deleteModel(...)` or a confirmed deletion plan | Fences stale writes; global event logs, other copies and backups have separate lifecycles |

For example, current delivery status may need identity loads but neither replay nor unrestricted search:

```java
@Model(
    persistence = ModelPersistence.DOCUMENT,
    eventPublication = EventPublication.NEVER
)
record DeliveryProgress(@EntityId DeliveryProgressId progressId, boolean completed) {}
```

```kotlin
@Model(
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

Cascade preparation follows stored owning relationships with the commit's final parent changes overlaid, including
a final no-op after an earlier move. Event-sourced values stay lazy until validation, deadlines, alias release or
commit coordination consumes them; the first value read reconstructs the selected deletions in one shared batch.
DOCUMENT revisions are still verified eagerly at the pinned boundary. Other Apply targets do not expand their
unrelated branches. Migrate existing relationships when changing their stored ownership;
changing `@Parent` alone does not rewrite them. Current SDK parent constraints are checked before following older
owning edges, so a newly added deletion protection still applies. Runtime validation detects children attached or moved
after the pinned boundary and invokes the configured conflict handling.

Cascade preparation reads the first child frontier directly, then fetches complete stored descendant subtrees only
for reached cascade children. A deep unchanged cascade therefore needs two graph requests rather than one per level.
A final or pending move can expose a subtree outside that metadata and
require another request. Non-owning descendants may contribute metadata, but their values remain unloaded unless
another validation needs them. Only collections actually fetched and retained after overlays count as complete.

## Model cache tracking

Explicit historical Graph reads validate the selected head before reusing an exact cached EVENT_SOURCED revision.
Complete reconstructed revisions may seed the existing Model cache with its normal publication fencing and
newer-revision precedence; they do not establish current tracker coverage. Keep the original Graph boundary for
relationships and history. Incomplete historical heads retain their replay errors, and `cached = false` and
`disableAutomaticModelCaching()` also exclude this reuse. No cache entry is added per requested snapshot.
Historical-only reads do not start the update tracker: a remote erasure cannot be served by a fresh head-validated
read, but a dormant cached body can remain until cache eviction or clearing.

Current Model loads can use cache entries whose state has been validated against the model store. The SDK starts
boundary observation asynchronously and maintains one tracking loop per cache tracker; concurrent first loads do
not create additional loops or reset its cursor. A transient initial failure leaves current loads on the ordinary
store-validation path and allows a later load to retry initialization.

Standalone current reads of cached EVENT_SOURCED Models can reuse an exact canonical revision after a fresh
head read validates the original lookup spelling, primary-ID precedence, stored type, sequence and state index.
A bounded repository-local identity hint selects a cache candidate; it never proves an alias binding. Unknown
aliases and evicted bodies retain one ordinary payload request. A previously observed identity can be admitted
canonically only with a read fence registered before that request; the first unknown alias read does not publish
under a newly discovered canonical key. A live candidate that changed can require a head request followed by
reconstruction at that first response's boundary. Cache loss during validation uses that same boundary; erased
history fails rather than redirecting a canonical spelling to another owner. A pure cache hit establishes no new
tracker coverage. Historical, transactional/staged, DOCUMENT and cache-disabled paths retain their own contracts.

An unchanged cached parent does not prove that its child relationships are current. Eligible single-target writes
with built-in RETRY can reuse a cached event-sourced parent without an extra store read. If deletion or another
transactional dependency later needs a fresh boundary, the SDK first verifies the parent's exact revision; a changed
revision triggers eager reevaluation within the normal retry budget. Constrained child writes retain eager validation.

Update pages use an exclusive cursor and strictly increasing state indices. Empty heartbeats retain that cursor;
fixed or backwards-moving fixture clocks do not permit repeated indices. If tracking fails, current loads validate
against the store until a valid recovery page has been fully processed. Applications should not suppress ordering
failures or disable the cache to compensate. These guarantees do not change event-bound or historical read semantics.

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
