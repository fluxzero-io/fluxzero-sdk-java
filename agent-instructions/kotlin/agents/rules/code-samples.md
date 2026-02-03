---
apply: always
---

# Fluxzero Code Samples

This guide summarizes common patterns in Fluxzero applications. Examples illustrate typical commands,
queries, aggregates, web endpoints, authentication, and testing—using projects, tasks, users, and other domains as
illustrative use cases.

## Domain Modeling

- **Aggregates**: Define the consistency boundary and root of your domain. Annotate with `@Aggregate` to enable event
  sourcing and optional search indexing.
  ```kotlin
  @Aggregate(searchable = true, eventPublication = EventPublication.IF_MODIFIED)
  data class Project(
      @EntityId val projectId: ProjectId,
      val details: ProjectDetails,
      val ownerId: UserId,
      @Member val tasks: List<Task>
  )
  ```

- **Entities and Value Objects**: Model nested elements and immutable data types.
  ```kotlin
  data class Task(
      @EntityId val taskId: TaskId,
      val details: TaskDetails,
      val completed: Boolean,
      val assigneeId: UserId?
  )

  data class ProjectDetails(@field:NotBlank val name: String)

  data class TaskDetails(@field:NotBlank val name: String)
  ```

## Global Identifiers

- Strongly typed IDs in `com.example.app.todo.api`:
  ```kotlin
  class ProjectId(id: String) : Id<Project>(id)

  class TaskId(id: String) : Id<Task>(id)
  ```
- Use `Fluxzero.generateId(ProjectId::class)` or `...generateId(TaskId::class)` when creating new IDs.

## Commands

- **Command interfaces**: Define a common interface per aggregate or module to group related commands and manage
  dispatch. Typically annotate it with `@TrackSelf` and `@Consumer(name="...")` from
  `io.fluxzero.sdk.tracking`.
  ```kotlin
  import io.fluxzero.sdk.Fluxzero
  import io.fluxzero.sdk.tracking.Consumer
  import io.fluxzero.sdk.tracking.TrackSelf
  import io.fluxzero.sdk.tracking.handling.HandleCommand
  import jakarta.validation.constraints.NotNull

  @TrackSelf    // asynchronously track commands after dispatch
  @Consumer(name = "user-update") // logical consumer for command handling
  interface UserUpdate {
      @get:NotNull
      val userId: UserId

      @HandleCommand
      fun handle() {
          Fluxzero.loadAggregate(userId)
                  .assertAndApply(this)
      }
  }
  ```
  A generic pattern—apply similarly for other domains like `OrderUpdate` or `InventoryUpdate`.

  When an update (command) is successfully applied to an aggregate, the command payload is automatically published as an event. These events can be handled using `@HandleEvent` within an event handler. E.g.:

  ```kotlin
  @Component
  class UserLifecycleHandler {
      @HandleEvent
      fun handle(event: CreateUser) {
          // do something like sending an email
      }
  }
  ```

- **Command definitions**: Implement the interface with a `data class`. Use Jakarta Validation, role-based checks, and
  event-sourcing annotations:
  ```kotlin
  @RequiresRole(Role.admin)
  data class CreateUser(
      override val userId: UserId,
      @field:Valid val details: UserDetails,
      val role: Role
  ) : UserUpdate
  ```

- **CreateTask (sub-entity)**: Under a parent aggregate interface, return the new sub-entity instance.
  ```kotlin
  data class CreateTask(
      @field:NotNull val projectId: ProjectId,
      @field:NotNull val taskId: TaskId,
      @field:Valid @field:NotNull val details: TaskDetails
  ) : ProjectUpdate, AssertOwner {
      @Apply
      fun apply(): Task {
          return Task(
              taskId = taskId,
              details = details,
              completed = false,
              assigneeId = null
          )
      }
  }
  ```

- **CompleteTask (sub-entity)**: Enforce legal checks on both parent and entity before updating.
  ```kotlin
  data class CompleteTask(
      @field:NotNull val projectId: ProjectId,
      @field:NotNull val taskId: TaskId
  ) : ProjectUpdate {
      @AssertLegal
      fun assertPermission(project: Project, task: Task, sender: Sender) {
          if (!sender.isAdmin()
              && project.ownerId != sender.userId()
              && task.assigneeId != sender.userId()) {
              throw ProjectErrors.noPermission
          }
      }

      @Apply
      fun apply(task: Task): Task {
          return task.copy(completed = true)
      }
  }
  ```

with:

```kotlin
object ProjectErrors {
    val noPermission = IllegalCommandException("You don't have permission to complete this task.")
}
```

