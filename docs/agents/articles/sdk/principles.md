# SDK Principles

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
    IDs, enums, booleans, timestamps) that are intentionally changed by a single command.
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
