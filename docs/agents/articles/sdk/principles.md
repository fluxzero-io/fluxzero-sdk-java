Build Fluxzero applications from the inside out. Start with command intent and domain state, add handlers and tests, then add projections/search, and expose web endpoints last.

Core rules for agents:

- Put commands, queries, and typed IDs in an `api` package; put Models and value objects in `api.model`; keep handlers and endpoints near the domain package.
- Keep Model state immutable. Choose independent lifecycle boundaries with `@Model` plus `@Parent`; reserve `@Member` for root-owned state. Use Java records for payloads and value objects where possible.
- Keep `@Apply` methods pure. They create, update, or delete state and are replayed during event sourcing, so they must not load data, search, call services, generate IDs, call `User.getCurrent()`, or use wall-clock time. Inject persisted message context such as `Sender` or timestamp explicitly.
- Put business invariants and state-dependent authorization in `@AssertLegal`, not in controllers or UI code.
- Prefer typed payloads. Commands are imperative, queries implement `Request<T>`, and present-tense command payloads usually become the event stream.
- Do not put the sending user's ID in command or query payloads. Inject user context through `Sender`.
- Use typed `Id<T>` values and generate new IDs with `Fluxzero.generateId(...)` at endpoints or command construction boundaries, never inside `@Apply`.
- Use `Fluxzero.currentTime()` or injected time for deterministic code.
- Use `Fluxzero.search(...)` for filtering and sorting instead of reimplementing it in clients.
- Prefer dedicated queries over static utility methods for reusable reads.
- Use `TestFixture` as the main verification boundary and do not use Mockito for core Fluxzero behavior.
- Implement only the commands, queries, endpoints, and abstractions required by the product behavior. Do not add speculative admin operations, wrapper annotations, or planning artifacts to the finished application.

Handlers, queries, and `@AssertLegal` methods may load entities, query, or search when that is part of orchestration or legality. Keep that work out of `@Apply`, because apply methods are replayed to rebuild aggregate state.

`Fluxzero.publishEvent(...)` publishes an explicit event message for handlers and projections. It does not mutate an event-sourced aggregate; aggregate history is created when an update is applied through the entity path.

When unsure, follow parent links before inventing a new pattern.

For new v2 state use automatic Model `@Apply` handling and atomic multi-Model commits. The entity/aggregate loading
route is retained for legacy state. Do not add a ceremonial command handler to a Model update.
