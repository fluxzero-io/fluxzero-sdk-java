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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.search.BulkUpdate;
import io.fluxzero.common.api.search.Constraint;
import io.fluxzero.common.api.search.CreateAuditTrail;
import io.fluxzero.common.api.search.DocumentStats;
import io.fluxzero.common.api.search.DocumentUpdate;
import io.fluxzero.common.api.search.FacetStats;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.GetDocuments;
import io.fluxzero.common.api.search.GetSearchHistogram;
import io.fluxzero.common.api.search.Group;
import io.fluxzero.common.api.search.HasDocument;
import io.fluxzero.common.api.search.ModelRelationConstraint;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.modeling.ModelGraphPathOverride;
import io.fluxzero.common.api.search.SearchCollection;
import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchHistogram;
import io.fluxzero.common.api.search.SearchModelDocuments;
import io.fluxzero.common.api.search.SearchModelGraphDocuments;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.search.bulkupdate.IndexDocument;
import io.fluxzero.common.api.search.bulkupdate.IndexDocumentIfNotExists;
import io.fluxzero.common.search.ModelGraphDocumentManifest;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.EntityMetadata;
import io.fluxzero.sdk.persisting.repository.ModelRepository;
import io.fluxzero.sdk.persisting.search.client.LocalDocumentHandlerRegistry;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.tracking.handling.HasLocalHandlers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.With;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.util.function.UnaryOperator.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Slf4j
public class DefaultDocumentStore extends AbstractNamespaced<DocumentStore> implements DocumentStore, HasLocalHandlers {

    private static final String NON_SEARCHABLE_MODEL_QUERY_PREFIX = "$nonSearchableModels/";

    @With
    private final Client client;
    @Getter
    private final DocumentSerializer serializer;
    @Delegate
    private final HasLocalHandlers handlerRegistry;
    private volatile Supplier<ModelRepository> modelRepositorySupplier = () -> null;
    private volatile Supplier<List<Class<?>>> modelTypesSupplier = List::of;

    public DefaultDocumentStore(
            Client client,
            DocumentSerializer serializer,
            HasLocalHandlers handlerRegistry) {
        this.client = client;
        this.serializer = serializer;
        this.handlerRegistry = handlerRegistry;
    }

    private DefaultDocumentStore(
            Client client,
            DocumentSerializer serializer,
            HasLocalHandlers handlerRegistry,
            Supplier<ModelRepository> modelRepositorySupplier,
            Supplier<List<Class<?>>> modelTypesSupplier) {
        this(client, serializer, handlerRegistry);
        this.modelRepositorySupplier = modelRepositorySupplier;
        this.modelTypesSupplier = modelTypesSupplier;
    }

    /** Configures typed materialized-graph reconstruction after the model subsystem has initialized. */
    public void configureModelGraphSupport(
            ModelRepository modelRepository,
            Supplier<List<Class<?>>> modelTypesSupplier) {
        this.modelRepositorySupplier = () -> Objects.requireNonNull(
                modelRepository, "modelRepository");
        this.modelTypesSupplier = Objects.requireNonNull(
                modelTypesSupplier, "modelTypesSupplier");
    }

    @Getter(lazy = true)
    private final SearchClient searchClient = client.getSearchClient();

    @Override
    public List<SearchCollection> getSearchCollections() {
        try {
            return getSearchClient().getSearchCollections();
        } catch (Exception e) {
            throw new DocumentStoreException("Could not retrieve search collections", e);
        }
    }

    @Override
    public CompletableFuture<Void> index(@NonNull Object object, @NonNull Object id, @NonNull Object collection,
                                         Instant begin, Instant end, Metadata metadata, Guarantee guarantee,
                                         boolean ifNotExists) {
        try {
            object = object instanceof Entity<?> e ? e.get() : object;
            return getSearchClient().index(List.of(serializer.toDocument(object, id.toString(),
                                                                         determineCollection(collection), begin, end,
                                                                         metadata)),
                                           guarantee, ifNotExists);
        } catch (Exception e) {
            throw new DocumentStoreException(format(
                    "Failed to store a document %s to collection %s", id, collection), e);
        }
    }