### Handling commands


**Standalone tracking handler:**

```kotlin
@Component
@Consumer(name = "notifications") // with this the handler consumes and processes commands in isolation
class NotificationHandler {
    @HandleCommand
    fun handle(command: SendEmail) {
        // send email by whatever method (e.g.: web request or smtp)
    }

    @HandleCommand
    fun handle(command: SendSlackMessage) {
        // ...
    }
}
```

**Standalone local handler:**

```kotlin
@Component
@LocalHandler(logMetrics = true)
class NotificationHandler {
    @HandleCommand
    fun handle(command: SendEmail) {
        // ...
    }

    @HandleCommand
    fun handle(command: SendSlackMessage) {
        // ...
    }
}
```

**Local self-handling:**

```kotlin
@RequiresRole(Role.admin)
data class SendEmail(
    val subject: String
    // ... other fields
) {
    @HandleCommand
    fun handle(smtpClient: SmtpClient) {
        // send the email using the Spring-injected SmtpClient
    }
}
```

**Tracking self-handling:**

```kotlin
@TrackSelf
@Consumer(name = "user-update")
interface UserUpdate {
    val userId: UserId

    @HandleCommand
    fun handle(): UserProfile {
        return Fluxzero.loadAggregate(userId).assertAndApply(this).get()
    }
}
```

**Stateful handler (saga):**

```kotlin
@Stateful
@Consumer(name = "stripe")
data class StripeTransaction(
    @Association val transactionId: TransactionId,
    @Association val stripeId: String,
    val retries: Int
) {
    companion object {
        @HandleEvent
        fun handle(event: MakePayment): StripeTransaction {
            val stripeId = makePayment(event)
            return StripeTransaction(
                transactionId = event.transactionId,
                stripeId = stripeId,
                retries = 0
            ) // automatically stores the handler in the repository
        }

        private fun makePayment(event: MakePayment): String {
            val response = Fluxzero.sendWebRequestAndWait(
                WebRequest.post(ApplicationProperties.require("stripe.url"))
                    .payload(event.toBody())
                    .build()
            )
            return response.getPayloadAs(String::class)
        }
    }

    @HandleEvent
    fun handle(event: StripeApproval): StripeTransaction? {
        // the event gets handled if it has a matching `stripeId` property
        Fluxzero.publishEvent(PaymentCompleted(transactionId))
        return null // this deletes the stateful handler from the repository
    }

    @HandleEvent
    fun handle(event: StripeFailure): StripeTransaction? {
        if (retries > 3) {
            Fluxzero.publishEvent(PaymentRejected(transactionId, "failed repeatedly"))
            return null
        }
        return copy(stripeId = makePayment(event), retries = retries + 1)
    }

    private fun makePayment(event: Any): String {
        val response = Fluxzero.sendWebRequestAndWait(
            WebRequest.post(ApplicationProperties.require("stripe.url"))
                .payload(event)
                .build()
        )
        return response.getPayloadAs(String::class)
    }
}
```

### Sending commands

Sending a command triggers domain behavior and optionally returns a result.

**Fire-and-forget:**

```kotlin
Fluxzero.sendAndForgetCommand(CreateUser("Alice"))

Fluxzero.sendAndForgetCommand(
    CreateUser("Alice"),
    Metadata.of("ipAddress", ipAddress),
    Guarantee.STORED
).join() // waits until the command has been stored by Fluxzero runtime
```

**Send and wait:**

```kotlin
val id = Fluxzero.sendCommandAndWait(CreateUser("Charlie"))
```

**Async:**

```kotlin
val future: CompletableFuture<UserId> =
    Fluxzero.sendCommand(CreateUser("Bob"))
```



## Queries

- **Query objects**: Implement `Request<T>` to define the return type of a query. For example, use
  `Request<List<Project>>` for listing projects.

- **List projects** (filter by owner unless admin):
  ```kotlin
  data class ListProjects(val dummy: Unit = Unit) : Request<List<Project>> {
      @HandleQuery
      fun handleQuery(sender: Sender): List<Project> {
          return Fluxzero.search(Project::class)
              .match(if (sender.isAdmin()) null else sender.userId(), "ownerId")
              .fetch(100)
      }
  }
  ```

- **Get single project**:
  ```kotlin
  data class GetProject(
      @field:NotNull val projectId: ProjectId
  ) : Request<Project> {
      @HandleQuery
      fun handleQuery(sender: Sender): Project? {
          return Fluxzero.search(Project::class)
              .match(projectId, "projectId")
              .match(if (sender.isAdmin()) null else sender.userId(), "ownerId")
              .fetchFirstOrNull()
      }
  }
  ```

