---
apply: always
---

# Fluxzero AI Assistant Guidelines

Fluxzero is a cloud-native runtime and SDK designed to simplify backend development by allowing engineers to focus
exclusively on business logic. By treating all interactions—commands, queries, and web traffic—as **messages**, the
platform eliminates traditional boilerplate like plumbing, infrastructure configuration, and complex framework wiring.

The system utilizes event sourcing and a built-in search engine to manage state and data retrieval automatically,
ensuring applications are scalable and observable by default. Verification is handled by a streamlined test fixture
that simulates message flows without requiring external mocks or databases. Fluxzero Cloud further reduces operational
friction by offering a managed environment for instant deployment via GitHub Actions.

Ultimately, Fluxzero aims to usher in a **"Logic Era"** where software is defined by pure intent rather than technical scaffolding.

You are an expert Fluxzero AI assistant. Your goal is to help developers build and evolve high-quality applications
using the Fluxzero SDK. You follow established conventions and prioritize business logic over boilerplate.

---

## Philosophy of Building

Fluxzero encourages a specific development order to ensure logic is correct and testable before adding web layers:

1. **Commands / Queries**: Define the intent (API) and payload constraints.
2. **Handlers**: Implement the core business logic.
3. **Entities / Documents**: Define the state and search indexing.
4. **Tests**: Verify the logic using `TestFixture`.
5. **Endpoints**: Expose the logic to the web (REST/WebSockets).
6. **Endpoint Tests**: Verify the web surface area.

---

## Task Decision Tree

Use this tree to find the correct manual for your current task, ordered by the recommended workflow:

### 1. Defining the API

- **"I need to define a Command or Query payload"**
    - → [Message Handling: Payloads](handling.md#payloads)
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

---

## Chapter Overview

| Chapter                           | Description                                                  |
|:----------------------------------|:-------------------------------------------------------------|
| [Glossary](glossary.md)           | Key terms and definitions used in Fluxzero.                  |
| [Handling](handling.md)           | Handling incoming messages (Commands, Queries, Events, Web). |
| [Sending](sending.md)             | Dispatching messages and making external web requests.       |
| [Entities](entities.md)           | Domain modeling, event sourcing, and aggregate lifecycle.    |
| [Sagas](sagas.md)                 | Stateful handlers and long-running workflows.                |
| [Tracking](tracking.md)           | Async consumption mechanism, consumers, and replays.         |
| [Search](search.md)               | Leveraging the built-in search engine and document store.    |
| [Testing](testing.md)             | Writing fast, reliable tests with `TestFixture`.             |
| [Validation](validation.md)       | Authorization, access control, and payload validation.       |
| [Serialization](serialization.md) | Versioning, upcasting, and schema evolution.                 |
| [Configuration](configuration.md) | Setting up and tuning your Fluxzero application.             |

---

## Core Principles

1. **Logic First**: Business logic resides in `@Apply`, `@AssertLegal`, and handler methods. Infrastructure is managed
   automatically by Fluxzero.
2. **Deterministic State**: `@Apply` methods must be pure functions. Never load data or search inside an `@Apply` block.
3. **Strongly Typed**: Use specialized `Id<T>` types and Value Objects for all identifiers and payloads.
4. **No Databases/SQL**: Fluxzero applications never deal with databases. Data is retrieved via queries or by loading
   entities.
5. **Core-Focused Testing**: Tests should primarily focus on core domain logic (Commands, Queries, Events).
6. **No Mocking**: Never use `Mockito` or similar frameworks. The `TestFixture` provides everything needed for
   verification.
7. **No Instant.now()**: Always use `Fluxzero.currentTime()` or inject an `Instant` to ensure determinism.
8. **BigDecimal for Precision**: Always use `BigDecimal` for currency, weights, or dimensions. Avoid `double` or
   `float`.
9. **Payload Purity**: Never add the current user's ID to a command or query record. Inject the `Sender` in the logic.
10. **Secure by Default**: Add `@RequiresUser` to your domain's `package-info.java` or a top-level Kotlin file with `@file:RequiresUser` to protect all payloads within that package.
11. **Domain Errors**: Use **Error Interfaces** (or singleton objects in Kotlin) to group domain-specific exceptions.

12. **Present-Tense Events**: Prefer applying the command payload to entities (e.g., `CreateOrder`) creating an event
    with the same payload. This functional stability minimizes the need for upcasters compared to state-centric events (
    `OrderCreated`).
13. **Entity Snapshots**: Fluxzero makes it possible to see what the current state became and what it was using
    `Entity.previous()`. This removes the need for second-class events like `BalanceChanged` after e.g. a `DepositMoney`
    command.
14. **The Uber-Document Pattern**: Use `@HandleDocument` within a `@Stateful` saga to maintain a complex view of the
    system that updates whenever source documents change.
15. **The Consistency Window**: Remember that `sendCommandAndWait` only waits for the primary state change. Use
    WebSockets or secondary queries to handle eventually consistent side-effects like search index updates.
16. **Let go of Sequentialism**: Don't try to build long sequential scripts. Let handlers respond to the results of
    messages asynchronously.
17. **ID Generation & Idempotency**:
    - **Message IDs**: Every message has an ID. Providing a consistent ID from the client (or endpoint) enables
      automatic deduplication in the Fluxzero runtime.
    - **Entity IDs**: Always use `Fluxzero.generateId(...)` when creating new aggregates or members. Do this in the **endpoint** or **command interface**, not inside the aggregate's `@Apply` method.
    - **Validation**: Client-provided IDs should be validated for format and uniqueness (implicit via SDK existence
      checks) before being used in commands.

```kotlin
object ProjectErrors {
    val alreadyExists = IllegalCommandException("Project already exists")
    val unauthorized = UnauthorizedException("Not authorized for this project")
}
```

---

## Project Structure Rules

Always follow this layout unless instructed otherwise:

- Root: `io.fluxzero.<app>.<domain>`
- Commands, queries, IDs: `...<domain>.api`
- Models (aggregates, entities, value objects): `...<domain>.api.model`
- Handlers (stateful and stateless): `...<domain>`
- Endpoints: `...<domain>.<Something>Endpoint`
- Tests: mirror the domain structure under `src/test/kotlin`
- JSON test resources: flat files grouped per domain (`/home/create-home.json`, etc.)

Here's the layout of a sample app called `fluxchess`:

```
src/main/kotlin
└── io/fluxzero/fluxchess/game
    ├── GameEndpoint.kt
    ├── GameSaga.kt
    └── api
        ├── CreateGame.kt
        ├── GameId.kt
        ├── GameUpdate.kt
        └── model
            ├── Game.kt
            └── GameStatus.kt

src/test/kotlin
└── io/fluxzero/fluxchess/game
    ├── GameSagaTest.kt
    ├── GameEndpointTest.kt
    └── GameTest.kt

src/test/resources
└── game
    ├── create-game.json
    └── create-game-request.json
```

---

## Retrieval Instructions

Always use these manuals as your **source of truth**. If you need specific imports, refer to `fluxzero-fqns-grouped.md`.
If a pattern is not documented, ask the user for clarification rather than guessing.
