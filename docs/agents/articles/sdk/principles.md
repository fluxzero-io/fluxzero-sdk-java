Build Fluxzero applications from the inside out. Start with command intent and domain state, add handlers and tests, then add projections/search, and expose web endpoints last.

For concurrent Model invariants, use the binding and read-boundary example at `/docs/sdk/entities/assert-legal`.
Identify the Model values/relations, ID source and conflict policy; neither `@AssertLegal`, current reads nor RETRY
make arbitrary search or helper I/O transactional. Resolve an unclear binding before adding an application workaround.

Core rules for agents:

- Before creating application classes, read `/docs/sdk/project-setup/package-structure` and choose concrete owning domains and package paths. Put commands, queries and typed IDs in `<domain>.api`, Models and values in `<domain>.api.model`, and separate handlers/endpoints in `<domain>`. Recheck the changed tree before finishing; follow established conventions in existing applications.
- Keep Model state immutable. Choose independent lifecycle boundaries with `@Model` plus `@Parent`; reserve `@Member` for root-owned state. Use Java records for payloads and value objects where possible.
- Choose Graph relations first, then deletion policy per relation. A plain typed ID registers no Graph edge;
  `@Parent` registers one with cascade deletion by default; `@Parent(deleteOnParentDeletion = false)` expresses
  a normal non-owning relation. Multiple parents may have different roles. Disabling cascade does not block
  parent deletion, and concrete Graph cycles remain forbidden. The Java/Kotlin Graph articles show an order line
  related to both its owning order and its non-owning product.
- Put descriptive business data copied into Model state in a cohesive details value object, even for just `name`.
  Group related configuration separately as settings. Keep typed identity, relationships, simple current status and
  execution bookkeeping distinct; do not make details a catch-all. A plain value needs neither `@Model` nor `@Member`.
  Creation can accept validated details, while `RenameProject(id, name)` updates only `details.name` and preserves
  other fields. The Java/Kotlin Model-state articles give selection criteria and a complete validated example.
- Keep `@Apply` methods pure. They create, update, or delete state and are replayed during event sourcing, so they must not load data, search, call services, generate IDs, call `User.getCurrent()`, or use wall-clock time. Inject persisted message context such as `Sender` or timestamp explicitly.
- Put business invariants and state-dependent authorization in `@AssertLegal`, not in controllers or UI code.
- Prefer typed payloads. Commands are imperative, queries implement `Request<T>`, and present-tense command payloads usually become the event stream.
- Do not put the sending user's ID in command or query payloads. Inject user context through `Sender`.
- Use typed `Id<T>` values and generate new IDs with `Fluxzero.generateId(...)` at endpoints or command construction boundaries, never inside `@Apply`.
- Use `Fluxzero.currentTime()` or injected time for deterministic code.
- Use `Fluxzero.search(...)` for filtering and sorting instead of reimplementing it in clients.
- Prefer dedicated queries over static utility methods for reusable reads.
- Give each external API interaction a local self-handling command or query and put its `WebRequestGateway` call in
  `@HandleCommand`/`@HandleQuery`. Dispatch that operation through Fluxzero rather than injecting an API-service bean.
  No `@TrackSelf` or `@Consumer` is needed merely for HTTP audit, retries or fixture tests; those annotations select a
  separate tracked delivery contract. Read `/docs/sdk/web/outbound-requests` for the complete pattern.
- Use `TestFixture` as the main verification boundary and do not use Mockito for core Fluxzero behavior.
- Implement only the commands, queries, endpoints, and abstractions required by the product behavior. Do not add speculative admin operations, wrapper annotations, or planning artifacts to the finished application.

Handlers, queries, and `@AssertLegal` methods may load entities, query, or search when that is part of orchestration or legality. Keep that work out of `@Apply`, because apply methods are replayed to rebuild aggregate state.

`Fluxzero.publishEvent(...)` publishes an explicit event message for handlers and projections. It does not mutate an event-sourced aggregate; aggregate history is created when an update is applied through the entity path.

When unsure, follow parent links before inventing a new pattern.

For new v2 state use automatic Model `@Apply` handling and atomic multi-Model commits. The entity/aggregate loading
route is retained for legacy state. Do not add a ceremonial command handler to a Model update.
