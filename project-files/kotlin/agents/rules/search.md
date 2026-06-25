# Search & Documents

Fluxzero features a built-in search engine that eliminates the need for external databases or SQL. Applications manage
data through a unified document store, leveraging automatic indexing and a rich set of search constraints.

---

## Quick Navigation

- [Core Principles](#core-rules)
- [Configuration & Indexing](#configuration)
    - [@Searchable & @Aggregate](#searchable)
    - [Facets & Sorting (@Facet, @Sortable)](#facets-sorting)
    - [Exclusion & Inclusion (@SearchExclude, @SearchInclude)](#exclude-include)
- [Searching for Data](#searching)
    - [Basic Constraints (match, lookAhead)](#basic-constraints)
    - [Temporal Filters (since, inLast)](#temporal-filters)
    - [Logical Grouping (All, Any, Not)](#logical-grouping)
- [Pagination & Sorting](#pagination-sorting)
- [Async Search Operations](#async-search)
- [Consistency & The Window](#consistency)
- [Document Retention](#retention)
- [Facet Statistics](#facet-stats)
- [Manual Indexing & Bulk Operations](#bulk-ops)
- [Collection Management](#collection-management)

---

<a name="core-rules"></a>

## Core Rules

1. **No SQL**: Data retrieval is performed exclusively via the `Fluxzero.search()` API or by loading entities.
2. **Automatic Indexing**: Aggregates with `searchable = true` are indexed automatically upon every update.
3. **Stateful Handlers**: `@Stateful` handlers are automatically searchable as they are backed by the document store.
4. **Case & Accent Insensitive**: Text searches and matches are case and accent insensitive by default.
5. **Last Known State**: The document store represents the "last known state" of an object. While the event stream is
   historical, search is optimized for current data.
6. **Collection Naming**: By default, collections are named after the class (e.g., `Project`). Use the `collection`
   attribute in annotations to override this.
7. **Server-side Search Logic**: Keep filtering and sorting in Fluxzero search calls (`match`, `any/all`, `sortBy`,
   etc.). Avoid re-implementing filtering/sorting in client app code.

---

<a name="configuration"></a>

## Configuration & Indexing

<a name="searchable"></a>

### @Searchable & @Aggregate

To enable search for an aggregate or any object, use the appropriate annotation.

```kotlin
@Aggregate(searchable = true, collection = "active_projects")
data class Project(...)

@Searchable(collection = "custom_docs")
data class ExternalDocument(...)
```

<a name="facets-sorting"></a>

### Facets & Sorting

- **@Facet**: Marks a field for high-performance exact matching and statistics collection.
- **@Sortable**: Required for any field you intend to use in a `sortBy(...)` clause. It is also required for **quantity
  filtering** (e.g., `greaterThan`) and checking for field existence.

```kotlin
data class Product(
    @EntityId val productId: ProductId,
    @Facet val category: String,
    @Sortable val price: BigDecimal,
    val description: String
)
```

<a name="exclude-include"></a>

### Exclusion & Inclusion

Use `@SearchExclude` to keep sensitive or internal data out of the search index. Conversely, use `@SearchInclude` to
explicitly include fields that might otherwise be ignored (e.g., specific getters).

For response shaping, prefer search projections instead of post-processing in app code:

- `exclude("path")` to remove fields from returned documents.
- `includeOnly("path1", "path2")` to return only specific fields.

---

<a name="searching"></a>

## Searching for Data

Access the search engine via `Fluxzero.search(Project::class)` or by providing a collection name.

<a name="basic-constraints"></a>

### Basic Constraints

- **match(value, paths...)**: Exact field match.
- **lookAhead(text, paths...)**: Search-as-you-type (prefix matching).
- **query(text, paths...)**: Full-text search with support for wildcards and operators (`*`, `&`, `|`).

```kotlin
val results: List<Project> = Fluxzero.search(Project::class)
    .lookAhead("flux", "name")
    .match("ACTIVE", "status")
    .fetch(10)
```

<a name="temporal-filters"></a>

### Temporal Filters

Filter documents based on their creation or modification time.

- **since(instant)** / **before(instant)**: Absolute time ranges.
- **inLast(duration)** / **beforeLast(duration)**: Relative time ranges.

```kotlin
val recent: List<Project> = Fluxzero.search(Project::class)
    .inLast(Duration.ofDays(7))
    .fetchAll()
```

<a name="logical-grouping"></a>

### Logical Grouping

Combine multiple constraints using logical operations.

```kotlin
val complex: List<User> = Fluxzero.search(User::class)
    .any(
        MatchConstraint.match("ADMIN", "role"),
        AllConstraint.all(
            MatchConstraint.match("USER", "role"),
            MatchConstraint.match(true, "vip")
        )
    )
    .fetchAll()
```

---

<a name="pagination-sorting"></a>

## Pagination & Sorting

Fluxzero supports efficient pagination and sorting.

- **sortBy(path, descending)**: Sort results by a specific field. The field MUST be annotated with `@Sortable`.
- **skip(int)**: Number of results to skip (offset).
- **fetch(int)**: Maximum number of results to return.
- **fetchAll()**: Returns all matching documents (use with caution for large collections).

---

<a name="async-search"></a>

## Async Search Operations

When a handler can return a `CompletableFuture`, prefer the async search methods instead of blocking on synchronous
search calls. Return the future directly; do not call `.join()` inside the handler unless the handler truly needs the
value before doing more work.

- **fetchAsync(int)** / **fetchAsync(int, Class<T>)**: Asynchronous equivalent of `fetch(...)`.
- **countAsync()**: Asynchronous equivalent of `count()`.
- **aggregateAsync(fields...)**: Asynchronous equivalent of `aggregate(...)`.
- **groupBy(paths...).aggregateAsync(fields...)**: Asynchronous grouped aggregation.
- **facetStatsAsync()**: Asynchronous equivalent of `facetStats()`.

```kotlin
@HandleQuery
fun handle(query: SearchProjects): CompletableFuture<List<Project>> =
    Fluxzero.search(Project::class.java)
        .lookAhead(query.term, "name")
        .fetchAsync(50, Project::class.java)

@HandleQuery
fun handle(query: ProjectFacetQuery): CompletableFuture<List<FacetStats>> =
    Fluxzero.search(Project::class.java)
        .match("ACTIVE", "status")
        .facetStatsAsync()
```

---

<a name="consistency"></a>

## Consistency & The Window

Fluxzero search is **eventually consistent**.

- **The Consistency Window**: When you send a command, aggregate state is updated first and indexing may follow
  asynchronously. For direct searchable aggregate updates, handler results now wait for asynchronous after-handler
  aggregate commits by default, so `sendCommandAndWait` followed by a query often sees the updated aggregate/search
  document.
- **Guarantee Boundary**: Do not assume immediate search consistency when the document is indexed as a downstream side
  effect, such as in an event handler or projection handler. In that case, wait for the projection's own completion
  signal or return the needed state from the command handler.
- **UI Tip**: For immediate feedback, return the new state directly from the command handler or use WebSockets to notify the UI when the projection is ready.

---

<a name="retention"></a>

## Document Retention

To manage the lifecycle of your documents and prevent infinite growth, you can define retention policies. This is
typically used for audit logs or time-sensitive data.

All documents in Fluxzero support an optional **start** and **end** timestamp, which can be configured via
`@Searchable(timestampPath = "...", endPath = "...")`. These timestamps are used for temporal querying (`.since()`,
`.inPeriod()`, etc.) and for retention.

Use `DocumentStore.createAuditTrail(collection, retentionDuration)` to configure a collection that automatically prunes
documents older than the specified duration (based on their timestamp).

```kotlin
@Component
class AuditTrailConfig {
    @Autowired
    fun setup(documentStore: DocumentStore) {
        documentStore.createAuditTrail("system_logs", Duration.ofDays(90))
    }
}
```

---

<a name="facet-stats"></a>

## Facet Statistics

Retrieve document counts grouped by their facet values. This is ideal for building dashboard summaries or filter
sidebars.

```kotlin
val stats: List<FacetStats> = Fluxzero.search(Product::class)
    .lookAhead("wireless")
    .facetStats()
```

---

<a name="bulk-ops"></a>

## Manual Indexing & Bulk Operations

You can index any object manually, even if it is not an aggregate.

### Index Operations

Use `Fluxzero.prepareIndex(object)` to create a builder for indexing or deleting a document. This is the preferred way
to configure complex index operations.

```kotlin
Fluxzero.prepareIndex(myObject)
    .id("custom-id")
    .collection("custom-collection")
    .indexAndWait()
```

### Bulk Updates

To perform multiple updates in a single transaction, create a collection of `BulkUpdate` objects (typically via
`IndexOperation#toBulkUpdate()`) and pass them to the document store.

```kotlin
val updates = listOf(
    Fluxzero.prepareIndex(doc1).toBulkUpdate(),
    Fluxzero.prepareIndex(doc2).id("delete-me").toBulkUpdate() // assuming delete logic
)

Fluxzero.get().documentStore().bulkUpdate(updates)
```

---

<a name="collection-management"></a>

## Collection Management

- **Moving & Deleting**: Use `Fluxzero.deleteCollection(name)` to wipe a collection. Documents can be moved between
  collections using bulk operations.
- **Rebuilding**: If you change your indexing configuration (e.g., adding a new `@Facet`), you can rebuild the
  collection by replaying the aggregate's event stream. See the [Document Rebuilding](tracking.md#document-rebuilding)
  section in the Tracking manual for more details.
