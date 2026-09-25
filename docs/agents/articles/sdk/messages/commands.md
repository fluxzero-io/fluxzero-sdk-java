Use a command when the user or system asks the app to change state.

Do not put the sending user's ID in the command payload. Inject `Sender` in the handler, legal assertion, or `@Apply` method, and keep the payload focused on domain intent plus typed IDs. Direct `Sender` injection in `@Apply` is replay-safe because Fluxzero resolves it from the applied message metadata; `User.getCurrent()` inside `@Apply` is not the supported pattern.

For new Model updates, put `@Apply` on the payload and let Fluxzero handle it automatically. Use an explicit handler
only for orchestration and invoke `Fluxzero.assertAndApply` once. One action may update several Models atomically;
separate externally dispatched commands are still separate operations.

Single-target Model routing is a fallback from defaults version `2026.09.10`, or with
`fluxzero.model.automaticRouting=true`; explicitly configuring `false` disables it. Explicit segments and routing
declarations retain precedence. Read Model conflicts for eligibility, identity and missing-value boundaries.

Generate IDs outside `@Apply`, but choose the generation boundary from identifier ownership. An endpoint may generate a Model ID only when the public API makes it server-owned. For service-owned workflow correlations, validate and accept the caller's public command first, check duplicate business identity, then generate the references inside the trusted handler and persist them through an internal applied update. A public/deserializable command constructor is not a trusted boundary for references the caller must not control.

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

Do not call handler methods directly from application code. Dispatch the command through `Fluxzero.sendCommand...` or route it through the Model operation so tracking, security, legality, and metadata behave the same way in tests and production.

For invariants spanning Models, use one atomic action with injected Model/Graph dependencies. A unique business
key can own an explicit claim Model; unrelated search followed by a write is not protected. See global invariants.

For bulk work, choose the intended completion boundary. One multi-Model action can be all-or-nothing. Separately
sent commands can succeed independently, so a best-effort import must report an outcome per item. Prevalidating a
list does not make later external dispatches atomic. Test late failure and inspect every affected Model and effect.

Use `allowedClasses` only for deliberately broad handler methods that must accept more than one payload type. Prefer a specific payload type on most command handlers so routing and annotation processing stay obvious.

For externally retried command submissions, reuse the same request ID so runtime idempotency can return the prior result instead of reapplying the command. This is request-level behavior for stored WebSocket commands with `Guarantee.STORED` or stronger, keyed by runtime client and request ID. It is not business uniqueness, and it is not a permanent global message-ID dedupe guarantee for every message type.
