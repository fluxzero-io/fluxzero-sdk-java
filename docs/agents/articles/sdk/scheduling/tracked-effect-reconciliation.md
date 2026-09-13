Use this when a tracked event consumer creates or cancels delayed work after a Model transition. An old event is a
trigger to inspect **current intent**, not proof that its original deadline still belongs in the active set.

## Persist intent, then reconcile the current Model

Start with ordinary event-sourced Models. A reminder has its own lifecycle and belongs to a project through `@Parent`;
deleting the project also deletes the reminder. The deadline and completion flag are execution state, not descriptive
details. ID types below extend `Id<Project>` and `Id<Reminder>`.

```java
@Model record Project(@EntityId ProjectId projectId) {}
@Model record Reminder(@EntityId ReminderId reminderId, @Parent ProjectId projectId,
                       Instant deadline, boolean completed) {}

record PlanReminder(ReminderId reminderId, ProjectId projectId, Instant deadline) {
    @Apply Reminder apply() { return new Reminder(reminderId, projectId, deadline, false); }
}
record RescheduleReminder(ReminderId reminderId, Instant deadline) {
    @Apply Reminder apply(Reminder current) {
        return new Reminder(reminderId, current.projectId(), deadline, false);
    }
}
record DeleteProject(ProjectId projectId) {
    @Apply Project apply(Project current) { return null; }
}
```

A registered tracked post-commit consumer reconciles the desired active schedule. A sole `Graph<Reminder>` parameter
receives direct changes **and cascaded deletion**. No parent-specific cleanup handler is needed for new commits that
record cascade notifications.

```java
@Consumer(name = "reminder-schedules", singleTracker = true,
          errorHandler = ThrowingErrorHandler.class)
final class ReminderSchedules {
    @HandleEvent
    void changed(Graph<Reminder> change) {
        Reminder current = Fluxzero.loadCurrentGraph(change.id(), Reminder.class).get();
        ScheduleId id = ScheduleId.of("reminder", change.id());
        if (current == null || current.completed()) {
            Fluxzero.cancelSchedule(id);
        } else {
            Fluxzero.scheduleCommand(new RunReminder(current.reminderId(), current.deadline()),
                                     id, current.deadline());
        }
    }
}
```

The Kotlin equivalent uses the same contract:

```kotlin
@Consumer(name = "reminder-schedules", singleTracker = true,
          errorHandler = ThrowingErrorHandler::class)
class ReminderSchedules {
    @HandleEvent
    fun changed(change: Graph<Reminder>) {
        val current = Fluxzero.loadCurrentGraph(change.id(), Reminder::class.java).get()
        val id = ScheduleId.of("reminder", change.id())
        if (current == null || current.completed) {
            Fluxzero.cancelSchedule(id)
        } else {
            Fluxzero.scheduleCommand(RunReminder(current.reminderId, current.deadline), id, current.deadline)
        }
    }
}
```

Register this consumer with the application. `singleTracker = true` serializes its reconciliation work, including
parent-triggered deletion and direct reminder events that may have different routing segments. Do not independently
write the same schedule from another consumer. Failures propagate to tracked retry through `ThrowingErrorHandler`;
stable IDs make replacement and cancellation repeatable.

The injected graph remains pinned to the triggering event. `loadCurrentGraph` deliberately escapes that historical
boundary: redelivering an old creation event must not restore a deleted reminder or an obsolete deadline. This is a
normal use of current reads, not restricted to synchronous nested commands. Schedule writes occur after commit, not
inside `@Apply` or `@AssertLegal`. They are an eventually reconciled effect, not part of the Model transaction.

## Guard the delivered command too

Cancellation cannot recall a command already delivered. An early or stale command must recheck current intent before
applying. Keep clock-dependent decisions in evaluation, not in replayed `@Apply` logic:

```java
record RunReminder(ReminderId reminderId, Instant expectedDeadline) {
    @InterceptApply RunReminder applicable(@Nullable Reminder current) {
        return current == null || current.completed() || !current.deadline().equals(expectedDeadline)
               || Fluxzero.currentTime().isBefore(expectedDeadline) ? null : this;
    }
    @Apply Reminder apply(Reminder current) {
        return new Reminder(reminderId, current.projectId(), current.deadline(), true);
    }
}
```

