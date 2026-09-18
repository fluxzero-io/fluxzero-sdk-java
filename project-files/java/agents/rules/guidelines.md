---

For concurrent Model invariants, read [Model state and protection boundaries](https://fluxzero.io/docs/guides/modeling-and-persistence/model-state-boundaries/).
Identify the Model values/relations, ID source and conflict policy. Inject the Model/Graph or load its Graph within
the mutation. Search, arbitrary manual Model reads and standalone assertions are not automatic commit dependencies.
An unclear binding must be resolved before adding an application workaround.
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

1. **Commands + Domain Model**: Define command intent, choose model boundaries by independent lifecycle, then define
   relationships, value objects, and invariants.
2. **State Transitions + Handlers**: Implement model `@Apply`/`@AssertLegal`; add message handlers only for orchestration,
   queries and side effects.
3. **Tests**: Verify domain behavior and invariants using `TestFixture`.
4. **Queries / Read Models / Side Effects**: Add search/read shaping and event-driven side effects.
5. **Endpoints Last**: Expose logic via REST/WebSockets as thin transport adapters.

---

Before creating application classes, follow [Project Structure Rules](#project-structure-rules): identify their
owning business domains and concrete package paths. Recheck the changed tree before finishing. Follow established
conventions in an existing application; this check does not authorize a cosmetic package migration.

## Task Decision Tree

Use this tree to find the correct manual for your current task, ordered by the recommended workflow:

### 0. Running The Project Locally

- **"I need to start, stop, inspect, or configure the local development environment"**
    - -> [Local Development](development.md)
- **"I need to create or edit `.fluxzero/dev.yaml`"**
    - -> Run `fz dev config`, as explained in [Local Development](development.md)

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

- **"I need to define Models, relationships, or apply state changes"**
    - → [Models and State](entities.md)
        - [Define a Model](entities.md#define-a-model)
        - [Intercept or rewrite updates (@InterceptApply)](entities.md#intercept-apply)
        - [Apply state changes (@Apply)](entities.md#apply)
        - [Implement permission checks (@AssertLegal)](entities.md#assertlegal)
        - [Load models and lazy graphs (Id, @Alias, Graph<T>)](entities.md#loading)
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
- **"I need to publish, consume, or interpret metrics"**
    - → [Metrics](metrics.md)
        - [Handle built-in SDK metrics](metrics.md#tracking-metrics)
        - [Understand ignored messages](metrics.md#ignored-messages)

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
        - [Automatic HTTP result and exception mapping](handling.md#http-mapping)
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
        - [Aliasing renamed classes or packages](serialization.md#type-aliases)
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

## Chapter Overview

| Chapter                                       | Description                                                  |
|:----------------------------------------------|:-------------------------------------------------------------|
| [Glossary](glossary.md)                       | Key terms and definitions used in Fluxzero.                  |
| [Handling](handling.md)                       | Handling incoming messages (Commands, Queries, Events, Web). |
| [Sending](sending.md)                         | Dispatching messages and making external web requests.       |
| [Models](entities.md)                         | Domain modeling, relationships, persistence and lifecycle.   |
| [Sagas](sagas.md)                             | Stateful handlers and long-running workflows.                |
| [Tracking](tracking.md)                       | Async consumption mechanism, consumers, and replays.         |
| [Metrics](metrics.md)                         | Observability signals, tracking metrics, and ignored messages. |
| [Runtime Interaction](runtime-interaction.md) | Cross-app message flow, delivery semantics, and scaling.     |
| [Search](search.md)                           | Leveraging the built-in search engine and document store.    |
| [Testing](testing.md)                         | Writing fast, reliable tests with `TestFixture`.             |
| [Local Development](development.md)           | Version-aligned CLI and dev-server configuration guidance.   |
| [Validation](validation.md)                   | Authorization, access control, and payload validation.       |
| [Serialization](serialization.md)             | Versioning, type aliases, upcasting, and schema evolution.   |
| [Configuration](configuration.md)             | Setting up and tuning your Fluxzero application.             |
| [Troubleshooting](troubleshooting.md)         | Resolving common issues and errors.                          |

---

## Core Principles

1. **Logic First**: Business logic resides in `@Apply`, `@AssertLegal`, and handler methods. Infrastructure is managed
   automatically by Fluxzero.
2. **Deterministic State**: `@Apply` methods must be pure functions. Never load data or search inside an `@Apply` block.
3. **Immutable Models**: Models are immutable state holders. Action logic normally lives on command/update payloads.
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
14. **Value Object Modeling (Details vs. Status)**: Model business details that map 1:1 into entity state as dedicated
    value objects (for example `TenantDetails`, `UserDetails`), even when there is only one field initially (like
    `name`). Keep top-level primitive/scalar fields for identifiers and simple status/control indicators (for example
    IDs, enums, booleans, timestamps) that are intentionally changed by a single command. Classify by meaning, not
    primitive type: a preference is configuration, a completion timestamp is status, and a requested delivery date
    is business input. Group coherent configuration separately; do not mix execution bookkeeping into editable
    details. Plain details need neither `@Model` nor `@Member`. A focused `RenameProject(id, name)` may still carry
    a scalar and replace only that field in the existing details. See [field selection and examples](entities.md#choose-details-configuration-and-state).
15. **Payload Purity**: Command/query payloads MUST NOT contain the sending user's ID. Handlers MUST inject `Sender`
    (`@Handle...`, `@AssertLegal`, `@Apply`) for user context.
16. **Secure by Default**: Add `@RequiresUser` to your domain's `package-info.java` to protect all payloads within that
    package.
17. **Domain Errors**: Use Error Interfaces like `ProjectErrors` to group domain-specific exceptions.
18. **Present-Tense Events**: Don't invent event types. The applied command payload (e.g. `CreateOrder`) is
    automatically reused for the event.
19. **Model History**: View current Model history through `Graph.previous()`, `revisions()` and
    `playBackToCondition(...)`. This removes the need for second-class events like `BalanceChanged` after a
    `DepositMoney` command solely to see what changed. Reserve `Entity<T>` for legacy Aggregate and persistence code.
20. **The Uber-Document Pattern**: Use `@HandleDocument` within a `@Stateful` saga to maintain a complex view of the
    system that updates whenever source documents change.
21. **The Consistency Window**: Direct Model documents selected by including `DOCUMENT` in the persistence set
    complete with the Model commit. Materialized Graph
    projections are asynchronous unless the operation selects `GraphProjectionCompletion.AWAIT`; unrelated handler
    side effects remain eventually consistent.
22. **Let go of Sequentialism**: Don't try to build long sequential scripts. Let handlers respond to the results of
    messages asynchronously.
23. **Model IDs**: Use `Fluxzero.generateId(...)` when creating new models or members. Do this in the **endpoint**
    or **command interface**, never inside `@Apply`.
24. **Message Idempotency**: Every message has an ID. Providing a consistent ID from the client (or endpoint) enables
    automatic deduplication in the Fluxzero runtime.
25. **Search Ownership**: Filtering and sorting MUST be implemented in `Fluxzero.search(...)`. Client app code MUST NOT
    re-implement filtering/sorting logic.
26. **Prefer Queries Over Utilities**: If you need to fetch or compute data, model it as a dedicated query (`Request<T>` + `@HandleQuery`) instead of static utility methods. This keeps logic discoverable, testable, and composable.
27. **Use Automatic Web Response Mapping**: Endpoint handlers should return their ordinary payload (or `void`) and let
    known exceptions propagate. `DefaultWebResponseMapper` already maps results and common exceptions to HTTP responses,
    including `ValidationException` to `400 Bad Request`. Do not add `try/catch` or construct a `WebResponse` merely to
    reproduce a default mapping; return a `WebResponse` only to override status, payload, or headers.

---

## Project Structure Rules

Group application code by business domain first. Within each domain, use `api` for commands, queries and typed IDs,
and `api.model` for state and value objects. A domain is a cohesive product area such as `catalog` or `ordering`;
`<domain>` is a placeholder for that name, not a literal application-wide `domain` package. Several related models
may belong to one domain. A package is not a requirement to create a separate service, module or deployment.

### Before creating files

For a new application, follow this convention unless the user or repository specifies another structure. Before
adding the first product classes, inspect the generated or existing tree, identify the relevant business domains,
and map the first commands, queries, IDs, models and handlers to concrete package paths. For an existing application,
follow its established conventions and make a deliberate migration only when the task calls for it. This is an
implementation check, not a request for user approval or a new planning document.

| Kind | Package | Example |
| --- | --- | --- |
| Command or query payload | `<root>.<domain>.api` | `com.example.shop.ordering.api.PlaceOrder` |
| Typed ID | `<root>.<domain>.api` | `com.example.shop.ordering.api.OrderId` |
| Model, details, status or other value object | `<root>.<domain>.api.model` | `com.example.shop.ordering.api.model.Order` |
| Separate handler, orchestration or endpoint | `<root>.<domain>` | `com.example.shop.ordering.OrderQueries` |
| Behavior tests | Same domain under the test source root | `com.example.shop.ordering.OrderTest` |
| JSON test resources | Flat files grouped per domain under `src/test/resources` | `ordering/place-order.json` |

Here `api` means the domain's message and model contract; it does not mean HTTP routes. A self-handling command or
query stays in `api` even when it contains an `@Apply`, `@HandleCommand` or `@HandleQuery` method. Do not add a
pass-through handler or a duplicate DTO merely to fill out this tree. Add endpoints only when the product needs them.

### Example tree

This Java example shows two domains so the boundary is visible. Only create the classes needed for the current feature.

```text
src/main/java/com/example/shop/
├── App.java
├── package-info.java
├── catalog/
│   ├── ProductQueries.java
│   └── api/
│       ├── CreateProduct.java
│       ├── GetProduct.java
│       ├── ProductId.java
│       └── model/
│           ├── Product.java
│           └── ProductDetails.java
└── ordering/
    ├── OrderQueries.java
    └── api/
        ├── PlaceOrder.java
        ├── GetOrder.java
        ├── OrderId.java
        └── model/
            ├── Order.java
            └── OrderDetails.java

src/test/java/com/example/shop/
├── catalog/ProductTest.java
└── ordering/OrderTest.java

src/test/resources/
├── catalog/create-product.json
└── ordering/place-order.json
```

Kotlin uses the same package names under `src/main/kotlin` and `src/test/kotlin`, with `.kt` files. Keep any Java
`package-info.java` under `src/main/java` at the matching package path. A later order HTTP adapter would be
`com.example.shop.ordering.OrderEndpoint` (`OrderEndpoint.java` or `OrderEndpoint.kt`).

### Keep domains cohesive

Avoid application-wide `commands`, `queries`, `models`, `domain`, `handlers` or `services` buckets that mix several
business domains. For example, put `PlaceOrder`, `OrderId` and `Order` together under `ordering.api` and
`ordering.api.model`, rather than splitting them across `shop.commands`, `shop.ids` and `shop.domain`.
Additional subpackages inside a large domain are useful when they describe a real responsibility; an owning domain
must remain recognizable. An external integration may form such a responsibility, with its message contracts in its
own `api` package. Package placement alone does not select local versus tracked handling.

### Before finishing

Inspect the changed production and test paths before committing:

- Each new type has an identifiable owning domain; related types have not drifted into global technical buckets.
- Commands, queries and typed IDs follow that domain's `api` convention; state and values use its `api.model`.
- Existing separate handlers and endpoints remain near their domain; no unnecessary layers were added.
- Tests mirror the owning domains, and package declarations, imports and package-level annotations match the paths.

Apply this check to code you add or change. Do not automatically rename an existing application's persisted payload
classes for cosmetic consistency. Moving a class can affect serialized type names, registration, inherited security,
web routing and consumer discovery. Preserve these contracts with the version's migration/type-alias facilities and
focused behavior or reconstruction tests when a move is explicitly in scope.

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
