# Independent Model Relationships

## Relationships

Use `@Parent` on the child:

```java
@Model
public record Task(
        @EntityId TaskId taskId,
        @Parent(pathInParent = "tasks") ProjectId projectId,
        TaskDetails details,
        boolean completed) {
}
```

The child remains an independent Model because its lifecycle is independent; `@Parent` expresses graph placement and
default cascade ownership. Being displayed below or deleted with the parent does not make it a `@Member`.

- Updating `projectId` moves the task.
- The parent and siblings do not need to load for a task-only change.
- Typed `Id<Parent>` supplies the relation type. A role is only needed for untyped/ambiguous IDs.
- For one polymorphic typed relation, use `@Parent(types = {Project.class, Folder.class}, ...) Id<?> parentId`; the
  concrete typed ID selects one statically declared parent type. Use separate properties for distinct relation roles.
- `pathInParent` is a stable public graph-placement and serialization contract. A pathless relation remains available through
  typed `Graph` traversal and parent-deletion lifecycle handling, but is not emitted as a named JSON graph edge.
- A child is logically deleted by default when any parent referenced by that `@Parent` is finally deleted. Set
  `deleteOnParentDeletion = false` for deliberately detached or independently retained children.
- Relationships are temporal; graph reconstruction can pin a `stateIndex`.
- Same-type recursion is supported. A `Folder` may hold
  `@Parent(pathInParent = "folders") FolderId parentFolderId`; Fluxzero accepts an arbitrarily deep tree and atomically rejects
  a concrete cycle. This remains a relation between independent Models, not an embedded recursive object.

Inject parents and further ancestors into assertions, interceptors and applies:

```java
@AssertLegal
void assertOpen(
        Task task,
        @Association("tasks") Project parent,
        Portfolio grandparent) {
    // Read-only ancestor dependencies.
}
```

Use `@Association` when the relation/path or same-type target would otherwise be ambiguous.

Every root and descendant in a materialized Graph retains its own serialized type and `@Revision`. The ordinary
serializer upcasts nodes independently and lazily; do not create a Graph-wide upcaster. Use
`@HandleDocument(modelGraph = Root.class)` and return the complete Graph only when evolved node JSON must be persisted
back into the derived projection. That operation must preserve the root, state boundary, nodes and placements and does
not modify direct Models, histories or relationships.

## Embedded members

`@Model` plus `@Member` is the intentional shared-stream option:

```java
@Model
public record Invoice(
        @EntityId InvoiceId invoiceId,
        @Member List<InvoiceLine> lines) {
}

public record InvoiceLine(
        @EntityId LineId lineId,
        BigDecimal amount) {
}
```

Choose this only if each line has no meaningful lifecycle outside its invoice: creation, every change, history,
retention and deletion all belong to the root. If any of those concerns can diverge, use a separate `@Model` plus
`@Parent`. A list-shaped field, frequent updates, or convenient whole-document storage is never sufficient reason to
use `@Member`.
