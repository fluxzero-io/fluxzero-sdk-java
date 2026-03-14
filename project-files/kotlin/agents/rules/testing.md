# Testing with TestFixture

<a name="testfixture"></a>

Fluxzero provides a specialized `TestFixture` to verify your domain logic and web endpoints without requiring external
mocks, databases, or complex framework wiring.

---

## Quick Navigation

- [Core Principles](#core-rules)
- [Configuration & Handlers](#configuration)
- [Test Phases (Given/When/Then)](#test-phases)
    - [Given Phase (Setup)](#given-phase)
    - [When Phase (Execution)](#when-phase)
    - [Then Phase (Assertion)](#then-phase)
- [Testing with JSON](#json-testing)
- [Advanced Testing Patterns](#advanced-testing)
    - [Time & Schedules](#time-schedules)
    - [User Context](#user-context)
    - [Search & Document Testing](#search-testing)
    - [Chaining & Result Mapping](#chaining)
- [Large Preconditions](#large-preconditions)
- [Mocking Time & Context](#mocking-context)
- [Testing Web Endpoints](#web-testing)

---

<a name="core-rules"></a>

## Core Principles

1. **Logic-First Testing**: Focus tests on the core domain (Commands, Queries, Events).
2. **External JSON**: Use JSON files for all complex inputs and expectations to keep tests readable.
3. **FQN in JSON**: Always use Fully Qualified Names (e.g., `io.fluxzero.app.api.CreateOrder`) for the `@class` property
   in JSON resources.
4. **No Spring/Mocks**: Avoid `@SpringBootTest` or Mockito. Use `TestFixture.create()` for lightweight, isolated tests.

---

<a name="configuration"></a>

## Configuration & Handlers

### Registering Handlers

The `TestFixture` manages an internal Fluxzero instance. You must register the handlers you want to test.

#### Synchronous vs. Asynchronous Testing

- **`TestFixture.create(...)` (Standard)**: Processes messages synchronously and predictably. This is the recommended
  default for most tests.
- **`TestFixture.createAsync(...)` (Realistic)**: Processes messages asynchronously, mirroring the production
  environment. Use this when you need to test concurrency, complex timing, or asynchronous coordination.
  Required for testing `@HandleError` behavior.

#### Registration Patterns

- **Class-based Registration (Recommended)**: Always register handlers by `Class` (e.g., `MyHandler::class`). This is
  mandatory for `@Stateful` sagas and `@SocketEndpoint`s because they contain internal properties managed by the SDK.
- **Instance-based Registration**: Possible for simple, stateless `@Component`s.

[//]: # (@formatter:off)
```kotlin
// At creation
val fixture = TestFixture.create(MyHandler::class, MySaga::class)

// Or during setup
fixture.registerHandlers(OtherHandler::class)
```
[//]: # (@formatter:on)

### Customizing Fluxzero

If you need to tune the Fluxzero instance (e.g., adding interceptors), pass a `DefaultFluxzero.builder()` to the
fixture.

Very short note: interceptors registered through Java `ServiceLoader` are also auto-loaded by the fixture, so they can
affect tests unless you isolate or avoid those registrations.

[//]: # (@formatter:off)
```kotlin
val fixture = TestFixture.create(
    DefaultFluxzero.builder().handlerInterceptor(MyInterceptor()), 
    MyHandler::class
)
```
[//]: # (@formatter:on)

### Setting Properties

Use `withProperty` to set application-level properties for the duration of the test.

[//]: # (@formatter:off)
```kotlin
fixture.withProperty("stripe.url", "http://mock-stripe")
```
[//]: # (@formatter:on)

---

## Test Phases (Given/When/Then)

The `TestFixture` follows a fluent API mirroring the behavior of your application.

<a name="given-phase"></a>

### Given Phase (Setup)

Use this phase to declare all prior context.

**Important Note on Side Effects**: In the `TestFixture`, all preconditions (commands, events, etc.) are processed
**fully and synchronously** before the `When` phase begins. This includes all asynchronous side effects from your
registered handlers (e.g., event handlers triggered by a command). The system is guaranteed to be "at rest" before your
test action is executed.
This also means you can safely test asynchronous flows: the fixture waits until processing is complete.

Any effects introduced during this phase are **ignored** by the `Then` phase assertions.

| Method                        | Usage                                              |
|:------------------------------|:---------------------------------------------------|
| `givenCommands(...)`          | Issues commands before the test triggers.          |
| `givenEvents(...)`            | Publishes events into the stream.                  |
| `givenAppliedEvents(id, ...)` | Replays events into a specific aggregate instance. |
| `givenDocument(...)`          | Pre-populates the search index with documents.     |
| `givenStateful(saga)`         | Pre-registers a stateful handler instance.         |
| `givenExpiredSchedules(...)`  | Simulates timers that have already triggered.      |

<a name="when-phase"></a>

### When Phase (Execution)

Specifies the action that triggers the behavior under test. Use **constructors for simple queries** to improve
readability and explicitly define the expected return type.

| Method                             | Usage                                      |
|:-----------------------------------|:-------------------------------------------|
| `whenCommand(cmd)`                 | Executes a domain command.                 |
| `whenQuery(query)`                 | Executes a data retrieval query.           |
| `whenSearching(coll, constraints)` | Verifies search logic and indexing.        |
| `whenUpcasting(data)`              | Tests versioning and schema evolution.     |
| `whenTimeElapses(duration)`        | Advances time and processes due schedules. |

**Example: Constructor Query**

[//]: # (@formatter:off)
```kotlin
fixture.whenQuery(GetProject(projectId))
       .expectResult(Project::class)
```
[//]: # (@formatter:on)

<a name="then-phase"></a>

### Then Phase (Assertion)

Assert and validate the outcomes of the `When` phase. Use **Error Interfaces** for clean exception assertions.

| Assertion Type                | Usage                                                       |
|:------------------------------|:------------------------------------------------------------|
| `expectEvents(...)`           | Asserts that specific events were published.                |
| `expectOnlyEvents(...)`       | Strict check: no other events allowed.                      |
| `expectNoEventsLike(...)`     | Negative check: specific events must NOT occur.             |
| `expectResult(value)`         | Verifies the return value of the handler.                   |
| `expectExceptionalResult(ex)` | Verifies that an invariant was enforced (exception thrown). |
| `expectError(...)`            | Catches "swallowed" or logged handler errors.               |
| `expectThat(fluxzero -> ...)` | Arbitrary checks against system state.                      |

**Example: Domain Error Assertion**

[//]: # (@formatter:off)
```kotlin
fixture.whenCommand(CloseProject(projectId))
       .expectExceptionalResult(ProjectErrors.alreadyClosed)
```
[//]: # (@formatter:on)

---

<a name="json-testing"></a>

## Testing with JSON

JSON files are stored in `src/test/resources` and should mirror your domain package structure.

### Using FQN

To ensure reliable type resolution, always use the full class path in the `@class` property.

[//]: # (@formatter:off)
```json
{
  "@class": "io.fluxzero.orders.api.CreateOrder",
  "orderId": "ORD-123",
  "amount": 50.0
}
```
[//]: # (@formatter:on)

### Extending JSON (@extends)

Reuse base configurations and override specific fields. You can use **absolute paths** (starting with `/`) to reference
JSON resources from other packages.

[//]: # (@formatter:off)
```json
{
  "@extends": "/shared/base-order.json",
  "amount": 100.0
}
```
[//]: # (@formatter:on)

---

<a name="advanced-testing"></a>

## Advanced Testing Patterns

<a name="time-schedules"></a>

### Time & Schedules

Fluxzero allows precise control over time-based behavior. Use `givenExpiredSchedules` to trigger past timers before the
test starts, and `whenTimeElapses` to simulate time passing during the test.

[//]: # (@formatter:off)
```kotlin
fixture
    .givenExpiredSchedules(TerminateAccount(...))
    .whenTimeElapses(Duration.ofDays(30))
    .expectEvents(DeleteAccount(...))
```
[//]: # (@formatter:on)

<a name="user-context"></a>

### User Context & Default User

- **Default User**: By default, tests run as the **System User** (`UserProvider#getSystemUser()`), which typically has
  full permissions.
- **Resolving Users**: The `UserProvider#getUserById(Object userId)` method is used to resolve user identifiers passed
  to `when...ByUser`.

[//]: # (@formatter:off)
```kotlin
fixture
    .whenQueryByUser("admin-user", GetSystemStats())
    .expectResult { stats -> stats.totalOrders() > 0 }
```
[//]: # (@formatter:on)

<a name="search-testing"></a>

### Search & Document Testing

Verify that your search constraints and facets work as expected.

[//]: # (@formatter:off)
```kotlin
fixture
    .givenDocument(Order("ORD-1", "PAID"), "orders")
    .whenSearching(Order::class, MatchConstraint.match("PAID", "status"))
    .expectResultContaining(Order("ORD-1", "PAID"))
```
[//]: # (@formatter:on)

<a name="chaining"></a>

### Chaining & Result Mapping

Use `.andThen()` to build multi-step scenarios. You can use `.asWebParameter("name")` to map a result (like a generated
ID) into subsequent web requests.

Substitution behavior:

- `.asWebParameter("name")` maps a specific earlier result to `{name}` placeholders.
- Without explicit mapping, placeholder substitution is positional by prior `when...` results.
- Use explicit names when chaining multiple IDs to avoid ambiguity.

[//]: # (@formatter:off)
```kotlin
fixture
    .whenPost("/api/users", "create-user.json")
    .asWebParameter("userId")
    .andThen()
    .whenGet("/api/users/{userId}")
    .expectResult(User::class)
```
[//]: # (@formatter:on)

[//]: # (@formatter:off)
```kotlin
fixture
    .whenPost("/api/projects", "create-project.json")   // returns projectId
    .asWebParameter("projectId")
    .andThen()
    .whenPost("/api/projects/{projectId}/tasks", "create-task.json") // returns taskId
    .asWebParameter("taskId")
    .andThen()
    .whenGet("/api/projects/{projectId}/tasks/{taskId}")
    .expectResult(TaskView::class)
```
[//]: # (@formatter:on)

---

<a name="large-preconditions"></a>

## Large Preconditions

For tests that require complex setup, issuing many `givenCommands` can become verbose.

- **JSON Inheritance**: Use `@extends` to build on top of base scenarios.
- **Fixture Commands**: Group setup commands into a single JSON array file and load it using
  `givenCommands("/setup/baseline.json")`.
- **Chained setup**: You can chain multiple `given` methods.
- **Manual JSON loading**: For custom setup code, use `JsonUtils` to map JSON resources to typed Java/Kotlin objects.

[//]: # (@formatter:off)
```kotlin
fixture
    .givenCommands("/baseline.json")
    .givenDocument(Config(...), "settings")
    .whenCommand(...)
```
[//]: # (@formatter:on)

---

<a name="mocking-context"></a>

## Mocking Time & Context

Ensure your tests are deterministic by controlling the environment.

- **Current Time**: Use `whenTimeElapses` to move time forward. The `TestFixture` maintains its own clock.
- **Identifiers**: TestFixtures use predictable auto-generated IDs, starting at `"0"`. If you need to assert a specific
  auto-generated ID, you can fix the ID generator using `DefaultFluxzero.builder().identityProvider(...)` in the fixture constructor.
- **User context**: Use `when...ByUser(userId, ...)` to simulate different users.

---

<a name="web-testing"></a>

## Testing Web Endpoints

While most testing should focus on core logic, each web endpoint should have at least one test to verify routing and
mapping.

### Internal Endpoints

[//]: # (@formatter:off)
```kotlin
@Nested
inner class ProjectsEndpointTests {
    val fixture = TestFixture.create(ProjectsEndpoint::class)

    @Test
    fun testPostEndpoint() {
        fixture
            .whenPost("/api/projects", "/projects/create-request.json")
            .expectResult(ProjectId::class)
            .expectEvents(CreateProject::class)
    }
}
```
[//]: # (@formatter:on)

When testing raw web request results, the fixture does not infer the response generic type. Cast it explicitly:

[//]: # (@formatter:off)
```kotlin
fixture
    .whenGet("/api/bookings")
    .expectResult { result: List<BookingProfile> -> result.size == 1 }
```
[//]: # (@formatter:on)

### Mocking External Backends (Real Web Handlers)

You can mock external backends (like Stripe or GitHub) by registering a component that handles requests to the external URL. This allows you to test your integration logic with "real" web handlers and URLs without actual network calls.

[//]: # (@formatter:off)
```kotlin
@Component
class StripeMock {
    @HandlePost("https://api.stripe.com/v1/charges")
    fun mockCharge(request: ChargeRequest): WebResponse {
        return WebResponse.ok(ChargeResponse("ch_success"))
    }
}

TestFixture fixture = TestFixture.create(MyPaymentHandler::class, StripeMock::class);

@Test
fun testStripeIntegration() {
    fixture.whenCommand(ProcessPayment(amount))
           .expectEvents(PaymentSucceeded::class)
}
```
[//]: # (@formatter:on)
