Use `@Deadline` for delayed work derived from a Model. A pure instance method declares its desired payload and
execution time. The Model commit carries changes to that intent; the existing scheduler delivers the work.

```java
@Model
record BookingHold(@EntityId HoldId holdId, Instant expiresAt, boolean confirmed) {
    @Deadline
    Schedule expiry() {
        return confirmed ? null : new Schedule(new ExpireBookingHold(holdId), expiresAt);
    }
}
```

`HoldId` extends `Id<BookingHold>`. Returning `null` means there is no desired deadline. Commands are the default
and receive the configured system user. When authentication supplies a caller, a missing system user rejects
the Model change before commit rather than inheriting that caller. Applications without user authentication remain supported. `@Deadline(command = false)` instead uses ordinary `@HandleSchedule`
handling. Give methods distinct categories such as `@Deadline("warning")` and `@Deadline("expiry")`.

## One-shot cron and delay

When the annotation supplies timing, return the payload directly:

```java
@Deadline(cron = "${booking.expiry.cron}", timeZone = "Europe/Amsterdam")
ExpireBookingHold expiry() {
    return confirmed ? null : new ExpireBookingHold(holdId);
}
```

Cron uses the same parser and `ApplicationProperties` substitution as `@Periodic`. For example configure
`booking.expiry.cron=0 9 * * *` (environment variable `BOOKING_EXPIRY_CRON`) for the next 09:00 in the selected zone.
Set the property to `-` (`Deadline.DISABLED`) to disable it. Configuration must be consistent across writers.
Property values are resolved during evaluation. New or changed declarations use the current value; changing a
property alone does not reconcile existing deadlines, and an unrelated Model write does not renew them.

For elapsed time use `@Deadline(delay = 2, timeUnit = TimeUnit.HOURS)`. There is no implicit zero-delay default:
a plain payload requires cron or a nonnegative delay. Cron takes precedence when both are specified; a returned
`Schedule` supplies its own timestamp. A `Message` return value preserves its application metadata.

These are **one-shot deadlines**. Cron selects the first match after the change; it does not repeat automatically.
Delay is measured from the change that creates or changes the desired payload. Both sides of the Model change are
evaluated with the same reference clock. An unrelated write does not move the deadline, and an unchanged declaration
does not recreate work. The SDK retains the originally planned time in Model metadata. Once that time has arrived,
changes to time, payload, metadata or ID do not recreate the deadline. Returning `null` and subsequently a payload
starts an explicit new cycle.

Kotlin follows the same contract:

```kotlin
@Model
data class BookingHold(
    @field:EntityId val holdId: HoldId,
    val expiresAt: Instant,
    val confirmed: Boolean
) {
    @Deadline
    fun expiry(): Schedule? =
        if (confirmed) null else Schedule(ExpireBookingHold(holdId), expiresAt)

    @Deadline("warning", delay = 1, timeUnit = TimeUnit.HOURS)
    fun warning(): WarnBookingHold? =
        if (confirmed) null else WarnBookingHold(holdId)
}
```

## Comparison, identity and deletion

Only changes to the desired time, payload, application metadata, or **explicit** schedule ID produce schedule
mutations. Automatically generated schedule IDs, message IDs, timestamps, and reserved metadata (`$` keys) do not.
Payload equality falls back to serialized type, revision, format and bytes when ordinary equality differs.
Keep the method deterministic: use Model state for explicit timestamps, rather than calling the wall clock yourself.

A changed declaration replaces the previous schedule only while its **recorded original time is still in the
future**. This includes payload changes and ancestor-derived timing. Once that time has arrived, any replacement
is ignored, even if the new time is in the future. This decision uses Model metadata, not scheduler status or a
record of successful handling. A slow or failed handler therefore does not change this rule.

A transition from a deadline to `null` cancels it and removes its metadata. A later `null` → payload transition
creates a new deadline, including when the previous cycle's time has passed. An unrelated write preserves the
original time rather than deriving it again from the latest commit timestamp.

