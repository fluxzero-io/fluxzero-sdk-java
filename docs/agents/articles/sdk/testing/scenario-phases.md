Use this for multi-step `TestFixture` tests and helper methods. Each fixture phase has one action (`when...`) followed by assertions about only that action.

Choose the evidence boundary before choosing a phase method. A convenient `when...` call is not proof of every product
requirement: keep independent validation, rejected-effect cleanup, boundary values, replay, and restart claims in the
requirement-to-evidence inventory. Direct durable Given APIs create synthetic reconstruction input; they do not prove a
retained persistence boundary.

Before calling a scenario complete, ask four questions: can every independent invalid field fail while all controls are
valid; is the rejected command or transition identifiable; are all forbidden state and effect categories absent; and,
if the test says “restart,” did a fresh application reconnect to retained storage without supplying the expected events,
documents, or schedules? Keep distinct scenarios when one failure would not falsify all four claims.

The phase types matter. `TestFixture` implements the initial `Given`/`When` API. A `when...` call returns `Then<R>`, implemented by `ResultValidator<R>`. `andThen()` is declared on `Then`, not on `TestFixture`, and its public return type is `Given<?>`.

```java
// Wrong: TestFixture has no andThen() method.
TestFixture next(TestFixture fixture) {
    return fixture.andThen();
}

// Wrong: Then.andThen() has the Given phase contract, not TestFixture.
TestFixture next(Then<?> then) {
    return then.andThen();
}

// Right: retain the phase interfaces.
Given<?> next(Then<?> then) {
    return then.andThen();
}
```

Import `io.fluxzero.sdk.test.Given`, `io.fluxzero.sdk.test.Then`, and `io.fluxzero.sdk.test.TestFixture`. Do not cast the next phase back to `TestFixture`; continue with its `Given`/`When` methods.

## Advance explicitly

`andThen()` starts the next phase. It clears the captured result, messages, errors, metrics, and newly-created schedules used by assertions, while preserving durable fixture state, current time, active schedules, registered handlers, and configuration.

```java
fixture.whenCommand(startAssetJob)
        .expectResult(AssetJob.class)
        .expectOnlyEvents(RecordAssetJob.class)
        .andThen()
        .whenQuery(new GetAssetJob(startAssetJob.assetJobId()))
        .expectResult((AssetJob job) -> job.status() == PENDING);
```

Do not expect the second phase to repeat the first phase's event. Conversely, omitting `andThen()` is not a way to accumulate several actions into one assertion window: the fluent API models one When/Then action at a time.

Reading a result does not advance the fixture:

```java
var started = fixture.whenCommand(startAssetJob)
        .expectResult(AssetJob.class);
AssetJob job = started.getResult(AssetJob.class);

started.andThen()
        .whenCommand(new CaptionCompleted(job.captionReference()))
        .expectOnlyEvents(CaptionCompleted.class);
```

`getResult()` and `getResult(Class<T>)` only return the current phase's value. Call `andThen()` once after extracting it before another action.

## Write helpers that leave a clean next phase

When a helper needs a generated reference, assert the expected type before reading it, then reset the assertion window:

```java
private static AssetJob startAssetJob(TestFixture fixture, StartAssetJob command) {
    var then = fixture.whenCommand(command)
            .expectResult(AssetJob.class);
    AssetJob result = then.getResult(AssetJob.class);
    then.andThen();
    return result;
}
```

Avoid helpers that call `getResult(...)` without first asserting the contract, or that return while the fixture still contains the helper action's outputs. A `void dispatch(...)` helper should likewise call `.andThen()` only after asserting the result/effects the helper promises; otherwise it can hide an exceptional result.

## Choose inclusive or exact assertions

- `expectEvents(a)` means at least the requested matching event occurred; additional events are allowed.
- `expectOnlyEvents(a)` means the event collection has exactly that count and content; duplicates or unrelated events fail.
- The same distinction applies to commands, queries, web requests/responses, metrics, and custom messages.
- Use `expectNoEvents()` or the category's equivalent for an exact empty collection.

For logical-once effects, use `expectOnlyWebRequests(expected)` or an exact predicate/count rather than `expectWebRequests(expected)`: an inclusive assertion does not detect a duplicate.

Schedules have two views:

- `expectNewSchedules` / `expectOnlyNewSchedules` inspect schedules created during the current phase.
- `expectSchedules` / `expectOnlySchedules` inspect all schedules still active after the phase.
- `expectNoNewSchedules()` proves this action created none; `expectNoSchedules()` proves none remain active. They are not interchangeable after cancellation or restart.

Finally, distinguish normal absence from failure. `expectNoResult()` asserts a successful `null` result. `expectExceptionalResult(SomeException.class)` asserts that handling failed with that exception. Calling `getResult()` does not convert, consume, or clear either outcome.
