# Model commits and external effects

A Model commit atomically stores its Model changes and associated documents/relations. A schedule, command sent to
another consumer, or remote HTTP write is a separate effect. `Guarantee.STORED` on that outgoing message does not
make it atomic with the Model operation.

## Record intent before acting on it

A safe integration starts with an accepted domain transition, then uses a registered tracked event handler to act on
that durable intent:

```java
@Consumer(name = "booking-effects")
public class BookingEffects {
    @HandleEvent
    void on(ConfirmBooking event, Booking booking) {
        Fluxzero.scheduleCommand(new RemindGuest(booking.bookingId()),
                "booking-reminder-" + booking.bookingId(), booking.reminderAt());
    }
}
```

Register this handler in the application and fixture. The injected Booking is the state at the event, useful for
explaining and executing that accepted intent. Use `loadCurrentGraph` deliberately when the job is to reconcile the
latest desired schedule instead. A delayed old event must not recreate a deadline that current state cancelled.

When effects depend on event order, route every relevant intent by the same stable Model ID with `@RoutingKey` and
use one named consumer. Different consumers have independent positions. A shared consumer name without a shared
segment is not per-Model ordering. Do not rely on automatic routing for a multi-target action; choose the effect's
ordering key explicitly.

A handler failure can occur after one external effect succeeded. Use stable schedule IDs, remote idempotency keys
where supported, and `@Stateful` execution records when retries or compensation need durable progress. The Model's
atomicity does not make a series of remote calls atomic or exactly-once.

## Explicit commit completion

Automatic Model handling completes its result after its commit. An explicit `Fluxzero.assertAndApply(...)` or independent `Graph.assertAndApply(...)` returns after its durable
commit; its async counterpart completes then. By contrast, `Graph.update(...)` / `delete()` can produce staged
views whose `get()` value alone does not prove durability. `Fluxzero.commit()` can explicitly flush pending automatic Model work and returns its
completion future. Compose later work after that future; never wait on it inside `@Apply`, which has not returned its
change yet. A forced commit creates a real boundary: failure afterward cannot undo it or a completed external effect.

Prefer a tracked durable-intent consumer when the effect must survive a process crash between commit and dispatch.
Explicitly awaiting a commit prevents early dispatch but does not itself close that crash window.

## Verify failure and recovery

Test rejection before commit, commit failure, effect failure, duplicate delivery and immediate cancellation after
acceptance. Assert exact remaining Models, events, active schedules and outbound requests. Verify a fast response can
resolve the committed primary ID and aliases. Use an asynchronous fixture or Runtime test for claims about tracked
ordering; a synchronous local fixture alone cannot prove that boundary.
