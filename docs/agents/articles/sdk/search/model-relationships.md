# Search Through Model Relationships

Use the central capability/freshness matrix at `/docs/sdk/entities/graph-search` before configuring storage. Start at
`@Model(searchable = true)`; typed composition descendants participate by default. `DOCUMENT` and `pathInParent`
do not activate indexing. Relation queries support counts/statistics but do not join a transaction readset.

Search current relationships without a precomputed tree document:

```java
List<Task> results = Fluxzero.search(Task.class)
    .whereAncestor(
        Project.class,
        MatchConstraint.match("ACTIVE", "status"))
    .fetchAll();
```

When a parent or ancestor ID is already known, avoid a document predicate:

```java
List<Task> projectTasks = Fluxzero.search(Task.class)
    .whereParent(projectId)
    .fetch(100);

List<Task> organisationTasks = Fluxzero.search(Task.class)
    .whereAncestor(organisationId)
    .fetch(100);
```

The ID overload starts from durable relationships and does not require a document for the parent or ancestor. A typed
`Id<T>` supplies its Model type; otherwise pass the functional ID and Model class. Use a loaded `Graph` for a
parent-scoped identity. Depth-bounded overloads support exact grandparents and further traversal.

Use the class-and-constraint overload to select related Models by their own indexed content. Both the returned
Model and the related Model must be effectively searchable. They use their canonical node documents; a composed Graph
is not used as a node source. Ancestor-ID filters only require the returned Model to be searchable: the ancestor need
not have a document. `DOCUMENT` and `pathInParent` do not activate search. Load a non-searchable Model by ID instead.

Use `whereParent`, `whereAncestor`, `whereChild` and `whereDescendant` for content-based traversal. Prefer
`searchGraph(Root.class).whereDescendant(Child.class, constraint)` for selective live Graph search based on children.
`searchGraph(Root.class).stream()` returns typed lazy `Graph<Root>` values through
explicit `@Parent(pathInParent = "...")` paths. It prefers a configured materialized graph projection and otherwise stitches
live; pass `true` as the second argument to force live composition. Use `fetch(..., ObjectNode.class)` only for an
explicit raw JSON boundary. Full-graph constraints mean the same on both routes, but broad free-form child filtering,
sorting or pagination should use a materialized projection when it cannot be narrowed through a relationship selector.

<a name="temporal-filters"></a>
