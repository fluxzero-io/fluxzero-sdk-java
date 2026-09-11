# Models and Graphs in SDK v2

Use `@Model` for new persisted domain state in Java and Kotlin. Keep existing `@Aggregate` state on its
compatibility API until a deliberate data migration; changing only its annotation is not a migration.
SDK v2 requires Java 25 or newer and a matching v2 Runtime for standalone Models.

Choose the focused state, actions or Graph article for the application's language:

- State: immutable Models, event-sourced/document persistence, typed identity and intentional embedded members.
- Actions: automatic `@Apply` command handling, recursive assertions, interception and atomic multi-Model commits.
- Graphs: independent children via `@Parent`, lazy navigation, exact event-state injection and graph search/projections.
- Conflicts: read dependencies, empty collections, `RETRY`/`FAIL`/`ACCEPT` and the cost of actual navigation.
- Migration: deletion, cascade, physical erasure and migration of already persisted aggregates.

Choose boundaries by lifecycle, not collection shape or storage convenience. State whose creation, changes, history,
retention or deletion can be independent is a separate Model connected with `@Parent`. A parent-scoped identity is
enough. Use `@Member` only when all these concerns deliberately belong to its root.

A command with applicable Model `@Apply` methods is handled automatically. Do not add a pass-through
`@HandleCommand`/`loadAggregate(...)` interface from an old example. A real orchestration handler can call
`Fluxzero.assertAndApply(command)` once. Keep applies deterministic and free of external effects.

A successful multi-Model commit covers all its Model events, direct documents and relationship deltas. Separate
commands, external searches and unrelated repositories are not automatically part of that transaction.
Public direct `DOCUMENT` state is synchronous with command completion; a derived graph projection is asynchronous
by default. Use `GraphProjectionCompletion.AWAIT` only when the operation requires projection completion.

For an invariant over children, inject `Graph<Parent>` and inspect the required child scope. Empty collections count.
An unambiguous typed parent ID needs no `@Association`; qualify only ambiguous targets or paths. Unused/value-only
Graphs resolved directly by ID add no relationship query. Indirect ancestor selection does protect its navigation.
Writers retain relationship-change evidence even when they do not themselves inject a Graph.

The retained entity articles describe legacy aggregate workflows unless explicitly identified as shared APIs.
Use the Model articles for new v2 behavior, and the legacy articles to maintain already persisted aggregate code.