    @Override
    public CompletableFuture<Void> index(Collection<?> objects, Object collection,
                                         String idPath, String beginPath,
                                         String endPath, Guarantee guarantee, boolean ifNotExists) {
        var documents = objects.stream().map(v -> DefaultIndexOperation.prepare(
                        this, v, collection, idPath, beginPath, endPath)
                .ifNotExists(ifNotExists).toDocument()).toList();
        try {
            return documents.isEmpty() ? CompletableFuture.completedFuture(null)
                    : getSearchClient().index(documents, guarantee, ifNotExists);
        } catch (Exception e) {
            throw new DocumentStoreException(
                    format("Could not store a list of documents for collection %s", collection), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T> CompletableFuture<Void> index(Collection<? extends T> objects, Object collection,
                                             Function<? super T, ?> idFunction,
                                             Function<? super T, Instant> beginFunction,
                                             Function<? super T, Instant> endFunction, Guarantee guarantee,
                                             boolean ifNotExists) {
        var documents = objects.stream().map(v -> DefaultIndexOperation.prepare(
                        this, v, collection,
                        (Function) idFunction, (Function) beginFunction, (Function) endFunction)
                .ifNotExists(ifNotExists).toDocument()).toList();
        try {
            return documents.isEmpty() ? CompletableFuture.completedFuture(null)
                    : getSearchClient().index(documents, guarantee, ifNotExists);
        } catch (Exception e) {
            throw new DocumentStoreException(
                    format("Could not store a list of documents for collection %s", collection), e);
        }
    }

    @Override
    public CompletableFuture<Void> bulkUpdate(Collection<? extends BulkUpdate> updates, Guarantee guarantee) {
        try {
            return updates.isEmpty() ? CompletableFuture.completedFuture(null) : getSearchClient()
                    .bulkUpdate(updates.stream().map(this::serializeAction)
                                        .collect(toMap(a -> format("%s_%s", a.getCollection(), a.getId()),
                                                       identity(), (a, b) -> b)).values(),
                                guarantee);
        } catch (Exception e) {
            throw new DocumentStoreException("Could not apply batch of search actions", e);
        }
    }

    public DocumentUpdate serializeAction(BulkUpdate update) {
        String collection = determineCollection(update.getCollection());
        var builder = DocumentUpdate.builder().collection(collection)
                .id(update.getId().toString()).type(update.getType());
        if (update instanceof IndexDocument u) {
            var document = u.getObject() instanceof SerializedDocument s
                    ? s : serializer.toDocument(u.getObject(), u.getId().toString(), collection, u.getTimestamp(),
                                                u.getEnd());
            return builder.object(document).build();
        } else if (update instanceof IndexDocumentIfNotExists u) {
            var document = u.getObject() instanceof SerializedDocument s
                    ? s : serializer.toDocument(u.getObject(), u.getId().toString(), collection, u.getTimestamp(),
                                                u.getEnd());
            return builder.object(document).build();
        }
        return builder.build();
    }


    @Override
    public <T> Search<T> search(SearchQuery.Builder searchBuilder) {
        return new DefaultSearch<>(searchBuilder);
    }

    @Override
    public <T> Search<T> search(@NonNull Class<T> collection) {
        EntityMetadata.RootConfiguration model = EntityMetadata.of(collection).rootConfiguration()
                .filter(configuration -> configuration.kind() == EntityMetadata.RootKind.MODEL)
                .orElse(null);
        Class<?> targetModelType = model == null ? null : collection;
        String queryCollection = model != null && !model.publicDocument()
                ? NON_SEARCHABLE_MODEL_QUERY_PREFIX + collection.getName()
                : determineCollection(collection);
        return new DefaultSearch<>(
                SearchQuery.builder().collection(queryCollection),
                targetModelType);
    }

    @Override
    public <T> Search<Graph<T>> searchGraph(
            Class<T> rootModelType,
            boolean forceAdHoc) {
        EntityMetadata metadata = EntityMetadata.validate(rootModelType);
        EntityMetadata.RootConfiguration root =
                metadata.rootConfiguration()
                        .orElseThrow(() ->
                                             new IllegalArgumentException(
                                                     rootModelType.getName()
                                                     + " is not an independent model"));
        if (root.kind()
            != EntityMetadata.RootKind.MODEL) {
            throw new IllegalArgumentException(
                    rootModelType.getName()
                    + " is not an independent model");
        }
        String rootCollection = metadata.modelDocumentCollection()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Graph search root %s has no current document"
                                .formatted(rootModelType.getName())));
        Optional<io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration>
                projection =
                metadata.graphProjectionConfiguration();
        boolean live =
                forceAdHoc
                || projection.isEmpty();
        String collection =
                live
                        ? projection.map(
                                        io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration
                                                ::getRootCollection)
                                .orElse(rootCollection)
                        : projection.orElseThrow()
                                .getCollection();
        ModelGraphComposition composition =
                live
                        ? projection.map(
                                        io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration
                                                ::getComposition)
                                .orElseGet(() ->
                                                   ModelGraphComposition
                                                           .builder()
                                                           .build())
                        : null;
        List<ModelGraphPathOverride> pathOverrides =
                live
                        ? projection.map(
                                        io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration
                                                ::getPathOverrides)
                                .orElseGet(List::of)
                        : List.of();
        Map<String, String> projectionPathOverrides =
                new LinkedHashMap<>();
        projection.ifPresent(configuration ->
                configuration.getPathOverrides().forEach(override ->
                        projectionPathOverrides.put(
                                override.getPath(),
                                override.getProjectionPath())));
        return new DefaultGraphSearch(
                SearchQuery.builder()
                        .collection(collection),
                rootModelType, composition,
                projectionPathOverrides,
                pathOverrides);
    }

    @Override
    public boolean hasDocument(Object id, Object collection) {
        return getSearchClient().documentExists(new HasDocument(id.toString(), determineCollection(collection)));
    }

    @Override
    public <T> Optional<T> fetchDocument(Object id, Object collection) {
        try {
            return getSearchClient().fetch(new GetDocument(id.toString(), determineCollection(collection)))
                    .map(serializer::fromDocument);
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not get document %s from collection %s", id, collection), e);
        }
    }

    @Override
    public <T> Optional<T> fetchDocument(Object id, Object collection, Class<T> type) {
        try {
            return getSearchClient().fetch(new GetDocument(id.toString(), determineCollection(collection)))
                    .map(d -> serializer.fromDocument(d, type));
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not get document %s from collection %s", id, collection), e);
        }
    }

    @Override
    public <T> Collection<T> fetchDocuments(Collection<?> ids, Object collection) {
        try {
            return getSearchClient().fetch(
                            new GetDocuments(ids.stream().map(Object::toString).collect(Collectors.toSet()),
                                             determineCollection(collection)))
                    .stream().map(serializer::<T>fromDocument).toList();
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not get documents %s from collection %s", ids, collection),
                                             e);
        }
    }

