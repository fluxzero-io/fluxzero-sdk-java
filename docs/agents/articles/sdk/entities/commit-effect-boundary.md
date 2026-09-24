This article describes the retained aggregate/entity path. For new `@Model` state, use the Model actions, Graphs
and conflicts articles; legacy cross-aggregate limitations do not describe one atomic multi-Model commit.

Use this whenever a command both changes an event-sourced aggregate and intends to schedule, cancel, publish, or send an external effect.

## `assertAndApply(...).get()` is not persistence

Inside a command handler, `Entity.assertAndApply(...)` updates the handler's `ModifiableAggregateRoot`. Calling `.get()` reads that in-memory updated state. The aggregate repository commits the collected events only after normal handler/batch completion; relationship and alias indexing follows that commit boundary.

This is unsafe:

```java
AssetJob updated = Fluxzero.<AssetJob>loadAggregate(command.assetJobId())
        .assertAndApply(new RecordAssetJob(...))
        .get();

Fluxzero.scheduleCommand(new ExpireAssetJob(updated.assetJobId()), updated.deadline());
webRequestGateway.sendAndForget(Guarantee.STORED, processingRequest(updated));
```

The schedule or `WebRequest` can be dispatched before the aggregate event is stored and before new aliases are queryable. A fast processor decision can then miss its correlation. If the handler later fails or aggregate commit conflicts, an external effect can remain even though the state transition did not commit. `Guarantee.STORED` applies to the outgoing message; it does not commit the aggregate first or make the two writes atomic.

The same rule applies to `apply(...)`, `assertAndApply(Collection<?>)`, schedule cancellation, command publication, and compensation. Never infer persistence from the updated `Entity` value.

## Persist intent, then perform effects post-commit

Let the command handler validate and apply a state transition only. Publish the applied payload as an aggregate event, then handle that event in a tracked consumer after it is stored:

```java
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.modeling.AggregateEventRouting;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.EventPublication;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.springframework.stereotype.Component;

@Aggregate(
        eventPublication = EventPublication.IF_MODIFIED,
        eventRouting = AggregateEventRouting.AGGREGATE_ID)
record AssetJob(/* persisted state */) {
}

@Component
@Consumer(name = "asset-job-transitions")
final class StartAssetJobHandler {
    @HandleCommand
    AssetJob handle(StartAssetJob command) {
        Entity<AssetJob> job = Fluxzero.loadAggregate(command.assetJobId());
        if (job.isPresent()) {
            return job.get();
        }
        return job.assertAndApply(RecordAssetJob.accept(command, newReferences())).get();
    }
}

@Component
@Consumer(name = "asset-job-effects")
final class AssetJobAcceptedEffects {
    @HandleEvent
    void on(RecordAssetJob event) {
        AssetJob committed = Fluxzero.<AssetJob>loadAggregate(event.assetJobId()).get();
        if (committed == null) {
            throw new IllegalStateException("committed job is missing");
        }
        Fluxzero.scheduleCommand(
                new Schedule(new ExpireAssetJob(event.assetJobId()),
                        "asset-job-deadline-" + event.assetJobId(), committed.deadline()), true);
        publishProcessingJobs(committed);
    }
}
```

In a Spring application, `@Component` makes the tracked event handler discoverable. Outside Spring, register `AssetJobAcceptedEffects` explicitly with the Fluxzero builder. An unregistered post-commit handler provides no effect delivery.

Put every ordered post-commit effect for the workflow—initial requests and deadline creation, deadline cancellation, and compensation—under the same named consumer, here `@Consumer(name = "asset-job-effects")`. Also route every published intent for one aggregate to the same message segment. The explicit aggregate-level rule above is the least fragile option: `eventRouting = AggregateEventRouting.AGGREGATE_ID` routes every event published from that aggregate by its aggregate ID.

Do not confuse event-source storage with tracked-event routing. Fluxzero stores an event-sourced update against its aggregate, but the default published-event routing is `AggregateEventRouting.MESSAGE_ROUTING_KEY`. Under that default, an applied payload's own `@RoutingKey` determines the message segment; without one, the aggregate ID is not automatically the segment key. Either configure `AGGREGATE_ID` once on the aggregate, or put the same primary-ID `@RoutingKey` on every applied payload that the effect consumer handles. Test the configured alternative explicitly. A single named consumer gives the handlers one tracking position, while the shared segment keeps one workflow on one tracker when that consumer has multiple threads. `singleTracker = true` is a global-order alternative when a safe segment key is unavailable, at the cost of parallelism.

Do not give component senders independent names such as `caption-jobs`, `artwork-cancellation`, and `asset-job-deadlines`. Independent consumers have independent tracking positions: a cancellation can run before the older processing publication, or deadline cancellation can run before deadline creation, leaving an orphan effect. Separate effect consumers require an explicit reconciliation design that tolerates reordering and repairs the final external state; naming them separately does not provide per-job ordering.

Use a stable schedule ID with `ifAbsent = true` and stable processor idempotency references so replay after a tracked-handler failure does not intentionally create a second logical effect. Keep transition-delta flags in committed state for compensation and cancellation; the post-commit consumer acts only on newly persisted intent. If several effects must be independently recoverable, persist separate intent events/messages rather than relying on one in-memory sequence to be atomic.

An alternative is a separate durable primary-ID command handled after the first event is stored. It must still re-load committed state, re-check the expected transition/reference, and use a delivery guarantee plus stable idempotency keys. Merely calling `sendAndForgetCommand(...)` later in the original aggregate handler does not move it past commit.

## Test the boundary

Keep these rows explicit:

- a successful command commits the event before a tracked effect consumer can load the aggregate by primary ID and its new alias;
- the post-commit consumer creates exactly one active deadline and the exact outbound requests;
- a command that fails before handler completion publishes no aggregate event, schedule, request, cancellation, or compensation;
- replay/retry uses the same schedule ID and processor reference and does not intentionally create another logical effect;
- a new fixture synthetically reconstructed from recorded events and schedules emits no setup effect;
- a fast correlated decision after acceptance resolves the newly committed alias and enters the primary-ID transition consumer.
- acceptance followed immediately by cancellation is observed in order by the shared effect consumer and leaves no orphan active deadline or processing effect.
- with a multi-threaded effect consumer, all published intents for one aggregate retain one routing segment; prove `eventRouting = AGGREGATE_ID` or an equivalent primary-ID `@RoutingKey` instead of assuming the default.

Use exact `TestFixture` event, command, active-schedule, and `WebRequest` assertions. Do not paper over a race with sleeps.
