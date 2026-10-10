`DocumentStore` and `Search` expose namespaced document maintenance without database access. Use these operations for
application-owned projections, read models, and audit collections. They do not delete the Model events or other
sources from which a projection may later be rebuilt.

## Targeted document operations

```java
DocumentStore documents = Fluxzero.get().documentStore();

documents.deleteDocument(documentId, "projection-v3").join();
documents.moveDocument(documentId, "projection-v2", "projection-v3").join();
```

The default overloads use `Guarantee.DEFAULT` (`STORED` in SDK 2.x unless explicitly overridden). Keep the returned future in the operation result and verify absence or
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
`Guarantee.STORED`; `executeAndForget()` uses `Guarantee.DEFAULT` and is a poor choice for operator tooling
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

Deleting a search collection is not complete domain-data deletion. To remove a persisted Model including its
events, snapshot, relationships, and searchable representation, use Model deletion instead.

## Activate collection-value semantics

Upgrade all Runtime nodes before setting `fluxzero.search.collectionValues=true` (`FLUXZERO_SEARCH_COLLECTION_VALUES`)
or defaults version `2026.10.09`. With older/absent defaults, the dedicated property can opt in; false always opts out.
The SDK adds a minimum beside the existing maximum only for distinct repeated values. The Runtime stores it in the
existing sortable JSON, with one extra minimum index per affected path; no values table or extra JSON column.
Reindex from typed application state through the normal document flow, for example `@HandleDocument`; the customer owns
scheduling, progress and retries. Resending old persisted maximum-only entries is insufficient. There is no Runtime
preparation, activation or backfill API. Include the actual Model source/Graph projection collections.

For an annotated collection/path without a minimum index, Runtime retains the original maximum SQL and behavior,
including explicit MIN. This also covers failed/incomplete minimum index creation. Once the index exists, it indexes
`coalesce(minimum, maximum)`, including old rows without minimum JSON. Queries across collections preserve each
collection's fallback in PostgreSQL.

Fallback is per document: missing minimum means use its existing maximum, including explicit `SearchValue.min`.
Partial reindexing immediately changes only rewritten rows. Old SDK writes replace the whole sortable JSON and remove
previous minima. Upgrade all Runtime readers before new SDK writes; the reserved `$metadata` minimum entries are not
business sortable fields. Annotated filtering, sorting and pagination stay in PostgreSQL. Unannotated paths retain their
existing Runtime evaluation route. Cursors retain last-hit keys but are not snapshots: reindexing or minimum-index repair may move rows across
page boundaries. Complete reindexing before paging when a stable result set is required.

Aliases and opaque sortable objects retain SDK-produced extrema without Runtime document decoding. Comparisons reuse
existing sortable numeric precision/range and text normalization. New requests use explicit protocol envelopes:
an old Runtime rejects them rather than ignoring unknown filters. Roll back query semantics using the false override.
