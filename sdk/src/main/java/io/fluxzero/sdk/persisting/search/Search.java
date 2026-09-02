/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.fluxzero.sdk.persisting.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.StreamInputStream;
import io.fluxzero.common.ThrowingBiConsumer;
import io.fluxzero.common.ThrowingFunction;
import io.fluxzero.common.api.search.Constraint;
import io.fluxzero.common.api.search.DocumentStats.FieldStats;
import io.fluxzero.common.api.search.FacetStats;
import io.fluxzero.common.api.search.Group;
import io.fluxzero.common.api.search.ModelRelationConstraint;
import io.fluxzero.common.api.search.SearchHistogram;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.constraints.AllConstraint;
import io.fluxzero.common.api.search.constraints.AnyConstraint;
import io.fluxzero.common.api.search.constraints.BetweenConstraint;
import io.fluxzero.common.api.search.constraints.ExistsConstraint;
import io.fluxzero.common.api.search.constraints.FacetConstraint;
import io.fluxzero.common.api.search.constraints.LookAheadConstraint;
import io.fluxzero.common.api.search.constraints.MatchConstraint;
import io.fluxzero.common.api.search.constraints.NotConstraint;
import io.fluxzero.common.api.search.constraints.QueryConstraint;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Id;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static io.fluxzero.common.SearchUtils.timeToInstant;
import static java.util.stream.Collectors.toList;

/**
 * Fluent interface for building and executing document search queries in Fluxzero.
 * <p>
 * A {@code Search} instance is typically obtained via {@code Fluxzero.search(DocumentType.class)} or
 * {@code Fluxzero.<DocumentType>search("collectionName")} and can be configured using a combination of time-based
 * constraints, field constraints, sorting rules, pagination, and content selection.
 * <p>
 * The search is only executed when a terminal operation like {@code fetch(...)} or {@code stream()} is invoked.
 * <p>
 * Supported operations include:
 * <ul>
 *   <li>Time-based filtering (e.g. {@link #since(Instant)}, {@link #inLast(Duration)})</li>
 *   <li>Content-based filtering (e.g. {@link #match(Object, String...)}, {@link #query(String, String...)})</li>
 *   <li>Sorting and pagination (e.g. {@link #sortByTimestamp()}, {@link #skip(Integer)})</li>
 *   <li>Aggregation and facets (e.g. {@link #aggregate(String...)}, {@link #facetStats()})</li>
 *   <li>Streaming and fetching results (e.g. {@link #stream()}, {@link #fetch(int)})</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * List<MyDocument> results = Fluxzero.search(MyDocument.class)
 *     .inLast(Duration.ofDays(30))
 *     .match("searchTerm", "title", "description")
 *     .sortByTimestamp(true)
 *     .fetch(50);
 * }</pre>
 *
 * @param <R> the default result type returned by terminal operations without an explicit class. Class-based searches
 *            establish this type automatically; dynamic collection names can use an explicit type witness at the
 *            search entry point.
 * @see Fluxzero#search
 */
public interface Search<R> {
    enum NullOrder {
        FIRST("nullsFirst"),
        LAST("nullsLast");

        private final String suffix;

        NullOrder(String suffix) {
            this.suffix = suffix;
        }

        public String suffix() {
            return suffix;
        }
    }

    /**
     * The default number of records to fetch in a single batch during search operations. Primarily used in streaming
     * and batch-fetching methods to control the size of each data retrieval operation.
     * <p>
     * A higher value increases the data fetch per operation, potentially reducing the number of retrievals but
     * consuming more memory. A lower value minimizes memory usage but may require more network or database calls for
     * large datasets.
     */
    int defaultFetchSize = 10_000;

    /*
        Timing
     */

    /**
     * Filters documents with timestamps since the given start time (inclusive).
     */
    default Search<R> since(Instant start) {
        return since(start, true);
    }

    /**
     * Filters documents with timestamps since the given start time.
     *
     * @param inclusive whether the start boundary is inclusive
     */
    Search<R> since(Instant start, boolean inclusive);

