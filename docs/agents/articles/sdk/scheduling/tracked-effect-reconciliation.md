Use this when a tracked event consumer creates or cancels delayed work after an aggregate transition. A historical

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.
event is a trigger to inspect current state, not sufficient proof that its creation-time schedule still belongs in the
active set.

## Reconcile desired state instead of repeating the old effect

Tracked consumers normally resume from their stored position, but retries, explicit position resets, rebuilds, and
other replay operations can deliver an earlier event again. The aggregate may already have advanced beyond the state
that originally emitted that event. Load current state and make the active schedule set converge to that state.

| Current aggregate state | Desired active schedules |
| --- | --- |
| Rollout queued | Canary, general-release, and completion deadlines |
| Canary rollout active | General-release and completion deadlines |
| General rollout active | Completion deadline only |
| Terminal | None |

Use stable schedule IDs so every reconciliation addresses the same logical work. Cancel IDs that no longer belong and
create only missing work that still belongs:

```java
@Component
@Consumer(name = "software-rollout-deadlines",
          errorHandler = ThrowingErrorHandler.class)
final class SoftwareRolloutDeadlines {

    @HandleEvent
    void on(RolloutRequested event) {
        reconcile(event.rolloutId());
    }

    @HandleEvent
    void on(CanaryStarted event) {
        reconcile(event.rolloutId());
    }

    @HandleEvent
    void on(GeneralRolloutStarted event) {
        reconcile(event.rolloutId());
    }

    @HandleEvent
    void on(RolloutFinished event) {
        reconcile(event.rolloutId());
    }

    @HandleEvent
    void on(RolloutAborted event) {
        reconcile(event.rolloutId());
    }

    private void reconcile(RolloutId id) {
        SoftwareRollout rollout = Fluxzero.<SoftwareRollout>loadAggregate(id).get();
        switch (rollout.status()) {
            case QUEUED -> {
                Fluxzero.scheduleCommand(new Schedule(
                        new StartCanary(id), canaryId(id),
                        rollout.canaryTime()), true);
                Fluxzero.scheduleCommand(new Schedule(
                        new StartGeneralRollout(id), generalId(id),
                        rollout.generalTime()), true);
                Fluxzero.scheduleCommand(new Schedule(
                        new FinishRollout(id), completionId(id),
                        rollout.completionTime()), true);
            }
            case CANARY -> {
                Fluxzero.cancelSchedule(canaryId(id));
                Fluxzero.scheduleCommand(new Schedule(
                        new StartGeneralRollout(id), generalId(id),
                        rollout.generalTime()), true);
                Fluxzero.scheduleCommand(new Schedule(
                        new FinishRollout(id), completionId(id),
                        rollout.completionTime()), true);
            }
            case GENERAL -> {
                Fluxzero.cancelSchedule(canaryId(id));
                Fluxzero.cancelSchedule(generalId(id));
                Fluxzero.scheduleCommand(new Schedule(
                        new FinishRollout(id), completionId(id),
                        rollout.completionTime()), true);
            }
            case COMPLETED, ABORTED -> {
                Fluxzero.cancelSchedule(canaryId(id));
                Fluxzero.cancelSchedule(generalId(id));
                Fluxzero.cancelSchedule(completionId(id));
            }
        }
    }
}
```

Register every event that can change the desired set with the same reconciliation function. Do not assume that a later
terminal event in a full replay will cancel work soon enough: an obsolete deadline can become active or expire while
the replay is still catching up.

## Know what `ifAbsent` protects

`ifAbsent = true` skips creation when the same schedule ID is active at that moment. It is useful for idempotent
reconciliation of unchanged state. It is not a historical once-only marker. After cancellation, the ID is absent and
an old event can create it again unless current state prevents that creation.

When a deadline itself changed, decide whether the old deadline must be replaced. `ifAbsent = true` deliberately keeps
the existing active schedule; cancel and recreate, or use replacement semantics, when the current state's deadline is
authoritative and different.

Keep schedule reconciliation in a registered tracked post-commit consumer. The aggregate command handler should persist
the transition intent; it should not treat `assertAndApply(...).get()` as a commit acknowledgement and immediately
perform the external schedule effect.

## Prove convergence and absence

Before completion, use exact active-set assertions for independent scenarios:

- the creation transition leaves every required ID and deadline active exactly once;
- each intermediate transition removes obsolete IDs and retains every later deadline;
- every terminal path leaves no active schedule for the aggregate;
- duplicate terminal delivery remains empty;
- redelivery of the original creation event against each intermediate state leaves only its remaining deadlines;
- redelivery of the original creation event against current terminal state creates no new schedule and leaves none
  active;
- retry after a failure repeats no non-idempotent effect that completed before the failure.

`expectNoNewSchedules()` proves only that the current action created none. Pair it with `expectNoScheduleLike(...)` or an
exact complete active-set assertion to prove that obsolete work is not still present.

Keep reconstruction claims separate. `givenScheduledCommands(...)` and `givenSchedules(...)` supply scheduler state to
the fixture; they can prove reconciliation from those inputs. They do not prove that another application instance
recovered schedules from retained persistence. Use the reconstruction capability table before calling a test a restart.
