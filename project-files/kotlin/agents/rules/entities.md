# Entities & Aggregates

In Fluxzero, aggregates and entities are immutable state holders. They define the data structure and the rules for
transitioning between states, while orchestration logic resides in handlers.

---

## Quick Navigation

- [Core Rules](#core-rules)
- [Defining State](#defining-state)
    - [Aggregates (@Aggregate)](#aggregates)
    - [Entities & Members](#entities)
    - [Ownership & Permissions](#ownership)
- [Applying State Changes (@Apply)](#apply)
- [Filtering Updates (@InterceptApply)](#intercept-apply)
- [Business Invariants (@AssertLegal)](#assertlegal)
- [Loading Entities](#loading-entities)

---

<a name="core-rules"></a>

## Core Rules

1. **Immutability**: State should be implemented as immutable `data classes`.
2. **Logic Separation**: Aggregates are "dumb". Handlers process messages and use `assertAndApply(this)` to interact
   with the aggregate. While it is possible to place `@Apply`, `@AssertLegal`, or `@InterceptApply` directly in the
   entity, this is **not recommended**; keep this logic in the command payload (using Kotlin extension methods if 
   needed, but ideally within the payload class itself).
3. **Pure Transitions**: `@Apply` methods must be pure functions. They should only depend on the current state and the
   command payload.
4. **Deterministic State**: Inside `@Apply`, **never** perform searches, load other entities, or trigger side effects.
5. **No Updates in State Logic**: Inside `@AssertLegal` and `@InterceptApply`, it is fine to query, load other entities,
   or perform searches, but **never** invoke updates.

---

<a name="defining-state"></a>

## Defining State

<a name="aggregates"></a>

### Aggregates (@Aggregate)

The consistency boundary root. Aggregates can be **event-sourced**, **document-based**, or both.

**Annotation Settings**:

- `searchable`: If `true`, the aggregate is automatically indexed in the document store.
- `collection`: (Optional) The name of the search collection.
- `eventPublication`: Controls when updates are stored and published.
    - `ALWAYS` (Default): Every applied update results in an event, even if no state changed.
    - `IF_MODIFIED`: Only creates/publishes an event if the aggregate's state actually changed (via `equals()` or `hashCode()`). **Recommended** for better performance and to simplify idempotency (duplicate commands that don't change state won't clutter the stream).
    - `NEVER`: No events are stored or published.
- `publicationStrategy`: Controls the destination of the event.
    - `STORE_AND_PUBLISH` (Default): Both persistent storage and real-time distribution to handlers.
    - `STORE_ONLY`: Persists to the event store but does not trigger handlers. Useful for silent migrations or audit-only state.
    - `PUBLISH_ONLY`: Triggers handlers but is not persisted. State changes won't survive a restart/replay.

```kotlin
@Aggregate(
    searchable = true, 
    eventPublication = EventPublication.IF_MODIFIED
)
data class Project(
    @EntityId val projectId: ProjectId,
    val details: ProjectDetails,
    @Alias(prefix = "owner-") val ownerId: UserId,
    @Member val tasks: List<Task> = emptyList()
)
```

<a name="entities"></a>

### Entities & Members

Nested components within an aggregate.

- **Data Classes**: Kotlin `data classes` work seamlessly. The SDK uses the `copy()` method for state updates.
- **Routing**: The `@EntityId` property must be present in the command payload for automatic routing. Use
  `@Member(idProperty = "otherProperty")` if names differ.

```kotlin
data class Task(
    @EntityId val taskId: TaskId,
    val details: TaskDetails,
    val completed: Boolean = false
)
```

<a name="ownership"></a>

### Ownership & Permissions

It is common practice to store an `ownerId` in an aggregate to enforce security in subsequent commands. Use **Error
Interfaces** to group related exceptions.

```kotlin
@AssertLegal
fun assertOwner(project: Project, sender: Sender) {
    if (project.ownerId != sender.userId()) {
        throw ProjectErrors.unauthorized
    }
}
```

---

<a name="apply"></a>

## Applying State Changes (@Apply)

The `@Apply` method has a **dual function**:

1. It performs the initial modification when a command is first handled.
2. It is reused to rebuild the aggregate state when **event sourcing** (replaying the event stream).

- **Creation**: Returns a new instance.
- **Update**: Takes the current instance and returns an updated copy.
- **Deletion**: Returns `null`.

#### Tip: Minimizing Upcasters (Present Tense vs. Past Tense)

Fluxzero encourages storing the **Command payload itself** as the event payload (e.g., `CreateUser`, `UpdateEmail`). This "
Present Tense" approach significantly reduces the need for upcasters compared to traditional "Past Tense" events (
`UserCreated`, `EmailUpdated`).

Because functional needs and API contracts are generally more stable than internal state representations, storing the
inputs directly often results in an event stream that requires very few schema transformations over many years.

```kotlin
data class UpdateProject(...) : ProjectUpdate {
    @Apply
    fun apply(project: Project): Project { ... }
}
```

---

<a name="intercept-apply"></a>

## Filtering Updates (@InterceptApply)

Use `@InterceptApply` to filter or modify an update **before** `@AssertLegal` and `@Apply` is called. Unlike `@Apply`,
you can query other aggregates or search here to enrich the payload.

```kotlin
@InterceptApply
fun enrichTask(task: CreateTask): CreateTask {
    // Logic to modify or block the update before @AssertLegal and @Apply is called
}
```

---

<a name="assertlegal"></a>

## Business Invariants (@AssertLegal)

Enforce rules before an update. If a check fails, throw an exception that extends from `FunctionalException`. These
exceptions are portable and often used for client-side (**4xx** type) errors.

- **Exceptions**: Use `IllegalCommandException` for 4xx-style functional errors.
- **Rule Separation**: Split different business rules into separate `@AssertLegal` methods.
- **Null Safety**: Use `@Nullable` to inject an entity that might not exist. If `@Nullable` is missing, the method will
  not be invoked if the entity is missing.

**Automatic Existence Checks**:
The SDK implicitly checks existence based on the `@Apply` method:

- **Missing Entity**: If the current state is injected without `@Nullable` but the entity is missing,
  `Entity#NOT_FOUND_EXCEPTION` is thrown.
- **Existing Entity**: If the entity exists but no current state is injected (creation),
  `Entity#ALREADY_EXISTS_EXCEPTION` is thrown.

---

<a name="loading-entities"></a>

## Loading Entities

### Retrieval Patterns

```kotlin
// Load by ID
val p: Project = Fluxzero.loadAggregate(projectId).get()

// Load by Alias
val p2: Project = Fluxzero.loadEntity("owner-$userId").get()

// Load aggregate from a member ID
val p3: Project = Fluxzero.loadAggregateFor(taskId).get()

// Load a specific member entity
val t: Task = Fluxzero.loadEntity(taskId).get()
```

> **Note on Event Replay**: Loading or injecting an aggregate inside an `@HandleEvent` method automatically plays it
> back to reflect its state at the moment that specific event occurred.