    /**
     * Initiates a search operation from a specified start date.
     *
     * @param start the start date from which the search is to be started.
     * @return a Search object initialized with the converted instant from the specified start date.
     */
    default Search<R> since(LocalDate start) {
        return since(timeToInstant(start, false));
    }

    /**
     * Filters documents with timestamps strictly before the given end time.
     */
    default Search<R> before(Instant endExclusive) {
        return before(endExclusive, false);
    }

    /**
     * Filters documents with timestamps before the given time.
     *
     * @param inclusive whether the end boundary is inclusive
     */
    Search<R> before(Instant end, boolean inclusive);

    /**
     * Filters and returns search results that occur before the specified end date, inclusive.
     *
     * @param endInclusive the end date to compare with, inclusive
     * @return a Search object containing results that occur before the specified end date
     */
    default Search<R> before(LocalDate endInclusive) {
        return before(timeToInstant(endInclusive, true));
    }

    /**
     * Filters out documents older than the given duration.
     */
    default Search<R> beforeLast(Duration period) {
        return before(Fluxzero.currentTime().minus(period));
    }

    /**
     * Filters documents within the last given duration (e.g., last 7 days).
     */
    default Search<R> inLast(Duration period) {
        return since(Fluxzero.currentTime().minus(period));
    }

    /**
     * Filters documents within the given time range.
     */
    default Search<R> inPeriod(Instant start, Instant endExclusive) {
        return inPeriod(start, true, endExclusive, false);
    }

    /**
     * Filters documents within a specified time range.
     */
    Search<R> inPeriod(Instant start, boolean startInclusive, Instant end, boolean endInclusive);

    /**
     * Filters the search results to include only those within the specified date range.
     */
    default Search<R> inPeriod(LocalDate start, LocalDate endInclusive) {
        return inPeriod(timeToInstant(start, false), timeToInstant(endInclusive, true));
    }

    /*
        Other constraints
     */

    /**
     * Adds a full-text lookahead constraint using the specified phrase.
     */
    default Search<R> lookAhead(String phrase, String... paths) {
        return constraint(LookAheadConstraint.lookAhead(phrase, paths));
    }

    /**
     * Adds a full-text search constraint for the given phrase.
     */
    default Search<R> query(String phrase, String... paths) {
        return constraint(QueryConstraint.query(phrase, paths));
    }

    /**
     * Adds an equality match constraint for the given value across one or more paths.
     */
    default Search<R> match(Object constraint, String... paths) {
        return match(constraint, false, paths);
    }

    /**
     * Adds a match constraint, optionally enforcing strict equality.
     */
    default Search<R> match(Object constraint, boolean strict, String... paths) {
        return constraint(MatchConstraint.match(constraint, strict, paths));
    }

    /**
     * Matches the value of a named facet.
     */
    default Search<R> matchFacet(String name, Object value) {
        return constraint(FacetConstraint.matchFacet(name, value));
    }

    /**
     * Matches a metadata key to a value.
     */
    default Search<R> matchMetadata(String key, Object value) {
        return match(value, true, "$metadata/" + escapeMetadataKey(key));
    }

    private static String escapeMetadataKey(String key) {
        if (key == null) {
            return null;
        }
        return key.replace(".", "\\.").replace("/", "\\/").replace("\"", "\\\"");
    }

    /**
     * Constrains the search to documents that have any of the given paths.
     */
    default Search<R> anyExist(String... paths) {
        return constraint(ExistsConstraint.exists(paths));
    }

    /**
     * Adds a lower-bound constraint for a field.
     */
    default Search<R> atLeast(Number min, String path) {
        return between(min, null, path);
    }

    /**
     * Adds an upper-bound constraint for a field.
     */
    default Search<R> below(Number max, String path) {
        return between(null, max, path);
    }

    /**
     * Adds a numeric range constraint.
     */
    default Search<R> between(Number min, Number maxExclusive, String path) {
        return constraint(BetweenConstraint.between(min, maxExclusive, path));
    }

