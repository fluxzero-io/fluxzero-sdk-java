# Model recipes: companions, preferences and execution (Java)

These recipes connect existing Model features; only `current()` is a convenience for an explicit current read.
All Models below use the default event-sourced persistence. They need neither `DOCUMENT` nor a stored Graph projection.
For brevity, declarations are shown as members of an example class and standard SDK imports are omitted.
Descriptive data belongs in cohesive details value objects; these examples only contain IDs, relations and simple status.

## One-to-one companion

A companion has an independent lifecycle but uses its parent's functional ID. The typed ID supplies the parent
type; no `@Association` or `@RegisterType` is needed here. The extra `@EntityId` prefix belongs only to the
companion's repository identity, not to the parent relation.


```java
@Model record Project(@EntityId ProjectId projectId, boolean enabled) {}
static class ProjectId extends Id<Project> { ProjectId(String value) { super(value, "project-"); } }
@Model record ProjectStatus(@EntityId(prefix = "status-") @Parent(pathInParent = "status") ProjectId projectId,
                            boolean online) {}
record CreateProject(ProjectId projectId) {
    @Apply Project apply() { return new Project(projectId, false); }
}
record PutStatus(ProjectId projectId, boolean online) {
    @Apply ProjectStatus apply(Project parent, @Nullable ProjectStatus current) {
        return new ProjectStatus(projectId, online);
    }
}
record RemoveStatus(ProjectId projectId) {
    @Apply ProjectStatus apply(ProjectStatus current) { return null; }
}
record RemoveProject(ProjectId projectId) {
    @Apply Project apply(Project current) { return null; }
}
```

The non-null `Project` parameter makes parent existence a prerequisite. The nullable `ProjectStatus` parameter
means intentional upsert: both first creation and replacement are allowed. A factory without that current-state
parameter would be create-only and reject duplicates.

For `ProjectId("example")`, the Project repository ID is `project-example`; the companion is
`status-project-example`. Both retain the same typed `projectId` property. Pass the **parent ID and companion class**
for a direct companion load. Passing only the typed ID selects Project.

The `status` path is still a child collection, not a special singleton JSON shape. The deterministic companion
identity enforces at most one per Project; it does not require one to exist. Removing the companion leaves its parent
intact; removing the parent cascades to the companion. Set a non-owning deletion policy only when that is the intended
lifecycle. This companion is not parent-scoped: affixes already provide a separate global identity.


```java
ProjectStatus status = Fluxzero.loadModel(projectId, ProjectStatus.class).get();
List<ProjectStatus> statuses = Fluxzero.loadGraph(projectId).childModels("status", ProjectStatus.class);
```

## One preference, multiple Graph views

Store the selected Device ID once on Space. Device's `primary` property is derived from its ancestor Graph
**at the same pinned boundary**; do not call `current()` or `loadCurrentGraph` inside that derivation.


```java
@Model record Space(@EntityId SpaceId spaceId, DeviceId primaryLightId) {}
static class SpaceId extends Id<Space> { SpaceId(String value) { super(value); } }
static class DeviceId extends Id<Device> { DeviceId(String value) { super(value); } }
@Model record Device(@EntityId DeviceId deviceId, @Parent(pathInParent = "devices") SpaceId spaceId) {
    @GraphProperty boolean primary(Graph<Space> space) {
        return space.get() != null && deviceId.equals(space.get().primaryLightId());
    }
}
record CreateSpace(SpaceId spaceId) {
    @Apply Space apply() { return new Space(spaceId, null); }
}
record AddDevice(DeviceId deviceId, SpaceId spaceId) {
    @Apply Device apply(Space space) { return new Device(deviceId, spaceId); }
}
record SelectPrimary(SpaceId spaceId, @Nullable DeviceId primaryLightId) {
    @AssertLegal void validSelection(Graph<Space> space) {
        if (primaryLightId != null && space.children(Device.class).stream()
                .noneMatch(device -> device.get().deviceId().equals(primaryLightId))) {
            throw new Rejected("Choose a device in this space");
        }
    }
    @Apply Space apply(Space space) { return new Space(spaceId, primaryLightId); }
}
static void requireUnselected(Graph<Device> device) {
    Device current = device.optional().orElseThrow(() -> new Rejected("Device not found"));
    Space parent = device.parent(Space.class).flatMap(Graph::optional)
            .orElseThrow(() -> new Rejected("Device has no current space"));
    if (current.deviceId().equals(parent.primaryLightId())) {
        throw new Rejected("Clear or replace the primary selection first");
    }
}
record MoveDevice(DeviceId deviceId, SpaceId newSpaceId) {
    @AssertLegal void legal(Graph<Device> device, Space destination) { requireUnselected(device); }
    @Apply Device apply(Device current) { return new Device(deviceId, newSpaceId); }
}
record DeleteDevice(DeviceId deviceId) {
    @AssertLegal void legal(Graph<Device> device) { requireUnselected(device); }
    @Apply Device apply(Device current) { return null; }
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


```java
Fluxzero.sendCommandAndWait(new SelectPrimary(spaceId, null));
Fluxzero.sendCommandAndWait(new MoveDevice(deviceId, destinationSpaceId));
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
| Apply specifically to one Model | `graph.assertAndApply(command)` | Target-specific Model pipeline, not all targets in the payload |

A successful preflight is not a reservation or guarantee: state may change afterward, and `afterHandler` checks have
not run. For ordinary execution, call the execution API once; it performs the checks itself. Do not routinely call
`assertLegal` first.

Using the Project/ProjectStatus types above, one action enables a Project and sets its operational status. The
after-handler assertion can reject the **whole** action. The remaining types show orchestration outside applies:


```java
record ActivateProject(ProjectId projectId, boolean operational) {
    @Apply Project enable(Project current) { return new Project(projectId, true); }
    @Apply ProjectStatus status(@Nullable ProjectStatus current) { return new ProjectStatus(projectId, operational); }
    @AssertLegal(afterHandler = true) void ready(ProjectStatus status) {
        if (!status.online()) { throw new Rejected("Project is not operational"); }
    }
}
static class Rejected extends FunctionalException { public Rejected(String message) { super(message); } }
enum Outcome { STARTED, REJECTED }
@Model record LaunchReport(@EntityId(prefix = "launch-") @Parent ProjectId projectId, Outcome outcome) {}
record RecordLaunchOutcome(ProjectId projectId, Outcome outcome) {
    @Apply LaunchReport apply(@Nullable LaunchReport current) { return new LaunchReport(projectId, outcome); }
}
record LaunchProject(ProjectId projectId, boolean operational) {}
@Consumer(name = "project-launches")
static class LaunchHandler {
    @HandleCommand boolean handle(LaunchProject request) {
        try {
            Fluxzero.sendCommandAndWait(new ActivateProject(request.projectId(), request.operational()));
        } catch (FunctionalException rejection) {
            Fluxzero.sendCommandAndWait(new RecordLaunchOutcome(request.projectId(), Outcome.REJECTED));
            return false;
        }
        Fluxzero.sendCommandAndWait(new RecordLaunchOutcome(request.projectId(), Outcome.STARTED));
        return true;
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


```java
Graph<Project> historical = Fluxzero.loadGraph(projectId); // inside an event handler
historical.get(); // pin the lazy source while still inside this event handler
Graph<Project> current = historical.current();
// historical still represents that event; current uses its own newly pinned boundary.
```
