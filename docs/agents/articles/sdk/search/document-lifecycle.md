Search documents represent current indexed state, not an immutable history. Choose identity, indexed time, retention,
update observation, rebuild, and deletion as separate contracts.

## Define indexed time deliberately

```java
@Searchable(
        collection = "knowledge-entry-history",
        timestampPath = "validFrom",
        endPath = "validUntil")
record HistoricalEntry(
        @EntityId String entryId,
        Instant validFrom,
        Instant validUntil,
        String text) {
}
```

`timestampPath` supplies the primary document time used by timestamp sorting and time filters. `endPath` gives the
document an interval; without it, the start timestamp also acts as the end. Verify null/missing/invalid time behavior
before using it as a retention or public temporal-query boundary.

## Separate audit retention from message-log retention

`DocumentStore.createAuditTrail(collection, retention)` configures a searchable audit collection whose old documents
are pruned according to document timestamps and platform behavior. It does not change event/custom/metrics log
retention. Conversely, `GenericGateway.setRetentionTime(...)` does not prune a search collection.

Do not claim a precise physical deletion instant from a retention duration. Test that the application writes the right
timestamp and collection; use supported operational observation for actual pruning.

## Observe document updates correctly

`@HandleDocument` follows one collection and is last-state oriented. When several versions of one document accumulate
before a lagging consumer reads them, intermediate versions may be skipped. Use document handling for current-state
projection/migration, not for a workflow that requires every transition.
A retained pending-work document is a different case: reconciliation may skip intermediate versions only if the
latest state still retains all unacknowledged actions. See `/docs/sdk/sagas` for the effect and failure boundaries.

For ordinary read models, returning a higher-revision document can rewrite it in place during a controlled rebuild. Use a new consumer name and
replay boundary, then verify final documents and public queries. Adding `@Facet`, `@Sortable`, `@SearchExclude`, or an
upcaster does not backfill existing documents by itself.

Ordinary higher-revision replacements retain the handled stored version's metadata, including through upcasting. Derived search indexes/exclusions are regenerated from the replacement.
To explicitly replace it, return `new Message(document, Metadata.of("source", "migration"))` in Java or
`Message(document, Metadata.of("source", "migration"))` in Kotlin. `Metadata.empty()` removes all metadata.
This is a complete metadata replacement, not a merge with the tracking envelope. The payload's revision gate,
document ID, timestamp-path precedence and direct-null deletion remain unchanged. Only payload and metadata
participate in this write; a Message's ID/time and other envelope fields do not
change the indexed document identity/times. Stored Graph rematerialization also preserves custom projection
metadata while refreshing its manifest; its stricter complete-Graph return contract remains in effect.

Custom serializers that create entirely new document envelopes or decoded messages must call
`DocumentMessageReader.retainSource(decodedOutput, originalInput)` for each output, associating it with the
unchanged original stored input even when batching, reordering or splitting. Standard input-envelope withers
preserve source attribution automatically. This transfers stored metadata provenance only; preserve ordinary
message identity/revision/transport fields separately. The same API applies in Java and Kotlin.

A typed Model handler observes the canonical searchable node and can only perform a state-preserving schema rewrite. Use `source = DocumentSource.MODEL_STATE` for the same maintenance on non-searchable DOCUMENT state. Model commands own business changes and deletion. A `Graph<T>` document handler observes logical Graph updates in every search mode; returning it migrates evolved verified current nodes, including at NONE. Affected projections follow durably without a full Modeltype scan. Use `graphMigration = GraphMigrationTarget.PROJECTION` for only the handled stored composition; that option is observational at NONE. Read `/docs/sdk/models/configuration` and `/docs/sdk/models/migration-testing` before choosing the route.

## Destructive maintenance

Targeted delete/move/bulk operations and collection deletion belong to search maintenance. Before deletion, count and
preview the exact constrained set; after the stored acknowledgement, query again. Deleting a searchable Model's
document does not delete its event-sourced Model. Use Model deletion when the Model itself must disappear.

Text exclusions are field-specific, even when another field contains the same value. They preserve the returned
body and do not disable existence, range, facet or sorting operations. Documents written before per-path exclusion
metadata was available must be reindexed from typed values after both the writing SDK and Runtime are upgraded;
the old summary cannot reconstruct the excluded paths. Custom inverters/summarizers own their indexing semantics.