    /**
     * Combines multiple constraints using a logical AND.
     */
    default Search<R> all(Constraint... constraints) {
        return constraint(AllConstraint.all(constraints));
    }

    /**
     * Combines multiple constraints using a logical OR.
     */
    default Search<R> any(Constraint... constraints) {
        return constraint(AnyConstraint.any(constraints));
    }

    /**
     * Negates a constraint using a logical NOT.
     */
    default Search<R> not(Constraint constraint) {
        return constraint(NotConstraint.not(constraint));
    }

    /**
     * Adds one or more custom constraints to the search using a logical AND.
     */
    Search<R> constraint(Constraint... constraints);

    /**
     * Requires a direct parent with the supplied typed identity.
     * <p>
     * Unlike the related-document overload, this selector starts directly from the parent's durable Model identity and
     * therefore does not require the parent to maintain a current-state document.
     */
    default Search<R> whereParent(Id<?> parentId) {
        Objects.requireNonNull(parentId, "Parent ID");
        return whereAncestor(parentId, parentId.getType(), 1, 1);
    }

    /**
     * Requires a direct parent with the supplied functional identity and Model type.
     * <p>
     * Use this overload for identifiers that do not extend {@link Id}. The Model type supplies any configured
     * {@code @EntityId} affixes needed to resolve its exact persisted identity.
     */
    default Search<R> whereParent(
            Object parentId, Class<?> parentType) {
        return whereAncestor(parentId, parentType, 1, 1);
    }

    /**
     * Requires the supplied existing Model graph to be the direct parent.
     * <p>
     * This overload is useful when the parent's persisted identity is scoped by one of its own parents.
     */
    default Search<R> whereParent(Graph<?> parent) {
        Objects.requireNonNull(parent, "Parent graph");
        return whereAncestorModelId(parent.id(), 1, 1);
    }

    /**
     * Requires a directly related parent document to match the supplied document constraints.
     */
    default Search<R> whereParent(
            Object collection, Constraint... constraints) {
        return whereAncestor(
                collection, 1, 1, constraints);
    }

    /**
     * Requires an ancestor document at any supported depth to match the supplied document constraints.
     */
    default Search<R> whereAncestor(
            Object collection, Constraint... constraints) {
        return whereAncestor(
                collection, 1, 64, constraints);
    }

    /**
     * Requires an ancestor with the supplied typed identity at any supported depth.
     * <p>
     * This selector uses only the durable Model relationship index; the ancestor does not need a current-state
     * document or public search projection.
     */
    default Search<R> whereAncestor(Id<?> ancestorId) {
        Objects.requireNonNull(ancestorId, "Ancestor ID");
        return whereAncestor(
                ancestorId, ancestorId.getType(), 1, 64);
    }

    /**
     * Requires an ancestor with the supplied typed identity within the given depth range.
     */
    default Search<R> whereAncestor(
            Id<?> ancestorId,
            int minDepth,
            int maxDepth) {
        Objects.requireNonNull(ancestorId, "Ancestor ID");
        return whereAncestor(
                ancestorId, ancestorId.getType(),
                minDepth, maxDepth);
    }

    /**
     * Requires an ancestor with the supplied functional identity and Model type at any supported depth.
     * <p>
     * Use this overload for identifiers that do not extend {@link Id}.
     */
    default Search<R> whereAncestor(
            Object ancestorId, Class<?> ancestorType) {
        return whereAncestor(
                ancestorId, ancestorType, 1, 64);
    }

    /**
     * Requires the supplied existing Model graph to be an ancestor at any supported depth.
     */
    default Search<R> whereAncestor(Graph<?> ancestor) {
        Objects.requireNonNull(ancestor, "Ancestor graph");
        return whereAncestorModelId(ancestor.id(), 1, 64);
    }

    /**
     * Requires the supplied existing Model graph to be an ancestor within the given depth range.
     */
    default Search<R> whereAncestor(
            Graph<?> ancestor,
            int minDepth,
            int maxDepth) {
        Objects.requireNonNull(ancestor, "Ancestor graph");
        return whereAncestorModelId(
                ancestor.id(), minDepth, maxDepth);
    }

