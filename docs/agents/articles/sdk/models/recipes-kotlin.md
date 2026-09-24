# Model recipes: companions, preferences and execution (Kotlin)

These recipes connect Model features, including explicit current reads and independently durable atomic updates.
All Models below use the default event-sourced persistence. They need neither `DOCUMENT` nor a stored Graph projection.
For brevity, declarations are shown as members of an example class and standard SDK imports are omitted.
Descriptive data belongs in cohesive details value objects; these examples only contain IDs, relations and simple status.

## Checked replacement and retryable updates

Use ordinary commands and `@Apply` for domain operations. These optional Graph methods directly replace **one
existing Model's state**; they do not invoke a domain payload's apply handlers or assertions.

| Method | Comparison and retry | Result after durable success |
| --- | --- | --- |
| `graph.compareAndSet(replacement)` | Receiver's `revisionStateIndex()`; one attempt | `true`; `false` for absence/revision conflict |
| `graph.updateAndGet(function, maxRetries)` | Fresh transactional Graph each attempt | Root after the successful commit |
| `graph.getAndUpdate(function, maxRetries)` | Same, with bounded reevaluation | Root before the successful attempt |

Function overloads without `maxRetries` use **0**. A budget of 2 permits at most three attempts. This budget
does not inherit the Model's `RETRY`/`ACCEPT` policy. Only a definitively rejected storage commit is retried;
application errors, unavailable pinned DOCUMENT state and uncertain transport outcomes propagate. Technical
failures must not be interpreted as a definite lost CAS.

The function returns its input, `current.update(...)` or `current.delete()`, never a different target, a null
Graph or an independently loaded Graph. Validate required invariants inside the callback. Consumed Model values,
aliases and relationship memberships are commit dependencies, including empty child collections. Search results
do not acquire transaction guarantees. Do not perform external effects or start nested mutations in the callback.
Perform invariant reads synchronously in that callback; do not move them to independently started worker threads.
Return new immutable values; do not modify `current.get()` in place. A failed commit cannot undo mutations to
a shared cached object. Protection on an earlier command's `@ProtectData` fields is not protection of Model state:
direct-update history serializes that state. The Model state-boundaries guide explains the storage/privacy contract.

These are independently durable operations: no invocation inside another Model mutation, no staged receiver,
and no provisional state from pending commands in the current batch. The application's configured namespace
is supported; consumer namespace overrides fail explicitly. Custom Graph/repository implementations are not
supported by these helpers. Updates never create an absent Model: function variants throw
`NoSuchElementException`; CAS returns false. A fresh attempt can see an independently recreated Model, so use
revision-based CAS or a domain generation check when lifecycle identity matters.

Even an unchanged value writes a new checked revision. Event-sourced Models retain the resulting replacement
through the SDK's direct-update replay mechanism; the function itself is not persisted and no domain event is
published for it. DOCUMENT-only Models remain eventless. Normal projection and deletion rules still apply.
The result retains its exact root value and revision, not a later writer's state. Lazy navigation keeps existing
historical-availability limits: a retained root is not retained history for deleted DOCUMENT-only children.

```kotlin
@Model data class Counter(@EntityId val id: String, val value: Int)
data class CreateCounter(val id: String) {
    @Apply fun apply() = Counter(id, 0)
}

// The Model already exists. Compare against this exact revision:
val observed = Fluxzero.loadCurrentGraph("counter", Counter::class.java)
val initial = requireNotNull(observed.get())
val replaced = observed.compareAndSet(initial.copy(value = initial.value + 1))

// Or calculate again after a rejected commit:
val after = observed.updateAndGet(
    { current -> current.update { value -> value.copy(value = value.value + 1) } }, 2)
```

### Consume-once versus an external effect

For a value that may be released only once, delete atomically and use the **returned** pre-delete root only
after success. Keep the default zero retries. A concurrent loser receives a public
`ModelCommitConflictException` (or `NoSuchElementException` if already absent); it must not release the value.

```kotlin
val consumed = Fluxzero.loadCurrentGraph("counter", Counter::class.java)
    .getAndUpdate { it.delete() }
val released = consumed.get() // deletion has committed before this line
```

