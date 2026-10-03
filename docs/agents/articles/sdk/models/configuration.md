# Model configuration

`@Model` does not activate search by default. Set `searchable = true` when that Model should independently start a
searchable scope. Persistence, search activation and optional Graph precomputation are independent. Searchable roots
include composed descendants by default; their local `searchable = false` does not veto inherited activation.
`Model.searchable` starts a searchable scope; `SearchSettings.includeDescendants` limits that root's scope;
`Parent.propagateSearch` gates propagation across each composed edge. A non-empty `pathInParent` opts an edge into search
composition; a pathless parent remains navigable but does not inherit search activation. Use
`@Parent(propagateSearch = false)` to block inherited activation through one composed edge and its subtree. The child can
still independently activate its own search Graph.

Graph materialization is configured separately with `graphProjection.mode`. Its default is `NONE`: searchable Models
still maintain their indexed node documents and Graph queries compose those nodes live, but Fluxzero does not store a
complete composed Graph document. Use `ASYNC` or `AWAIT` only when that complete Graph should also be materialized.

Storage choices do not establish privacy. DOCUMENT plus effective `eventPublication = NEVER` supports
eventless current state, not history or `previous()`. Non-searchable documents still support identity/relationship
reads, and `@ProtectData` does not protect values copied into Model state. Read the linked **Model state:
persistence and protection boundaries** article before using these controls for sensitive data.

**Want to use `previous()`? Keep `EVENT_SOURCED` enabled** (event sourcing is the default persistence strategy). `DOCUMENT` alone
stores only the current document, not previous versions. Adding `DOCUMENT` to event sourcing preserves history;
replacing event sourcing with `DOCUMENT` removes that guarantee. Cache depth and snapshots are optimizations, not
substitutes for a durable event history. Every historical Graph node whose value you inspect needs that history.

The default is event sourcing without a direct document or periodic snapshots. Add storage only for a concrete
read requirement. Use the central matrix at `/docs/sdk/entities/graph-search`.
Node and relationship queries use the same canonical searchable source. A searchable node can be selected by ancestor
ID even when that ancestor is not searchable; filtering on ancestor content requires its searchable source.

Important settings:

- `name`: durable logical Model type name; defaults to the concrete class's simple name. Keep an explicit value stable
  across Java class/package renames. It is separate from serializer payload types and has no aliases or FQN fallback.
  `fluxzero.model.namePrefix` is prepended literally for applications sharing a namespace (`billing` + `Invoice` =
  `billingInvoice`). Changing either value after data exists requires an application-managed data transition.
- `persistence`: selects a non-empty set of durable representations:
  - `{EVENT_SOURCED}` (default): reconstruct from Model events; search activation may separately maintain a canonical indexed node.
  - `{EVENT_SOURCED, DOCUMENT}`: reconstruct from events and maintain internal current state; searchability controls its indexes.
  - `{DOCUMENT}`: load authoritative state from the canonical document; it can be entirely internal and unsearchable.
- `ignoreUnknownEvents`: deliberately tolerates unhandled stored events during event-sourced reconstruction.
- `searchable`: independent search activation, defaulting to `false`. False means no independent activation, while a
  searchable ancestor's composed scope can still include this type. This never changes Model load authority.
- `searchSettings`: per-node collection and timestamp paths plus `includeDescendants` (default true). Settings alone
  do not activate search. Parent paths describe composition only. The default canonical collection preserves the
  existing internal source; do not migrate an old public DocumentProjection collection into this setting blindly.
- Source schema rewrites must retain identity, business state and head proof. Ordinary typed Model document handlers
  observe the same canonical node and use the guarded schema rewrite route. Explicit `source = MODEL_STATE` handlers also permit
  internal state maintenance for a non-searchable DOCUMENT Model. Use normal Model commands for business changes.
- `eventPublication`: controls whether unchanged transitions create an event.
- `publicationStrategy`: `DEFAULT`, `STORE_AND_PUBLISH`, `STORE_ONLY` or `PUBLISH_ONLY`.
- `snapshotPeriod` and `maxSnapshotCount`: event-sourcing optimizations. A positive `snapshotPeriod` enables
  periodic snapshots. Positive `maxSnapshotCount` values bound retention, zero keeps one, and **any negative value**
  retains all periodic snapshots. Explicit physical erasure still removes them; event-history retention is separate.
  Upgrade the Runtime before enabling negative counts; older Runtime versions reject them. Existing snapshots are
  retained from activation onward; snapshots already removed by an earlier limit are not recovered.
- `checkpointPeriod`: bounds repeated replay work within one reconstruction session.
- `cached` and `cachingDepth`: current and previous revisions retained in the SDK cache.
- `conflictPolicy`: `ACCEPT`, `RETRY`, `FAIL` or inherited `DEFAULT` for concurrent writes.
- `commitPolicy`: controls commit timing and completion-phase concurrency; normally keep `DEFAULT`.
- `automaticHandling`: opt out when an explicit command handler must call `Fluxzero.assertAndApply`.
- `graphProjection.mode`: independent from search activation. `NONE` (default) stores no complete Graph document and
  composes indexed nodes live when read; it does not disable Graph search. `ASYNC` stores a composed Graph; `AWAIT`
  also waits for affected projections. Completion/waiting configuration alone never activates materialization in NONE.
- `graphProjection.collection` and `pathOverrides`: optional stored Graph collection and replacements for canonical
  composition paths. Path overrides also apply to live queries. The default collection is the logical Model name plus
  `-graphs`, or the explicitly configured node collection plus `-graphs`.

Persistence does not control event storage or publication. Those remain owned by `eventPublication`,
`publicationStrategy` and per-apply overrides. Event-sourcing-only options such as `ignoreUnknownEvents`, snapshots
and replay checkpoints are rejected on DOCUMENT-only Models.

In Kotlin, annotation arrays use `[ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT]` and nested annotations
omit `@`, for example `searchSettings = SearchSettings(includeDescendants = false)`.

## Historical Graph strictness

`fluxzero.model.graph.strict` (`FLUXZERO_MODEL_GRAPH_STRICT`) defaults to false, independently of
`fluxzero.defaults.version`. Ordinary historical Graphs use current DOCUMENT-only values when their historical
revision is unavailable. Set the property to true for strict historical reads, or use `graph.strict(true)` for one
view. `graph.strict(false)` restores ordinary reads even under a strict application default. Historical absence and
relationships remain pinned; mutations, assertions and replay always stay strict. Read
`/docs/sdk/models/temporal-graphs` for Runtime compatibility and the complete contract.

## Document handler scope

`@HandleDocument` infers the searchable node from `Task` and logical root-plus-descendants scope from `Graph<Task>`.
Use `source = DocumentSource.MODEL_STATE` with a Model value for internal schema maintenance, including non-searchable
DOCUMENT state. There is no fallback and no implicit activation. Ancestor-only content changes do not trigger a Task
Graph; `@GraphProperty` adds no subscriptions. Moves update old and new ancestor Graphs.

NONE retains small durable root update markers and hydrates indexed nodes on read; `previous()` is unavailable.
Returning the unchanged injected Graph migrates evolved canonical nodes in every mode. Each write upcasts the
verified current source and preserves business state/head/history. Only affected roots receive durable projection
updates or NONE markers. Revision-only registration preserves rebuild cursors; composition/type-scope changes still
require rebuilding. Use `graphMigration = GraphMigrationTarget.PROJECTION` to migrate only an existing ASYNC/AWAIT
composition; this option is observational at NONE. Handler completion confirms node storage, while projections follow
asynchronously; AWAIT applies to ordinary Model commits.
