Use `TestFixture` for local behavior tests. Register external handler components and annotation-driven class handlers that participate in the scenario. Do not register every ordinary local self-handling command/query payload class: dispatching its payload lets the local registry discover its own handler, while explicit registration of a zero-component class with a zero-parameter method can make that method match unrelated messages.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

During an active Fluxzero dev session, write and change these tests normally but let the dev server execute them. It
selects changed or impacted tests and reports the exact decision through `get_test_status`; do not start a parallel
wrapper test run. Selective execution changes how quickly evidence is produced, not what evidence the test must prove.

Before declaring the implementation complete, map every material requirement to one direct observation that would fail
if that requirement were removed. Use the verification-boundaries inventory for independent validation, transition,
rejected-effect cleanup, replay, generated-contract, and restart proof; use scenario phases for the exact fixture shape.

Typical checks:

- Command tests dispatch the command and assert emitted events, errors, follow-up messages, or state observed after a later load/query.
- Query tests assert typed results.
- Web endpoint tests call `whenGet`, `whenPost`, their `...ByUser` variants, or `whenWebRequestByUser`.
- Integration side effects can be asserted with `expectWebRequest`.

`TestFixture.create(...)` runs synchronously in the same thread and is the default for focused behavior tests. Use `TestFixture.createAsync(...)` when testing asynchronous consumers, tracking behavior, and `@HandleError`. Given steps are processed and at rest before the When step runs; later Then assertions focus on the When step, not every setup message. For multi-step helpers, exact message counts, result extraction, and active-versus-new schedules, read scenario phases.

Use the fixture APIs rather than mocking internals:

- `givenCommands`, `givenEvents`, `givenDocuments`, `givenStateful`, and schedule setup for preconditions.
- `whenCommand`, `whenQuery`, `whenSearching`, `whenGet`/`whenPost`, time movement, and upcasting for behavior under test.
- Then assertions for events, documents, command/query results, exceptions, search results, web responses, metrics, schedules, and outgoing web requests.

Do not directly call `@Apply`, `@AssertLegal`, or handler methods as the main proof of domain behavior. Such calls bypass dispatch metadata, validation, authorization, event storage, and replay. A narrow unit test may supplement a fixture scenario, but each product rule must be proven through its real Fluxzero command/query boundary.

JSON fixtures use Fluxzero serialization metadata such as `@class` and `@extends`. Keep JSON examples close to the domain test resources and use them when historical payload shape matters.

For registered types, `@class` may be a unique simple name or distinguishing suffix; otherwise use an FQN. Use
`@revision` for historical serialized roots, and type aliases for compatible historical class/package renames.
Arrays and NDJSON resources can mix current and revisioned records. Read the focused JSON-fixture and revisioned-JSON
articles before constructing migration fixtures.

The default fixture wrapper returns the system user when no active user is present and delegates ID lookup to a provider registered on the builder. Use `whenCommandByUser`, `whenQueryByUser`, and related helpers for user-sensitive rules. To exercise a configured provider's `getUserById(...)`, keep the normal fixture wrapper and pass a user ID, not a constructed `User`, in at least one scenario. Use `withProductionUserProvider()` separately for unauthenticated/no-fallback behavior; it switches to the process-wide `UserProvider.defaultUserProvider` and does not preserve an arbitrary provider registered only on that fixture builder.

Neither a constructed user nor a user ID proves HTTP credential establishment: `...ByUser` helpers add or resolve `$user` directly. When a frontend signs in with a cookie or bearer token, use HTTP authentication boundary testing and at least one raw `whenWebRequest(...)` carrying that credential.

For ownership rules, dispatch as a named user, observe the state in a later step so aggregate reconstruction is exercised, then try the follow-up command as both the owner and another user. Fix time-dependent scenarios with `atFixedTime(...)` or fixture time movement; never make a test depend on the machine's current date.

When a product brief asks for a frontend-callable API, use a two-layer test matrix:

| Layer | What to prove |
| --- | --- |
| Command/query | Domain success, illegal transitions, duplicates, validation, authorization, search/count results, and side effects |
| HTTP/WebSocket | Every advertised operation is reachable with the expected request/response fields, caller identity, status/error mapping, and live delivery |

At minimum, add one transport test per frontend-callable operation plus negative transport tests for product-critical constraints. Directly invoking an endpoint method can be useful as a narrow unit test, but it does not prove route binding, payload mapping, user propagation, or response conversion. Use the focused routed HTTP endpoint testing article to build an operation matrix and keep domain, routed HTTP, and served OpenAPI evidence separate.

Turn the product brief and implemented domain errors into a coverage checklist. Cover every required success path and every implemented error branch for those requirements, including close-before-open style transitions. Exercise bulk/import commands as bulk operations, including successful multi-item execution, duplicate-in-request and existing-state collisions, authorization, and the selected failure/visibility semantics; testing only the child command is insufficient. Use the focused bulk-workflow matrix to distinguish local nested rollback, external partial commit, fail-fast behavior, continue-and-report best effort, and staged all-or-nothing behavior.

For independent validation, roles, query fields, and state branches, use the behavior-matrix article. Keep all control inputs valid, change one cause per negative scenario, assert the intended exception type/path or domain rule, and prove both exact-role and inherited-role outcomes. A single payload that violates two constraints cannot protect either constraint independently.
For a lifecycle, enumerate each command against `MISSING` and every source state, including repeated and conflicting
terminal outcomes. When a product requires the same transition from two source states, test both. Pair successful
event/metric assertions with rejected-command assertions for no event, no custom metric, and unchanged state.

For WebSocket endpoints, register `@SocketEndpoint` classes by class and model socket frames as websocket `WebRequest` messages with a stable `sessionId`. A live view needs two fixture scenarios: opening after seeded state must assert the initial snapshot payload, and an already-open session must receive a `WebResponse` after the domain command/event. When the product says all open viewers receive updates, open two distinct session IDs as Given state and assert one outgoing response for each ID. Querying a projection after the command proves state, not socket delivery. These are fixture-level socket behavior tests; use a local runtime client only when a real network handshake or serialization smoke test is required.

For generated OpenAPI or other discoverable contracts, request the served document through `TestFixture.whenGet(...)`, parse it, and assert paths, methods, operation IDs, request fields, and response schemas structurally. Reflection over `@ApiDoc` does not prove annotation processing or runtime route registration. Broad substring checks can pass when an operation is missing or bound to the wrong shape. Generated OpenAPI intentionally omits WebSocket lifecycle pseudo-methods; document that protocol separately and prove the socket route and delivery with WebSocket fixture scenarios.

For schema evolution, read serialization. Increment `@Revision` on changed top-level payloads/documents and verify upcasters with `TestFixture.whenUpcasting(...)`.

For tracked handler failures that require compensation or a correction, use an asynchronous fixture and the error-corrections article. Assert the original `expectError(...)`, the triggered follow-up message, and its final observable result separately.

Prefer behavior assertions over direct state inspection. Do not add Mockito for core Fluxzero behavior; the fixture provides the runtime wiring.

When behavior depends on aliases, associations, or deadlines reconstructed from durable artifacts, use the focused
reconstruction article and name the tested boundary precisely. A new default fixture seeded with recorded artifacts is
synthetic reconstruction; persistence-backed restart requires a retained external runtime/client and a fresh application
without manual reseeding. For two independently correlated components, use the correlated-workflow matrix and the
behavior matrix so both directions, every message/reference pairing, late decisions, repeated compensation suppression,
interleaved workflows, and reconstructed routes are falsifiable.