    /**
     * Requires an ancestor with the supplied functional identity and Model type within the given depth range.
     * <p>
     * The related Model itself is never loaded or searched. Models with parent-scoped primary identities cannot be
     * resolved from a functional ID alone; use an exact persisted identity obtained from their {@code Graph} instead.
     */
    default Search<R> whereAncestor(
            Object ancestorId,
            Class<?> ancestorType,
            int minDepth,
            int maxDepth) {
        Objects.requireNonNull(ancestorId, "Ancestor ID");
        Objects.requireNonNull(ancestorType, "Ancestor type");
        EntityMetadata metadata =
                EntityMetadata.validate(ancestorType);
        if (!metadata.isModel()) {
            throw new IllegalArgumentException(
                    ancestorType.getName()
                    + " is not an independent Model");
        }
        return whereAncestorModelId(
                metadata.repositoryId(ancestorId),
                minDepth, maxDepth);
    }

    private Search<R> whereAncestorModelId(
            Object ancestorModelId,
            int minDepth,
            int maxDepth) {
        return relation(ModelRelationConstraint.builder()
                                .direction(ModelRelationConstraint.RelationDirection.ANCESTOR)
                                .relatedModelId(Objects.requireNonNull(
                                        ancestorModelId,
                                        "Persisted ancestor identity").toString())
                                .minDepth(minDepth)
                                .maxDepth(maxDepth)
                                .build());
    }

    /**
     * Requires an ancestor document within the supplied depth range to match the document constraints.
     */
    default Search<R> whereAncestor(
            Object collection,
            int minDepth,
            int maxDepth,
            Constraint... constraints) {
        return relation(ModelRelationConstraint.builder()
                                .direction(ModelRelationConstraint.RelationDirection.ANCESTOR)
                                .query(SearchQuery.builder()
                                               .collection(ClientUtils.determineRelationSearchCollection(collection))
                                               .constraints(List.of(constraints))
                                               .build())
                                .minDepth(minDepth)
                                .maxDepth(maxDepth)
                                .build());
    }

    /**
     * Requires a directly related child document to match the supplied document constraints.
     */
    default Search<R> whereChild(
            Object collection, Constraint... constraints) {
        return whereDescendant(
                collection, 1, 1, constraints);
    }

    /**
     * Requires a descendant document at any supported depth to match the supplied document constraints.
     */
    default Search<R> whereDescendant(
            Object collection, Constraint... constraints) {
        return whereDescendant(
                collection, 1, 64, constraints);
    }

    /**
     * Requires a descendant document within the supplied depth range to match the document constraints.
     */
    default Search<R> whereDescendant(
            Object collection,
            int minDepth,
            int maxDepth,
            Constraint... constraints) {
        return relation(ModelRelationConstraint.builder()
                                .direction(ModelRelationConstraint.RelationDirection.DESCENDANT)
                                .query(SearchQuery.builder()
                                               .collection(ClientUtils.determineRelationSearchCollection(collection))
                                               .constraints(List.of(constraints))
                                               .build())
                                .minDepth(minDepth)
                                .maxDepth(maxDepth)
                                .build());
    }

    /**
     * Adds advanced current-state model relationship constraints using logical AND.
     * <p>
     * Class-based related queries use the model's actual current-document collection. This includes the private,
     * type-isolated collection of a model that participates in graph composition without maintaining a direct public
     * document. Related documents are selected before relationship traversal and target search, so a selective child
     * constraint does not require live composition of unrelated roots.
     * Constraints with exact related model IDs skip related-document selection and can therefore start from an
     * event-sourced Model that has no current document.
     * <p>
     * Implementations that do not support independent-model graph search fail when this method is called.
     */
    default Search<R> relation(
            ModelRelationConstraint... constraints) {
        throw new UnsupportedOperationException(
                "Independent-model graph search is not supported");
    }

    /*
        Sorting
     */

