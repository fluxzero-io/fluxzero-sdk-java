# Search Through Model Relationships

Use the central capability/freshness matrix at `/docs/sdk/entities/graph-search` before configuring storage. Start at
plain `@Model`; an explicit composition path already maintains the indexed component needed for relationship search.
Relation queries do not support the statistics-based `count()` terminal and do not join a transaction readset.

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

Use the class-and-constraint overload when IDs must be selected by related Model content. It requires that related
Model's own public document or independently maintained internal Graph-component document; an explicit composition
path or `materializeGraph = true` supplies the latter. A reference-only `DOCUMENT` projection without such a Graph role
supplies no content, facet or sortable indexes. The whole materialized Graph projection is not searched as the Model
itself. The returned target
also needs a direct document (public or reference-only) or internal Graph-component document. A standalone event-sourced target
without either is loaded by ID rather than searched.

Use `whereParent`, `whereAncestor`, `whereChild` and `whereDescendant` for content-based traversal. Prefer
`searchGraph(Root.class).whereDescendant(Child.class, constraint)` for selective live Graph search based on children.
`searchGraph(Root.class).stream()` returns typed lazy `Graph<Root>` values through
explicit `@Parent(pathInParent = "...")` paths. It prefers a configured materialized graph projection and otherwise stitches
live; pass `true` as the second argument to force live composition. Use `fetch(..., ObjectNode.class)` only for an
explicit raw JSON boundary. Full-graph constraints mean the same on both routes, but broad free-form child filtering,
sorting or pagination should use a materialized projection when it cannot be narrowed through a relationship selector.

<a name="temporal-filters"></a>
