# Entities & Aggregates

In Fluxzero, aggregates and entities are immutable state holders. They simply define the data structure, while the rules
for transitioning between states lie in commands and orchestration logic resides in handlers.

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
3. **Pure Transitions**: `@Apply` methods must be pure functions, as they are also used during event-sourcing. They
   should only depend on the current state and the command payload.
4. **Deterministic State**: Inside `@Apply`, **never** perform searches, load other entities, or trigger side effects.
   Do those things in event handlers.
5. **No Updates in State Logic**: Inside `@AssertLegal` and `@InterceptApply`, it is fine to query, load other entities,
   or perform searches, but **never** invoke updates.
6. **Model Mutable Subparts as Entities**: If a nested object can be created/updated/deleted independently, model it as
   an entity (`@EntityId` + `@Member`) rather than a plain value object field.

---

<a name="defining-state"></a>

## Defining State

<a name="aggregates"></a>

### Aggregates (@Aggregate)

The consistency boundary root. Aggregates can be **event-sourced**, **document-based**, or both.

**Annotation Settings**:

- `searchable`: If `true`, the aggregate is automatically indexed in the document store.
- `collection`: (Optional) The name of the search collection. Defaults to the aggregate simple name.
- `eventPublication`: Controls when updates are stored and published.
    - `ALWAYS` (Default): Every applied update results in an event, even if no state changed.
    - `IF_MODIFIED`: Only creates/publishes an event if the aggregate's state actually changed (via `equals()` or
      `hashCode()`). **Recommended** to simplify idempotency (updates that don't change state won't clutter the stream).
    - `NEVER`: No events are stored or published. Used when just updating the aggregate document. Ensure
      `cached = false` in this case.
- `publicationStrategy`: Controls the destination of the event.
    - `STORE_AND_PUBLISH` (Default): Persist to the event store and global event log for distribution to handlers.
    - `STORE_ONLY`: Persists to the event store but does not trigger handlers. Useful for silent migrations or
      audit-only state.
    - `PUBLISH_ONLY`: Triggers handlers but don't persist to the event store. Useful to inform of triggers that do not
      change the aggregate state.

