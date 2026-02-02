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
  ```java
  @Aggregate(searchable = true, eventPublication = EventPublication.IF_MODIFIED)
  @Builder(toBuilder = true)
  public record Project(
      @EntityId ProjectId projectId,
      ProjectDetails details,
      UserId ownerId,
      @With @Member List<Task> tasks
  ) {}
  ```

- **Entities and Value Objects**: Model nested elements and immutable data types.
  ```java
  public record Task(
      @EntityId TaskId taskId,
      TaskDetails details,
      boolean completed,
      UserId assigneeId
  ) {}

  public record ProjectDetails(@NotBlank String name) {}

  public record TaskDetails(@NotBlank String name) {}
  ```

## Global Identifiers

- Strongly typed IDs in `com.example.app.todo.api`:
  ```java
  public class ProjectId extends Id<Project> {
      public ProjectId(String id) { super(id); }
  }

  public class TaskId extends Id<Task> {
      public TaskId(String id) { super(id); }
  }
  ```
- Use `Fluxzero.generateId(ProjectId.class)` or `...generateId(TaskId.class)` when creating new IDs.

## Commands

- **Command interfaces**: Define a common interface per aggregate or module to group related commands and manage
  dispatch. Typically annotate it with `@TrackSelf` and `@Consumer(name="...")` from
  `io.fluxzero.sdk.tracking`.
  ```java
  import io.fluxzero.sdk.Fluxzero;
  import io.fluxzero.sdk.tracking.Consumer;
  import io.fluxzero.sdk.tracking.TrackSelf;
  import io.fluxzero.sdk.tracking.handling.HandleCommand;
  import jakarta.validation.constraints.NotNull;

  @TrackSelf    // asynchronously track commands after dispatch
  @Consumer(name = "user-update") // logical consumer for command handling
  public interface UserUpdate {
      @NotNull UserId userId();

      @HandleCommand
      default void handle() {
          Fluxzero.loadAggregate(userId())
                       .assertAndApply(this);
      }
  }
  ```
  A generic pattern—apply similarly for other domains like `OrderUpdate` or `InventoryUpdate`.

  When an update (command) is successfully applied to an aggregate, the command payload is automatically published as an event. These events can be handled using `@HandleEvent` within an event handler. E.g.:

  ```java
  @Component
  class UserLifecycleHandler {
      @HandleEvent
      void handle(CreateUser event) {
          // do something like sending an email
      }
  }
  ```

- **Command definitions**: Implement the interface with a `record`. Use Jakarta Validation, role-based checks, and
  event-sourcing annotations:
  ```java
  @RequiresRole(Role.admin)
  public record CreateUser(
      UserId userId,
      @Valid UserDetails details,
      Role role
  ) implements UserUpdate
  ```

- **CreateTask (sub-entity)**: Under a parent aggregate interface, return the new sub-entity instance.
  ```java
  public record CreateTask(
      @NotNull ProjectId projectId,
      @NotNull TaskId taskId,
      @Valid @NotNull TaskDetails details
  ) implements ProjectUpdate, AssertOwner {
      @Apply
      Task apply() {
          return Task.builder()
                     .taskId(taskId)
                     .details(details)
                     .completed(false)
                     .build();
      }
  }
  ```

- **CompleteTask (sub-entity)**: Enforce legal checks on both parent and entity before updating.
  ```java
  public record CompleteTask(
      @NotNull ProjectId projectId,
      @NotNull TaskId taskId
  ) implements ProjectUpdate {
      @AssertLegal
      void assertPermission(Project project, Task task, Sender sender) {
          if (!sender.isAdmin()
              && !project.ownerId().equals(sender.userId())
              && !(task.assigneeId() != null && task.assigneeId().equals(sender.userId()))) {
              throw ProjectErrors.noPermission;
          }
      }

      @Apply
      Task apply(Task task) {
          return task.toBuilder()
                     .completed(true)
                     .build();
      }
  }
  ```

with:

