`@Apply` methods perform deterministic state transitions and are reused when Fluxzero rebuilds an entity from its event stream.

Signatures determine intent:

```java
@Apply
Project apply() { ... }              // create

@Apply
Project apply(Project current) { ... } // update

@Apply
Project apply(Project current) {
    return null;                       // delete
}
```

Keep `@Apply` pure: no searches, no loading other entities, no external requests, no random IDs, no `User.getCurrent()`, and no current wall clock. If an update does not change state, consider aggregate settings such as `EventPublication.IF_MODIFIED` to reduce noise.

Calling `assertAndApply(...).get()` in a handler exposes the updated in-memory aggregate; it does not prove that the event has committed or that new aliases are queryable. Do not schedule, cancel, publish a command, or send an external request immediately afterward in the same handler. Persist the transition/intent, then perform effects from a registered tracked post-commit consumer as described in aggregate commit and effect boundaries.

When actor identity affects persisted state, inject the application's concrete `Sender` as an `@Apply` parameter. Fluxzero records the dispatch user in message metadata, and parameter injection resolves that stored user again while rebuilding the aggregate. Do not read ambient thread-local user state and do not add a client-supplied user ID to the command.

```java
@Apply
KnowledgeArticle apply(KnowledgeArticle current, Sender sender) {
    return current.withApprovedBy(sender.userId());
}
```

When time affects a transition, inject the message timestamp as an `Instant` parameter or use `Fluxzero.currentTime()` at the handling/legal boundary. Never call `Instant.now()`, `LocalDate.now()`, or another system clock from `@Apply`; replay must produce the same state.

Returning `null` deletes an entity or clears a member. Child/member updates rebuild parent state automatically, and a single update payload can define multiple `@Apply` methods when it needs to affect both a child and a root aggregate shape.

Apply methods can inject the current state, ancestor entities, the update payload, `Message`, `Metadata`, the message timestamp, and concrete user context when needed. Prefer those explicit parameters over static ambient access. If existing state is required and absent, Fluxzero raises a not-found style failure; mark the state parameter nullable only for create-or-upsert paths that intentionally allow absence. Creating state that already exists raises an already-exists style failure unless the aggregate/update settings explicitly relax that behavior.

Use `@InterceptApply` when an update must be rewritten, suppressed, or expanded before legality and apply methods run. Interceptors may load or query because they are orchestration around the transition, but keep the final `@Apply` method deterministic.