    @Override
    public <T> Collection<T> fetchDocuments(Collection<?> ids, Object collection, Class<T> type) {
        try {
            return getSearchClient().fetch(
                            new GetDocuments(ids.stream().map(Object::toString).collect(Collectors.toSet()),
                                             determineCollection(collection)))
                    .stream().map(d -> serializer.fromDocument(d, type)).toList();
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not get documents %s from collection %s", ids, collection),
                                             e);
        }
    }

    @Override
    public CompletableFuture<Void> deleteDocument(Object id, Object collection, Guarantee guarantee) {
        try {
            return getSearchClient().delete(id.toString(), determineCollection(collection), guarantee);
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not delete document %s from collection %s", id, collection),
                                             e);
        }
    }

    @Override
    public CompletableFuture<Void> moveDocument(Object id, Object collection, Object targetCollection,
                                                Guarantee guarantee) {
        try {
            return getSearchClient().move(id.toString(), determineCollection(collection),
                                          determineCollection(targetCollection),
                                          guarantee);
        } catch (Exception e) {
            throw new DocumentStoreException(format(
                    "Could not move document %s from collection %s to collection %s", id, collection, targetCollection),
                                             e);
        }
    }

    @Override
    public CompletableFuture<Void> deleteCollection(Object collection) {
        try {
            return getSearchClient().deleteCollection(determineCollection(collection));
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not delete collection %s", collection), e);
        }
    }

    @Override
    public CompletableFuture<Void> createAuditTrail(Object collection, Duration retentionTime) {
        try {
            return getSearchClient().createAuditTrail(
                    new CreateAuditTrail(determineCollection(collection), Optional.ofNullable(
                            retentionTime).map(Duration::getSeconds).orElse(null), Guarantee.STORED));
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not create audit trail %s", collection), e);
        }
    }

    @Override
    protected DocumentStore createForNamespace(String namespace) {
        Client namespacedClient = client.forNamespace(namespace);
        HasLocalHandlers namespacedHandlerRegistry = handlerRegistry instanceof LocalDocumentHandlerRegistry local
                ? local.forNamespace(namespace) : handlerRegistry;
        return namespacedClient == client && namespacedHandlerRegistry == handlerRegistry ? this
                : new DefaultDocumentStore(
                        namespacedClient, serializer,
                        namespacedHandlerRegistry,
                        () -> modelRepositorySupplier.get()
                                .forNamespace(namespace),
                        modelTypesSupplier);
    }

    protected class DefaultSearch<R> implements Search<R> {

        private final SearchQuery.Builder queryBuilder;
        private final Class<?> targetModelType;
        private final List<String> sorting = new ArrayList<>();
        private final List<String> pathFilters = new ArrayList<>();
        private final List<ModelRelationConstraint> relationConstraints =
                new ArrayList<>();
        protected ModelGraphComposition graphComposition;
        protected List<ModelGraphPathOverride>
                graphPathOverrides = List.of();
        private volatile int skip;

        protected DefaultSearch() {
            this(SearchQuery.builder(), null);
        }

        protected DefaultSearch(SearchQuery.Builder queryBuilder) {
            this(queryBuilder, null);
        }

        protected DefaultSearch(
                SearchQuery.Builder queryBuilder,
                Class<?> targetModelType) {
            this.queryBuilder = queryBuilder;
            this.targetModelType = targetModelType;
        }

        @Override
        public Search<R> since(Instant start, boolean inclusive) {
            queryBuilder.since(start).sinceExclusive(!inclusive);
            return this;
        }

        @Override
        public Search<R> before(Instant end, boolean inclusive) {
            queryBuilder.before(end).beforeInclusive(inclusive);
            return this;
        }

        @Override
        public Search<R> inPeriod(Instant start, boolean startInclusive, Instant end, boolean endInclusive) {
            queryBuilder.since(start).sinceExclusive(!startInclusive).before(end).beforeInclusive(endInclusive);
            return this;
        }

        @Override
        public Search<R> constraint(Constraint... constraints) {
            switch (constraints.length) {
                case 0:
                    break;
                case 1:
                    queryBuilder.constraint(constraints[0]);
                    break;
                default:
                    queryBuilder.constraints(Arrays.asList(constraints));
                    break;
            }
            return this;
        }

        @Override
        public Search<R> relation(
                ModelRelationConstraint... constraints) {
            for (ModelRelationConstraint constraint :
                    constraints) {
                useModelCurrentDocumentCollection();
                relationConstraints.add(
                        Objects.requireNonNull(
                                constraint,
                                "Model relation constraint"));
            }
            return this;
        }

        private void useModelCurrentDocumentCollection() {
            if (targetModelType == null) {
                return;
            }
            String collection = EntityMetadata.validate(targetModelType)
                    .modelDocumentCollection()
                    .orElseThrow(() -> new IllegalArgumentException(
                            ("Relationship search target %s has no current-state document; "
                             + "load it as a Model or Graph instead")
                                    .formatted(targetModelType.getName())));
            queryBuilder.collections(List.of(collection));
        }

        @Override
        public Search<R> sortByTimestamp(boolean descending) {
            return sortBy("timestamp", descending);
        }

        @Override
        public Search<R> sortByScore() {
            sorting.add("-score");
            return this;
        }

        @Override
        public Search<R> sortBy(String path, boolean descending) {
            sorting.add((descending ? "-" : "") + path);
            return this;
        }

        @Override
        public Search<R> exclude(String... paths) {
            pathFilters.addAll(Arrays.stream(paths).map(p -> "-" + p).toList());
            return this;
        }

        @Override
        public Search<R> includeOnly(String... paths) {
            pathFilters.addAll(Arrays.asList(paths));
            return this;
        }

        @Override
        public Search<R> skip(Integer n) {
            if (n != null) {
                this.skip = n;
            }
            return this;
        }

        @Override
        public Stream<SearchHit<R>> streamHits() {
            return fetchHitStream(null, null);
        }

        @Override
        public Stream<SearchHit<R>> streamHits(int fetchSize) {
            return fetchHitStream(null, null, fetchSize);
        }

        @Override
        public <T> Stream<SearchHit<T>> streamHits(Class<T> type) {
            return fetchHitStream(null, type);
        }

        @Override
        public <T> Stream<SearchHit<T>> streamHits(Class<T> type, int fetchSize) {
            return fetchHitStream(null, type, fetchSize);
        }

        @Override
        public List<R> fetch(int maxSize) {
            return this.<R>fetchHitStream(maxSize, null).map(SearchHit::getValue).collect(toList());
        }

        @Override
        public <T> List<T> fetch(int maxSize, Class<T> type) {
            return fetchHitStream(maxSize, type).map(SearchHit::getValue).collect(toList());
        }

        @Override
        public CompletableFuture<List<R>> fetchAsync(int maxSize) {
            return fetchAsync(maxSize, null);
        }

        @Override
        public <T> CompletableFuture<List<T>> fetchAsync(int maxSize, Class<T> type) {
            SearchDocuments request = searchRequest(maxSize);
            int fetchSize = Math.min(
                    maxSize, defaultFetchSize);
            CompletableFuture<List<SearchHit<SerializedDocument>>> future =
                    graphComposition != null
                            ? getSearchClient().searchModelGraphAsync(
                                    new SearchModelGraphDocuments(
                                            request,
                                            relationConstraints,
                                            graphComposition,
                                            graphPathOverrides),
                                    fetchSize)
                            : relationConstraints.isEmpty()
                                    ? getSearchClient().searchAsync(
                                            request, fetchSize)
                                    : getSearchClient().searchModelsAsync(
                                            new SearchModelDocuments(
                                                    request,
                                                    relationConstraints),
                                            fetchSize);
            return future.thenApply(hits -> mapHits(hits, type));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private <T> List<T> mapHits(List<SearchHit<SerializedDocument>> hits, Class<T> type) {
            if (SerializedDocument.class.equals(type)) {
                return (List) hits.stream().map(SearchHit::getValue).toList();
            }
            Function<SerializedDocument, T> convertFunction =
                    converter(type);
            return hits.stream().map(hit -> hit.map(convertFunction).getValue()).toList();
        }

        protected <T> Stream<SearchHit<T>> fetchHitStream(Integer maxSize, Class<T> type) {
            return fetchHitStream(maxSize, type, maxSize == null
                    ? defaultFetchSize : Math.min(maxSize, defaultFetchSize));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        protected <T> Stream<SearchHit<T>> fetchHitStream(Integer maxSize, Class<T> type, int fetchSize) {
            SearchDocuments request = searchRequest(maxSize);
            Stream<SearchHit<SerializedDocument>> hitStream =
                    graphComposition != null
                            ? getSearchClient().searchModelGraph(
                                    new SearchModelGraphDocuments(
                                            request,
                                            relationConstraints,
                                            graphComposition,
                                            graphPathOverrides),
                                    fetchSize)
                            : relationConstraints.isEmpty()
                                    ? getSearchClient().search(
                                            request, fetchSize)
                                    : getSearchClient().searchModels(
                                            new SearchModelDocuments(
                                                    request,
                                                    relationConstraints),
                                            fetchSize);
            if (SerializedDocument.class.equals(type)) {
                return (Stream) hitStream;
            }
            Function<SerializedDocument, T> convertFunction =
                    converter(type);
            return hitStream.map(hit -> hit.map(convertFunction));
        }

        @SuppressWarnings("unchecked")
        protected <T> Function<SerializedDocument, T>
                converter(Class<T> type) {
            Class<?> effectiveType =
                    type == null
                            ? defaultResultType()
                            : type;
            return document -> (T) convert(
                    document, effectiveType);
        }

        protected Object convert(
                SerializedDocument document,
                Class<?> effectiveType) {
            return effectiveType == null
                    ? serializer.fromDocument(document)
                    : serializer.fromDocument(
                            document, effectiveType);
        }

        protected Class<?> defaultResultType() {
            return null;
        }

        protected boolean hasPathFilters() {
            return !pathFilters.isEmpty();
        }

        @Override
        public SearchHistogram fetchHistogram(int resolution, int maxSize) {
            requireOrdinarySearch("histograms");
            return getSearchClient().fetchHistogram(new GetSearchHistogram(queryBuilder.build(), resolution, maxSize));
        }

        @Override
        public GroupSearch groupBy(String... paths) {
            requireOrdinarySearch("grouped statistics");
            return new DefaultGroupSearch(Arrays.asList(paths));
        }

        @Override
        public List<FacetStats> facetStats() {
            requireOrdinarySearch("facet statistics");
            return getSearchClient().fetchFacetStats(queryBuilder.build())
                    .stream().filter(this::isPublicFacet).toList();
        }

        @Override
        public CompletableFuture<List<FacetStats>> facetStatsAsync() {
            requireOrdinarySearch("facet statistics");
            return getSearchClient().fetchFacetStatsAsync(queryBuilder.build())
                    .thenApply(stats -> stats.stream().filter(this::isPublicFacet).toList());
        }

        private boolean isPublicFacet(FacetStats stats) {
            return !stats.getName().startsWith("$metadata/")
                   && !ModelGraphDocumentManifest.FACET_NAME.equals(stats.getName());
        }

        @Override
        public CompletableFuture<Void> delete(int batchSize) {
            requireOrdinarySearch("bulk delete");
            return getSearchClient().delete(queryBuilder.build(), Guarantee.STORED, batchSize);
        }

        @Override
        public CompletableFuture<Void> move(Object targetCollection) {
            requireOrdinarySearch("bulk move");
            return getSearchClient().move(queryBuilder.build(), determineCollection(targetCollection),
                                          Guarantee.STORED);
        }

        private SearchDocuments searchRequest(
                Integer maxSize) {
            return SearchDocuments.builder()
                    .query(queryBuilder.build())
                    .maxSize(maxSize)
                    .sorting(sorting)
                    .pathFilters(pathFilters)
                    .skip(skip)
                    .build();
        }

        private void requireOrdinarySearch(
                String operation) {
            if (!relationConstraints.isEmpty()
                || graphComposition != null) {
                throw new UnsupportedOperationException(
                        "Model relationship search and graph composition are not yet supported for "
                        + operation);
            }
        }

        @AllArgsConstructor
        protected class DefaultGroupSearch implements GroupSearch {
            private final List<String> groupBy;

            @Override
            public Map<Group, Map<String, DocumentStats.FieldStats>> aggregate(String... fields) {
                return getSearchClient().fetchStatistics(queryBuilder.build(), Arrays.asList(fields), groupBy).stream()
                        .collect(toMap(DocumentStats::getGroup, DocumentStats::getFieldStats));
            }

            @Override
            public CompletableFuture<Map<Group, Map<String, DocumentStats.FieldStats>>> aggregateAsync(
                    String... fields) {
                return getSearchClient().fetchStatisticsAsync(queryBuilder.build(), Arrays.asList(fields), groupBy)
                        .thenApply(stats -> stats.stream()
                                .collect(toMap(DocumentStats::getGroup, DocumentStats::getFieldStats)));
            }
        }
    }

    protected class DefaultGraphSearch<T> extends DefaultSearch<Graph<T>> {

        private final Class<T> rootModelType;
        private final Map<String, String> pathOverrides;

        protected DefaultGraphSearch(
                SearchQuery.Builder queryBuilder,
                Class<T> rootModelType,
                ModelGraphComposition composition,
                Map<String, String> pathOverrides,
                List<ModelGraphPathOverride>
                        requestPathOverrides) {
            super(queryBuilder);
            this.rootModelType = rootModelType;
            this.pathOverrides = Map.copyOf(pathOverrides);
            this.graphComposition = composition;
            this.graphPathOverrides =
                    List.copyOf(requestPathOverrides);
        }

        @Override
        protected Class<?> defaultResultType() {
            return Graph.class;
        }

        @Override
        protected Object convert(
                SerializedDocument document,
                Class<?> effectiveType) {
            if (Graph.class.equals(effectiveType)) {
                if (hasPathFilters()) {
                    throw new IllegalStateException(
                            "Typed Graph results require complete model documents; "
                            + "request ObjectNode results when includeOnly or exclude is configured");
                }
                return MaterializedGraphFactory.create(
                        document, rootModelType, serializer,
                        modelRepositorySupplier,
                        modelTypesSupplier.get(),
                        pathOverrides);
            }
            return super.convert(document, effectiveType);
        }
    }
}
