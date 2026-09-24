Use a command when the user or system asks the app to change state.

Do not put the sending user's ID in the command payload. Inject `Sender` in the handler, legal assertion, or `@Apply` method, and keep the payload focused on domain intent plus typed IDs. Direct `Sender` injection in `@Apply` is replay-safe because Fluxzero resolves it from the applied message metadata; `User.getCurrent()` inside `@Apply` is not the supported pattern.

For new Model updates, put `@Apply` on the payload and let Fluxzero handle it automatically. Use an explicit handler
only for orchestration and invoke `Fluxzero.assertAndApply` once. One action may update several Models atomically;
separate externally dispatched commands are still separate operations.

Single-target Model routing is a fallback from defaults version `2026.09.10`, or with
`fluxzero.model.automaticRouting=true`; explicitly configuring `false` disables it. Explicit segments and routing
declarations retain precedence. Read Model conflicts for eligibility, identity and missing-value boundaries.

Retained legacy aggregate update shape:

```java
@TrackSelf
@Consumer(name = "project-update")
public interface ProjectUpdate extends Request<Project> {
    @RoutingKey
    ProjectId projectId();

    @HandleCommand
    default Project handle() {
        return Fluxzero.loadAggregate(projectId()).assertAndApply(this).get();
    }
}
```

Then model each update as a record implementing that interface. Put state changes in `@Apply` methods on the command record. Read tracked versus local self-handling commands when this application must accept the command from the production runtime; a synchronous fixture can execute a local payload handler without proving tracked ingress.

Generate IDs outside `@Apply`, but choose the generation boundary from identifier ownership. An endpoint may generate an aggregate ID only when the public API makes it server-owned. For service-owned workflow correlations, validate and accept the caller's public command first, check duplicate business identity, then generate the references inside the trusted handler and persist them through an internal applied update. A public/deserializable command constructor is not a trusted boundary for references the caller must not control.

For actor- and time-sensitive rules, keep client input separate from trusted message context:

```java
public record ApproveArticle(ArticleId articleId) implements ArticleUpdate {
    @AssertLegal
    void assertReviewWindowOpen(KnowledgeArticle article, Instant timestamp) {
        if (article.reviewAfter().isAfter(timestamp.atZone(ZoneOffset.UTC).toLocalDate())) {
            throw ArticleErrors.reviewWindowNotOpen;
        }
    }

    @Apply
    KnowledgeArticle apply(KnowledgeArticle article, Sender sender) {
        return article.withApprovedBy(sender.userId());
    }
}
```

The client supplies only `articleId`; the persisted transition receives the authenticated sender and message timestamp from Fluxzero.

`@HandleCommand` defaults `skipExpiredRequests` to `false` so command semantics are preserved during replays. Set it deliberately only when historical requests should be skipped.

Use `Request<R>` on command payloads when callers need a typed result from `Fluxzero.sendCommand(...)` or `Fluxzero.sendCommandAndWait(...)`. Use fire-and-forget sends only when the caller does not need a result; a fire-and-forget command cannot return one later.

Do not call handler methods directly from application code. Dispatch the command through `Fluxzero.sendCommand...` or route it through the local entity helper so tracking, security, legality, and metadata behave the same way in tests and production.

When a command enforces a business-unique key across aggregate roots, the invariant key may differ from the aggregate
being mutated. Route every claimant by that invariant key through the same named tracked consumer, and keep the check
and write in one invocation. Read global invariants across aggregate roots before implementing a `loadEntity(key)`
check followed by an update to another aggregate ID.

The following bulk guidance describes legacy aggregate or externally dispatched command workflows, not one atomic
multi-Model apply. For bulk commands, define the required failure semantics before implementation. Choose the shape from the consistency boundary instead of treating every batch as one atomic command:

- If every item updates one aggregate, model one bulk command on that aggregate, validate the whole payload, and apply one state transition.
- If items update different aggregate IDs, an outer bulk command is an orchestrator. Child commands keep their own legality rules, but their completion boundary depends on dispatch. A nested command handled locally reuses the active invocation, so aggregates touched by that invocation can roll back when the outer handler fails. A child dispatched externally has an independent request and persistence boundary, so an earlier child can commit before a later request fails. Do not promise either outcome without testing the actual routing boundary and querying every affected aggregate after a late failure.
- A sequential fail-fast orchestrator stops at the first failure; it is not best effort, and its earlier-item visibility still depends on the completion boundary above.
- For best-effort import, continue after individual failures, make partial success explicit, and return one correlated outcome per item.
- For all-or-nothing behavior across aggregate IDs, do not claim atomicity merely because the outer handler prevalidated and looped. Use a deliberately modeled staging/commit workflow or another supported transaction boundary, and test the actual rollback/visibility contract.

Do not hide cross-aggregate fan-out in a command handler without naming its failure and visibility behavior. Validate duplicate IDs and malformed items within the request before dispatching children, but remember that prevalidation alone does not make later external dispatches atomic. Use the bulk-workflow testing recipe to cover successful multi-item execution, existing-state collisions, failures early and late in the list, exact visibility for every target, authorization, and product-required side effects.

Use `allowedClasses` only for deliberately broad handler methods that must accept more than one payload type. Prefer a specific payload type on most command handlers so routing and annotation processing stay obvious.

For externally retried command submissions, reuse the same request ID so runtime idempotency can return the prior result instead of reapplying the command. This is request-level behavior for stored WebSocket commands with `Guarantee.STORED` or stronger, keyed by runtime client and request ID. It is not business uniqueness, and it is not a permanent global message-ID dedupe guarantee for every message type.
