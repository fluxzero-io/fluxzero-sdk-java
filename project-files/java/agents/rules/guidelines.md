---
apply: always
---

# Fluxzero AI Assistant Guidelines

You are an expert Fluxzero AI agent. Your goal is to help build and evolve high-quality applications
using the Fluxzero SDK. Prioritize established conventions and business logic over boilerplate.

Execution cadence and backlog workflow are defined in `AGENTS.md` in the root of the repository.

---

## Philosophy of Building

Fluxzero encourages an **inside-out** development order to ensure logic is correct and testable.
Prefer model/DDD fidelity over fast breadth. Do not begin with endpoints; begin with domain commands and model.

1. **Commands + Domain Model**: Define command intent, aggregate/entity boundaries, value objects, and invariants.
2. **Handlers + State Transitions**: Implement `@HandleCommand`/`@HandleQuery` with `@Apply` and `@AssertLegal`.
3. **Tests**: Verify domain behavior and invariants using `TestFixture`.
4. **Queries / Read Models / Side Effects**: Add search/read shaping and event-driven side effects.
5. **Endpoints Last**: Expose logic via REST/WebSockets as thin transport adapters.

---

## Task Decision Tree

Use this tree to find the correct manual for your current task, ordered by the recommended workflow:

### 1. Defining the API

- **"I need to define a new Command or Query payload"**
    - → [Message Handling](handling.md)
