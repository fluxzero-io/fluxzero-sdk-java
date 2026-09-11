# Recipe: Search Model State

<a name="searchable"></a>

### Model documents and @Searchable values

Include `DOCUMENT` in a Model's persistence set to store a direct document. Use `@Searchable` for an ordinary document
value; do not annotate a Model with it.

[//]: # (@formatter:off)
```java
@Model(
        persistence = {ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT},
        document = @DocumentProjection(collection = "active_projects"))
public record Project(...) {}

@Model(
        persistence = ModelPersistence.DOCUMENT,
        document = @DocumentProjection(searchable = false))
public record UserPreferences(@EntityId UserId userId, ...) {}

@Searchable(collection = "custom_docs")
public record ExternalDocument(...) {}
```
[//]: # (@formatter:on)

`DocumentProjection.searchable = false` keeps a Model document out of unrestricted typed Model search. Without a
separate Graph role it stays in the normal resolved collection—by default the resolved logical Model name or the explicitly
configured collection—but its summary/reversary, facets and sortables are empty. Keeping the collection stable supports
adoption of existing documents. Direct Model loads, aliases and exact parent/ancestor-ID relations still work. If the
same Model participates in Graph composition, its current component document retains the independently required
indexes without becoming publicly searchable; shape those with `@SearchExclude`, `@Facet` and `@Sortable`.

<a name="facets-sorting"></a>

### Facets & Sorting

- **@Facet**: Marks a field for high-performance exact matching and statistics collection.
- **@Sortable**: Required for any field you intend to use in a `sortBy(...)` clause. It is also required for **quantity
  filtering** (e.g., `greaterThan`) and checking for field existence.

[//]: # (@formatter:off)
```java
public record Product(
    @EntityId ProductId productId,
    @Facet String category,
    @Sortable BigDecimal price,
    String description
) {}
```
[//]: # (@formatter:on)

<a name="exclude-include"></a>

### Exclusion & Inclusion

Use `@SearchExclude` to keep sensitive or internal data out of the search index. Conversely, use `@SearchInclude` to
explicitly include fields that might otherwise be ignored (e.g., specific getters).

For response shaping, prefer search projections instead of post-processing in app code:

- `exclude("path")` to remove fields from returned documents.
- `includeOnly("path1", "path2")` to return only specific fields.

---

<a name="searching"></a>

Public Model documents selected by including `DOCUMENT` and keeping `DocumentProjection.searchable = true` are
**synchronous with Model-commit completion**.

- **Direct model guarantee**: `sendCommandAndWait` followed by a direct model search observes the committed direct
  document.
- **Graph projection window**: a materialized whole-root graph is asynchronous by default. Use
  `GraphProjectionCompletion.AWAIT` for an operation whose result must wait for affected roots to reach its state
  boundary.
- **Guarantee Boundary**: Do not assume immediate search consistency when the document is indexed as a downstream side
  effect, such as in an event handler or projection handler. In that case, wait for the projection's own completion
  signal or return the needed state from the command handler.
- **UI Tip**: For immediate feedback, return the new state directly from the command handler or use WebSockets to notify the UI when the projection is ready.

---

<a name="retention"></a>
