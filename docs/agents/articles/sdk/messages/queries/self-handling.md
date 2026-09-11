Use this when query behavior lives on the query payload itself or when several query classes are registered in one fixture. Choose local or tracked delivery deliberately; do not use `@TrackSelf` merely as a routing patch.

Examples using `@Aggregate`, `Entity<T>` or aggregate repository methods on this page cover existing 1.x persisted state. For new 2.x domain state, use Models (`/docs/sdk/entities`). Migration requires an explicit data plan.

## Local self-handling queries

A query record with an `@HandleQuery` method and no `@TrackSelf` is discovered from the dispatched payload type and handled immediately as a local self-handler:

```java
public record GetProject(ProjectId projectId) implements Request<Project> {
    @HandleQuery
    Project handle() {
        return Fluxzero.loadAggregate(projectId).get();
    }
}

TestFixture.create()
        .whenQuery(new GetProject(projectId))
        .expectResult((Project result) -> result.projectId().equals(projectId));
```

Do not register `GetProject.class` merely to make `whenQuery(...)` work. The local registry inspects the query payload itself.

## Tracked self-handling queries

Add `@TrackSelf` when the query must be published and processed by tracking rather than handled immediately. Spring detects scanned `@TrackSelf` types and installs a payload-class filter. Outside Spring, register them explicitly. Use an asynchronous fixture when the test must exercise tracked consumption:

```java
@TrackSelf
@Consumer(name = "project-query")
public record FindProjects(String term) implements Request<List<Project>> {
    @HandleQuery
    List<Project> handle() {
        return Fluxzero.search(Project.class)
                .lookAhead(term, "name", "description")
                .fetch(20, Project.class);
    }
}
```

Tracked delivery changes persistence, replay, timeout, and consumer behavior. It is not required for an ordinary synchronous read.

## Avoid explicitly registered, unconstrained zero-parameter handlers

An ordinary local self-handler discovered from the dispatched query payload is already constrained by that payload
type. The collision risk begins when a class is explicitly registered: a registered handler method with no typed
payload parameter and empty `allowedClasses` can match every query. This is easy to trigger with a
zero-component record because registering its class creates a reusable zero-argument instance:

```java
public record GetConferenceStats() implements Request<ConferenceStats> {
    @HandleQuery
    ConferenceStats handle() {    // broad if this class is explicitly registered
        return computeConferenceStats();
    }
}
```

If explicit class registration is intentional, constrain the handler:

```java
public record GetConferenceStats() implements Request<ConferenceStats> {
    @HandleQuery(allowedClasses = GetConferenceStats.class)
    ConferenceStats handle() {
        return computeConferenceStats();
    }
}
```

A standalone handler can instead use the query payload as a typed parameter, which naturally constrains matching:

```java
@Component
final class UserQueries {
    @HandleQuery
    List<UserProfile> handle(FindUsers query, Sender sender) {
        return findVisibleUsers(query, sender);
    }
}
```

Test two different zero-component queries in the same fixture configuration and assert each typed result. This catches a broad handler answering the wrong query and causing a late `ClassCastException`. In fixture setup, explicitly register external handler components and annotation-driven class handlers that need registration; do not collect every ordinary local self-handling payload class into the handler list.