```java
public interface ProjectErrors {
  IllegalCommandException noPermission = new IllegalCommandException("You don't have permission to complete this task.");
}
```

### Handling commands


**Standalone tracking handler:**

```java
@Component
@Consumer(name = "notifications") // with this the handler consumes and processes commands in isolation 
class NotificationHandler {
    @HandleCommand
    void handle(SendEmail command) {
        // send email by whatever method (e.g.: web request or smtp)
    }

    @HandleCommand
    void handle(SendSlackMessage command) {
      // ...
    }
}
```

**Standalone local handler:**

```java
@Component
@LocalHandler(logMetrics = true) 
class NotificationHandler {
  @HandleCommand
  void handle(SendEmail command) {
    // ...
  }

  @HandleCommand
  void handle(SendSlackMessage command) {
    // ...
  }
}
```

**Local self-handling:**

```java
@Value
@RequiresRole(Role.admin)
public class SendEmail {
  String subject;
  // ... other fields

  @HandleCommand
  void handle(@Autowired SmtpClient smtpClient) {
    // send the email using the Spring-injected SmtpClient
  }
}
```

**Tracking self-handling:**

```java
@TrackSelf
@Consumer(name = "user-update")
public interface UserUpdate {

  UserId userId();

  @HandleCommand
  default UserProfile handle() {
    return Fluxzero.loadAggregate(userId()).assertAndApply(this).get();
  }
}
```

**Stateful handler (saga):**

```java
@Stateful
@Consumer(name = "stripe")
@Builder(toBuilder = true)
record StripeTransaction(@Association TransactionId transactionId, @Association String stripeId, int retries) {
  @HandleEvent
  static StripeTransaction handle(MakePayment event) {
    String stripeId = makePayment();
    return new StripeTransaction(event.transactionId(), stripeId,
                                 0); //automatically stores the handler in the repository
  }

  String makePayment() {
    WebResponse response = Fluxzero.sendWebRequestAndWait(
            WebRequest.post(ApplicationProperties.require("stripe.url")).payload(toBody(event)).build());
    return response.getPayloadAs(String.class);
  }

  @HandleEvent
  StripeTransaction handle(StripeApproval event) { //the event gets handled if it has a matching `stripeId` property 
    Fluxzero.publishEvent(new PaymentCompleted(transactionId));
    return null; // this deletes the stateful handler from the repository
  }

  @HandleEvent
  StripeTransaction handle(StripeFailure event) {
    if (retries > 3) {
      Fluxzero.publishEvent(new PaymentRejected(transactionId, "failed repeatedly"));
      return null;
    }
    return toBuilder().stripeId(makePayment()).retries(retries + 1).build();
  }
}
```

### Sending commands

Sending a command trigges domain behavior and optionally returns a result.

**Fire-and-forget:**

```java
Fluxzero.sendAndForgetCommand(new CreateUser("Alice"));
        
Fluxzero.sendAndForgetCommand(new CreateUser("Alice"), Metadata.of("ipAddress", ipAddress), Guarantee.STORED).join(); //waits until the command has been stored by Fluxzero runtime
```

**Send and wait:**

```java
UserId id = Fluxzero.sendCommandAndWait(new CreateUser("Charlie"));
```

**Async:**

```java
CompletableFuture<UserId> future =
    Fluxzero.sendCommand(new CreateUser("Bob"));
```



## Queries

- **Query objects**: Implement `Request<T>` to define the return type of a query. For example, use
  `Request<List<Project>>` for listing projects.

- **List projects** (filter by owner unless admin):
  ```java
  public record ListProjects() implements Request<List<Project>> {
      @HandleQuery
      List<Project> handleQuery(Sender sender) {
          return Fluxzero.search(Project.class)
                              .match(sender.isAdmin() ? null : sender.userId(), "ownerId")
                              .fetch(100);
      }
  }
  ```

