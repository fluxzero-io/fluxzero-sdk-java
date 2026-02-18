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

[//]: # (@formatter:off)
```java
@Aggregate(searchable = true, collection = "active_projects")
public record Project(...) {}

@Searchable(collection = "custom_docs")
public record ExternalDocument(...) {}
```
[//]: # (@formatter:on)

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

---

<a name="searching"></a>

## Searching for Data

Access the search engine via `Fluxzero.search(Class<T>)` or by providing a collection name.

<a name="basic-constraints"></a>

### Basic Constraints

- **match(value, paths...)**: Exact field match.
- **lookAhead(text, paths...)**: Search-as-you-type (prefix matching).
- **query(text, paths...)**: Full-text search with support for wildcards and operators (`*`, `&`, `|`).

[//]: # (@formatter:off)
```java
List<Project> results = Fluxzero.search(Project.class)
    .lookAhead("flux", "name")
    .match("ACTIVE", "status")
    .fetch(10);
```
[//]: # (@formatter:on)

<a name="temporal-filters"></a>

### Temporal Filters

Filter documents based on their creation or modification time.

- **since(instant)** / **before(instant)**: Absolute time ranges.
- **inLast(duration)** / **beforeLast(duration)**: Relative time ranges.

[//]: # (@formatter:off)
```java
List<Project> recent = Fluxzero.search(Project.class)
    .inLast(Duration.ofDays(7))
    .fetchAll();
```
[//]: # (@formatter:on)

<a name="logical-grouping"></a>

### Logical Grouping

Combine multiple constraints using logical operations.

[//]: # (@formatter:off)
```java
List<User> complex = Fluxzero.search(User.class)
    .any(
        MatchConstraint.match("ADMIN", "role"),
        AllConstraint.all(
            MatchConstraint.match("USER", "role"),
            MatchConstraint.match(true, "vip")
        )
    )
    .fetchAll();
```
[//]: # (@formatter:on)

---

<a name="pagination-sorting"></a>

## Pagination & Sorting

Fluxzero supports efficient pagination and sorting.

- **sortBy(path, descending)**: Sort results by a specific field. The field MUST be annotated with `@Sortable`.
- **skip(int)**: Number of results to skip (offset).
- **fetch(int)**: Maximum number of results to return.
- **fetchAll()**: Returns all matching documents (use with caution for large collections).

---

<a name="consistency"></a>

## Consistency & The Window

Fluxzero search is **eventually consistent**.

- **The Consistency Window**: When you send a command, the aggregate state is updated immediately (consistent), but the search index update happens asynchronously. There is usually a few-millisecond delay before the document is searchable.
- **Guarantee**: `sendCommandAndWait` ensures the command was handled, but does NOT guarantee the search index is
  up-to-date. Agents MUST NOT assume immediate search consistency after command handling.
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

[//]: # (@formatter:off)
```java
@Component
class AuditTrailConfig {
    @Autowired
    void setup(DocumentStore documentStore) {
        documentStore.createAuditTrail("system_logs", Duration.ofDays(90));
    }
}
```
[//]: # (@formatter:on)

---

<a name="facet-stats"></a>

## Facet Statistics

Retrieve document counts grouped by their facet values. This is ideal for building dashboard summaries or filter
sidebars.

[//]: # (@formatter:off)
```java
List<FacetStats> stats = Fluxzero.search(Product.class)
    .lookAhead("wireless")
    .facetStats();
```
[//]: # (@formatter:on)

---

<a name="bulk-ops"></a>

## Manual Indexing & Bulk Operations

You can index any object manually, even if it is not an aggregate.

### Index Operations

Use `Fluxzero.prepareIndex(object)` to create a builder for indexing or deleting a document. This is the preferred way
to configure complex index operations.

[//]: # (@formatter:off)
```java
Fluxzero.prepareIndex(myObject)
    .id("custom-id")
    .collection("custom-collection")
    .indexAndWait();
```
[//]: # (@formatter:on)

### Bulk Updates

To perform multiple updates in a single transaction, create a collection of `BulkUpdate` objects (typically via
`IndexOperation#toBulkUpdate()`) and pass them to the document store.

[//]: # (@formatter:off)
```java
var updates = List.of(
    Fluxzero.prepareIndex(doc1).toBulkUpdate(),
    Fluxzero.prepareIndex(doc2).id("delete-me").toBulkUpdate() // assuming delete logic
);

Fluxzero.get().documentStore().bulkUpdate(updates);
```
[//]: # (@formatter:on)

---

<a name="collection-management"></a>

## Collection Management

- **Moving & Deleting**: Use `Fluxzero.deleteCollection(name)` to wipe a collection. Documents can be moved between
  collections using bulk operations.
- **Rebuilding**: If you change your indexing configuration (e.g., adding a new `@Facet`), you can rebuild the
  collection by replaying the aggregate's event stream. See the [Document Rebuilding](tracking.md#document-rebuilding)
  section in the Tracking manual for more details.
