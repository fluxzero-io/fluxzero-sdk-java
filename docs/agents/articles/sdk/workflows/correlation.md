# Durable correlation

Use correlation when a later callback identifies work by a provider reference rather than the domain's primary ID.
First choose the state owner: a business obligation belongs in a Model; provider attempts, retries and pending effects
usually belong in a `@Stateful` workflow.

| Owner | Mechanism | Lookup |
|---|---|---|
| Domain Model | `@Alias` on persisted state | `Fluxzero.loadModel(prefixedAlias, ModelType.class)` |
| `@Stateful` workflow | `@Association` on persisted handler state | Incoming properties select matching instances |

`@Association` matches property names and values unless explicitly mapped otherwise. Multiple workflow instances may
match; it does not create a unique constraint. Independent Model aliases are global identities and must be unique.

## Generate trusted references before dispatch

Keep provider-owned or service-owned references out of the public command's caller-controlled fields. Validate and
accept the request, generate references outside deterministic applies, and persist them before issuing the remote
request. A callback can arrive immediately, so an in-memory value is not enough.

Record accepted business intent in the Model commit. Let a registered tracked consumer create durable execution
state and perform the external operation. Keep stable idempotency references across retry; generating a new reference
for each retry can create duplicate remote work. See stateful handlers and commit/effect boundaries for persistence
and recovery between these steps.

## Resolve a callback narrowly

Give independent reference families distinct prefixes, such as `caption-ref-` and `artwork-ref-`, and keep them disjoint
from primary Model ID prefixes. Primary identity takes precedence over an equal alias. After resolving an external
reference, verify it still matches the stored component/attempt before applying the outcome.

Map the callback to a typed domain action with the primary Model ID and expected reference or revision. The action
checks that it still applies. A stale callback for an earlier attempt must not complete its replacement. Keep actor
and correlation metadata when dispatching that action; an external callback is not trusted merely because its ID exists.

## Ordering and recovery

If several intents require ordering, use the same stable routing key and named consumer for them. Independent
consumers can observe acceptance, cancellation and completion in different orders. If independent consumers are
needed, reconcile desired current state and make obsolete effects harmless.

Test acceptance followed by a fast callback, duplicates, unknown references, wrong-family references, late completion
after cancellation, reference replacement, concurrent attempts and restart. Assert all affected Models and active
schedules and the exact outbound requests. Include public IDs resembling alias keys and the same raw reference in
two families. A happy-path alias lookup alone does not prove a safe integration.
