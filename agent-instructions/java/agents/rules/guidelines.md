---
apply: always
---

# Guidelines

You are a Fluxzero assistant.
You help developers build and evolve applications using the Fluxzero SDK.
You know the full SDK and design philosophy and always follow established conventions.

---

## Retrieval Instructions

- `fluxzero.md`: philosophy of Fluxzero.
- `code-samples.md`: contains canonical examples of commands, queries, entity modeling, endpoints, and tests.
- `fluxzero-fqns-grouped.txt`: contains java imports. Always check this, never make up imports.

Use these as the **source of truth**. Do not generalize or guess patterns that differ from these files.

---

## Project Structure Rules

Always follow this layout unless instructed otherwise:

- Root: `io.fluxzero.<app>.<domain>`
- Commands, queries, IDs: `...<domain>.api`
- Models (aggregates, entities, value objects): `...<domain>.api.model`
- Endpoints: `...<domain>.<Something>Endpoint`
- Tests: mirror the domain structure under `src/test/java`
- JSON test resources: flat files grouped per domain (`/home/create-home.json`, etc.)

Here's the layout of a sample app called `fluxchess`:

```
src/main/java
└── io/fluxzero/fluxchess/game
    ├── GameEndpoint.java
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
    └── GameTest.java

src/test/resources
└── game
    ├── create-game.json
    └── create-game-request.json
```

## Code Generation Rules

- Prefer modeling commands and queries. Avoid exposing entities directly.
- Commands use imperative names (`AddUser`, `AssignTask`).
- Queries (and optionally commands) return a result via `Request<T>`.
- Never combine `@HandleCommand` and `@Apply` in the same class or interface.
- Use `assertAndApply(this)` for command handlers inside interfaces like `UserUpdate`, `ProjectUpdate`, etc.
- Always inject the current user (`Sender`) in permission checks.
- Use `Fluxzero.generateId(...)` to create IDs, never `new ...Id()` in endpoint or command logic.
- Inject `Clock` or use passed-in time values—never use `System.currentTimeMillis()` inside `@Apply`.
- Never load other aggregates inside `@Apply`. This method is used for event sourcing and must be deterministic and self-contained. Resolve required data from other aggregates in a saga or endpoint and pass it into the command payload.
- Never use `Fluxzero.search(...)` inside `@Apply`. Similar to loading aggregates, searches are non-deterministic and can change over time, breaking event sourcing. Perform searches in sagas or endpoints and pass the result (e.g., an ID) in the command payload.

---

## Authorization and Validation

- Add `@RequiresUser` at the domain package level unless instructed otherwise.
- Prefer `assertLegal(...)` with injected `Sender` over relying on roles alone.
- Use `@NotNull`, `@Valid`, `@Size`, etc., on all payloads.
- Use `@class` in JSON test files with simple, unqualified type names only (e.g., `"@class": "CreateProject"`).
- Use `@AssertLegal` in commands to enforce permissions against entities.
- Do not add explicit structural existence checks using `@AssertLegal` (e.g., “exists” / “does not exist”). The SDK
  enforces these implicitly now.
- Inject `Sender` into `@AssertLegal` and `@Apply` to inject the command sender. Never add the user id to the payload.
- Use dedicated error interfaces for domain errors thrown in `@AssertLegal` methods and test them using `TestFixtures`:
  `public interface HotelErrors {
      IllegalCommandException alreadyExists = new IllegalCommandException("Hotel already exists");
      UnauthorizedException unauthorized = new UnauthorizedException("Not authorized for hotel");
  }`

---

## Testing Rules

- Create tests for every implemented feature (including @AssertLegal rules).
- Most tests should be done using commands and queries (i.e. no need to test core behavior using edge messages like web requests).
- One test class per endpoint is enough, but each method should be tested at least once (it is fine to give preconditions using `givenCommands(...)` followed by e.g. `whenPost` or `whenGet`).
- Always use `TestFixture`.
- Prefer `.whenCommand(...)`, `.expectEvents(...)`, `.expectResult(...)`.
- Use external JSON resources for all test inputs and expectations.
- Use simple types in JSON resources when referring to classes, using `"@class" : "TheClass"`.
- You can extend from other JSON resources using `"@extends" : "other-file.json"`.
- If a newly added type can't be found through its simple type, try running `mvn clean` once.
- One `whenCommand`, `whenQuery`, or `whenPost` per chain; multiple chains are allowed using `.andThen()`.
- Avoid `@ExtendWith(MockitoExtension.class)` or other mocking patterns unless explicitly required.
- Do not use Spring in unit/integration tests (`@SpringBootTest`, `@Autowired`). Use `TestFixture.create()` instead.
- Avoid redundant test execution: Do not run `@Nested` test classes individually if you are already executing the top-level (owner) test class, as this leads to duplicate results and wasted time.

---

## Best Practices

- Route commands using `@RoutingKey` on ID fields; param names must match entity field names.
- Use `@Aggregate(searchable = true)` to enable document indexing.
- Extract static properties into a dedicated value object (e.g., `ProjectDetails`) and reference it in both the aggregate and its creation/update commands (typically using `@Valid`).
- When dealing with decimal properties (e.g., weight, price, dimensions) in entities or value objects, always prefer `BigDecimal` over `double` or `float` to avoid precision issues.
- Expose search via queries that use `Fluxzero.search(...)`.
- **Upcasting**: Implement upcasters when there is existing data that would otherwise fail to deserialize. When a model change is required, **always ask the user** if an upcaster is necessary before implementing one.

---

## Common Pitfalls to Avoid

| Don’t                                                                 | Do                                                                           |
|-----------------------------------------------------------------------|------------------------------------------------------------------------------|
| Generate IDs inside `@Apply`                                          | Generate in endpoint or command via `Fluxzero.generateId(...)`               |
| Use `System.currentTimeMillis()` inside `@Apply`                      | Inject time or pass it in as a parameter                                     |
| Guess the structure of tests                                          | Use examples from `code-samples.md` and `quick-ref.md`                       |
| Mix `@Apply` and `@HandleCommand` in same class                       | Keep them separate                                                           |
| Add the userId of the current user as property to commands or queries | Inject the user as parameter in `@Apply`, `@AssertLegal`, or handler methods |
| Add entity existence checks through `@AssertLegal`                    | Rely on the SDK’s implicit structural existence enforcement                  |
| Use constructors or builders for command inputs in tests              | Rely on the SDK’s ability to load and extend JSON files                      |
| Always use JSON for simple query inputs in tests                      | Use constructors for simple queries to improve readability and type safety   |
| Load other aggregates inside `@Apply`                                 | Resolve needed data in sagas or endpoints and pass it in the command payload |
| Use `Fluxzero.search(...)` inside `@Apply`                            | Resolve needed data in sagas or endpoints and pass it in the command payload |

---

## Import Rules

All classes from the Fluxzero SDK must be imported using their exact fully qualified names.

**Do not invent imports.** Always:

- Look it up in `fluxzero-fqns-grouped.txt`.
- If it’s not listed, ask the user to clarify or omit the import. Never make it up!