A lost response after commit is still an uncertain delivery outcome; deletion does not guarantee the caller
receives the value exactly once. Do not read or expose it from inside the transformation.

For an OAuth/payment/worker call, deletion is not enough. Persist a claim with an operation/generation ID,
then call the provider **after the claim commits**, then persist completion using the acknowledged claim revision.
Reject an already claimed/completed state in the claim callback. On failure retain the claim for reconciliation.
Use the provider's idempotency/status API when available; never repeat an uncertain token rotation merely because
a lease expires. Completion/recovery and provider effects are separate operations, not one atomic transaction.
This pattern needs domain-specific recovery; these helpers do not add leases, TTL, erasure guarantees or
exactly-once external effects.

## One-to-one companion

A companion has an independent lifecycle but uses its parent's functional ID. The typed ID supplies the parent
type; no `@Association` or `@RegisterType` is needed here. The extra `@EntityId` prefix belongs only to the
companion's repository identity, not to the parent relation.


```kotlin
@Model data class Project(@EntityId val projectId: ProjectId, val enabled: Boolean)
class ProjectId(value: String) : Id<Project>(value, "project-")
@Model data class ProjectStatus(
    @EntityId(prefix = "status-") @Parent(pathInParent = "status") val projectId: ProjectId,
    val online: Boolean
)
data class CreateProject(val projectId: ProjectId) {
    @Apply fun apply() = Project(projectId, false)
}
data class PutStatus(val projectId: ProjectId, val online: Boolean) {
    @Apply fun apply(parent: Project, current: ProjectStatus?) = ProjectStatus(projectId, online)
}
data class RemoveStatus(val projectId: ProjectId) {
    @Apply fun apply(current: ProjectStatus): ProjectStatus? = null
}
data class RemoveProject(val projectId: ProjectId) {
    @Apply fun apply(current: Project): Project? = null
}
```

The non-null `Project` parameter makes parent existence a prerequisite. The nullable `ProjectStatus` parameter
means intentional upsert: both first creation and replacement are allowed. A factory without that current-state
parameter would be create-only and reject duplicates.

For `ProjectId("example")`, the Project repository ID is `project-example`; the companion is
`status-project-example`. Both retain the same typed `projectId` property. Pass the **parent ID and companion class**
for a direct companion load. Passing only the typed ID selects Project.

A missing companion returns an empty Model/Graph even when its type declares `@Alias`.
The undecorated alias fallback never reinterprets an unrelated primary Model (such as the parent) as the companion.
Real aliases and compatible canonical IDs still resolve; an explicit wrong-type lookup or wrong-type alias still fails.

The `status` path is still a child collection, not a special singleton JSON shape. The deterministic companion
identity enforces at most one per Project; it does not require one to exist. Removing the companion leaves its parent
intact; removing the parent cascades to the companion. Set a non-owning deletion policy only when that is the intended
lifecycle. This companion is not parent-scoped: affixes already provide a separate global identity.


```kotlin
val status = Fluxzero.loadModel(projectId, ProjectStatus::class.java).get()
val statuses = Fluxzero.loadGraph(projectId).childModels("status", ProjectStatus::class.java)
```

## One preference, multiple Graph views

Store the selected Device ID once on Space. Device's `primary` property is derived from its ancestor Graph
**at the same pinned boundary**; do not call `current()` or `loadCurrentGraph` inside that derivation.