- **Get single project**:
  ```java
  public record GetProject(@NotNull ProjectId projectId)
      implements Request<Project> {
      @HandleQuery
      Project handleQuery(Sender sender) {
          return Fluxzero.search(Project.class)
                               .match(projectId, "projectId")
                               .match(sender.isAdmin() ? null : sender.userId(), "ownerId")
                               .fetchFirstOrNull();
      }
  }
  ```

### Handling queries

**Standalone tracking handler:**

```java
@Component
class UserQueryHandler {
    @HandleQuery
    UserProfile handle(GetUserProfile query) {
        return new UserProfile(...);
    }
}
```

**Standalone local handler:**

```java
@Component
@LocalHandler
class UserQueryHandler {
  @HandleQuery
  UserProfile handle(GetUserProfile query) {
    return new UserProfile(...);
  }
}
```

**Local self-handler with content filtering:**

```java
public record GetUserProfile(@NotNull UserId userId) implements Request<UserProfile> {
  @HandleQuery
  @FilterContent
  UserProfile handle() {
    return new UserProfile(...);
  }
}
```

with content filtering logic in the profile:

```java
@Aggregate
public record UserProfile(
        @EntityId UserId userId,
        UserDetails details
) {
  @FilterContent
  UserProfile filter(Sender sender) {
    return sender.isAdminOr(userId) ? this : null;
  }
}
```

### Fluxzero Search

```java
List<UserAccount> admins = Fluxzero
    .search(UserAccount.class) //or input a search collection by name, e.g. "users"
    .match("admin", "roles.name")
    .lookAhead("pete") //searches for words anywhere starting with pete, ignoring capitalization or accents     
    .inLast(Duration.ofDays(30))
    .sortBy("lastLogin", true) // true for descending. Make sure property `lastLogin` has `@Sortable`.
    .skip(100)
    .fetch(100);
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

```java
// Combining multiple exclusions using NOT and Facets
List<Luggage> activeLuggage = Fluxzero.search(Luggage.class)
    .not(FacetConstraint.matchFacet("status", List.of(LOADED, DELIVERED)))
    .fetchAll(Luggage.class);

// Complex logical grouping
List<User> complexFilter = Fluxzero.search(User.class)
    .any(
        MatchConstraint.match("active", "status"),
        AllConstraint.all(
            MatchConstraint.match("pending", "status"),
            MatchConstraint.match(true, "vip")
        )
    )
    .fetchAll(User.class);

// Time-based filtering combined with status exclusion
List<Luggage> delayedBags = Fluxzero.search(Luggage.class)
    .beforeLast(Duration.ofHours(2))
    .not(FacetConstraint.matchFacet("status", List.of(LOADED, DELIVERED)))
    .fetchAll(Luggage.class);
```

When a field or getter is annotated with `@Facet`, you can also retrieve **facet statistics**:

```java
public record Product(ProductId productId, 
                      @Facet String category,
                      @Facet String brand,
                      String name,
                      BigDecimal price) {
}

List<FacetStats> stats = Fluxzero.search(Product.class)
        .lookAhead("wireless")
        .facetStats();
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

```java
Fluxzero.index(myObject);
```  

This stores `myObject` in the document store so it can be queried later via `Fluxzero.search(...)`.

- If the object is annotated with `@Searchable`, any declared `collection`, `timestampPath`, or `endPath` will be used.
- If a field is annotated with `@EntityId`, it becomes the document ID. Otherwise, a random ID is generated.
- Timestamps can be inferred from annotated paths or passed explicitly.

You can also specify the collection in which the object should be stored directly:

```java
Fluxzero.index(myObject, "customCollection");
```

### Sending queries

**Blocking:**

```java
UserProfile profile =
    Fluxzero.queryAndWait(new GetUserProfile("user456"));
```

**Async:**

```java
CompletableFuture<UserProfile> result =
    Fluxzero.query(new GetUserProfile(new UserId("user123")));
```


## Scheduling

**Sending, cancelling and handling a schedule:**

