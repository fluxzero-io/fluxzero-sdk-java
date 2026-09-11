Use this focused signature map when Java inference turns a fixture result into `Object`, a schedule constructor is

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
unclear, or a helper returns the wrong phase type. These are the application-facing signatures; keep conceptual test
design in the linked testing articles.

## Choose the result path before calling domain accessors

| Fixture action | Declared result | Type it before domain access |
| --- | --- | --- |
| `whenCommand(...)`, `whenQuery(...)` | `Then<Object>` | `.<ResultType>expectResult(...)` or `expectResult(ResultType.class)` |
| `whenSearching(...)` | `Then<List<R>>` | bind `R` on `fixture.<DocumentType>whenSearching(...)` |
| `whenGet(...)`, `whenPost(...)`, `whenWebRequest(...)` | transport result through `Then<Object>` | `expectWebResult(response -> response.<BodyType>getPayloadAs(BodyType.class)...)` |

Choose this at the first assertion. If one untyped lambda calls `status()`, `total()`, `items()`, and an identifier
accessor, the compiler can report every method as missing from `java.lang.Object`; those diagnostics share one type
inference cause. One supported type witness or class-narrowing step fixes the root rather than each accessor.

## Fixture factories and phase types

```java
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.test.Given;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.test.Then;

TestFixture TestFixture.create(Object... handlers)
TestFixture TestFixture.create(FluxzeroBuilder builder, Object... handlers)
TestFixture TestFixture.createAsync(Object... handlers)
TestFixture TestFixture.createAsync(FluxzeroBuilder builder, Object... handlers)
TestFixture TestFixture.createAsync(
        FluxzeroBuilder builder, Client client, Object... handlers)

Then<Object> TestFixture.whenQuery(Object query)
Then<Object> TestFixture.whenQueryByUser(Object user, Object query)
Then<Object> TestFixture.whenCommand(Object command)
Then<Object> TestFixture.whenPost(String path, Object payload)
Then<Object> TestFixture.whenGet(String path)
Then<Object> TestFixture.whenWebRequest(WebRequest request)
<R> Then<List<R>> TestFixture.whenSearching(
        Object collection, UnaryOperator<Search> constraints)
Given<?> Then.andThen()
```

The overload accepting a `Client` is an integration seam for an externally managed client. Default factories create an
in-memory client. Passing a client does not by itself prove persistence or a process restart; the external store/runtime
must outlive the first application instance and the second instance must reconnect without manual reseeding.

## Diagnose accessor methods missing on `java.lang.Object`

The command, query, and web helpers above return `Then<Object>`. A compiler error such as `cannot find symbol: method
status() on java.lang.Object` means the assertion lambda still sees that declared result type; it is not evidence that
the domain accessor is absent. For command or query results, provide the generic method witness or establish the result
class first:

```java
fixture.whenQuery(new GetItem(itemId))
        .<ItemView>expectResult(view ->
                view.itemId().equals(itemId)
                && view.enabled());

fixture.whenQuery(new GetItem(itemId))
        .expectResult(ItemView.class)
        .expectResult(view -> view.itemId().equals(itemId));

fixture.whenQuery(new ListItems())
        .<List<ItemView>>expectResult(items ->
                items.size() == 2
                && items.getFirst().enabled());
```

For direct search, bind `R` on `whenSearching(...)` instead:

```java
fixture.<ItemView>whenSearching("item-views", search -> search)
        .expectResult(items -> items.stream()
                .allMatch(ItemView::enabled));
```

Web helpers return a transport result through `WebResponse`. Do not call a domain accessor directly in
`expectResult(...)`; decode the payload in `expectWebResult(...)`. `getPayloadAs(...)` is declared as
`<R> R getPayloadAs(Type)`, not `getPayloadAs(Class<R>)`, so the class argument does not bind `R`. Add the explicit
method type witness before chaining a domain accessor:

```java
fixture.whenGet("/api/items/item-1")
        .expectWebResult(response -> response.getStatus() == 200
                && response.<ItemView>getPayloadAs(ItemView.class).enabled());
```

An explicitly typed local variable is equivalent. Without either form,
`response.getPayloadAs(ItemView.class).enabled()` reports `enabled()` missing on `java.lang.Object`. The same rule
applies to `WebRequest#getPayloadAs(Type)` in outbound-request assertions.

If that diagnostic persists, check the static type at the first fixture call and the assertion method being selected
before inspecting the whole fixture bytecode surface.

Relevant `Then<R>` result signatures are:

```java
Then<R> expectResult(Object expected)
<R2 extends R> Then<R2> expectResult(Class<? extends R2> type)
<R2 extends R> Then<R2> expectResult(ThrowingPredicate<R2> predicate)
Then<R> expectNoResult()
Then<R> expectExceptionalResult(Class<? extends Throwable> type)
```

`expectNoResult()` means a successful `null` result. It is not an empty list or an exception. The query's
`Request<R>` generic is always the unwrapped public value type; read query result contracts when a handler returns
`Optional<R>` or the compiler reports that the request handler should return `Optional<...>`.

## Schedule constructors and setup methods

```java
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.scheduling.Schedule;

new Schedule(payload, deadline)
new Schedule(payload, scheduleId, deadline)
new Schedule(payload, metadata, scheduleId, deadline)

fixture.givenSchedules(Schedule... schedules)
fixture.givenScheduledCommands(Schedule... schedules)
```

Use `givenSchedules(...)` for payloads handled by `@HandleSchedule`. Use `givenScheduledCommands(...)` when the persisted
schedule dispatches its payload as a command. `Schedule#getScheduleId()` and `Schedule#getDeadline()` expose the exact
identity and deadline for predicate assertions.

| Setup call | It proves | It does not prove |
| --- | --- | --- |
| `givenSchedules(...)` / `givenScheduledCommands(...)` | Behavior from the scheduler artifacts supplied by this test | That instance A stored them or instance B recovered them |
| `givenAppliedEvents(...)` | Aggregate reconstruction from the history supplied by this test | Retained event persistence across application instances |

If a Given call supplies the exact schedule later asserted, describe the test as synthetic reconstruction from supplied
state. Persistence-backed restart requires a retained external client/runtime and a fresh application without manual
reseeding of the events, documents, or schedules being proved.

```java
expectOnlyNewSchedules(Object... expected)
expectOnlySchedules(Object... expected)
expectOnlyScheduledCommands(Object... expected)
```

The first inspects schedules created in this phase; the second inspects all active schedules after it; the third matches
scheduled command payloads. Use a `ThrowingPredicate<Schedule>` when both ID and deadline are part of the contract.
