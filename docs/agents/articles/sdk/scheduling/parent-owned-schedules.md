A schedule can belong to the lifetime of one or more **already committed Models**. Declare ownership on its payload
with `@Parent`, or select parents explicitly on `Schedule`. This works for schedule messages and scheduled commands.

```java
record RunReminder(@Parent ReminderId reminderId, Instant expectedDeadline) {}

Fluxzero.scheduleCommand(new RunReminder(reminderId, deadline), "reminder-" + reminderId, deadline);
```

Here `ReminderId extends Id<Reminder>`. Plan this from a tracked post-commit handler: the parent must exist in the
schedule's namespace when planning starts. Do not schedule before its creation commit, or inside replayed `@Apply`
logic. A missing, deleted or erased parent rejects planning rather than creating an unowned schedule.

## What deletion means

A successful, applicable `@Apply` returning `null` deletes its target. That cancels schedules owned by that Model,
as does cascaded deletion or explicit hard erasure. Deleting **any** selected owner is sufficient. Updating a parent,
changing its status or moving it under another parent does not cancel its schedules.

Cancellation is asynchronous and works without an application cleanup consumer, including while the application is
offline. It is not atomic with the Model commit. Already delivered schedules and commands cannot be recalled.
Keep current-intent guards for stale deadlines, duplicate delivery and concurrent deletion. Ownership does not replace
reconciliation when status changes or a deadline is moved.

The schedule is not a Model or Graph node. Its payload does not need `@Model`; `pathInParent` and `apiDoc` do not
compose a graph for it. Null parent values and `@Parent(deleteOnParentDeletion = false)` are ignored. Multiple
references are deduplicated. Typed IDs use their Model's canonical identity mapping. Untyped values use their string
representation; with `@Parent(SomeModel.class)` the declared type's identity mapping is applied. Aliases are not resolved.
For a parent-scoped identity supply its complete canonical repository ID, not just its local component.

## Explicit selection, replacements and retries

```java
var message = new Schedule(new SendReminder(), "reminder-" + reminderId, deadline);
Fluxzero.get().messageScheduler().schedule(message.withParents(reminderId));
Fluxzero.get().messageScheduler().schedule(message.withParents()); // deliberately unowned
```

`withParents(...)` replaces, rather than adds to, payload declarations. An empty argument list opts out.
Its typed IDs use Model identity mapping; plain strings must already be canonical. Explicit parent IDs are stored in
message metadata: do not use this form to duplicate sensitive values that should only exist in a protected payload.
Inferred bindings add opaque tokens, not a second plaintext copy of annotated IDs.

Protected parent fields are restored only into a temporary ownership read view; outbound data stays redacted.
Missing protected parent data rejects planning, while unrelated protected fields are not read for ownership.
Dispatch interceptors may replace the owning payload. Serialized replacements of an already owning payload are
re-evaluated against their final form. To introduce ownership from an initially unowned opaque serialized payload,
select explicit parent metadata or replace the logical payload before serialization; ordinary unowned wire transforms
do not acquire a new local-deserialization requirement.

An accepted same-ID replacement replaces ownership too. An ordinary replacement becomes unowned.
An ignored `ifAbsent` request does not transfer ownership. Deletion cleanup cannot remove a newer replacement bound
to a recreated parent with the same ID.

Once the initial binding has been acquired, storage retries and automatic periodic continuations keep that parent
lifetime; delete/recreate does not revive old work. This also covers handler returns of `Duration`, `Message`
or `Schedule`, and `null` under `@Periodic`. A retrieved schedule retains its binding when rescheduled.
Use `withParents(...)` explicitly when deliberately starting new work for a new lifetime.
A genuinely fresh scheduling call is a new intent; replayed old events must still check current Model state.

## Observe and test

Each actual auto-cancellation emits a best-effort `ScheduleAutoCancelled(scheduleId, messageId, deadline)` metric.
There is no payload or raw parent ID in that metric. It reports removal from stored scheduling, not prevention of an
already in-flight execution. Metrics failure does not undo cancellation, and a crash between removal and publication
can lose telemetry; this is not an exactly-once audit log.

LocalClient performs cancellation deterministically after the Model commit. Test both direct and cascade deletion,
and assert the **entire active set**, not only schedules created during the When phase:

```java
fixture.givenCommands(new CreateReminder(reminderId))
       .given(fc -> fc.messageScheduler().scheduleCommand(
           new Schedule(new RunReminder(reminderId, deadline), "reminder", deadline)))
       .whenCommand(new DeleteReminder(reminderId)) // @Apply Reminder apply(Reminder current) { return null; }
       .expectSuccessfulResult()
       .expectOnlyActiveScheduledCommands()
       .expectMetrics(ScheduleAutoCancelled.class)
       .expectNoErrors();
```

Test in-flight/stale commands separately. A persistence-backed integration test must await eventual cancellation.
The asynchronous local fixture waits for accepted schedules, not rejected old-lifetime attempts or ignored `ifAbsent`
requests. Dispatch assertions still report attempts made during When; active-schedule assertions check stored work.
Ordinary schedules use the existing protocol. Ownership requires a supporting scheduling service; older or custom
clients/services that do not implement it fail explicitly, never silently drop ownership.
