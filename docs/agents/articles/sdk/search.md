# Model Search

1. **No SQL**: Data retrieval is performed exclusively via the `Fluxzero.search()` API or by loading entities.
2. **Automatic Indexing**: Models whose persistence set contains `DOCUMENT` maintain a direct current-state document.
3. **Stateful Handlers**: `@Stateful` handlers are automatically searchable as they are backed by the document store.
4. **Case & Accent Insensitive**: Text searches and matches are case and accent insensitive by default.
5. **Last Known State**: The document store represents the "last known state" of an object. While the event stream is
   historical, search is optimized for current data.
6. **Collection Naming**: By default, collections are named after the class (e.g., `Project`). Configure a Model's
   direct collection through `document = @DocumentProjection(collection = "...")`.
7. **Server-side Search Logic**: Keep filtering and sorting in Fluxzero search calls (`match`, `any/all`, `sortBy`,
   etc.). Avoid re-implementing filtering/sorting in client app code.

---

<a name="configuration"></a>

## Document configuration

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

## Consistency

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
