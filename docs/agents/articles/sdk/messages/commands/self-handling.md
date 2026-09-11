# Automatic Model Commands

<a name="handlecommand"></a>

Used for messages that intend to change state.

**Automatic model commands**

Commands that define model `@Apply` methods need no `@HandleCommand`. Fluxzero resolves typed IDs and performs the
model commit automatically. Use `@Consumer` only when this command needs an explicit consumer override; otherwise the
configured package consumer handles it.

**Example: Creating, Updating, and Deleting Models**

[//]: # (@formatter:off)
```java
// 1. Create model
public record CreateProject(ProjectId projectId, @NotNull @Valid ProjectDetails details) {
    @Apply
    Project apply() {
        return Project.builder().projectId(projectId).details(details).build();
    }
}

// 2. Update model
public record UpdateProjectDetails(ProjectId projectId, @NotNull @Valid ProjectDetails details) {
    @Apply
    Project apply(Project project) {
        return project.toBuilder().details(details).build();
    }
}

// 3. Logically delete model
public record DeleteProject(ProjectId projectId) {
    @Apply
    Project apply(Project project) {
        return null; // Clears current state but preserves model events
    }
}
```
[//]: # (@formatter:on)

**Example: Independently stored child model**

Use `@Parent` on the child model. Creating or updating it does not rewrite the parent.

[//]: # (@formatter:off)
```java
// Task is @Model and has @Parent(pathInParent = "tasks") ProjectId projectId.
public record CreateTask(ProjectId projectId, @NotNull TaskId taskId, @NotNull @Valid TaskDetails details) {
    @Apply
    Task apply() {
        return Task.builder().taskId(taskId).projectId(projectId).details(details).build();
    }
}

// 2. Update Sub-Entity
public record UpdateTaskStatus(@NotNull TaskId taskId, boolean completed) {
    @Apply
    Task apply(Task task) {
        return task.toBuilder().completed(completed).build();
    }
}

// 3. Delete Sub-Entity
public record RemoveTask(@NotNull TaskId taskId) {
    @Apply
    Task apply(Task task) {
        return null; // Deletes the entity
    }
}
```
[//]: # (@formatter:on)

**Example: Standalone Command Handler**

Used for orchestration or actions that do not define a model transition. An explicit handler that does update models
should call `Fluxzero.assertAndApply(update)` once.

[//]: # (@formatter:off)
```java
@Component
class EmailHandler {
    @HandleCommand
    void handle(SendWelcomeEmail command) {
        // Logic to trigger email via an external gateway
    }
}
```
[//]: # (@formatter:on)
