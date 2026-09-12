# Model and Graph read conflicts

This contract applies to both Java and Kotlin applications.

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

Injected Graph reads also count: values/type/alias/revision reads protect Model heads; child collections (including empty
ones), parent navigation and indirect ancestor selection protect inspected relationships. Scans include rejected candidates.
Do not replace graph invariants with an extra guard Model solely to detect membership races on a matching post-RC8
SDK/Runtime. RETRY reevaluates on a fresh pinned boundary; FAIL rejects; ACCEPT retains only apply dependencies through
every rebase. Complete reads within evaluation, including joined parallel scans. Historical views, external search and
unrelated repository reads are not implicitly transactional. Types sharing a path share a conservative dependency;
remapped paths protect all source paths, and physical erasure invalidates older Graph reads namespace-wide.
Upgrade all Runtime instances first: older Runtimes reject the new relationship-aware wire request. Eligible reads at
the exact cached namespace boundary or across known contiguous head-only writes retain atomic-CAS planning; other older
reads need database validation. Unused or value-only Graph injection resolved directly by ID adds no membership proof/query;
indirect ancestor injection still protects the relationships used to select that ancestor. Writers still retain
evidence of removed/reparented relations, even without their own Graph injection, to protect concurrent readers.
Head-only writes remain batchable. Physical cleanup advances the namespace and an identity-free cleanup position. Stored Model
payload/history formats are unchanged. Custom repositories must return SDK views such as `Graphs.compose` for
transactional navigation; opaque custom Graphs fail explicitly, while ordinary custom reads remain supported.

## Automatic routing

With `fluxzero.defaults.version >= 2026.09.10`, or `fluxzero.model.automaticRouting=true`, a command with one statically
unambiguous, non-collection Model apply gets a routing fallback based on its canonical Model ID, including typed-ID
affixes and parent scope. No Model is loaded and no apply is executed to find that ID. Intercepted, dynamic and
multi-apply commands do not receive this inferred route. An event affecting exactly one Model gets the corresponding
fallback from its actual committed target. Explicit segments, `@RoutingKey` fields and type-level metadata/property
declarations win, including declarations whose value is absent. Multiple targets never select an arbitrary first ID.
Set `fluxzero.model.automaticRouting=false` to disable both fallbacks. Aggregate routing is unchanged. A command's
segment is not blindly inherited: external producers or interceptors may have assigned it for a different key.

Dedicated overrides win in both directions. Their environment-variable names are `FLUXZERO_MODEL_AUTOMATICROUTING`
and `FLUXZERO_MODEL_CONFLICTPOLICY`; the defaults marker is `FLUXZERO_DEFAULTS_VERSION`. Routing reduces avoidable
concurrency but never replaces read-dependency validation.
