/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.sdk.persisting.search.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.MaterializeModelAction;
import io.fluxzero.common.api.modeling.ModelGraphEdge;
import io.fluxzero.common.api.modeling.ModelGraphProjectionConfiguration;
import io.fluxzero.common.api.modeling.ModelSnapshotMutation;
import io.fluxzero.common.api.search.CreateAuditTrail;
import io.fluxzero.common.api.search.DocumentStats;
import io.fluxzero.common.api.search.DocumentUpdate;
import io.fluxzero.common.api.search.FacetEntry;
import io.fluxzero.common.api.search.FacetStats;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.GetDocuments;
import io.fluxzero.common.api.search.GetSearchHistogram;
import io.fluxzero.common.api.search.HasDocument;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.ModelRelationConstraint;
import io.fluxzero.common.api.search.SearchCollection;
import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchHistogram;
import io.fluxzero.common.api.search.SearchModelGraphDocuments;
import io.fluxzero.common.api.search.SearchModelDocuments;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.search.Document;
import io.fluxzero.common.search.ModelGraphDocumentSearch;
import io.fluxzero.common.search.ModelGraphDocumentStitcher;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.search.SearchHit;
import io.fluxzero.sdk.tracking.IndexUtils;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.fluxzero.common.api.search.SearchCollectionType.auditTrail;
import static io.fluxzero.common.api.search.SearchCollectionType.regular;
import static java.util.Comparator.comparing;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * In-memory implementation of the {@link SearchClient}, intended for local testing and development.
 * <p>
 * Stores all indexed documents in memory, with support for basic search, statistics, and deletion logic. Ideal for use
 * in test scenarios where a real Fluxzero Runtime connection is not available or needed.
 */
public class InMemorySearchStore implements SearchClient {
    protected static final Function<SerializedDocument, String> identifier =
            d -> asIdentifier(d.getCollection(), d.getId());

    protected static String asIdentifier(String collection, String documentId) {
        return collection + "/" + documentId;
    }

    private final Map<String, SerializedDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, Long> modelDocumentStateIndices =
            new ConcurrentHashMap<>();
    private final Map<String, Long>
            modelGraphProjectionStateIndices =
            new ConcurrentHashMap<>();

    private final AtomicLong nextIndex = new AtomicLong();
    private final Map<String, ConcurrentSkipListMap<Long, SerializedMessage>> messageLogs = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, List<SerializedMessage>>> monitors = new CopyOnWriteArrayList<>();
    private final Set<String> collections = ConcurrentHashMap.newKeySet();
    private final Set<String> auditTrails = ConcurrentHashMap.newKeySet();

    @Getter
    @Setter
    private Duration retentionTime;

    private final ModelRelationResolver modelRelationResolver;
    private final ModelGraphResolver modelGraphResolver;
    private final ModelDocumentCollectionResolver
            modelDocumentCollectionResolver;

    public InMemorySearchStore(Duration retentionTime) {
        this(retentionTime, null, null, null);
    }

    public InMemorySearchStore(
            Duration retentionTime,
            ModelRelationResolver modelRelationResolver) {
        this(retentionTime, modelRelationResolver,
             null, null);
    }

    public InMemorySearchStore(
            Duration retentionTime,
            ModelRelationResolver modelRelationResolver,
            ModelGraphResolver modelGraphResolver) {
        this(retentionTime, modelRelationResolver,
             modelGraphResolver, null);
    }

    public InMemorySearchStore(
            Duration retentionTime,
            ModelRelationResolver modelRelationResolver,
            ModelGraphResolver modelGraphResolver,
            ModelDocumentCollectionResolver
                    modelDocumentCollectionResolver) {
        this.retentionTime = retentionTime;
        this.modelRelationResolver = modelRelationResolver;
        this.modelGraphResolver = modelGraphResolver;
        this.modelDocumentCollectionResolver =
                modelDocumentCollectionResolver;
    }