    /**
     * Sorts results by timestamp in ascending order.
     */
    default Search<R> sortByTimestamp() {
        return sortByTimestamp(false);
    }

    /**
     * Sorts results by timestamp.
     *
     * @param descending whether to sort in descending order
     */
    Search<R> sortByTimestamp(boolean descending);

    /**
     * Sorts results by timestamp, with explicit null ordering.
     */
    default Search<R> sortByTimestamp(boolean descending, NullOrder nullOrder) {
        return sortBy("timestamp", descending, nullOrder);
    }

    /**
     * Sorts results by full-text relevance score.
     */
    Search<R> sortByScore();

    /**
     * Sorts results by a specific document field.
     */
    default Search<R> sortBy(String path) {
        return sortBy(path, false);
    }

    /**
     * Sorts results by a field, with control over the sort direction.
     */
    Search<R> sortBy(String path, boolean descending);

    /**
     * Sorts results by a specific document field, with explicit null ordering.
     */
    default Search<R> sortBy(String path, NullOrder nullOrder) {
        return sortBy(path, false, nullOrder);
    }

    /**
     * Sorts results by a field, with control over both sort direction and null ordering.
     */
    default Search<R> sortBy(String path, boolean descending, NullOrder nullOrder) {
        return sortBy(path + ":" + nullOrder.suffix(), descending);
    }

    /*
        Content filtering
     */

    /**
     * Excludes specific fields from the returned documents.
     */
    Search<R> exclude(String... paths);

    /**
     * Includes only the specified fields in the returned documents.
     */
    Search<R> includeOnly(String... paths);

    /*
        Pagination
     */

    /**
     * Skips the first N results.
     */
    Search<R> skip(Integer n);

    /*
        Execution
     */

    /**
     * Fetches up to the given number of matching documents and deserializes them to the stored type. Returns the
     * deserialized values as instances of type {@code R}.
     */
    List<R> fetch(int maxSize);

    /**
     * Asynchronously fetches up to the given number of matching documents and deserializes them to the stored type.
     * <p>
     * This is the asynchronous counterpart of {@link #fetch(int)}. The returned future completes with a materialized
     * list containing at most {@code maxSize} results.
     *
     * @param maxSize the maximum number of matching documents to fetch
     * @return a future containing the deserialized search results
     */
    default CompletableFuture<List<R>> fetchAsync(int maxSize) {
        return fetchAsync(maxSize, null);
    }

    /**
     * Fetches up to the given number of documents and deserializes them to the specified type.
     */
    <T> List<T> fetch(int maxSize, Class<T> type);

    /**
     * Asynchronously fetches up to the given number of documents and deserializes them to the specified type.
     * <p>
     * This is the asynchronous counterpart of {@link #fetch(int, Class)}. Use this method when handling requests that
     * can return a {@link CompletableFuture}; for very large result sets, prefer the streaming methods.
     *
     * @param maxSize the maximum number of matching documents to fetch
     * @param type    the type to deserialize each document to
     * @param <T>     the expected result type
     * @return a future containing the deserialized search results
     */
    <T> CompletableFuture<List<T>> fetchAsync(int maxSize, Class<T> type);

    /**
     * Fetches all matching documents and deserializes each to its stored type. Returns the deserialized values as
     * instances of type {@code R}.
     */
    default List<R> fetchAll() {
        return stream().collect(toList());
    }

    /**
     * Fetches all matching documents and deserializes them to the specified type.
     */
    default <T> List<T> fetchAll(Class<T> type) {
        return this.stream(type).collect(toList());
    }

    /**
     * Fetches the first matching document if available and deserializes it to the stored type. Returns the deserialized
     * value as an optional instance of type {@code R}.
     */
    default Optional<R> fetchFirst() {
        return fetch(1).stream().findFirst();
    }

    /**
     * Fetches the first matching document if available and deserializes it as an optional value of the specified type.
     */
    default <T> Optional<T> fetchFirst(Class<T> type) {
        return this.fetch(1, type).stream().findFirst();
    }