Without an explicit ID the SDK derives a stable ID from the Model's canonical identity and deadline category.
Return `new Schedule(payload, "public-id", time)` to select an ID for external `Fluxzero.cancelSchedule("public-id")`.
An ID change cancels the previous ID. IDs must be unique across active deadline categories in the namespace.
External cancellation has the ordinary scheduler semantics; already delivered commands are not recalled.
The SDK does not read or record that cancellation. An unchanged declaration leaves it alone; a changed declaration
may schedule again while the recorded original time remains in the future.

Logical Model deletion, including cascades, cancels deadlines by default. No `@Parent` on the payload is required.
`@Deadline(cancelOnDeletion = false)` leaves that schedule independent of the owner's deletion. Default ownership
reuses the existing parent-owned scheduler mechanism. Use ordinary parent-owned scheduling for independent
schedules with payload-based lifetime bindings.

## Ancestors and Graph dependencies

A method can receive a typed Model ancestor or a `Graph<T>` view:

```java
@Deadline
Schedule expiry(BookingPolicy policy) {
    return confirmed || policy == null ? null
        : new Schedule(new ExpireBookingHold(holdId), createdAt.plus(policy.holdDuration()));
}
```

Ancestor changes and relationship changes reevaluate affected declarations in the same Model operation. A Graph
parameter also supports dependencies on descendants and siblings. Relation navigation selects connected candidates,
and Model values use the existing batched Graph-loading path. Only declarations that inject context require this
traversal; a local-state declaration does not add Graph reads to unrelated changes. Large dependency fan-out still
adds work to the originating commit. All declarations must be reachable in the writing application's Model catalog,
and every writer of the Model or its injected context must use the same declarations.

## Inspecting recorded deadlines

`Graph.deadlines()` returns an immutable map from category to `DeadlineInfo`, containing the public `scheduleId`,
original `deadline`, `command` mode and `cancelOnDeletion` policy. The values belong to that Graph revision, including
materialized search Graphs. They describe planned work: a future timestamp is not proof that an external caller
has not canceled the schedule, and a past timestamp is not proof that its handler succeeded.

The SDK stores these values under the reserved `$fluxzero.deadline.*` namespace in existing event, document and
snapshot metadata. Live input and dispatch metadata cannot overwrite these entries. An unrelated Model change
retains them. No deadline tables or schema migrations are needed. Older Model revisions without deadline metadata
remain readable; adding an annotation does not automatically schedule unchanged historical state.

## Delivery, recovery and limits

Reads, assertions, replay and historical migration do not schedule work. Live commits carry concrete scheduler
mutations alongside Model state. Runtime writes them in the same database transaction, so a failed commit changes
neither. A duplicate commit receipt does not repeat scheduler mutations. Document-based Models also work without
publishing an event. Context changes persist affected owners' metadata as part of the same Model operation.

Enable this feature only on a Runtime supporting `CommitModelsWithDeadlines`; older servers reject that distinct
request type. Ordinary Model and scheduler requests retain their existing paths. There is no deadline projection,
execution claim, or cancellation-status query. Local timer registration uses the scheduler's existing delivery
checks after commit, without using those checks to decide Model deadline changes.

Delivery and retries use the ordinary scheduler and command/`@HandleSchedule` contracts. Keep handlers idempotent
and validate relevant current Model state. Cron on `@Deadline` does not make the declaration periodic; explicitly
returning another schedule from a schedule handler remains ordinary scheduling behavior.

Use synchronous and asynchronous `TestFixture` variants with fixed time and `expectOnlyActiveScheduledCommands`
or `expectOnlySchedules`. Check the complete active set after creation, replacement, cancellation and deletion,
and advance time beyond several cron matches to verify one-shot behavior. Durable restart guarantees additionally
require Runtime/storage tests; fixture reconstruction alone does not prove them.

`@Deadline` currently applies to Models. Stateful handlers do not yet support it.
