# Model configuration

Start with plain `@Model`. Introduce an option only for a concrete application requirement; do not copy
explicit persistence, caching or publication settings into ordinary Model examples.
For a searchable root Graph, prefer `materializeGraph = true` with `searchGraph` when that is the desired view.

Storage choices do not establish privacy. DOCUMENT plus effective `eventPublication = NEVER` supports
eventless current state, not history or `previous()`. Non-searchable documents still support identity/relationship
reads, and `@ProtectData` does not protect values copied into Model state. Read the linked **Model state:
persistence and protection boundaries** article before using these controls for sensitive data.

**Want to use `previous()`? Keep `EVENT_SOURCED` enabled** (the default `@Model` already does). `DOCUMENT` alone
stores only the current document, not previous versions. Adding `DOCUMENT` to event sourcing preserves history;
replacing event sourcing with `DOCUMENT` removes that guarantee. Cache depth and snapshots are optimizations, not
substitutes for a durable event history. Every historical Graph node whose value you inspect needs that history.

The default is event sourcing without a direct document or periodic snapshots. Add storage only for a concrete
read requirement. Use the central matrix at `/docs/sdk/entities/graph-search`.
Relationship-scoped search can use an internal component without `DOCUMENT`; ordinary `search(T.class)` lists cannot.

Important settings:

- `name`: durable logical Model type name; defaults to the concrete class's simple name. Keep an explicit value stable
  across Java class/package renames. It is separate from serializer payload types and has no aliases or FQN fallback.
  `fluxzero.model.namePrefix` is prepended literally for applications sharing a namespace (`billing` + `Invoice` =
  `billingInvoice`). Changing either value after data exists requires an application-managed data transition.
- `persistence`: selects a non-empty set of durable representations:
  - `{EVENT_SOURCED}` (default): reconstruct from Model events, without a direct document.
  - `{EVENT_SOURCED, DOCUMENT}`: reconstruct from events; maintain an internal source and separate public DOCUMENT projection.
  - `{DOCUMENT}`: load authoritative state from the internal source, not an independently rewritten public projection.
- `ignoreUnknownEvents`: deliberately tolerates unhandled stored events during event-sourced reconstruction.
- `document`: optional `@DocumentProjection` configuration for the direct collection, timestamp paths, and public
  searchability. It is valid only when `persistence` contains `DOCUMENT`; use `searchable = false` for a document that
  remains parent/ancestor-queryable but has no public content indexes. The separate internal source supports Model
  loads, verified state and Graph composition; a Graph role retains its own internal indexes. Public rewrites cannot
  change that source. Use `@HandleDocument(modelState = T.class)` (Kotlin: `T::class`) for schema-only source reindexing;
  `documentClass` selects the public projection and `modelGraph` the materialized Graph. See the migration guide.
- `eventPublication`: controls whether unchanged transitions create an event.
- `publicationStrategy`: `DEFAULT`, `STORE_AND_PUBLISH`, `STORE_ONLY` or `PUBLISH_ONLY`.
- `snapshotPeriod` and `maxSnapshotCount`: event-sourcing optimizations.
- `checkpointPeriod`: bounds repeated replay work within one reconstruction session.
- `cached` and `cachingDepth`: current and previous revisions retained in the SDK cache.
- `conflictPolicy`: `ACCEPT`, `RETRY`, `FAIL` or inherited `DEFAULT` for concurrent writes.
- `commitPolicy`: controls commit timing and completion-phase concurrency; normally keep `DEFAULT`.
- `automaticHandling`: opt out when an explicit command handler must call `Fluxzero.assertAndApply`.
- `materializeGraph`: enables the optional durable whole-tree read model.
- `graphProjection`: optional advanced `@GraphProjection` configuration; its collection defaults to the resolved direct
  Model collection plus `-graphs` when a direct document exists, or `<logical Model name>-graphs` otherwise, and
  materializes the complete finite graph without implicit size limits.

Persistence does not control event storage or publication. Those remain owned by `eventPublication`,
`publicationStrategy` and per-apply overrides. Internal Graph-component documents are also orthogonal: they neither
make an `EVENT_SOURCED` Model directly searchable nor change its load path. Event-sourcing-only options such as
`ignoreUnknownEvents`, snapshots and replay checkpoints are rejected on `DOCUMENT`-only Models.

In Kotlin, annotation arrays use `[ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT]`
and nested annotations omit `@`, for example `document = DocumentProjection(searchable = false)`.