    /**
     * Fetches the first matching document if available and deserializes it to the stored type. Returns the deserialized
     * value as an instance of type {@code R}.
     */
    default R fetchFirstOrNull() {
        return fetchFirst().orElse(null);
    }

    /**
     * Fetches the first matching document if available and deserializes it to the specified type.
     */
    default <T> T fetchFirstOrNull(Class<T> type) {
        return this.fetch(1, type).stream().findFirst().orElse(null);
    }

    /**
     * Streams matching values, deserializing each to the stored type. Documents will typically be fetched in batches
     * from the backing store. For the {@link DefaultDocumentStore default implementation}, the fetch size is 10,000.
     */
    default Stream<R> stream() {
        return streamHits().map(SearchHit::getValue);
    }

    /**
     * Streams matching values, deserializing each to the stored type. Documents will be fetched in batches of size
     * {@code fetchSize} from the backing store.
     */
    default Stream<R> stream(int fetchSize) {
        return streamHits(fetchSize).map(SearchHit::getValue);
    }

    /**
     * Streams matching values, deserializing each to the specified type. Documents will typically be fetched in batches
     * from the backing store. For the {@link DefaultDocumentStore default implementation}, the fetch size is 10,000.
     */
    default <T> Stream<T> stream(Class<T> type) {
        return this.streamHits(type).map(SearchHit::getValue);
    }

    /**
     * Streams matching values, deserializing each to the specified type. Documents will be fetched in batches of size
     * {@code fetchSize} from the backing store.
     */
    default <T> Stream<T> stream(Class<T> type, int fetchSize) {
        return this.streamHits(type, fetchSize).map(SearchHit::getValue);
    }

    /**
     * Streams matching values of the specified type as a lazily populated UTF-8 {@link InputStream}.
     */
    default <T> InputStream toUtf8InputStream(Class<T> type, ThrowingFunction<T, String> mapper) {
        return toUtf8InputStream(type, mapper, defaultFetchSize);
    }

    /**
     * Streams matching values of the specified type as a lazily populated UTF-8 {@link InputStream}, fetching
     * documents in
     * batches of {@code fetchSize}.
     */
    default <T> InputStream toUtf8InputStream(Class<T> type, ThrowingFunction<T, String> mapper, int fetchSize) {
        return toInputStream(type, (doc, outputStream) -> {
            String value = mapper.apply(doc);
            if (value != null) {
                outputStream.write(value.getBytes(StandardCharsets.UTF_8));
            }
        }, fetchSize);
    }

    /**
     * Streams matching values of the specified type as a lazily populated {@link InputStream}.
     */
    default <T> InputStream toInputStream(Class<T> type, ThrowingBiConsumer<T, OutputStream> writer) {
        return toInputStream(type, writer, defaultFetchSize);
    }

    /**
     * Streams matching values of the specified type as a lazily populated {@link InputStream}, fetching documents in
     * batches of {@code fetchSize}.
     */
    default <T> InputStream toInputStream(Class<T> type, ThrowingBiConsumer<T, OutputStream> writer, int fetchSize) {
        return new StreamInputStream<>(stream(type, fetchSize), writer);
    }

    /**
     * Streams matching values as NDJSON using the stored document types and the default fetch size.
     */
    default InputStream toNdjsonInputStream() {
        return new StreamInputStream<>(stream(JsonNode.class), (doc, outputStream) -> {
            outputStream.write(JsonUtils.asBytes(doc));
            outputStream.write('\n');
        });
    }

    /**
     * Streams raw search hits (document + metadata). Documents will typically be fetched in batches from the backing
     * store. For the {@link DefaultDocumentStore default implementation}, the fetch size is 10,000.
     */
    Stream<SearchHit<R>> streamHits();

    /**
     * Streams raw search hits (document + metadata). Documents will be fetched in batches of size {@code fetchSize}
     * from the backing store. For the {@link DefaultDocumentStore default implementation}, the fetch size is 10,000.
     */
    Stream<SearchHit<R>> streamHits(int fetchSize);