### Handling queries

**Standalone tracking handler:**

```kotlin
@Component
class UserQueryHandler {
    @HandleQuery
    fun handle(query: GetUserProfile): UserProfile {
        return UserProfile(...)
    }
}
```

**Standalone local handler:**

```kotlin
@Component
@LocalHandler
class UserQueryHandler {
    @HandleQuery
    fun handle(query: GetUserProfile): UserProfile {
        return UserProfile(...)
    }
}
```

**Local self-handler with content filtering:**

```kotlin
data class GetUserProfile(
    @field:NotNull val userId: UserId
) : Request<UserProfile> {
    @HandleQuery
    @FilterContent
    fun handle(): UserProfile {
        return UserProfile(...)
    }
}
```

with content filtering logic in the profile:

```kotlin
@Aggregate
data class UserProfile(
    @EntityId val userId: UserId,
    val details: UserDetails
) {
    @FilterContent
    fun filter(sender: Sender): UserProfile? {
        return if (sender.isAdminOr(userId)) this else null
    }
}
```

### Fluxzero Search

```kotlin
val admins: List<UserAccount> = Fluxzero
    .search(UserAccount::class) // or input a search collection by name, e.g. "users"
    .match("admin", "roles.name")
    .lookAhead("pete") // searches for words anywhere starting with pete, ignoring capitalization or accents
    .inLast(Duration.ofDays(30))
    .sortBy("lastLogin", true) // true for descending. Make sure property `lastLogin` has `@Sortable`.
    .skip(100)
    .fetch(100)
```

Fluxzero supports a rich set of constraints:

- `lookAhead("cat", paths...)` – search-as-you-type lookups
- `query("*text & (cat* | hat)", paths...)` – full-text search
- `match(value, path)` – field match
- `matchFacet(name, value)` – match field with `@Facet` (generally faster than `match` for common words)
- `between(min, max, path)` – numeric or time ranges
- `since(...)`, `before(...)`, `beforeLast(...)`, `inLast(...)` – temporal filters
- `anyExist(...)` – match if *any* of the fields are present
- Logical operations: `not(...)`, `all(...)`, `any(...)`
- Grouping: `AllConstraint.all(...)`, `AnyConstraint.any(...)`

**Advanced Constraint Usages:**

```kotlin
// Combining multiple exclusions using NOT and Facets
val activeLuggage: List<Luggage> = Fluxzero.search(Luggage::class)
    .not(FacetConstraint.matchFacet("status", listOf(LOADED, DELIVERED)))
    .fetchAll(Luggage::class)

// Complex logical grouping
val complexFilter: List<User> = Fluxzero.search(User::class)
    .any(
        MatchConstraint.match("active", "status"),
        AllConstraint.all(
            MatchConstraint.match("pending", "status"),
            MatchConstraint.match(true, "vip")
        )
    )
    .fetchAll(User::class)

// Time-based filtering combined with status exclusion
val delayedBags: List<Luggage> = Fluxzero.search(Luggage::class)
    .beforeLast(Duration.ofHours(2))
    .not(FacetConstraint.matchFacet("status", listOf(LOADED, DELIVERED)))
    .fetchAll(Luggage::class)
```

When a field or getter is annotated with `@Facet`, you can also retrieve **facet statistics**:

```kotlin
data class Product(
    val productId: ProductId,
    @field:Facet val category: String,
    @field:Facet val brand: String,
    val name: String,
    val price: BigDecimal
)

val stats: List<FacetStats> = Fluxzero.search(Product::class)
    .lookAhead("wireless")
    .facetStats()
```

This gives you document counts per facet value:

```json
[
  { "name": "category", "value": "headphones", "count": 55 },
  { "name": "brand", "value": "Acme", "count": 45 },
  { "name": "brand", "value": "NoName", "count": 10 }
]
```

Aggregates with `searchable = true` are stored automatically on update.
You can index any object manually using:

```kotlin
Fluxzero.index(myObject)
```

This stores `myObject` in the document store so it can be queried later via `Fluxzero.search(...)`.

- If the object is annotated with `@Searchable`, any declared `collection`, `timestampPath`, or `endPath` will be used.
- If a field is annotated with `@EntityId`, it becomes the document ID. Otherwise, a random ID is generated.
- Timestamps can be inferred from annotated paths or passed explicitly.

You can also specify the collection in which the object should be stored directly:

```kotlin
Fluxzero.index(myObject, "customCollection")
```

### Sending queries