```java
@Component
public class UserLifecycleHandler {
  @HandleEvent
  void handle(CloseAccount event) {
    Fluxzero.schedule(
            new TerminateAccount(event.getUserId()),
            "AccountClosed-" + event.getUserId(),
            Duration.ofDays(30)
    );
  }

  @HandleEvent
  void handle(ReopenAccount event) {
    Fluxzero.cancelSchedule("AccountClosed-" + event.getUserId());
  }

  @HandleSchedule
  void handle(TerminateAccount schedule) {
    // logic here
  }
}
```

**Scheduling a command:**

```java
@Component
public class UserLifecycleHandler {
  @HandleEvent
  void handle(CloseAccount event) {
    Fluxzero.scheduleCommand(
            new TerminateAccount(event.getUserId()),
            "AccountClosed-" + event.getUserId(),
            Fluxzero.currentTime().plus(10, ChronoUnit.DAYS)
    );
  }

  // ...
}
```

### Periodic schedules

**On payload class:**

```java
@Periodic(delay = 5, timeUnit = TimeUnit.MINUTES)
public record RefreshWeatherData() {
}
```

**On handler method:**

```java
@Periodic(cron = "0 0 * * MON", timeZone = "Europe/Amsterdam")
@HandleSchedule
void weeklySync(PollData schedule) {
    ...
}
```

**Wait until schedule is started manually:**

```java
@Periodic(delay = 5, timeUnit = TimeUnit.MINUTES, autostart = false)
public record RefreshData(String index) {
}
```

## Web Endpoints

- **API Endpoints**: All API endpoints (except for `@ServeStatic` ones) should be annotated with `@Path("/api")` to ensure that API calls don't clash with frontend routes.
  ```java
  @Component
  @Path("/api/projects")
  public class ProjectsEndpoint {
  ```

- Expose REST routes for projects and tasks:
  ```java
  @Component
  @Path("/api/projects")
  public class ProjectsEndpoint {
      @HandlePost
      ProjectId createProject(ProjectDetails details) {
          var id = Fluxzero.generateId(ProjectId.class);
          Fluxzero.sendCommandAndWait(new CreateProject(id, details));
          return id;
      }

      @HandleGet
      List<Project> listProjects() {
          return Fluxzero.queryAndWait(new ListProjects());
      }

      @HandleGet("/{projectId}")
      Project getProject(@PathParam ProjectId projectId) {
          return Fluxzero.queryAndWait(new GetProject(projectId));
      }

      @HandlePost("/{projectId}/tasks")
      TaskId createTask(@PathParam ProjectId projectId, TaskDetails details) {
          var taskId = Fluxzero.generateId(TaskId.class);
          Fluxzero.sendCommandAndWait(
              new CreateTask(projectId, taskId, details)
          );
          return taskId;
      }

      @HandlePost("/{projectId}/tasks/{taskId}/complete")
      void completeTask(@PathParam ProjectId projectId,
                        @PathParam TaskId taskId) {
          Fluxzero.sendCommandAndWait(new CompleteTask(projectId, taskId));
      }

      @HandlePost("/{projectId}/tasks/{taskId}/assign")
      void assignTask(@PathParam ProjectId projectId,
                      @PathParam TaskId taskId,
                      UserId assigneeId) {
          Fluxzero.sendCommandAndWait(
              new AssignTask(projectId, taskId, assigneeId)
          );
      }
  }
  ```

- **Static Content**: Use `@ServeStatic` to serve static assets (like a frontend) from the classpath.
  ```java
  @Component
  @ServeStatic("/")
  public class UiEndpoint {
  }
  ```

## Upcasting

Upcasting transforms old versions of serialized objects (messages, documents, stateful handlers, etc.) into the current version during deserialization.

- **ObjectNode Upcaster**: Used for modifying the payload of an object.
  ```java
  @Revision(2)
  public record Project(...) {
      @Component
      public static class ProjectUpcaster {
          @Upcast(type = "com.example.app.todo.api.model.Project", revision = 1)
          public JsonNode upcastRev1(ObjectNode payload) {
              if (!payload.has("details")) {
                  payload.putObject("details").put("name", "Untitled Project");
              }
              return payload;
          }
      }
  }
  ```