- **"I need to handle an incoming message"**
    - → [Message Handling](handling.md)
        - [Handle a Command (State changes)](handling.md#handlecommand)
        - [Handle a Query (Read-only)](handling.md#handlequery)
        - [Handle an Event / Notification](handling.md#events-notifications)
        - [Specialized Handlers (Schedules, Documents, etc.)](handling.md#specialized-handlers)

### 2. Implementing Logic & State

- **"I need to define Aggregates, Entities, or apply state changes"**
    - → [Entities & Aggregates](entities.md)
        - [Define an Aggregate or Entity](entities.md#aggregates)
        - [Intercept or rewrite updates (@InterceptApply)](entities.md#intercept-apply)
        - [Apply state changes (@Apply)](entities.md#apply)
        - [Implement permission checks (@AssertLegal)](entities.md#assertlegal)
        - [Load entities (Id, @Alias, Entity<T>)](entities.md#loading-entities)
- **"I need to search for data or work with documents"**
    - → [Search & Documents](search.md)
        - [Configure search indexing (@Searchable)](search.md#configuration)
        - [Perform a Search (Constraints, Logical grouping)](search.md#searching)
        - [Get Facet Statistics](search.md#facet-stats)
        - [Bulk Updates & Manual Indexing](search.md#bulk-ops)

### 3. Orchestration & Reliability

- **"I need to build a long-running process or workflow"**
    - → [Stateful Sagas](sagas.md) (@Stateful)
- **"I need to configure async consumption, threads, or handle replays"**
    - → [Tracking & Reliability](tracking.md)
        - [Configure a Consumer (threads, retries)](tracking.md#consumer)
        - [Error Correcting / Retroactive updates](tracking.md#error-correcting)
        - [Message Replays & Document Rebuilding](tracking.md#replays)
- **"I need to understand cross-app runtime interaction, delivery semantics, or tracker scaling"**
    - → [Runtime Interaction Model](runtime-interaction.md)

### 4. Sending & Scheduling

- **"I need to send a message or trigger an action"**
    - → [Sending Messages](sending.md)
        - [Send a Command or Query](sending.md#internal-messages)
        - [Schedule an action for later](sending.md#schedules)
        - [Routing Keys & Segments](sending.md#routing-keys)
        - [Make an external web request](sending.md#web-sending)

### 5. Web Surface

- **"I need to expose my logic via REST or WebSockets"**
    - → [Message Handling: Web](handling.md#web-handling)
        - [REST Endpoints (@HandleGet, etc.)](handling.md#web-requests)
        - [Serve static files (@ServeStatic)](handling.md#serve-static)
        - [WebSockets (@SocketEndpoint)](handling.md#websocket)

### 6. Verification (Testing)

- **"I need to write or update tests for my logic"**
    - → [Testing](testing.md)
        - [Using TestFixture](testing.md#testfixture)
        - [JSON testing patterns & FQN](testing.md#json-testing)

### 7. Specialized Configuration

- **"I need to secure my API or validate payloads"**
    - → [Validation & Security](validation.md)
        - [Payload validation (Jakarta annotations)](validation.md#payload-validation)
        - [Access control (@RequiresRole, @RequiresUser)](validation.md#rbac)
        - [Content filtering (@FilterContent)](validation.md#content-filtering)
        - [Data protection (@ProtectData, @DropProtectedData)](validation.md#data-protection)
- **"I need to handle versioning or schema evolution"**
    - → [Serialization](serialization.md)
        - [Payload Upcasting (ObjectNode)](serialization.md#payload-upcasting)
        - [Data Upcasting (Full message)](serialization.md#data-upcasting)
        - [Incrementing revisions (@Revision)](serialization.md#revision)
- **"I need to configure the application"**
    - → [Configuration](configuration.md)
        - [Application Properties](configuration.md#property-resolution)
        - [SDK Setup](configuration.md#client-configuration)

### 8. Troubleshooting

- **"I'm encountering an error or something isn't working"**
    - → [Troubleshooting](troubleshooting.md)

### 9. Advanced Runtime Operations (Use Only When Needed)

- **"I need replay-based recovery from historical handler failures (dynamic DLQ)"**
    - → [Error Correcting & Retroactive Updates](tracking.md#error-correcting)
- **"I need custom message logs/topics with explicit retention control"**
    - → [Sending Messages](sending.md#custom-topics)
- **"I need advanced builder-level behavior (consumer predicates, resolvers, toggles)"**
    - → [Configuration](configuration.md#advanced-builder-patterns)
- **"I need to reason about multi-consumer behavior, result resolution, or cross-namespace tradeoffs"**
    - → [Runtime Interaction Model](runtime-interaction.md#agent-defaults)

---

## Skill Shortcuts

Skills accelerate execution but do not replace these manuals.

- [slice-delivery](../skills/slice-delivery/SKILL.md): backlog-driven one-slice delivery and checkpoints.
- [domain-core](../skills/domain-core/SKILL.md): commands/queries/entities/invariants implementation.
- [messaging-scheduling](../skills/messaging-scheduling/SKILL.md): send/publish/query/schedule and tracing.
- [web-surface](../skills/web-surface/SKILL.md): REST/WebSocket transport handlers and endpoint tests.
- [search-readmodels](../skills/search-readmodels/SKILL.md): search constraints, sorting, projections, consistency window.
- [stateful-orchestration](../skills/stateful-orchestration/SKILL.md): `@Stateful` lifecycle and workflow orchestration.
- [replay-correction](../skills/replay-correction/SKILL.md): bounded replay and retroactive correction.
- [schema-evolution](../skills/schema-evolution/SKILL.md): `@Revision` and upcasting/downcasting changes.
- [runtime-bootstrap](../skills/runtime-bootstrap/SKILL.md): FluxzeroBuilder/client/consumer configuration.

---

## Chapter Overview

| Chapter                                       | Description                                                  |
|:----------------------------------------------|:-------------------------------------------------------------|
| [Glossary](glossary.md)                       | Key terms and definitions used in Fluxzero.                  |
| [Handling](handling.md)                       | Handling incoming messages (Commands, Queries, Events, Web). |
| [Sending](sending.md)                         | Dispatching messages and making external web requests.       |
| [Entities](entities.md)                       | Domain modeling, event sourcing, and aggregate lifecycle.    |
| [Sagas](sagas.md)                             | Stateful handlers and long-running workflows.                |
| [Tracking](tracking.md)                       | Async consumption mechanism, consumers, and replays.         |
| [Runtime Interaction](runtime-interaction.md) | Cross-app message flow, delivery semantics, and scaling.     |
| [Search](search.md)                           | Leveraging the built-in search engine and document store.    |
| [Testing](testing.md)                         | Writing fast, reliable tests with `TestFixture`.             |
| [Validation](validation.md)                   | Authorization, access control, and payload validation.       |
| [Serialization](serialization.md)             | Versioning, upcasting, and schema evolution.                 |
| [Configuration](configuration.md)             | Setting up and tuning your Fluxzero application.             |
| [Troubleshooting](troubleshooting.md)         | Resolving common issues and errors.                          |

---

## Core Principles

1. **Logic First**: Business logic resides in `@Apply`, `@AssertLegal`, and handler methods. Infrastructure is managed
   automatically by Fluxzero.
2. **Deterministic State**: `@Apply` methods must be pure functions. Never load data or search inside an `@Apply` block.
3. **Dumb Aggregates**: Aggregates are immutable state holders. They do not handle messages themselves.
4. **Naming Convention**: Commands are imperative (`CreateUser`), Queries are descriptive (`GetUserProfile`). Events
   reflect facts and are typically the action payload (`CreateUser`).
5. **Method Precedence**: When multiple handler methods match a message, the most specific one (matching the payload
   type the most) wins.
6. **Multiple Handlers**: A message can be handled by multiple independent handlers. Each handler will process the
   message once.
7. **Strongly Typed**: Use specialized `Id<T>` types and Value Objects for all identifiers and payloads.
8. **No Databases/SQL**: Fluxzero applications never deal with databases. Data is retrieved via queries or by loading
   entities.
9. **Core-Focused Testing**: Tests should primarily focus on core domain logic (Commands, Queries, Events).
10. **Behavior over State**: Test behavior, not state. Use message inputs (commands/queries) and observable outputs
    (events/results/errors) as the test boundary.
11. **No Mocking**: Never use `Mockito` or similar frameworks. The `TestFixture` provides everything needed for
    verification.
12. **No Instant.now()**: Always use `Fluxzero.currentTime()` or inject an `Instant` to ensure determinism.
13. **BigDecimal for Precision**: Always use `BigDecimal` for currency, weights, or dimensions. Avoid `double` or
    `float`.
14. **Value Object Modeling**: Always model commands and entities to use Value Objects (e.g., `TaskDetails`) instead of
    separate primitive fields (like `name`). This prevents having to change the whole command/event/document structure
    when adding fields later.
15. **Payload Purity**: Command/query payloads MUST NOT contain the sending user's ID. Handlers MUST inject `Sender`
    (`@Handle...`, `@AssertLegal`, `@Apply`) for user context.
16. **Secure by Default**: Add `@RequiresUser` to your domain's `package-info.java` to protect all payloads within that
    package.
17. **Domain Errors**: Use Error Interfaces like `ProjectErrors` to group domain-specific exceptions.
18. **Present-Tense Events**: Don't invent event types. The applied command payload (e.g. `CreateOrder`) is
    automatically reused for the event.
19. **Entity History**: Fluxzero enables viewing Entity history using `Entity.previous()`. This removes the need for
    second-class events like `BalanceChanged` after e.g. a `DepositMoney` command to see what changed.
20. **The Uber-Document Pattern**: Use `@HandleDocument` within a `@Stateful` saga to maintain a complex view of the
    system that updates whenever source documents change.
21. **The Consistency Window**: Remember that `sendCommandAndWait` only waits for the primary state change. Use
    WebSockets or secondary queries to handle eventually consistent side-effects like search index updates.
22. **Let go of Sequentialism**: Don't try to build long sequential scripts. Let handlers respond to the results of
    messages asynchronously.
23. **Entity IDs**: Use `Fluxzero.generateId(...)` when creating new aggregates or members. Do this in the **endpoint**
    or **command interface**, never inside the aggregate's `@Apply` method.
24. **Message Idempotency**: Every message has an ID. Providing a consistent ID from the client (or endpoint) enables
    automatic deduplication in the Fluxzero runtime.
25. **Search Ownership**: Filtering and sorting MUST be implemented in `Fluxzero.search(...)`. Client app code MUST NOT
    re-implement filtering/sorting logic.
26. **Prefer Queries Over Utilities**: If you need to fetch or compute data, model it as a dedicated query (`Request<T>` + `@HandleQuery`) instead of static utility methods. This keeps logic discoverable, testable, and composable.

---

## Project Structure Rules

Follow this layout unless instructed otherwise:

- Root: `io.fluxzero.<app>.<domain>`
- Commands, queries, IDs: `...<domain>.api`
- Models (aggregates, entities, value objects): `...<domain>.api.model`
- Handlers (stateful and stateless): `...<domain>`
- Endpoints: `...<domain>.<Something>Endpoint`
- Tests: mirror the domain structure under `src/test/java`
- JSON test resources: flat files grouped per domain (`/home/create-home.json`, etc.)

Here's the layout of a sample app called `fluxchess`:

```
src/main/java
└── io/fluxzero/fluxchess/game
    ├── GameEndpoint.java
    ├── GameSaga.java
    ├── package-info.java
    └── api
        ├── CreateGame.java
        ├── GameId.java
        ├── GameUpdate.java
        └── model
            ├── Game.java
            └── GameStatus.java

src/test/java
└── io/fluxzero/fluxchess/game
    ├── GameSagaTest.java
    ├── GameEndpointTest.java
    └── GameTest.java

src/test/resources
└── game
    ├── create-game.json
    └── create-game-request.json
```

---

## Retrieval Instructions

Always use these manuals as your **source of truth**. If you need specific imports, refer to `fluxzero-fqns-grouped.md`.
If a pattern is not documented, ask the user for clarification rather than guessing.

---

## What is Fluxzero?

Fluxzero is a cloud-native runtime and SDK that simplifies backend development by treating all interactions—commands,
queries, and web traffic—as **messages**. This eliminates boilerplate like plumbing, infrastructure configuration,
and complex framework wiring.

The system utilizes event sourcing and a built-in search engine to manage state and data retrieval automatically.
Verification is handled by a streamlined test fixture that simulates message flows without requiring external mocks
or databases.

Ultimately, Fluxzero aims to usher in a **"Logic Era"** where software is defined by pure intent rather than
technical scaffolding.