    /**
     * Streams raw search hits (document + metadata). Documents will be fetched in batches of size {@code fetchSize}
     * from the backing store. For the {@link DefaultDocumentStore default implementation}, the fetch size is 10,000.
     */
    <T> Stream<SearchHit<T>> streamHits(Class<T> type);

    /**
     * Streams raw search hits (document + metadata). Documents will be fetched in batches of size {@code fetchSize}
     * from the backing store. For the {@link DefaultDocumentStore default implementation}, the fetch size is 10,000.
     */
    <T> Stream<SearchHit<T>> streamHits(Class<T> type, int fetchSize);

    /*
        Aggregation
     */

    /**
     * Computes a histogram for the timestamp distribution of matching documents.
     */
    SearchHistogram fetchHistogram(int resolution, int maxSize);

    /**
     * Groups search results by field(s) and supports aggregations.
     */
    GroupSearch groupBy(String... paths);

    /**
     * Returns the number of matching documents.
     */
    default Long count() {
        return aggregate().values().stream().findFirst().map(FieldStats::getCount).orElse(0L);
    }

    /**
     * Asynchronously returns the number of matching documents.
     * <p>
     * This is the asynchronous counterpart of {@link #count()}.
     *
     * @return a future containing the matching document count
     */
    default CompletableFuture<Long> countAsync() {
        return aggregateAsync().thenApply(stats ->
                stats.values().stream().findFirst().map(FieldStats::getCount).orElse(0L));
    }

    /**
     * Returns field statistics for one or more fields.
     */
    default Map<String, FieldStats> aggregate(String... fields) {
        return groupBy().aggregate(fields).getOrDefault(Group.of(), Collections.emptyMap());
    }

    /**
     * Asynchronously returns field statistics for one or more fields.
     * <p>
     * This is the asynchronous counterpart of {@link #aggregate(String...)} and returns the statistics for the
     * ungrouped result set.
     *
     * @param fields the fields to compute statistics for; omit fields to request the default count statistics
     * @return a future containing field statistics keyed by field name
     */
    default CompletableFuture<Map<String, FieldStats>> aggregateAsync(String... fields) {
        return groupBy().aggregateAsync(fields).thenApply(stats ->
                stats.getOrDefault(Group.of(), Collections.emptyMap()));
    }

    /**
     * Returns facet statistics for the current search.
     */
    List<FacetStats> facetStats();

    /**
     * Asynchronously returns facet statistics for the current search.
     * <p>
     * This is the asynchronous counterpart of {@link #facetStats()}.
     *
     * @return a future containing facet value counts for the matching documents
     */
    CompletableFuture<List<FacetStats>> facetStatsAsync();

    /*
        Delete and move
     */

    /**
     * Deletes all matching documents in the current search.
     * <p>
     * This is equivalent to calling {@link #delete(int) delete(0)}, which lets the runtime choose the batch size.
     */
    default CompletableFuture<Void> delete() {
        return delete(0);
    }

    /**
     * Deletes all matching documents in the current search, using the requested batch size to control how many
     * documents are removed by each delete statement.
     * <p>
     * The batch size does not limit the total number of documents that are deleted. For a positive batch size, the
     * runtime repeatedly selects and deletes at most that many matching documents until no matches remain. This keeps
     * individual statements and their locks shorter, at the cost of executing multiple statements.
     * <ul>
     *     <li>{@code 0} lets the runtime choose its default batch size;</li>
     *     <li>a positive value sets the maximum number of documents processed per batch;</li>
     *     <li>a negative value requests deletion with one unbounded statement.</li>
     * </ul>
     * Each batch may be committed independently. If a later batch fails, documents removed by earlier batches remain
     * deleted. The returned future completes only after all matching documents have been processed, or completes
     * exceptionally when a batch fails.
     *
     * @param batchSize requested delete batch size
     * @return a future that completes when deletion has finished
     */
    CompletableFuture<Void> delete(int batchSize);

    /**
     * Moves all matching documents in the current search to the given collection.
     *
     * @param targetCollection the collection to move to
     */
    CompletableFuture<Void> move(Object targetCollection);
}
