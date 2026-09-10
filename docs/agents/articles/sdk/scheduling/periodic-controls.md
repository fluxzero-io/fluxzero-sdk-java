Use these controls when a recurring schedule needs more than a fixed delay or cron expression. Periodic scheduling has
different return semantics from an ordinary handler-created one-off chain, so test the periodic boundary explicitly.

## Start and identify the schedule deliberately

`@Periodic` can annotate the payload type or its `@HandleSchedule` method. Configure one of `cron` or a positive
`delay`; cron takes precedence. `scheduleId` defaults to the payload class name, so set it when two logical recurring
jobs use the same payload type.

`autoStart = true` starts or reconciles the periodic schedule during handler registration. Set it to `false` when an
application action must call `Fluxzero.schedulePeriodic(...)` explicitly. `initialDelay` controls only the first run;
subsequent runs use `delay` or cron. Avoid depending on an implicit initial-delay default: set the intended value and
test whether the first run is immediate or delayed.

Cron values may reference an application property. Use `Periodic.DISABLED` (`"-"`) or a property default such as
`${maintenanceCron:-}` when absence should disable the schedule rather than make application startup fail.

## Distinguish normal and error continuation

```java
@Periodic(
        delay = 10,
        delayAfterError = 1,
        timeUnit = TimeUnit.MINUTES,
        continueOnError = true)
record RefreshReferenceData() {
}
```

`continueOnError = true` keeps the periodic schedule after a handler failure. A non-negative `delayAfterError`
temporarily replaces the normal fixed delay after that failure. With `continueOnError = false`, a failure stops the
periodic schedule and `delayAfterError` is ignored. Use continuation only for an idempotent operation whose failure is
observable; otherwise a permanently failing task can become a quiet retry loop.

## Handler return semantics

For a periodic handler:

- `void` or `null` continues with the annotation's periodic settings;
- `Duration` or `Instant` overrides the next execution;
- another payload or `Schedule` defines the next scheduled message;
- `throw new CancelPeriodic()` stops the recurring schedule intentionally.

Do not copy the one-off rule that a `null` result ends a manually returned-duration chain onto `@Periodic`: periodic
metadata supplies the next run. Conversely, `CancelPeriodic` is control flow, not an error for the consumer policy to
retry.

## Test first, next, error, and cancellation behavior

Anchor the fixture with `atFixedTime(...)`. Use `whenTimeElapses(...)` or `whenTimeAdvancesTo(...)` for ordinary clock
movement and `whenScheduleExpires(...)` when the exact scheduled payload is the action under test. Seed restart state
with `givenSchedules(...)`, `givenScheduledCommands(...)`, or `givenExpiredSchedules(...)` according to the durable
shape being reconstructed.

Assert both newly created schedules (`expectOnlyNewSchedules`) and the complete active set (`expectOnlySchedules`) at
the boundary where each matters. Cover the configured first delay, a normal next run, a throwing run, the
`continueOnError` branch, explicit cancellation, and synthetic schedule reconstruction without sleeping. Use a retained
external runtime and a fresh application without manual reseeding when the test must prove persistence-backed restart.
