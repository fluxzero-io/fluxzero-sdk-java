Use scheduling when a message should run later or periodically. Keep time deterministic with `Fluxzero.currentTime()` or injected time; do not use `Instant.now()` in scheduling logic.

For deadline tests, prove the required boundary rather than only the happy-path delivery: exact active-schedule counts,
cleanup after every relevant terminal path, stale or duplicate delivery, and the distinction between synthetic schedule
seeding and persistence-backed restart belong in the verification-boundaries and reconstruction guidance.

When one of several competing commands is rejected, identify that command and assert that no active schedule carries
its ID or payload; then prove every remaining schedule belongs to the winner. `givenSchedules(...)` and
`givenScheduledCommands(...)` supply expected scheduler state to a fixture. They can prove behavior from that state, but
not that an earlier application persisted it or that a fresh application restored it without reseeding.

Before calling deadline behavior complete, keep these evidence rows independent: exact creation ID and deadline; the
value immediately before and at each boundary; the complete active set after every terminal path; stale and duplicate
delivery against every materially different current state; and synthetic reconstruction versus retained-store restart.
One successful expiry or one terminal cleanup path does not prove the symmetric rows.

Choose the dispatch shape deliberately:

- `Fluxzero.schedule(...)` publishes a schedule message handled by `@HandleSchedule`.
- `Fluxzero.scheduleCommand(...)` dispatches the payload as a command at the deadline.
- `Fluxzero.schedulePeriodic(...)` uses `@Periodic` metadata.
- `Fluxzero.cancelSchedule(...)` cancels by schedule ID.

```java
Fluxzero.schedule(
    new ExpireInvite(inviteId),
    "ExpireInvite-" + inviteId,
    Duration.ofDays(7)
);
```

```java
Fluxzero.scheduleCommand(
    new ArchiveProject(projectId),
    "ArchiveProject-" + projectId,
    Fluxzero.currentTime().plus(Duration.ofDays(30))
);
```

Schedule IDs are part of the contract. If a schedule with the same ID already exists, the default behavior replaces it.
Use the overloads with `ifAbsent = true` when a create-if-missing semantic is required. That flag checks the active set;
it does not remember that an ID existed before cancellation. In a tracked event consumer, replaying an older event can
therefore recreate cancelled work. Reconcile the complete desired active set from current aggregate state instead of
blindly repeating the historical event's effect.

When a deadline is caused by a new aggregate transition, do not schedule or cancel it immediately after `assertAndApply(...).get()` in the same handler. That value is not a commit acknowledgement. Persist the deadline intent, then let a registered tracked post-commit consumer create or cancel the stable schedule; read aggregate commit and effect boundaries.

```java
Fluxzero.schedule(
    new SendReminder(taskId),
    "Reminder-" + taskId,
    Fluxzero.currentTime().plus(Duration.ofHours(2))
);
```

Handle schedule payloads with `@HandleSchedule`. For an ordinary non-periodic chain, returning a `Duration` schedules
a follow-up run while `null` or `void` creates no next schedule. `@Periodic` is different: its metadata continues after
a `null`/`void` result. Read periodic controls before combining handler return values with recurring scheduling.

```java
@HandleSchedule
Duration poll(PollProvider tick) {
    pollExternalProvider();
    return Duration.ofMinutes(5);
}
```

Use `@Periodic` for recurring schedules:

```java
@Periodic(delay = 5, timeUnit = TimeUnit.MINUTES)
public record PollProvider() {
}
```

```java
@Periodic(cron = "0 0 * * MON", timeZone = "Europe/Amsterdam")
public record WeeklyDigest() {
}
```

Throw `CancelPeriodic` from the handler to stop a periodic schedule from inside its own execution.

Test scheduling with `TestFixture` time movement and schedule assertions. Do not sleep in tests; advance fixture time to
trigger schedules. To prove behavior from a supplied deadline, use synthetic reconstruction in a new fixture with
`givenScheduledCommands(...)` for command deadlines or `givenSchedules(...)` for ordinary schedule payloads. Those
Given calls do not prove that a schedule survived persistence restart; that requires a retained external runtime and a
fresh application without manual reseeding.
