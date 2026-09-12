# Model Search And Graph Composition

## Search and graph composition

Public Model documents selected by including `DOCUMENT` and keeping `DocumentProjection.searchable = true` are
synchronous with successful commit completion:

```java
List<Task> open = Fluxzero.search(Task.class)
        .match(false, "completed")
        .fetchAll();
```

Filter by current related documents:

```java
List<Task> tasks = Fluxzero.search(Task.class)
        .whereAncestor(
                Project.class,
                MatchConstraint.match(
                        "active", "status"))
        .fetchAll();
```

Use `.whereParent(projectId)` or `.whereAncestor(organisationId)` when the related typed ID is known. This traverses
durable relationships directly and needs no parent or ancestor document. Use the ID-plus-Model-class overload for
untyped functional IDs and a loaded `Graph` for parent-scoped identities. The returned target must still have either a
public document or a relation-scoped current component document maintained for Graph participation; standalone event-sourced targets
without one should be loaded by ID.

Use the class-and-constraint `whereParent`, `whereAncestor`, `whereChild` and `whereDescendant` overloads when related
IDs must first be selected by current Model content. They use that Model's own public document or independently
maintained Graph-component document. A reference-only `DOCUMENT` projection without such a Graph role does
not add content, facet or sortable indexes. `materializeGraph = true` supplies an internal root document but does not
make the whole Graph projection the related predicate source. Prefer
`searchGraph(Root.class).whereDescendant(Child.class, constraint)` over a broad forced-live
nested-path filter when the child type is known. Use
`searchGraph(Root.class).stream()` for complete typed lazy `Graph<Root>` results without a cast or type witness. It
reads a configured `@GraphProjection` by default and otherwise stitches the applicable current documents live;
`searchGraph(Root.class, true)`
forces live composition. Use `fetch(..., ObjectNode.class)` for explicit raw JSON. Enable materialization with
`@Model(materializeGraph = true)`. Include `DOCUMENT` separately only when the Model itself needs a current document;
set `DocumentProjection.searchable = false` when that document must not be publicly searchable. Without a separate
Graph role its payload remains reference-loadable but its summary/reversary, facets and sortables are not indexed. A
Graph-component role retains its independently required indexes; shape those explicitly with `@SearchExclude`, `@Facet` and
`@Sortable`. A blank projection collection
appends `-graphs` to the direct Model collection when one exists, or to the
logical root-Model name otherwise; explicit lower-level composition limits fail rather than returning a partial graph.