**Blocking:**

```kotlin
val profile: UserProfile =
    Fluxzero.queryAndWait(GetUserProfile("user456"))
```

**Async:**

```kotlin
val result: CompletableFuture<UserProfile> =
    Fluxzero.query(GetUserProfile(UserId("user123")))
```


## Scheduling

**Sending, cancelling and handling a schedule:**

```kotlin
@Component
class UserLifecycleHandler {
    @HandleEvent
    fun handle(event: CloseAccount) {
        Fluxzero.schedule(
            TerminateAccount(event.userId),
            "AccountClosed-${event.userId}",
            Duration.ofDays(30)
        )
    }

    @HandleEvent
    fun handle(event: ReopenAccount) {
        Fluxzero.cancelSchedule("AccountClosed-${event.userId}")
    }

    @HandleSchedule
    fun handle(schedule: TerminateAccount) {
        // logic here
    }
}
```

**Scheduling a command:**

```kotlin
@Component
class UserLifecycleHandler {
    @HandleEvent
    fun handle(event: CloseAccount) {
        Fluxzero.scheduleCommand(
            TerminateAccount(event.userId),
            "AccountClosed-${event.userId}",
            Fluxzero.currentTime().plus(10, ChronoUnit.DAYS)
        )
    }

    // ...
}
```

### Periodic schedules

**On payload class:**

```kotlin
@Periodic(delay = 5, timeUnit = TimeUnit.MINUTES)
data class RefreshWeatherData(val dummy: Unit = Unit)
```

**On handler method:**

```kotlin
@Periodic(cron = "0 0 * * MON", timeZone = "Europe/Amsterdam")
@HandleSchedule
fun weeklySync(schedule: PollData) {
    // ...
}
```

**Wait until schedule is started manually:**

```kotlin
@Periodic(delay = 5, timeUnit = TimeUnit.MINUTES, autostart = false)
data class RefreshData(val index: String)
```

## Web Endpoints

- **API Endpoints**: All API endpoints (except for `@ServeStatic` ones) should be annotated with `@Path("/api")` to ensure that API calls don't clash with frontend routes.
  ```kotlin
  @Component
  @Path("/api/projects")
  class ProjectsEndpoint {
  ```

- Expose REST routes for projects and tasks:
  ```kotlin
  @Component
  @Path("/api/projects")
  class ProjectsEndpoint {
      @HandlePost
      fun createProject(details: ProjectDetails): ProjectId {
          val id = Fluxzero.generateId(ProjectId::class)
          Fluxzero.sendCommandAndWait(CreateProject(id, details))
          return id
      }

      @HandleGet
      fun listProjects(): List<Project> {
          return Fluxzero.queryAndWait(ListProjects())
      }

      @HandleGet("/{projectId}")
      fun getProject(@PathParam projectId: ProjectId): Project {
          return Fluxzero.queryAndWait(GetProject(projectId))
      }

      @HandlePost("/{projectId}/tasks")
      fun createTask(
          @PathParam projectId: ProjectId,
          details: TaskDetails
      ): TaskId {
          val taskId = Fluxzero.generateId(TaskId::class)
          Fluxzero.sendCommandAndWait(
              CreateTask(projectId, taskId, details)
          )
          return taskId
      }

      @HandlePost("/{projectId}/tasks/{taskId}/complete")
      fun completeTask(
          @PathParam projectId: ProjectId,
          @PathParam taskId: TaskId
      ) {
          Fluxzero.sendCommandAndWait(CompleteTask(projectId, taskId))
      }

      @HandlePost("/{projectId}/tasks/{taskId}/assign")
      fun assignTask(
          @PathParam projectId: ProjectId,
          @PathParam taskId: TaskId,
          assigneeId: UserId
      ) {
          Fluxzero.sendCommandAndWait(
              AssignTask(projectId, taskId, assigneeId)
          )
      }
  }
  ```

- **Static Content**: Use `@ServeStatic` to serve static assets (like a frontend) from the classpath.
  ```kotlin
  @Component
  @ServeStatic("/")
  class UiEndpoint
  ```

## Upcasting

Upcasting transforms old versions of serialized objects (messages, documents, stateful handlers, etc.) into the current version during deserialization.

- **ObjectNode Upcaster**: Used for modifying the payload of an object.
  ```kotlin
  @Revision(2)
  data class Project(...) {
      @Component
      class ProjectUpcaster {
          @Upcast(type = "com.example.app.todo.api.model.Project", revision = 1)
          fun upcastRev1(payload: ObjectNode): JsonNode {
              if (!payload.has("details")) {
                  payload.putObject("details").put("name", "Untitled Project")
              }
              return payload
          }
      }
  }
  ```

