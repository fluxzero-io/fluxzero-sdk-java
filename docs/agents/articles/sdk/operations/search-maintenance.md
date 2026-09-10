`DocumentStore` and `Search` expose namespaced document maintenance without database access. Use these operations for
application-owned projections, read models, and audit collections. They do not delete the aggregate events or other
sources from which a projection may later be rebuilt.

## Targeted document operations

```java
DocumentStore documents = Fluxzero.get().documentStore();

documents.deleteDocument(documentId, "projection-v3").join();
documents.moveDocument(documentId, "projection-v2", "projection-v3").join();
```

The default overloads use `Guarantee.STORED`. Keep the returned future in the operation result and verify absence or
the target collection afterward. A move changes the collection of the indexed document; handlers following a document
collection log must be reviewed for the move.

For query-selected maintenance, build the same indexed constraints used for a read and finish with `delete()` or
`move(targetCollection)`:

```java
Fluxzero.search("projection-v2")
        .match("OBSOLETE", true, "status")
        .delete()
        .join();
```

Before executing, run the same constrained search as a count and bounded result preview. Do not replace a precise indexed
predicate with `fetchAll()` plus Java filtering.

## Bulk updates

Use one collection-scoped builder when mixed index and delete operations belong in one stored request:

```java
Fluxzero.bulkUpdate("projection-v3")
        .index(currentDocument)
        .delete(obsoleteDocumentId)
        .executeAndWait();
```

`indexIfNotExists(...)` prevents replacement of an existing ID. `execute()` returns a future with
`Guarantee.STORED`; `executeAndForget()` deliberately uses `Guarantee.NONE` and is a poor choice for operator tooling
that must report a verified outcome. Treat each collection/document-ID pair as one final update in the batch: do not
depend on repeated operations for the same ID being executed sequentially, and do not claim that a multi-document bulk
request is an application transaction.

When operations are prepared independently, `Fluxzero.prepareIndex(value).toBulkUpdate()` produces a public
`BulkUpdate` value and `DocumentStore.bulkUpdate(updates)` stores the collected batch. This does not turn an index
operation into a deletion merely by changing its ID; use an explicit delete update or the collection-scoped builder
for mixed index/delete work.

## Collection deletion and audit trails

`Fluxzero.deleteCollection(collection)` or `DocumentStore.deleteCollection(collection)` permanently deletes every
document in that collection. Require explicit approval naming the namespace and resolved collection. First record a
count and result preview, and identify whether a replay can rebuild the collection. After deletion, verify both the collection and
any dependent public query or socket snapshot.

`DocumentStore.createAuditTrail(collection, retentionTime)` configures a collection as a searchable audit trail whose
history is pruned according to its retention. A null retention delegates retention choice to the runtime. Treat audit
retention as a data-governance decision and do not copy a platform default into application assumptions.

Deleting a search collection is not complete domain-data deletion. To remove a persisted aggregate including its
events, snapshot, relationships, and searchable representation, use aggregate deletion instead.
