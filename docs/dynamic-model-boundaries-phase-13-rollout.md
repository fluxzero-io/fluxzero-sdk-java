# Phase 13 — Model-first rollout and compatibility report

Date: 2026-07-27

## Public direction

`@Model` is the persistence concept for new Fluxzero applications. A model can keep deliberately embedded `@Member`
values when one stream, cache, document and lifecycle are desired, while `@ParentId` relates independently stored
models when their lifecycle or consistency boundary may change.

`@Aggregate` and its loading APIs remain compatible throughout Fluxzero 1.x so existing persisted streams can continue
to load. They are documented only as legacy APIs. Adding Java's `@Deprecated` marker is reserved for the 2.0 line so a
1.x SDK update does not introduce warnings throughout existing applications. Changing only the annotation is not a
valid migration because stream layout, identity, documents and lifecycle boundaries change.

## Executable coverage

`PersistenceRootParityTest` executes the same contract against a legacy aggregate and a model for behavior that should
remain externally identical:

| Contract | Aggregate | Model |
| --- | --- | --- |
| create, update, logical delete and recreate | same executable test | same executable test |
| event-sourced loading and direct synchronous search | same executable test | same executable test |
| `Entity.previous()` | same executable test | same executable test |
| document-loaded state still stores and publishes events | same executable test | same executable test |
| `eventPublication = NEVER` direct state and search | same executable test | same executable test |
| failed assertion rollback | same executable test | same executable test |
| failed interceptor rollback | same executable test | same executable test |
| periodic snapshot and cold reconstruction | same executable test | same executable test |
| applied-event metadata | same executable test | same executable test |
| asynchronous fixture completion | same executable test | same executable test |
| `STORE_ONLY` history without global publication | same executable test | same executable test |
| embedded `@Member` values sharing one root stream | same executable test | same executable test |

Model-only behavior has dedicated tests instead of being forced into an aggregate-shaped contract:

- multi-model actions, independent lifecycle and unrelated-model assertion/apply dependencies;
- parent and ancestor injection, moves, detaches and current/historical graph loading;
- conflicts, apply-only rebasing, action idempotency and one global publication;
- hash-partitioned stream/relationship persistence and shared-payload ownership;
- synchronous direct documents, graph stitching and durable graph projections;
- snapshots, unknown-event policy, namespace isolation and document-loaded historical reconstruction;
- hard and cascading erasure, detached lineage and late-write fences;
- long-polling remote cache coherence for event-sourced and document-loaded models.

The real runtime suite now covers direct commit/load/search, event-sourced and document-loaded models, tracked model
parameter injection, logical deletion/recreation, hard deletion, ancestor injection, graph projection and moves.
The JDBC search contract also verifies erasure before any model snapshot partition has previously existed.

## Universal message-handler model parameters

`ModelEntityParameterResolver` supports `T` and `Entity<T>` parameters in every selected message handler kind.
Commands, queries, web requests and responses, schedules, results, errors, metrics, documents and custom messages use
one current handler load context when their payload or metadata identifies a model. Event-sourced targets share its
pinned repository boundary; current-only document targets retain direct-document semantics. Events and notifications
use the persisted model-action boundary instead.

- IDs follow the model apply rules: canonical `@EntityId`, one unique typed `Id<T>`, or
  `@Association("payloadProperty")`.
- `@Association` first selects a payload or metadata ID property and can disambiguate multiple IDs of the same model
  type. `excludeMetadata = true` restricts it to payload/graph lookup.
- A directly addressed child can supply parents, grandparents and further ancestors without repeating their IDs in
  the message. `@Association("parentPath")` selects an ancestor edge when the type alone is ambiguous.
- Loads use the handler consumer namespace. Events and notifications use the exact action/substep boundary; other
  messages use the current repository context.
- Direct targets and ancestors share one handler-scoped load context. Repeated parameters therefore do not perform
  repeated repository loads.
- `Entity<T>` may be empty after logical deletion. A non-null bare `T` does not match an absent model.
- Ordinary events without model-action metadata never trigger an implicit current-state load.

The resolver is placed before the legacy aggregate entity resolver. Its reflection work is prepared with the handler
plan. Candidate selection remains side-effect-free: repository I/O starts only after a handler containing resolvable
model parameters has actually been selected.

## Package-scoped default consumers

`fluxzero.tracking.unconfiguredHandlerConsumerMode` supports:

- `perPackage`: one generated consumer per exact handler package and message type;
- `perHandler`: one generated consumer per handler class;
- `defaultAppConsumer`: the original shared application consumer.

An explicit property always wins. Without it, defaults version `2026.07.27` selects `perPackage`, versions from
`2026.05.20` through `2026.07.26` select `perHandler`, and older or missing versions retain
`defaultAppConsumer`. Explicit `@Consumer` annotations and matching builder configurations remain more specific.

Automatic model command handlers expose the command payload type as their tracking target. They therefore use the same
package and root-package consumer selection as explicit command handlers; loose command classes need no common marker
interface.

## Documentation

The getting-started material, first application tutorial, modeling/search guides, README model chapter and Java/Kotlin
agent manuals now teach `@Model`, `Fluxzero.loadModel`, automatic model actions, independent relations and model graph
search. Aggregate material is confined to an explicitly marked 1.x legacy section or migration warning.

## Verification

- `./mvnw -pl sdk -am -Dtest='PersistenceRootParityTest,ModelEntityParameterResolverTest,ModelActionHandlerIntegrationTest,ConsumerConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
  — 88 tests passed.
- SDK `./mvnw -B install` — full reactor passed, including 1,925 SDK tests and Java/Kotlin downstream projects.
- Runtime
  `./mvnw -pl runtime -Dtest='ModelIntegrationTest,JdbcSearchStoreTest,JdbcRumSearchStoreTest' test`
  — 99 real runtime/JDBC tests passed.
- Runtime `./mvnw -B install` — full reactor passed, including 632 runtime tests.
- `./mvnw -B site -Pjavadoc` — site and Javadoc generation passed. Existing unrelated Javadoc link diagnostics and
  downstream Maven Doxia compatibility warnings remain warnings.