```kotlin
data class RunReminder(val reminderId: ReminderId, val expectedDeadline: Instant) {
    @InterceptApply
    fun applicable(current: Reminder?): RunReminder? =
        if (current == null || current.completed || current.deadline != expectedDeadline
            || Fluxzero.currentTime().isBefore(expectedDeadline)) null else this

    @Apply
    fun apply(current: Reminder) = current.copy(completed = true)
}
```

This example marks the work completed; any external side effect still needs its own retry/idempotency contract.
If a logical reminder ID can be deleted and recreated with the same deadline, add a generation token to its persisted
intent and command and check that too. A deadline is not a universal identity for an execution.

## Know what `ifAbsent` protects

The example uses replacement semantics: the current Model's deadline is authoritative. `ifAbsent = true` would retain
an existing active schedule with an obsolete deadline. It is useful only when an unchanged schedule should be preserved,
and is not a historical once-only marker. After cancellation, an old event could create the ID again unless current
state prevents creation.

## Assert the complete active command set

`expectOnlyScheduledCommands(...)` checks only commands **scheduled during When**. It can pass while an older
schedule is still active. The local fixture's `expectOnlyActiveScheduledCommands(...)` checks all active scheduled
commands and unwraps their payloads. Use a `Predicate<Schedule>` to include ID, deadline **and** command contents:

```java
record RunTask(String taskId, Instant expectedDeadline) {}

@Test
void replacementLeavesExactlyOneActiveCommand() {
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    Instant oldDeadline = start.plusSeconds(3600);
    Instant deadline = oldDeadline.plusSeconds(600);
    RunTask command = new RunTask("task-1", deadline);

    TestFixture.create().atFixedTime(start)
            .givenScheduledCommands(new Schedule(new RunTask("task-1", oldDeadline), "task-1", oldDeadline))
            .whenExecuting(fc -> Fluxzero.scheduleCommand(command, "task-1", deadline))
            .expectOnlyActiveScheduledCommands((Predicate<Schedule>) s ->
                    s.getScheduleId().equals("task-1")
                    && s.getDeadline().equals(deadline)
                    && s.getPayload().equals(command))
            .andThen().whenExecuting(fc -> Fluxzero.cancelSchedule("task-1"))
            .expectOnlyActiveScheduledCommands().expectNoSchedules();
}
```

If replacement accidentally uses another ID, the assertion fails because both commands remain active.
In an application test, replace `whenExecuting` with the real reschedule/delete command and register its reconciliation
handler; the assertion stays the same. Run the scenario asynchronously too when tracked handlers own reconciliation.

Kotlin uses `Predicate<Schedule> { it.scheduleId == "task-1" && it.deadline == deadline &&
it.getPayload<Any>() == command }`. A plain `Schedule` expectation does **not** compare ID; the predicate does.
This new assertion excludes ordinary `@HandleSchedule` payloads. Use `expectNoSchedules()` for absence of both kinds.
It requires a local scheduling client and fails explicitly for remote clients: observing remote writes is not a
complete inventory. For a retained remote store, verify known IDs through `messageScheduler().getSchedule(id)`
and do not claim that checks of known IDs discover every unknown stale ID.

## Lifecycle test matrix

Test creation, deadline replacement, completion, direct deletion and cascade deletion. Reconcile an old change against
newer current state: it must not restore obsolete work. Exercise stale and early delivered commands, and retry after a
schedule-operation failure. Use exact active-ID/deadline assertions; `expectNoNewSchedules()` alone does not prove that
an obsolete schedule is absent.

Keep reconstruction claims separate. `givenScheduledCommands(...)` and `givenSchedules(...)` supply scheduler state;
they do not prove another application instance recovered persisted schedules. Ordinary Model event reconstruction
requires `EVENT_SOURCED`; `DOCUMENT` alone keeps no previous versions. In particular, **to use `previous()` for
historical values, keep event sourcing enabled**. Current-intent reconciliation above does not need `previous()`.

An existing `@Aggregate` workflow can apply the same principle with its current aggregate load. New Model workflows use
the Model/Graph API shown here; do not cross over to `loadAggregate` for Model state.