```kotlin
@Model data class Space(@EntityId val spaceId: SpaceId, val primaryLightId: DeviceId?)
class SpaceId(value: String) : Id<Space>(value)
class DeviceId(value: String) : Id<Device>(value)
@Model data class Device(@EntityId val deviceId: DeviceId,
                        @Parent(pathInParent = "devices") val spaceId: SpaceId) {
    @GraphProperty fun primary(space: Graph<Space>) = deviceId == space.get()?.primaryLightId
}
data class CreateSpace(val spaceId: SpaceId) {
    @Apply fun apply() = Space(spaceId, null)
}
data class AddDevice(val deviceId: DeviceId, val spaceId: SpaceId) {
    @Apply fun apply(space: Space) = Device(deviceId, spaceId)
}
data class SelectPrimary(val spaceId: SpaceId, val primaryLightId: DeviceId?) {
    @AssertLegal fun validSelection(space: Graph<Space>) {
        if (primaryLightId != null && space.children(Device::class.java)
                .none { it.get()?.deviceId == primaryLightId }) {
            throw Rejected("Choose a device in this space")
        }
    }
    @Apply fun apply(space: Space) = space.copy(primaryLightId = primaryLightId)
}
companion object {
    fun requireUnselected(device: Graph<Device>) {
        val current = device.get() ?: throw Rejected("Device not found")
        val parent = device.parent(Space::class.java).orElseThrow { Rejected("Device has no current space") }
            .get() ?: throw Rejected("Device has no current space")
        if (current.deviceId == parent.primaryLightId) {
            throw Rejected("Clear or replace the primary selection first")
        }
    }
}
data class MoveDevice(val deviceId: DeviceId, val newSpaceId: SpaceId) {
    @AssertLegal fun legal(device: Graph<Device>, destination: Space) = requireUnselected(device)
    @Apply fun apply(current: Device) = current.copy(spaceId = newSpaceId)
}
data class DeleteDevice(val deviceId: DeviceId) {
    @AssertLegal fun legal(device: Graph<Device>) = requireUnselected(device)
    @Apply fun apply(current: Device): Device? = null
}
```

`@GraphProperty` runs when a Graph is serialized, including a response Graph. Serializing the raw Device value
does not invoke it. A derived value may appear in a serialized/materialized projection, but is not a second authoritative
Model field. This annotation neither maintains the selection nor enforces its integrity.

Here the domain policy is explicit: select only a Device currently in the Space, and clear or replace the selection
before moving or deleting that Device. `destination` also requires the new Space to exist. The assertions read injected
Graphs, so both inspected values and relationship membership participate in the normal RETRY/FAIL conflict checks.
An alternate policy may clear the old preference in the same multi-Model command that moves/deletes the child; do not
perform a nested command inside `@Apply`.


```kotlin
Fluxzero.sendCommandAndWait<Any?>(SelectPrimary(spaceId, null))
Fluxzero.sendCommandAndWait<Any?>(MoveDevice(deviceId, destinationSpaceId))
```

After the move, the old Space no longer contains the Device; it is not automatically selected in the destination.
Deleting it now succeeds. If either its old Space or the Device changes concurrently, normal conflict reevaluation
protects the assertions; no local lock is needed. These are rules for these explicit commands, not a blanket policy
for every future mutation. Deleting a Space cascades to its Devices and removes its preference together.

A child-stored `primary` flag is a valid alternative when that better expresses the domain, but requires a separate
“at most one selected child” invariant. Do not store both forms as independent truth or add a reverse `@Parent` edge.

## Assess, execute or orchestrate

| Intent | API | Boundary |
| --- | --- | --- |
| Preview immediate legality | `Fluxzero.assertLegal(command)` | Interceptors and before-assertions, no applies/commit |
| Execute a local Model action | `Fluxzero.assertAndApply(command)` | Full Model pipeline; one atomic multi-Model action |
| Dispatch a command and await its result | `Fluxzero.sendCommandAndWait(command)` | Normal dispatch, routing and handler selection |
| Apply specifically to one Model | `graph.assertAndApply(command)` | Selected writes and Model-owned handlers; all applicable command assertions |

The Graph target restricts writes, not the command's business conditions. A Reservation command's
`@AssertLegal` that reads `Graph<Product>` still runs, even when only the Reservation is selected. Its Product
reads participate in RETRY/FAIL conflict checks; before/after assertions and nested validation keep their normal
timing. This does not widen the apply scope; Model-owned handlers retain their existing target filtering.
Bind the Product ID normally; a missing or ambiguous required binding is an error, not permission to skip the check.

A successful preflight is not a reservation or guarantee: state may change afterward, and `afterHandler` checks have
not run. For ordinary execution, call the execution API once; it performs the checks itself. Do not routinely call
`assertLegal` first.

Using the Project/ProjectStatus types above, one action enables a Project and sets its operational status. The
after-handler assertion can reject the **whole** action. The remaining types show orchestration outside applies:


