# Models and Graphs

Use the focused articles below for state, commands, relationships and history.

Model discovery is independent of optional `@RegisterType` serialization aliases. Enable SDK annotation processing
(Kotlin: kapt) in every Model contract module; Model declarations contribute
`META-INF/io.fluxzero.sdk.modeling.Model`. Rebuild older contract JARs to generate this index. A classic shaded JAR
must append all contributing Model indexes (Maven Shade: `AppendingTransformer`); service merging alone is insufficient.
Cold discovery uses locally available classes and never registers their handlers or loads missing JARs automatically.
Abstract/interface contracts with an identity remain discoverable; identity-less inheritance templates are excluded.

For explicit replay-free current state, use `Fluxzero.loadCurrentModelState(id, ModelType.class)` (Kotlin:
`ModelType::class.java`) or the typed-ID overload. It returns read-only `ModelState<T>`, verified against a durable
head, and requires a maintained Model document plus shared state contracts. Missing/deleted Models are distinct from
missing/stale/unversioned documents, which fail. It does not populate replay caches or join transaction readsets.
It also requires a matching Runtime and a document whose body/head proof was captured during trusted Model
materialization/adoption. Older unproven documents and ordinary search overwrites are not silently accepted.
Use injected Models/Graphs for invariants; `loadCurrentGraph` still uses the authoritative load path, not this API.

Use `@Model` for persisted domain state in Java and Kotlin.
The SDK requires Java 25 or newer and a compatible Runtime for standalone Models.

Choose the focused state, actions or Graph article for the application's language:

- State: immutable Models, details versus settings/status, validated creation and targeted edits, and typed identity.
- Configuration: optional storage, search projections and operational settings; start with plain `@Model`.
- Actions: automatic `@Apply` command handling, recursive assertions, interception and atomic multi-Model commits.
- Graphs: independent children via `@Parent`, lazy navigation, exact event-state injection and graph search/projections.
- Conflicts: read dependencies, empty collections, `RETRY`/`FAIL`/`ACCEPT` and the cost of actual navigation.
- Deletion: logical deletion, cascading lifecycles and planned physical erasure.

First distinguish business state from workflow memory: provider correlation, retries and pending external effects
usually belong in `@Stateful`, not in a business Model solely because an attempt has its own lifecycle.
Then choose business Model boundaries by lifecycle, not collection shape or storage convenience. State whose creation, changes, history,
retention or deletion can be independent is a separate Model connected with `@Parent`. A parent-scoped identity is
enough. Use `@Member` only when all these concerns deliberately belong to its root.

Descriptive business data belongs in a cohesive details value object, even when it contains only `name`.
Keep configuration, identity, relationships, current status and execution bookkeeping distinct. A plain details
value is neither a separate Model nor a Member. The language-specific state articles explain the field-selection
criteria and show creation plus `RenameProject(id, name)` without losing other details.

A command with applicable Model `@Apply` methods is handled automatically. No pass-through
command handler is needed. A real orchestration handler can call
`Fluxzero.assertAndApply(command)` once. Keep applies deterministic and free of external effects.

A successful multi-Model commit covers all its Model events, direct documents and relationship deltas. Separate
commands, external searches and unrelated repositories are not automatically part of that transaction.
Public direct `DOCUMENT` state is synchronous with command completion; a derived graph projection is asynchronous
by default. Use `GraphProjectionCompletion.AWAIT` only when the operation requires projection completion.

For an invariant over children, inject `Graph<Parent>` and inspect the required child scope. Empty collections count.
An unambiguous typed parent ID needs no `@Association`; qualify only ambiguous targets or paths. Unused/value-only
Graphs resolved directly by ID add no relationship query. Indirect ancestor selection does protect its navigation.
Writers retain relationship-change evidence even when they do not themselves inject a Graph.
