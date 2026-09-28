# Model and Graph read conflicts

This contract applies to both Java and Kotlin applications.

## Conflict policy

Model commits default to `ModelConflictPolicy.DEFAULT`, resolved from apply/model, builder configuration or application
properties. Public policies are:

- `ACCEPT`: preserve the event once; rebase derived documents and relationships on current merged model state.
- `RETRY`: reload and rerun assertions/interceptors/applies.
- `FAIL`: return the conflict.

If multiple applies request different policies, the stricter applicable policy wins; failure is not weakened by retry.

`DEFAULT` inherits explicit Model/application configuration and otherwise means `RETRY`, for both updates and first
creations, independently of `fluxzero.defaults.version`. Use `fluxzero.model.conflictPolicy`
(`FLUXZERO_MODEL_CONFLICT_POLICY`) or builder/Model/Apply settings to choose an explicit policy.
The default conflict budget is three retries after the initial attempt. Override it with
`fluxzero.model.maxConflictRetries` (`FLUXZERO_MODEL_MAX_CONFLICT_RETRIES`).
A changed Product or parent collection therefore reevaluates a new child's creation. This does not make a factory an
upsert: the normal apply-compatibility check still rejects an occupied target after retry. An intentionally nullable
existing-Model apply is a separate upsert choice. A staged Graph update that started from absence also cannot overwrite
a concurrent creation. Explicit ACCEPT still fails a first-creation conflict instead of rebasing it into an overwrite.
ACCEPT validates apply dependencies and writes, excluding
assertion-/interceptor-only reads; RETRY and FAIL validate the full evaluation readset. Conflict-free eligible Runtime
commits use the same cached-head/atomic-boundary optimization regardless of policy.

Before reevaluating a rejected commit with DOCUMENT-only dependencies, RETRY waits for pending authoritative
document materialization. If a newer document has already replaced the requested version, preparation may advance
to a consistent boundary before rerunning the action. This does not move an already evaluated or historical snapshot,
and it does not retry a business assertion that rejects the newly loaded state.

With ASYNC consumer handling, automatic Model commits that start after the handler also coordinate overlapping
readsets within the tracking batch. Evaluation stays parallel; a ready commit first waits for earlier evaluations to
discover their readsets, then waits only for overlapping predecessors in the same namespace and reevaluates before
committing. Disjoint scopes can commit concurrently. A Model command that read pending state waits for its producer
to finish, then reevaluates even if that producer failed. Its own assertions determine its result; it does not inherit
another command's rejection. A validation failure against pending state is provisional too: partial changes are never
staged, and evaluation resumes after the discovered predecessors settle. This dependency coordination does not consume
or increase the configured conflict retry budget. External writers and newly discovered readsets still require atomic
conflict validation. Ordinary handlers that return results based on pending state retain their producer-success barrier;
they cannot publish a successful result from state that was rejected.

One terminal preparation failure is not a retryable storage rejection: deletion/cascade planning may lose a pinned
DOCUMENT version to a concurrent replacement or deletion. It raises `ModelCommitConflictException` without submitting
or reevaluating the mutation, even with RETRY or provisional predecessors. Inspect `getReadConflict()` for the commit
ID, unavailable canonical Model ID and pinned read index. `getResult()` is null for this local failure; for ordinary
storage conflicts it remains populated and `getReadConflict()` is null. Do not automatically resubmit a stale deletion.
JDBC needs an updated Runtime returning typed historical-read evidence. With an older Runtime, this early rejection
remains a service error; the SDK never guesses from error text. Older SDKs ignore the additive error detail.

Injected and synchronous manually loaded Graph reads inside a Model mutation count: values/type/alias/revision reads protect Model heads; child collections (including empty
ones), parent navigation and indirect ancestor selection protect inspected relationships. Scans include rejected candidates.
Do not replace graph invariants with an extra guard Model solely to detect membership races with the supported Model/Graph commit protocol. RETRY reevaluates on a fresh pinned boundary; FAIL rejects; ACCEPT retains only apply dependencies through
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

## Choose the reads a rule actually needs

Membership and values are separate dependencies. For a Project limited to five Tasks:

| Read | Java | Kotlin | Decision evidence |
| --- | --- | --- | --- |
| Count Task children | `project.children(Task.class).size()` | `project.children(Task::class.java).size` | Matching membership, including an empty result; no Task values are needed |
| Inspect Task values | `project.childModels(Task.class)` | `project.childModels(Task::class.java)` | Membership and the returned Task values |

Injected Project state is another read dependency if an apply requests `Project`, even if the assertion only counts
children. Use the smallest read that expresses the rule; loading every child's value creates unnecessary conflicts.
Class-based selection covers locally known types only. For a cross-application count that includes unknown types,
use `namedChildren(exactModelName, false)` and count the returned placements without requesting their values.

## Follow one composed operation

Suppose Project has three Tasks and a registered `@InterceptApply` expands one command into ordered AddTask A and
AddTask B payloads. Each AddTask checks the five-Task limit before returning its new Task:

1. A sees three children and stages a fourth Task and its parent relationship.
2. B sees four children, including staged A, and stages a fifth.
3. Commit validates the consumed reads and persists both additions atomically. No intermediate commit exposes A alone.

Starting with four Tasks instead, A stages successfully but B sees five and rejects. Neither addition is stored.
A storage conflict under RETRY reruns the entire operation against a fresh attempt boundary; it does not retry only
B against leftover staged A. The ordinary business rejection itself is not retried hoping the rule becomes valid.
Put the capacity assertion on AddTask: an assertion matching only the intercepted wrapper does not protect its
replacement payloads. Separately dispatched commands remain separate operations, even inside one handler.
Keep outgoing HTTP, scheduling and other external effects outside retryable assertions, interceptors and applies.

## Automatic routing

With `fluxzero.defaults.version >= 2026.09.10`, or `fluxzero.model.automaticRouting=true`, a command with one statically
unambiguous, non-collection Model apply gets a routing fallback based on its canonical Model ID, including typed-ID
affixes and parent scope. No Model is loaded and no apply is executed to find that ID. Intercepted, dynamic and
multi-apply commands do not receive this inferred route. An event affecting exactly one Model gets the corresponding
fallback from its actual committed target. Explicit segments, `@RoutingKey` fields and type-level metadata/property
declarations win, including declarations whose value is absent. Multiple targets never select an arbitrary first ID.
Set `fluxzero.model.automaticRouting=false` to disable both fallbacks. A command's
segment is not blindly inherited: external producers or interceptors may have assigned it for a different key.

Dedicated overrides win in both directions. Their conventional environment-variable names are `FLUXZERO_MODEL_AUTOMATIC_ROUTING`
and `FLUXZERO_MODEL_CONFLICT_POLICY`; the compact aliases remain supported. The defaults marker is `FLUXZERO_DEFAULTS_VERSION`. Routing reduces avoidable
concurrency but never replaces read-dependency validation.
