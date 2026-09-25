Use this article for ordinary business compensation after cancellation, expiry, rejection, or a late successful component. These are domain outcomes, not `@HandleError` corrections.

## Emit effects from transition deltas

Persisted state describes what is true now. It does not by itself prove that a side effect is new. Never send compensation merely because `compensationRequested` is currently `true`: every later event would send it again.

Model a false-to-true intent transition and emit only for the event produced by that transition. Never send, publish, load, or compute an external effect inside `@Apply`.

```java
record RequestArtworkCancellation(AssetJobId assetJobId) {
    @InterceptApply
    Object onlyWhenNeeded(AssetJob job) {
        return job.status().isTerminalFailure()
                && job.artworkStatus() == CONFIRMED
                && !job.artworkCancellationRequested()
                ? this : null;
    }

    @Apply
    AssetJob apply(AssetJob job) {
        return job.withArtworkCancellationRequested(true);
    }
}

record RecordDecisionAndCompensation(AssetJobId assetJobId, Object decision) {
    @InterceptApply
    List<Object> expand() {
        return List.of(decision, new RequestArtworkCancellation(assetJobId));
    }
}

// Submit one composite action; both payload updates belong to that Model operation.
Fluxzero.assertAndApply(new RecordDecisionAndCompensation(jobId, processorDecision));

@Component
@Consumer(name = "asset-job-effects")
final class ArtworkCancellationSender {
    @HandleEvent
    void on(RequestArtworkCancellation event, AssetJob job) {
        sendArtworkCancellation(
                job.artworkReference(),
                "artwork-cancel-" + job.assetJobId());
    }
}
```

The composite action expands inside one Model operation: the decision runs first, then the intent interceptor
sees that resulting state. If either update fails, the operation does not commit. Do not replace this with
`Graph.assertAndApply(List.of(...))`: that convenience performs separate durable Model commits in order.

`@InterceptApply` suppresses the intent unless the decision made compensation newly necessary. With
`EventPublication.IF_MODIFIED`, only the false-to-true update publishes its intent event. The tracked sender observes
that durable event and uses a stable remote idempotency key. Explicit `Fluxzero.assertAndApply(...)` returns after
commit; a tracked consumer additionally closes the process-crash gap between recording intent and dispatching it.

Keep ordered effects in one consumer and give every intent the same primary-ID `@RoutingKey`. Different consumer
names have independent positions. When independent consumers are necessary, design reconciliation explicitly.

Sending `RequestArtworkCancellation` as a separate tracked command is an intentional extra ordering and completion
boundary. Use that only when separate application/consumer ownership is required, and test the partial-progress and
retry semantics explicitly. A handler that watches every later workflow event and recomputes effects from all true
flags is unsafe.

Use a stable business idempotency key derived from the workflow/component/logical effect, or store an explicit generated key with the intent. Retries may repeat network delivery; the downstream processor must be able to recognize the same logical compensation. Do not promise exactly-once transport.

## Preserve first-decision and terminal rules

For each component, the first processor decision wins:

- pending + confirm records confirmed;
- pending + reject records rejected;
- confirmed/rejected + duplicate or conflicting decision is a no-op;
- when the workflow is already failed, expired, or cancelled, a first late confirmation records confirmed and creates one new compensation intent;
- a first late rejection records rejected without compensation;
- the existing terminal workflow status never changes.

At the initial terminal transition, create compensation intents only for components already confirmed. A still-pending component is handled later by its own first decision. Store separate intent flags/keys per component.

`EventPublication.IF_MODIFIED` can suppress events for unchanged Model state, but it cannot repair a handler that sends before determining whether a transition is new. Decide and persist idempotency in domain state.

## Keep error correction separate

Use `@HandleError` when a tracked handler itself failed and an error-stream correction or retry is required. Processor rejection, expiry, and caller-requested cancellation are successful handling of business messages. Routing them through the error log obscures the state machine and makes replay behavior harder to reason about.

## Test logical-once behavior

Use a matrix that includes:

- rejection when the other component is pending and when it is confirmed;
- expiry and cancellation with zero, one, and two confirmed components;
- first late confirmation after each terminal outcome;
- first late rejection after each terminal outcome;
- duplicate confirmation, duplicate rejection, and conflicting second decision;
- an unrelated later event after a compensation flag became true;
- replay/reconstruction followed by a duplicate or late decision.

For every row, assert exact outgoing compensation count, component, URL/body, and stable idempotency reference. The particularly important regression is: compensate one confirmed component, process a later rejection for the other component, and assert that the already-requested compensation is not published again.
