# Dynamic model boundaries — aggregate/model test parity

Date: 2026-07-26

## Rule

`@Aggregate` and `@Model` share storage configuration and several observable lifecycle contracts, but they are not two
implementations of the same abstraction. A test belongs in the shared parity matrix only when an application should be
unable to observe which root mechanism implements the scenario.

[`PersistenceRootParityTest`](../sdk/src/test/java/io/fluxzero/sdk/modeling/PersistenceRootParityTest.java) executes the
same parameterized scenario for both root kinds. Separate tests remain where the mechanism is deliberately different.

## Executable parity matrix

| Observable contract | Aggregate coverage | Model coverage | Shared parameter |
| --- | --- | --- | --- |
| applied event is stored and the root can be loaded | existing repository suites | model repository/commit suites | yes |
| directly changed searchable root is visible when commit returns | `SearchableAggregateTest` | `ModelCommitHandlerIntegrationTest` | yes |
| update replaces the direct document | aggregate repository/search suites | model commit/repository suites | yes |
| `Entity.previous()` exposes the retained predecessor | aggregate playback/cache suites | model cache/repository suites | yes |
| logical delete removes current load/document but retains stream history | event-sourcing/search suites | model commit/repository suites | yes |
| the same identity can be recreated after logical delete | aggregate event-sourcing suites | model commit suite | yes |
| `eventSourced = false` changes load strategy but still stores applied events | `SearchableAggregateTest` and repository suites | model repository/commit suites | yes |
| `EventPublication.NEVER` can still update a current document | `SearchableAggregateTest` | model commit committer suite | yes |
| configured snapshot period produces a reconstructable root | `EventSourcingRepositoryTest` | `DefaultModelRepositoryTest` | same parity fixture enables period 2; cursor mechanics remain mechanism-specific |
| synchronous and asynchronous `TestFixture` paths complete after storage | aggregate given/when/then suites | `TestFixtureModelApiTest` and model handler integration | shared synchronous lifecycle plus dedicated async tests |
| websocket runtime commit, load, direct search and global event publication | `AggregateIntegrationTest` | `ModelIntegrationTest` | separate fixtures because the wire commits intentionally differ |

The runtime model test consumes the published event through a real EVENT tracker. This matters because a model event is
inserted into the global event log by `CommitModels`; the SDK must not publish the same event a second time merely
to make a local fixture observe it.

## Deliberately aggregate-only

- embedded `@Member` routing and traversal: members share one root stream and are not independent models;
- `loadAggregateFor` and legacy aggregate-relationship repair;
- mutable and `void` applies retained for compatibility;
- aggregate-root commit batching through `ModifiableAggregateRoot`;
- aggregate event playback/cache synchronization through legacy aggregate events;
- aggregate event-routing compatibility and legacy commit-policy ordering.

These tests must keep running, but copying them to `@Model` would assert behavior the model design intentionally
removes.

## Deliberately model-only

- one stream, cache, snapshot, document and lifecycle per independent ID;
- multi-model commits and one global event with multiple stream memberships;
- `ACCEPT` re-evaluation of derived documents and relationships;
- parent/ancestor injection, temporal graph reads, moves and path-qualified ambiguity;
- graph search in either direction;
- logical versus explicit hard/cascading model deletion.

## Untyped load and stored type

An untyped load now reads at most the first model-stream membership and asks the payload-side `@Apply` factories which
model type they write for the requested exact ID. If there is exactly one answer, stored model-type metadata is not
used. This extra read exists only on the explicitly untyped path; typed IDs and typed loads retain their existing hot
path.

Stored type remains a fallback for document-loaded models, incomplete/non-stored histories, and model-side apply
handlers. It is reconstruction metadata, not identity, and is resolved through the Serializer's existing type-caster
chain before class loading.

## Aggregate `Fluxzero.assertAndApply`

The global convenience method remains model-only for now. An aggregate call is safe only when the aggregate root ID and
type are explicit. Inferring a root from an arbitrary child ID, or choosing one of several IDs in a payload, would
silently change existing aggregate routing semantics. Existing
`Fluxzero.loadAggregate(id, type).assertAndApply(update)` remains the unambiguous aggregate API.