```kotlin
data class ActivateProject(val projectId: ProjectId, val operational: Boolean) {
    @Apply fun enable(current: Project) = current.copy(enabled = true)
    @Apply fun status(current: ProjectStatus?) = ProjectStatus(projectId, operational)
    @AssertLegal(afterHandler = true) fun ready(status: ProjectStatus) {
        if (!status.online) throw Rejected("Project is not operational")
    }
}
class Rejected(message: String) : FunctionalException(message)
enum class Outcome { STARTED, REJECTED }
@Model data class LaunchReport(@EntityId(prefix = "launch-") @Parent val projectId: ProjectId,
                               val outcome: Outcome)
data class RecordLaunchOutcome(val projectId: ProjectId, val outcome: Outcome) {
    @Apply fun apply(current: LaunchReport?) = LaunchReport(projectId, outcome)
}
data class LaunchProject(val projectId: ProjectId, val operational: Boolean)
@Consumer(name = "project-launches")
class LaunchHandler {
    @HandleCommand fun handle(request: LaunchProject): Boolean {
        try {
            Fluxzero.sendCommandAndWait<Any?>(ActivateProject(request.projectId, request.operational))
        } catch (rejection: FunctionalException) {
            Fluxzero.sendCommandAndWait<Any?>(RecordLaunchOutcome(request.projectId, Outcome.REJECTED))
            return false
        }
        Fluxzero.sendCommandAndWait<Any?>(RecordLaunchOutcome(request.projectId, Outcome.STARTED))
        return true
    }
}
```

`assertLegal(ActivateProject(id, false))` can pass because the failing assertion is after-handler.
Actually executing that action rejects with `Rejected`: neither Project nor ProjectStatus changes, and no accepted
Model event is published. Executing with `true` commits both together.

`LaunchHandler` has its own `project-launches` consumer: a blocking nested command must not need the same consumer
that is waiting for its result. Retain that separation in production consumer configuration.
It illustrates real orchestration, not a pass-through handler for `ActivateProject`. The latter needs
no explicit command handler: its Model applies are handled automatically. The launch handler deliberately catches
only `FunctionalException`, records a rejected outcome with a separate command, and lets technical faults propagate.
Do not label infrastructure failures as business rejections or include sensitive technical details in functional errors.

`ActivateProject` and `RecordLaunchOutcome` are **separate commits** even though the caller waits for each. A crash
between them, or failure to record the outcome, can leave activation without its report; this example does not promise
durable workflow resumption or exactly-once external effects. If changes must always succeed together, express them in
one Model action instead. Keep network calls and orchestration out of `@Apply`/`@InterceptApply`, whose code also runs
during replay or retry. Target-specific Graph applies and a loop of separately dispatched commands do not turn into
one multi-Model action.

## A fresh view without losing history

`graph.current()` opens the **same Model** at a new current namespace boundary using its owning repository.
It does not reconstruct an ID from the value, so repository affixes, companion IDs and parent-scoped identities survive.
The call pins the new boundary immediately; values and relationships stay lazy. The result captures the current
message-batch overlay, just like `loadCurrentGraph`, and is not a continuously updating object.

A moved node navigates to its current parents. A deleted Model has an empty value; the old event-sourced Graph remains
readable. A parent-scoped move changes identity, so refreshing the old identity does not follow it to a different one.
Current reads are for deliberate reconciliation (for example delayed scheduling), not the default for command invariants.
They do not carry an injected Graph's transaction read provenance. Keep invariant reads on the injected view.

The fresh view does not carry view-only path selections, filters, mapped values, staged Graph edits or response
context. Reapply presentation choices deliberately, including authorization/content filters before returning a response.
Unknown metadata-only nodes cannot refresh without their local Model contract. Custom Graphs/repositories must support
exact current reads explicitly; otherwise this shortcut fails, never switching to a global application or value fallback.
This is not a document-only state read; authoritative loading may still require replay. For `previous()` after overwrite,
the inspected Model needs event-sourced history.


```kotlin
val historical = Fluxzero.loadGraph(projectId) // inside an event handler
historical.get() // pin the lazy source while still inside this event handler
val current = historical.current()
// historical still represents that event; current uses its own newly pinned boundary.
```