    @Override
    public List<SearchCollection> getSearchCollections() {
        return collections.stream()
                .map(c -> new SearchCollection(c, auditTrails.contains(c) ? auditTrail : regular))
                .sorted(comparing(SearchCollection::getName)).toList();
    }

    @Override
    public CompletableFuture<Void> index(List<SerializedDocument> documents, Guarantee guarantee, boolean ifNotExists) {
        Map<String, SerializedDocument> updates = documents.stream()
                .collect(toMap(identifier, identity(), (a, b) -> b, LinkedHashMap::new));
        if (ifNotExists) {
            updates.keySet().removeAll(this.documents.keySet());
        }
        this.documents.putAll(updates);
        updates.values().stream().map(SerializedDocument::getCollection).forEach(collections::add);
        storeMessages(updates);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Stream<SearchHit<SerializedDocument>> search(SearchDocuments searchDocuments, int fetchSize) {
        SearchQuery query = searchDocuments.getQuery();
        Stream<SerializedDocument> documentStream = documents.values().stream();
        if (searchDocuments.getDocumentIds() != null
            && !searchDocuments.getDocumentIds()
                    .isEmpty()) {
            Set<String> ids = Set.copyOf(
                    searchDocuments.getDocumentIds());
            documentStream = documentStream.filter(
                    document -> ids.contains(document.getId()));
        }
        documentStream = documentStream.filter(query::matches);
        documentStream = documentStream.sorted(
                comparing(SerializedDocument::deserializeDocument, Document.createComparator(searchDocuments)));
        if (!searchDocuments.getPathFilters().isEmpty()) {
            Predicate<Document.Path> pathFilter = searchDocuments.computePathFilter();
            documentStream = documentStream.map(d -> d.deserializeDocument().filterPaths(pathFilter))
                    .map(SerializedDocument::new);
        }
        if (searchDocuments.getSkip() > 0) {
            documentStream = documentStream.skip(searchDocuments.getSkip());
        }
        if (searchDocuments.getLastHit() != null) {
            documentStream = documentStream.dropWhile(d -> !d.getId().equals(searchDocuments.getLastHit().getId()))
                    .skip(1);
        }
        if (searchDocuments.getMaxSize() != null) {
            documentStream = documentStream.limit(searchDocuments.getMaxSize());
        }
        return documentStream.map(SearchHit::fromDocument);
    }

    @Override
    public Stream<SearchHit<SerializedDocument>> searchModels(
            SearchModelDocuments request,
            int fetchSize) {
        if (modelRelationResolver == null) {
            throw new UnsupportedOperationException(
                    "Independent-model graph search has no relationship resolver");
        }
        LinkedHashSet<String> candidates = null;
        for (ModelRelationConstraint relation :
                request.getRelations()) {
            List<String> related = search(
                    SearchDocuments.builder()
                            .query(relation.getQuery())
                            .maxSize(
                                    relation.getMaxRelatedModels()
                                    + 1)
                            .build(),
                    relation.getMaxRelatedModels() + 1)
                    .map(SearchHit::getId)
                    .toList();
            if (related.size()
                > relation.getMaxRelatedModels()) {
                throw new IllegalArgumentException(
                        "Related model query exceeds maxRelatedModels "
                        + relation.getMaxRelatedModels()
                        + "; narrow the query or use a materialized graph projection");
            }
            Set<String> resolved =
                    modelRelationResolver.resolve(
                            new LinkedHashSet<>(related),
                            relation);
            if (candidates == null) {
                candidates = new LinkedHashSet<>(
                        resolved);
            } else {
                candidates.retainAll(resolved);
            }
            if (candidates.isEmpty()) {
                return Stream.empty();
            }
        }
        if (request.getSearch().getDocumentIds()
            != null
            && !request.getSearch()
                    .getDocumentIds().isEmpty()) {
            candidates.retainAll(
                    request.getSearch().getDocumentIds());
        }
        return search(
                request.getSearch().toBuilder()
                        .documentIds(List.copyOf(candidates))
                        .build(),
                fetchSize);
    }

    @Override
    public Stream<SearchHit<SerializedDocument>>
    searchModelGraph(
            SearchModelGraphDocuments request,
            int fetchSize) {
        if (modelGraphResolver == null) {
            throw new UnsupportedOperationException(
                    "Independent-model graph composition has no graph resolver");
        }
        SearchDocuments graphSearch =
                request.getSearch();
        SearchDocuments candidateSearch =
                SearchDocuments.builder()
                        .query(SearchQuery.builder()
                                       .collections(
                                               graphSearch.getQuery()
                                                       .getCollections())
                                       .build())
                        .documentIds(
                                graphSearch.getDocumentIds())
                        .maxSize(
                                request.getComposition()
                                        .getMaxModels()
                                + 1)
                        .build();
        List<SerializedDocument> roots =
                (request.getRelations().isEmpty()
                        ? search(
                                candidateSearch,
                                request.getComposition()
                                        .getMaxModels()
                                + 1)
                        : searchModels(
                                new SearchModelDocuments(
                                        candidateSearch,
                                        request.getRelations()),
                                request.getComposition()
                                        .getMaxModels()
                                + 1))
                        .map(SearchHit::getValue)
                        .toList();
        if (roots.size()
            > request.getComposition()
                    .getMaxModels()) {
            throw new IllegalArgumentException(
                    "Model graph search exceeds maxModels "
                    + request.getComposition()
                            .getMaxModels()
                    + " before composition; narrow the roots or use a materialized graph projection");
        }
        if (roots.isEmpty()) {
            return Stream.empty();
        }
        List<ModelGraphEdge> edges =
                ModelGraphDocumentStitcher
                        .applyPathOverrides(
                                modelGraphResolver.resolve(
                                        roots.stream()
                                                .map(SerializedDocument::getId)
                                                .collect(
                                                        java.util.stream.Collectors
                                                                .toCollection(
                                                                        LinkedHashSet::new)),
                                        request.getComposition()),
                                request.getPathOverrides());
        LinkedHashSet<String> graphIds =
                roots.stream()
                        .map(SerializedDocument::getId)
                        .collect(
                                java.util.stream.Collectors
                                        .toCollection(
                                                LinkedHashSet::new));
        edges.forEach(edge -> {
            graphIds.add(edge.getParentId());
            graphIds.add(edge.getChildId());
        });
        LinkedHashMap<String, SerializedDocument>
                graphDocuments =
                resolveGraphDocuments(
                        roots, graphIds);
        long collectionCount =
                graphDocuments.values().stream()
                        .map(SerializedDocument::getCollection)
                        .distinct().count();
        if (collectionCount
            > request.getComposition()
                    .getMaxCollections()) {
            throw new IllegalArgumentException(
                    "Model graph composition exceeds maxCollections "
                    + request.getComposition()
                            .getMaxCollections()
                    + "; narrow the result or use a materialized graph projection");
        }
        return ModelGraphDocumentSearch.apply(
                        ModelGraphDocumentStitcher.stitch(
                                roots, edges,
                                graphDocuments,
                                request.getComposition()),
                        graphSearch)
                .stream().map(
                SearchHit::fromDocument);
    }

    @Override
    public boolean documentExists(HasDocument r) {
        return Optional.ofNullable(documents.get(asIdentifier(r.getCollection(), r.getId()))).isPresent();
    }

    @Override
    public Optional<SerializedDocument> fetch(GetDocument r) {
        return Optional.ofNullable(documents.get(asIdentifier(r.getCollection(), r.getId())));
    }

    /**
     * Resolves target model IDs from related matches at the current relationship boundary.
     */
    @FunctionalInterface
    public interface ModelRelationResolver {
        Set<String> resolve(
                Set<String> relatedModelIds,
                ModelRelationConstraint constraint);
    }

    /**
     * Resolves explicitly placed current child edges for root models.
     */
    @FunctionalInterface
    public interface ModelGraphResolver {
        List<ModelGraphEdge> resolve(
                Set<String> rootModelIds,
                ModelGraphComposition composition);
    }

    /**
     * Resolves the exact current-document collection for model IDs.
     */
    @FunctionalInterface
    public interface ModelDocumentCollectionResolver {
        Map<String, String> resolve(
                Set<String> modelIds);
    }

    private LinkedHashMap<String, SerializedDocument>
    resolveGraphDocuments(
            List<SerializedDocument> roots,
            Set<String> graphIds) {
        LinkedHashMap<String, SerializedDocument>
                result = new LinkedHashMap<>();
        roots.forEach(document ->
                              result.put(
                                      document.getId(),
                                      document));
        if (modelDocumentCollectionResolver
            != null) {
            modelDocumentCollectionResolver.resolve(
                            graphIds)
                    .forEach((modelId, collection) -> {
                        SerializedDocument document =
                                documents.get(
                                        asIdentifier(
                                                collection,
                                                modelId));
                        if (document != null) {
                            result.put(
                                    modelId,
                                    document);
                        }
                    });
            return result;
        }
        documents.values().stream()
                .filter(document ->
                                graphIds.contains(
                                        document.getId()))
                .forEach(document -> {
                    SerializedDocument existing =
                            result.putIfAbsent(
                                    document.getId(),
                                    document);
                    if (existing != null
                        && !existing.getCollection()
                                .equals(
                                        document.getCollection())) {
                        throw new IllegalArgumentException(
                                "Model %s has current documents in both %s and %s"
                                        .formatted(
                                                document.getId(),
                                                existing.getCollection(),
                                                document.getCollection()));
                    }
                });
        return result;
    }

    @Override
    public Collection<SerializedDocument> fetch(GetDocuments request) {
        return request.getIds().stream().distinct()
                .map(id -> documents.get(asIdentifier(request.getCollection(), id)))
                .filter(Objects::nonNull).toList();
    }

    @Override
    public CompletableFuture<Void> delete(SearchQuery query, Guarantee guarantee, int batchSize) {
        documents.values().removeIf(query::matches);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> move(SearchQuery query, String targetCollection, Guarantee guarantee) {
        var matches = documents.values().stream().filter(query::matches).toList();
        documents.values().removeAll(matches);
        return index(matches.stream().map(d -> d.withCollection(targetCollection)).toList(),
                     guarantee, false);
    }

    @Override
    public CompletableFuture<Void> delete(String documentId, String collection, Guarantee guarantee) {
        documents.remove(asIdentifier(collection, documentId));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> move(String documentId, String collection, String targetCollection,
                                        Guarantee guarantee) {
        SerializedDocument document = documents.remove(asIdentifier(collection, documentId));
        if (document == null) {
            return CompletableFuture.completedFuture(null);
        }
        var matches = List.of(document);
        documents.values().removeAll(matches);
        return index(matches.stream().map(d -> d.withCollection(targetCollection)).toList(),
                     guarantee, false);
    }

    @Override
    public CompletableFuture<Void> createAuditTrail(CreateAuditTrail request) {
        if (!collections.contains(request.getCollection())) {
            auditTrails.add(request.getCollection());
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> deleteCollection(String collection, Guarantee guarantee) {
        truncateCollection(collection);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public List<DocumentStats> fetchStatistics(SearchQuery query, List<String> fields, List<String> groupBy) {
        return DocumentStats.compute(documents.values().stream().filter(query::matches)
                                             .map(SerializedDocument::deserializeDocument), fields, groupBy);
    }

    @Override
    public SearchHistogram fetchHistogram(GetSearchHistogram request) {
        SearchQuery query = request.getQuery();
        List<Long> results = IntStream.range(0, request.getResolution()).mapToLong(i -> 0L).boxed().collect(toList());
        if (query.getSince() == null) {
            return new SearchHistogram(null, query.getBefore(), results);
        }
        if (query.getBefore() == null) {
            query = query.toBuilder().before(Instant.now()).build();
        }
        long min = query.getSince().toEpochMilli();
        long delta = query.getBefore().toEpochMilli() - min;
        long step = Math.min(1, delta / request.getResolution());

        search(SearchDocuments.builder().query(query).build(), -1)
                .map(h -> h.getValue().deserializeDocument())
                .collect(groupingBy(d -> (d.getTimestamp().toEpochMilli() - min) / step))
                .forEach((bucket, hits) -> results.set(bucket.intValue(), (long) hits.size()));
        return new SearchHistogram(query.getSince(), query.getBefore(), results);
    }

    @Override
    public List<FacetStats> fetchFacetStats(SearchQuery query) {
        return documents.values().stream().filter(query::matches).flatMap(d -> d.getFacets().stream())
                .collect(groupingBy(identity(), TreeMap::new, toList())).values().stream().map(group -> {
                    FacetEntry first = group.getFirst();
                    return new FacetStats(first.getName(), first.getValue(), group.size());
                }).sorted(comparing(FacetStats::getCount).reversed()).toList();
    }

    @Override
    public CompletableFuture<Void> bulkUpdate(Collection<DocumentUpdate> updates, Guarantee guarantee) {
        updates.stream().collect(groupingBy(DocumentUpdate::getType)).forEach((type, list) -> {
            switch (type) {
                case delete -> list.forEach(u -> delete(u.getId(), u.getCollection(), guarantee));
                case index -> index(list.stream().map(DocumentUpdate::getObject).toList(), guarantee, false);
                case indexIfNotExists -> index(list.stream().map(DocumentUpdate::getObject).toList(), guarantee, true);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void>
            materializeModelAction(
                    MaterializeModelAction action) {
        Map<String, SerializedDocument> indexed =
                new LinkedHashMap<>();
        action.getDocuments().forEach(update -> {
            long current =
                    modelDocumentStateIndices
                            .getOrDefault(
                                    update.getModelId(),
                                    -1L);
            if (current >= update.getStateIndex()) {
                return;
            }
            modelDocumentStateIndices.put(
                    update.getModelId(),
                    update.getStateIndex());
            var mutation =
                    update.getMutation();
            SerializedDocument document =
                    mutation.getDocument();
            if (document == null) {
                documents.remove(
                        asIdentifier(
                                mutation.getCollection(),
                                update.getModelId()));
            } else {
                documents.put(
                        identifier.apply(document),
                        document);
                indexed.put(
                        identifier.apply(document),
                        document);
                collections.add(
                        document.getCollection());
            }
        });
        storeMessages(indexed);
        action.getSnapshots().forEach(update -> {
            SerializedDocument document =
                    update.getMutation().toDocument(
                            update.getModelId(),
                            update.getSequenceNumber(),
                            update.getStateIndex());
            documents.putIfAbsent(
                    identifier.apply(document),
                    document);
            collections.add(
                    document.getCollection());
            trimModelSnapshots(
                    update.getModelId(),
                    update.getMutation()
                            .getMaxSnapshotCount());
        });
        return CompletableFuture.completedFuture(
                null);
    }

    /**
     * Synchronously materializes affected roots for the SDK-only graph-projection worker.
     */
    public synchronized void materializeModelGraphProjection(
            ModelGraphProjectionConfiguration
                    configuration,
            Set<String> rootIds,
            long stateIndex,
            boolean rebuild) {
        if (modelGraphResolver == null
            || modelDocumentCollectionResolver
               == null) {
            throw new UnsupportedOperationException(
                    "Independent-model graph projection has no graph resolvers");
        }
        if (rebuild) {
            documents.values().removeIf(
                    document ->
                            configuration.getCollection()
                                    .equals(
                                            document.getCollection())
                            && !rootIds.contains(
                                    document.getId()));
            String prefix =
                    configuration.getCollection()
                    + "/";
            modelGraphProjectionStateIndices
                    .keySet()
                    .removeIf(key ->
                                      key.startsWith(prefix)
                                      && !rootIds.contains(
                                              key.substring(
                                                      prefix.length())));
        }
        if (rootIds.isEmpty()) {
            return;
        }
        Map<String, String> pathOverrides =
                configuration.getPathOverrides()
                        .stream()
                        .collect(
                                LinkedHashMap::new,
                                (map, override) ->
                                        map.put(
                                                override.getPath(),
                                                override.getProjectionPath()),
                                Map::putAll);
        Map<String, SerializedDocument> indexed =
                new LinkedHashMap<>();
        for (String rootId : rootIds) {
            String projectionKey =
                    asIdentifier(
                            configuration
                                    .getCollection(),
                            rootId);
            long current =
                    modelGraphProjectionStateIndices
                            .getOrDefault(
                                    projectionKey, -1L);
            if (!rebuild
                && current >= stateIndex) {
                continue;
            }
            SerializedDocument root =
                    documents.get(
                            asIdentifier(
                                    configuration
                                            .getRootCollection(),
                                    rootId));
            if (root == null) {
                documents.remove(
                        projectionKey);
                modelGraphProjectionStateIndices.put(
                        projectionKey,
                        stateIndex);
                continue;
            }
            List<ModelGraphEdge> edges =
                    modelGraphResolver.resolve(
                                    Set.of(rootId),
                                    configuration
                                            .getComposition())
                            .stream()
                            .map(edge -> {
                                String path =
                                        pathOverrides
                                                .getOrDefault(
                                                        edge.getPath(),
                                                        edge.getPath());
                                return Objects.equals(
                                        path,
                                        edge.getPath())
                                        ? edge
                                        : new ModelGraphEdge(
                                                edge.getChildId(),
                                                edge.getParentId(),
                                                edge.getParentType(),
                                                path,
                                                edge.getValidFrom(),
                                                edge.getValidUntil());
                            })
                            .toList();
            LinkedHashSet<String> graphIds =
                    new LinkedHashSet<>();
            graphIds.add(rootId);
            edges.forEach(edge -> {
                graphIds.add(
                        edge.getParentId());
                graphIds.add(
                        edge.getChildId());
            });
            LinkedHashMap<String, SerializedDocument>
                    graphDocuments =
                    resolveGraphDocuments(
                            List.of(root),
                            graphIds);
            long collectionCount =
                    graphDocuments.values()
                            .stream()
                            .map(SerializedDocument
                                         ::getCollection)
                            .distinct()
                            .count();
            if (collectionCount
                > configuration.getComposition()
                        .getMaxCollections()) {
                throw new IllegalArgumentException(
                        "Model graph projection exceeds maxCollections "
                        + configuration
                                .getComposition()
                                .getMaxCollections());
            }
            SerializedDocument composed =
                    ModelGraphDocumentStitcher
                            .stitch(
                                    List.of(root),
                                    edges,
                                    graphDocuments,
                                    configuration
                                            .getComposition())
                            .getFirst()
                            .withCollection(
                                    configuration
                                            .getCollection());
            documents.put(
                    projectionKey,
                    composed);
            indexed.put(
                    projectionKey,
                    composed);
            collections.add(
                    configuration
                            .getCollection());
            modelGraphProjectionStateIndices.put(
                    projectionKey,
                    stateIndex);
        }
        storeMessages(indexed);
    }

    private void trimModelSnapshots(
            String modelId,
            int configuredMaximum) {
        int maximum =
                Math.max(1, configuredMaximum);
        List<SerializedDocument> snapshots =
                documents.values().stream()
                        .filter(document ->
                                        ModelSnapshotMutation.COLLECTION
                                                .equals(
                                                        document.getCollection()))
                        .filter(document ->
                                        document.getFacets()
                                                .stream()
                                                .anyMatch(
                                                        facet ->
                                                                ModelSnapshotMutation.MODEL_ID_FACET
                                                                        .equals(
                                                                                facet.getName())
                                                                && modelId.equals(
                                                                        facet.getValue())))
                        .sorted(
                                comparing(
                                        InMemorySearchStore
                                                ::snapshotSequence)
                                        .reversed())
                        .toList();
        snapshots.stream()
                .skip(maximum)
                .forEach(snapshot ->
                                 documents.remove(
                                         identifier.apply(
                                                 snapshot)));
    }

    private static long snapshotSequence(
            SerializedDocument document) {
        return document.getFacets().stream()
                .filter(facet ->
                                ModelSnapshotMutation.SEQUENCE_NUMBER
                                        .equals(
                                                facet.getName()))
                .map(FacetEntry::getValue)
                .mapToLong(Long::parseLong)
                .findFirst()
                .orElseThrow();
    }

    public Stream<SerializedMessage> openStream(String collection, Long lastIndex, int maxSize) {
        var map = messageLogs.get(collection);
        if (map == null) {
            return Stream.empty();
        }
        lastIndex = lastIndex == null ? -1L : lastIndex;
        return map.tailMap(lastIndex, false).values().stream().limit(maxSize);
    }

    public synchronized void truncateCollection(String collection) {
        documents.values().removeIf(d -> Objects.equals(collection, d.getCollection()));
        messageLogs.remove(collection);
        collections.remove(collection);
        auditTrails.remove(collection);
        notifyMonitors(collection, List.of());
    }

    protected synchronized void storeMessages(Map<String, SerializedDocument> updates) {
        if (!monitors.isEmpty()) {
            Map<String, List<SerializedMessage>> byCollection
                    = updates.values().stream().collect(groupingBy(SerializedDocument::getCollection, mapping(
                    this::asSerializedMessage, toList())));
            try {
                byCollection.forEach((collection, messages) -> {
                    var log = messageLogs.computeIfAbsent(collection, c -> new ConcurrentSkipListMap<>());
                    messages.forEach(m -> {
                        log.values().removeIf(mOld -> mOld.getMessageId().equals(m.getMessageId()));
                        log.put(m.getIndex(), m);
                    });
                });
                if (retentionTime != null) {
                    purgeExpiredMessages(retentionTime);
                }
            } finally {
                byCollection.forEach(this::notifyMonitors);
            }
        }
    }

    protected SerializedMessage asSerializedMessage(SerializedDocument document) {
        long index = nextIndex.updateAndGet(IndexUtils::nextIndex);
        Metadata metadata = Metadata.of("$start", document.getTimestamp(), "$end", document.getEnd());
        var result = new SerializedMessage(document.getDocument(), metadata, document.getId(),
                                           IndexUtils.millisFromIndex(index));
        result.setIndex(index);
        return result;
    }

    protected void purgeExpiredMessages(Duration messageExpiration) {
        var threshold = Fluxzero.currentTime().minus(messageExpiration).toEpochMilli();
        messageLogs.values().forEach(messageLog -> messageLog.headMap(
                IndexUtils.maxIndexFromMillis(threshold), true).clear());
    }

    protected void notifyMonitors(String collection, List<SerializedMessage> messages) {
        this.notifyAll();
        monitors.forEach(m -> m.accept(collection, messages));
    }

    public synchronized Registration registerMonitor(BiConsumer<String, List<SerializedMessage>> monitor) {
        monitors.add(monitor);
        return () -> monitors.remove(monitor);
    }

    public Registration registerMonitor(String collection, Consumer<List<SerializedMessage>> monitor) {
        return registerMonitor((c, messages) -> {
            if (Objects.equals(collection, c)) {
                monitor.accept(messages);
            }
        });
    }

    @Override
    public void close() {
    }
}