[//]: # (@formatter:off)
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
[//]: # (@formatter:on)

<a name="entities"></a>

### Entities & Members

Nested components within an aggregate.

- **Data Classes**: Kotlin `data classes` work seamlessly. The SDK uses the `copy()` method for state updates.
- **Routing**: The `@EntityId` property must be present in the command payload for automatic routing. Use
  `@Member(idProperty = "otherProperty")` if names differ.

#### Entity Boundary Heuristics

Model a type as an entity (root or `@Member`) when most of these are true:

- it has an identity that must stay stable over time,
- it can be created/updated/deleted without replacing the whole parent object,
- commands often target it directly (or by ID) as part of normal workflows,
- it carries lifecycle/status transitions of its own.

Keep it as a value object when it is replaced as one whole and has no independent lifecycle.

Entities can be nested many levels deep (`a -> b -> c -> d`). Any level can declare `@Member` children and be targeted
through routing as long as IDs are present.

[//]: # (@formatter:off)
```kotlin
data class Task(
    @EntityId val taskId: TaskId,
    val details: TaskDetails,
    val completed: Boolean = false
)
```
```kotlin
@Aggregate
data class A(
    @EntityId val aId: AId,
    @Member val bs: List<B> = emptyList()
)

data class B(
    @EntityId val bId: BId,
    @Member val cs: List<C> = emptyList()
)

data class C(
    @EntityId val cId: CId,
    @Member val ds: List<D> = emptyList()
)

data class D(
    @EntityId val dId: DId,
    val details: DDetails
)
```
[//]: # (@formatter:on)

<a name="ownership"></a>

### Ownership & Permissions

It is common practice to store an `ownerId` in an aggregate to enforce security in subsequent commands. Use **Error
Interfaces** to group related exceptions.

[//]: # (@formatter:off)
```kotlin
@AssertLegal
fun assertOwner(project: Project, sender: Sender) {
    if (project.ownerId != sender.userId()) {
        throw ProjectErrors.unauthorized
    }
}
```
[//]: # (@formatter:on)

---

<a name="apply"></a>

## Applying State Changes (@Apply)

The `@Apply` method has a **dual function**:

1. It performs the initial modification when a command is first handled.
2. It is reused to rebuild the aggregate state when **event sourcing** (replaying the event stream).

> **Important**: Applying an update to an entity via `@Apply` is what triggers **event publication**. In Fluxzero,
> events are a **side effect** of applying state changes to an entity. Depending on the
`@Aggregate(eventPublication=...)` setting, this will result in an event being stored and/or distributed to other
> handlers.

- **Creation**: Returns a new instance.
- **Update**: Takes the current instance and returns an updated copy.
- **Deletion**: Returns `null`.

```kotlin
data class CreateUser(val userId: UserId, val profile: UserProfile) {
    @Apply
    fun apply(): UserAccount {
        return UserAccount(userId, profile, accountClosed = false)
    }
}
```
```kotlin
data class UpdateProfile(val userId: UserId, val profile: UserProfile) {
    @Apply
    fun apply(current: UserAccount): UserAccount {
        return current.copy(profile = profile)
    }
}
```
```kotlin
data class DeleteUser(val userId: UserId) {
    @Apply
    fun apply(current: UserAccount): UserAccount? {
        return null // delete
    }
}
```
```kotlin
data class AddTask(val projectId: ProjectId, val taskId: TaskId, val details: TaskDetails) {
    @Apply
    fun apply(): Task {
        return Task(taskId, details, completed = false) // direct child creation
    }
}
```

You can apply updates directly to child/member entities (like `Task`) without manually rebuilding the parent. Fluxzero
immutably updates the parent aggregate (using Kotlin `copy(...)`) and inserts/replaces the child.

One update can also define multiple `@Apply` methods for different levels in the hierarchy. This is useful when a
single message should change both a member entity and the aggregate root.

```kotlin
data class CreatePaymentAttempt(val paymentId: String, val paymentAttemptId: String) {
    @Apply
    fun apply(): PaymentAttempt {
        return PaymentAttempt(paymentAttemptId)
    }

    @Apply
    fun apply(payment: Payment): Payment {
        return payment.copy(status = "pending")
    }
}
```

Here the same update creates a new `PaymentAttempt` and updates the root aggregate status in one state transition.

#### Tip: Minimizing Upcasters (Present Tense vs. Past Tense)

Fluxzero encourages applying the Command payload itself (e.g., `CreateUser`, `UpdateEmail`), which will result in a
"Present Tense" event stream.

Because functional needs and API contracts are generally more stable than internal state representations, storing the
inputs directly often results in an event stream that requires very few schema transformations (upcasters) over many
years.

### Automatic Existence Checks

The SDK implicitly checks existence based on the `@Apply` signature:

- **Missing Entity**: If current state is injected as a non-null type but the entity is missing,
  `Entity#NOT_FOUND_EXCEPTION` is thrown.
- **Existing Entity**: If no current state is injected (creation signature) but the entity already exists,
  `Entity#ALREADY_EXISTS_EXCEPTION` is thrown.

These checks can be relaxed by:

- using nullable injected parameters (for example `current: UserAccount?`) so methods can run when the entity/member is
  missing, or
- setting `@Apply(disableCompatibilityCheck = true)` for advanced cases where compatibility checks should be skipped.

---

<a name="intercept-apply"></a>

## Filtering Updates (@InterceptApply)

Use `@InterceptApply` to filter or modify an update **before** `@AssertLegal` and `@Apply` is called. Unlike `@Apply`,
you can query other aggregates or search here to enrich the payload.

In most cases, `@InterceptApply` lives on the update class being handled, so that update is available as `this` (not as
an injected method parameter).

[//]: # (@formatter:off)
```kotlin
data class CreateUser(val userId: UserId, val profile: UserProfile) {
    @InterceptApply
    fun ignoreNoChange(current: UserAccount): Any? {
        return if (current.profile == profile) null else this
    }
}
```
```kotlin
data class CreateUser(val userId: UserId, val profile: UserProfile) {
    @InterceptApply
    fun rewriteCreateAsUpdate(current: UserAccount): UpdateProfile {
        // Non-null current means this interceptor is only invoked when UserAccount exists.
        return UpdateProfile(userId, profile)
    }
}
```
```kotlin
data class BulkCreateTasks(val tasks: List<CreateTask>) {
    @InterceptApply
    fun expandBulk(): List<CreateTask> {
        return tasks
    }
}
```
```kotlin
data class CompleteTask(val projectId: ProjectId, val taskId: TaskId) {
    @InterceptApply
    fun skipWhenAlreadyCompleted(project: Project, task: Task): Any? {
        // You can inject both parent aggregate and addressed member entity.
        return if (task.completed) null else this
    }
}
```
```kotlin
data class AddTask(val projectId: ProjectId, val taskId: TaskId, val details: TaskDetails) {
    @InterceptApply
    fun skipDuplicate(task: Task?): Any? {
        // Child does not exist yet on create path; nullable type lets this run in both cases.
        return if (task != null) null else this
    }
}
```
[//]: # (@formatter:on)

Flux recursively applies interceptors until no further transformation is needed.

For bulk expansion, returned updates are applied sequentially to the same loaded aggregate/member context, so each
later update sees the state produced by earlier updates in the list.

Parameter injection rules are the same as `@AssertLegal`: if a parameter like `current: UserAccount` is non-null, the
interceptor is skipped when that entity is missing. Use nullable types when you want the interceptor to run for both
create and update paths.

`@InterceptApply` also works for member-entity updates. Interceptors can inspect child/member state and inject both the
child entity and parent aggregate in the same method. Parent injection is optional when only member state is needed.

### Invocation Order

1. Intercept using `@InterceptApply`
2. Assert preconditions using `@AssertLegal`
3. Apply state using `@Apply`

### Return Type Semantics

| Return value                    | Effect                    |
|:--------------------------------|:--------------------------|
| `null` or `Unit`                | Suppress update           |
| `this`                          | No change                 |
| New update object               | Rewrite the update        |
| `Collection` / `Stream` / `Optional` | Emit multiple updates |

> **Tip**: For idempotent handling of unchanged state, prefer
> `@Aggregate(eventPublication = EventPublication.IF_MODIFIED)`.

---

<a name="assertlegal"></a>

## Business Invariants (@AssertLegal)

Enforce rules before an update. If a check fails, throw an exception that extends from `FunctionalException`. These
exceptions are portable and often used for client-side (**4xx** type) errors.

- **Exceptions**: Prefer domain `Errors` objects (for example `UserErrors.accountClosed`). These constants typically
  wrap `IllegalCommandException`/`UnauthorizedException` and keep behavior consistent across handlers and tests.
- **Rule Separation**: Split different business rules into separate `@AssertLegal` methods.
- **Null Safety**: Use nullable types (for example `current: UserAccount?`) to inject an entity that might not exist.
  With a non-null parameter, the method will not be invoked if the entity is missing.
- **Existence checks**: Prefer relying on the automatic checks in [Applying State Changes (@Apply)](#apply) instead of
  duplicating existence checks in `@AssertLegal`.

Define domain error constants close to invariant logic:

[//]: # (@formatter:off)
```kotlin
object ProjectErrors {
    val unauthorized: FunctionalException = UnauthorizedException("Unauthorized for action")
    val accountClosed: FunctionalException = IllegalCommandException("Account is closed")
    val maxTasksReached: FunctionalException = IllegalCommandException("Project cannot have more than 3 tasks")
    val taskCompleted: FunctionalException = IllegalCommandException("Task has already completed")
}
```
[//]: # (@formatter:on)

Using shared error constants makes tests simpler and less brittle: assertions can verify exact domain errors directly,
instead of comparing exception message strings.

Example patterns:

[//]: # (@formatter:off)
```kotlin
data class UpdateProfile(val userId: UserId, val profile: UserProfile) {
    @AssertLegal
    fun assertAccountNotClosed(current: UserAccount) {
        if (current.accountClosed) {
            throw ProjectErrors.accountClosed
        }
    }
}
```
```kotlin
data class AddTask(val projectId: ProjectId, val taskId: TaskId, val details: TaskDetails) {
    @AssertLegal
    fun assertTaskLimit(project: Project, task: Task?) {
        // Parent + child injection in one invariant; nullable child allows missing/new member.
        if (task == null && project.tasks.size >= 3) {
            throw ProjectErrors.maxTasksReached
        }
    }
}
```
[//]: # (@formatter:on)

---

<a name="loading-entities"></a>

## Loading Entities

### Retrieval Patterns

[//]: # (@formatter:off)
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
[//]: # (@formatter:on)

> **Note on Event Replay**: Loading or injecting an aggregate inside an `@HandleEvent` method automatically plays it
> back to reflect its state at the moment that specific event occurred.
