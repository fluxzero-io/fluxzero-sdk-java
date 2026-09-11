# Model Commit And Effects

## Batch-local command consistency

Automatic model commands in one tracking batch and ordered routing segment have read-your-writes. A later command sees
an earlier staged model update, including changed parent and ancestor relations, before the earlier commit completes.
When their read/write sets overlap, the later command waits for the predecessor's durable result and is then
reevaluated against canonical state before committing. Predecessor failure fails the dependent chain. Unrelated model
chains remain parallel.

Do not treat this as one transaction across commands: each command retains its own atomic commit, result and conflict
policy. Different consumers or routing segments have no implied ordering; configure a shared consumer and routing key
when that order is a domain requirement.

## Conflict policy

Model commits default to `ModelConflictPolicy.DEFAULT`, resolved from apply/model, builder configuration or application
properties. Public policies are:

- `ACCEPT`: preserve the event once; rebase derived documents and relationships on current merged model state.
- `RETRY`: reload and rerun assertions/interceptors/applies.
- `FAIL`: return the conflict.

If multiple applies request different policies, the stricter applicable policy wins; failure is not weakened by retry.

The implicit update policy is `RETRY` from defaults version `2026.09.09`, otherwise `ACCEPT`.
`fluxzero.model.conflictPolicy` and explicit builder/Model/Apply settings override it. Implicit first creations still
fail on conflict: the new default must not turn create-if-absent into an upsert. Explicit RETRY also reevaluates creation
and requires create-only assertions when appropriate. ACCEPT validates apply dependencies and writes, excluding
assertion-/interceptor-only reads; RETRY and FAIL validate the full evaluation readset. Conflict-free eligible Runtime
commits use the same cached-head/atomic-boundary optimization regardless of policy.