- **Data Upcaster**: Used for changing the type, revision, or metadata of the serialized object.
  ```kotlin
  @Component
  class CreateProjectUpcaster {
      @Upcast(type = "com.example.app.old.CreateProject", revision = 0)
      fun upcastRev0(data: Data<JsonNode>): Data<JsonNode> {
          return data.withType("com.example.app.todo.api.CreateProject").withRevision(1)
      }
  }
  ```

Upcasting is applied to **ALL** deserializing objects in Fluxzero, ensuring they always match the latest model.

## Authentication & Authorization

- Enforce roles directly on command interfaces or implementations. Example: an admin-only create-user command signature:
  ```kotlin
  @RequiresRole(Role.admin)
  data class CreateUser(
      override val userId: UserId,
      @field:Valid val details: UserDetails,
      val role: Role
  ) : UserUpdate
  ```

## Testing Patterns

**Command tests:**

```kotlin
class ProjectTests {

    private val fixture = TestFixture.create()

    @Test
    fun successfullyCreateProject() {
        fixture.whenCommand("/todo/create-project.json")
            .expectEvents("/todo/create-project.json")
    }

    @Test
    fun creatingDuplicateProjectIsRejected() {
        fixture.givenCommands("/todo/create-project.json")
            .whenCommand("/todo/create-project.json")
            .expectExceptionalResult(Entity.ALREADY_EXISTS_EXCEPTION)
    }

    @Test
    fun canCreateTaskForOwnedProject() {
        fixture.givenCommands("/todo/create-project.json")
            .whenCommand("/todo/create-task.json")
            .expectEvents("/todo/create-task.json")
    }

    @Test
    fun cannotAssignCompletedTask() {
        fixture.givenCommands(
            "/todo/create-project.json",
            "/todo/create-task.json",
            "/todo/complete-task.json"
        )
            .whenCommand("/todo/assign-task.json")
            .expectExceptionalResult(TaskErrors.alreadyCompleted)
    }
}
```

JSON files used in tests can look like this:

```json
{
  "@class": "io.fluxzero.app.api.DepositMoney",
  "amount": 23,
  "recipient": "userA"
}
```

Note: In Kotlin tests, you must use fully qualified class names in JSON files.

You can extend another JSON file like this:

```json
{
  "@extends": "deposit-to-userA.json",
  "recipient": "userB"
}
```

Or like this if referring from another package:

```json
{
  "@extends": "/deposits/deposit-to-userA.json",
  "recipient": "userB"
}
```

**Query tests:**

```kotlin
@Test
fun listProjectsReturnsOwnedProjects() {
    fixture.givenCommands("/todo/create-project.json")
        .whenQuery(ListProjects())
        .expectResult { result -> result.isNotEmpty() }
}

@Test
fun nonOwnerCannotGetProject() {
    fixture.givenCommands("/todo/create-project.json")
        .givenCommands("/user/create-other-user.json")
        .whenQueryByUser("otherUser", "/todo/get-project.json")
        .expectNoResult()
}
```

**Schedule tests:**

```kotlin
class UserLifecycleTests {
    private val testFixture = TestFixture.create(UserLifecycleHandler::class)

    @Test
    fun accountIsTerminatedAfterClosing() {
        testFixture
            .givenCommands(
                CreateUser(myUserProfile),
                CloseAccount(userId)
            )
            .whenTimeElapses(Duration.ofDays(30))
            .expectEvents(AccountTerminated(userId))
    }

    @Test
    fun accountReopeningCancelsTermination() {
        testFixture
            .givenCommands(
                CreateUser(myUserProfile),
                CloseAccount(userId),
                ReopenAccount(userId)
            )
            .whenTimeElapses(Duration.ofDays(30))
            .expectNoEventsLike(AccountTerminated::class)
    }
}
```

**Endpoint tests:**

```kotlin
@Nested
class ProjectsEndpointTests {
    private val testFixture = TestFixture.create(ProjectsEndpoint())

    @Test
    fun createProjectViaPost() {
        fixture.whenPost("/projects", "/todo/create-project-request.json")
            .expectResult(ProjectId::class)
            .expectEvents(CreateProject::class)
    }

    @Test
    fun completeTaskViaEndpoint() {
        fixture.givenCommands("/todo/create-project.json", "/todo/create-task.json")
            .whenPost("/projects/p1/tasks/t1/complete", null)
            .expectEvents(CompleteTask::class)
    }
}
```