- **Data Upcaster**: Used for changing the type, revision, or metadata of the serialized object.
  ```java
  @Component
  public class CreateProjectUpcaster {
      @Upcast(type = "com.example.app.old.CreateProject", revision = 0)
      public Data<JsonNode> upcastRev0(Data<JsonNode> data) {
          return data.withType("com.example.app.todo.api.CreateProject").withRevision(1);
      }
  }
  ```

Upcasting is applied to **ALL** deserializing objects in Fluxzero, ensuring they always match the latest model.

## Authentication & Authorization

- Enforce roles directly on command interfaces or implementations. Example: an admin-only create-user command signature:
  ```java
  @RequiresRole(Role.admin)
  public record CreateUser(UserId userId, @Valid UserDetails details, Role role) implements UserUpdate
  ```

## Testing Patterns

**Command tests:**

```java
public class ProjectTests {

  final TestFixture fixture = TestFixture.create();

  @Test
  void successfullyCreateProject() {
    fixture.whenCommand("/todo/create-project.json")
            .expectEvents("/todo/create-project.json");
  }

  @Test
  void creatingDuplicateProjectIsRejected() {
    fixture.givenCommands("/todo/create-project.json")
            .whenCommand("/todo/create-project.json")
            .expectExceptionalResult(Entity.ALREADY_EXISTS_EXCEPTION);
  }

  @Test
  void canCreateTaskForOwnedProject() {
    fixture.givenCommands("/todo/create-project.json")
            .whenCommand("/todo/create-task.json")
            .expectEvents("/todo/create-task.json");
  }

  @Test
  void cannotAssignCompletedTask() {
    fixture.givenCommands(
                    "/todo/create-project.json", "/todo/create-task.json", "/todo/complete-task.json"
            )
            .whenCommand("/todo/assign-task.json")
            .expectExceptionalResult(TaskErrors.alreadyCompleted);
  }
}
```

JSON files used in tests can look like this:

```json
{
  "@class": "DepositMoney",
  "amount": 23,
  "recipient": "userA"
}
```

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

```java
@Test
void listProjectsReturnsOwnedProjects() {
    fixture.givenCommands("/todo/create-project.json")
           .whenQuery(new ListProjects())
           .expectResult(result -> !result.isEmpty());
}

@Test
void nonOwnerCannotGetProject() {
    fixture.givenCommands("/todo/create-project.json")
           .givenCommands("/user/create-other-user.json")
           .whenQueryByUser("otherUser", "/todo/get-project.json")
           .expectNoResult();
}
```

**Schedule tests:**

```java
public class UserLifecycleTests {
  final TestFixture testFixture = TestFixture.create(UserLifecycleHandler.class);

  @Test
  void accountIsTerminatedAfterClosing() {
    testFixture
            .givenCommands(new CreateUser(myUserProfile),
                           new CloseAccount(userId))
            .whenTimeElapses(Duration.ofDays(30))
            .expectEvents(new AccountTerminated(userId));
  }

  @Test
  void accountReopeningCancelsTermination() {
    testFixture
            .givenCommands(new CreateUser(myUserProfile),
                           new CloseAccount(userId),
                           new ReopenAccount(userId))
            .whenTimeElapses(Duration.ofDays(30))
            .expectNoEventsLike(AccountTerminated.class);
  }
}
```

**Endpoint tests:**

```java
@Nested
class ProjectsEndpointTests {
    final TestFixture testFixture = TestFixture.create(new ProjectsEndpoint());

    @Test
    void createProjectViaPost() {
        fixture.whenPost("/projects", "/todo/create-project-request.json")
               .expectResult(ProjectId.class)
               .expectEvents(CreateProject.class);
    }

    @Test
    void completeTaskViaEndpoint() {
        fixture.givenCommands("/todo/create-project.json", "/todo/create-task.json")
               .whenPost("/projects/p1/tasks/t1/complete", null)
               .expectEvents(CompleteTask.class);
    }
}
```


