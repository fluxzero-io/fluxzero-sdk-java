# Kotlin Models And Updates

The Model lifecycle and commit rules are the same in Java and Kotlin. Use immutable data classes, typed IDs and
`copy(...)` for state changes. This example keeps both events and a direct current document:

```kotlin
import io.fluxzero.sdk.modeling.EntityId
import io.fluxzero.sdk.modeling.Id
import io.fluxzero.sdk.modeling.Model
import io.fluxzero.sdk.modeling.ModelPersistence
import io.fluxzero.sdk.persisting.eventsourcing.Apply

class ProjectId(value: String) : Id<Project>(value, "project-")

@Model
data class Project(@EntityId val projectId: ProjectId, val name: String)

data class CreateProject(val projectId: ProjectId, val name: String) {
    @Apply
    fun apply() = Project(projectId, name)
}

data class RenameProject(val projectId: ProjectId, val name: String) {
    @Apply
    fun apply(project: Project) = project.copy(name = name)
}

data class DeleteProject(val projectId: ProjectId) {
    @Apply
    fun apply(project: Project): Project? = null
}
```

Creation without a current Model requires absence; the non-null update parameter requires an existing Model. A
nullable Model parameter deliberately allows either state. Returning `null` logically deletes the value while event
storage/publication follows the configured policy. Do not use `Unit` for an apply result.

Commands with applicable Model applies are handled automatically. Add `@HandleCommand` only when the handler owns
actual orchestration; call `Fluxzero.assertAndApply(command)` once, or return its asynchronous counterpart's future.
Keep `@Apply` deterministic: generate IDs before constructing the command and perform no I/O, nested writes or
side effects inside applies.

Use `@field:NotNull`, `@field:NotBlank` and `@field:Valid` for payload constraints as appropriate; Kotlin non-null
types alone do not document every runtime request validation requirement. Put business invariants in `@AssertLegal`.

`Fluxzero.loadModel(projectId).get()` reads a direct value. Use `Graph<Project>` when code needs relationships,
historical boundaries or staged updates. An independent child uses its own `@Model` and `@Parent` relationship;
an embedded `@Member` shares the root's complete lifecycle. Read the Model and Graph articles before choosing that
boundary.
