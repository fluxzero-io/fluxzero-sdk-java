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
import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchHistogram;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.search.bulkupdate.IndexDocument;
import io.fluxzero.common.api.search.bulkupdate.IndexDocumentIfNotExists;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.tracking.handling.HasLocalHandlers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.util.function.UnaryOperator.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@AllArgsConstructor
@Slf4j
public class DefaultDocumentStore implements DocumentStore, HasLocalHandlers {
    private final SearchClient client;
    @Getter
    private final DocumentSerializer serializer;
    @Delegate
    private final HasLocalHandlers handlerRegistry;

    @Override
    public CompletableFuture<Void> index(@NonNull Object object, Object id, Object collection, Instant begin,
                                         Instant end, Metadata metadata, Guarantee guarantee, boolean ifNotExists) {
        try {
            object = object instanceof Entity<?> e ? e.get() : object;
            return client.index(List.of(serializer.toDocument(object, id.toString(),
                                                              determineCollection(collection), begin, end, metadata)),
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
            return client.index(documents, guarantee, ifNotExists);
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
            return client.index(documents, guarantee, ifNotExists);
        } catch (Exception e) {
            throw new DocumentStoreException(
                    format("Could not store a list of documents for collection %s", collection), e);
        }
    }

    @Override
    public CompletableFuture<Void> bulkUpdate(Collection<? extends BulkUpdate> updates, Guarantee guarantee) {
        try {
            return client.bulkUpdate(updates.stream().map(this::serializeAction)
                                             .collect(toMap(a -> format("%s_%s", a.getCollection(), a.getId()),
                                                            identity(), (a, b) -> b)).values(),
                                     guarantee);
        } catch (Exception e) {
            throw new DocumentStoreException("Could not apply batch of search actions", e);
        }
    }

    public DocumentUpdate serializeAction(BulkUpdate update) {
        String collection = determineCollection(update.getCollection());
        var builder = DocumentUpdate.builder().collection(collection).id(update.getId()).type(update.getType());
        if (update instanceof IndexDocument u) {
            var document = u.getObject() instanceof SerializedDocument s
                    ? s : serializer.toDocument(u.getObject(), u.getId(), collection, u.getTimestamp(), u.getEnd());
            return builder.object(document).build();
        } else if (update instanceof IndexDocumentIfNotExists u) {
            var document = u.getObject() instanceof SerializedDocument s
                    ? s : serializer.toDocument(u.getObject(), u.getId(), collection, u.getTimestamp(), u.getEnd());
            return builder.object(document).build();
        }
        return builder.build();
    }


    @Override
    public Search search(SearchQuery.Builder searchBuilder) {
        return new DefaultSearch(searchBuilder);
    }

    @Override
    public boolean hasDocument(Object id, Object collection) {
        return client.documentExists(new HasDocument(id.toString(), determineCollection(collection)));
    }

    @Override
    public <T> Optional<T> fetchDocument(Object id, Object collection) {
        try {
            return client.fetch(new GetDocument(id.toString(), determineCollection(collection)))
                    .map(serializer::fromDocument);
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not get document %s from collection %s", id, collection), e);
        }
    }

    @Override
    public <T> Optional<T> fetchDocument(Object id, Object collection, Class<T> type) {
        try {
            return client.fetch(new GetDocument(id.toString(), determineCollection(collection)))
                    .map(d -> serializer.fromDocument(d, type));
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not get document %s from collection %s", id, collection), e);
        }
    }

    @Override
    public <T> Collection<T> fetchDocuments(Collection<?> ids, Object collection) {
        try {
            return client.fetch(new GetDocuments(ids.stream().map(Object::toString).collect(Collectors.toSet()),
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
            return client.fetch(new GetDocuments(ids.stream().map(Object::toString).collect(Collectors.toSet()),
                                                 determineCollection(collection)))
                    .stream().map(d -> serializer.fromDocument(d, type)).toList();
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not get documents %s from collection %s", ids, collection),
                                             e);
        }
    }

    @Override
    public CompletableFuture<Void> deleteDocument(Object id, Object collection) {
        try {
            return client.delete(id.toString(), determineCollection(collection), Guarantee.STORED);
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not delete document %s from collection %s", id, collection),
                                             e);
        }
    }

    @Override
    public CompletableFuture<Void> deleteCollection(Object collection) {
        try {
            return client.deleteCollection(determineCollection(collection));
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not delete collection %s", collection), e);
        }
    }

    @Override
    public CompletableFuture<Void> createAuditTrail(Object collection, Duration retentionTime) {
        try {
            return client.createAuditTrail(new CreateAuditTrail(determineCollection(collection), Optional.ofNullable(
                    retentionTime).map(Duration::getSeconds).orElse(null), Guarantee.STORED));
        } catch (Exception e) {
            throw new DocumentStoreException(format("Could not create audit trail %s", collection), e);
        }
    }

    @RequiredArgsConstructor
    protected class DefaultSearch implements Search {

        private final SearchQuery.Builder queryBuilder;
        private final List<String> sorting = new ArrayList<>();
        private final List<String> pathFilters = new ArrayList<>();
        private volatile int skip;

        protected DefaultSearch() {
            this(SearchQuery.builder());
        }

        @Override
        public Search since(Instant start, boolean inclusive) {
            queryBuilder.since(start).sinceExclusive(!inclusive);
            return this;
        }

        @Override
        public Search before(Instant end, boolean inclusive) {
            queryBuilder.before(end).beforeInclusive(inclusive);
            return this;
        }

        @Override
        public Search inPeriod(Instant start, boolean startInclusive, Instant end, boolean endInclusive) {
            queryBuilder.since(start).sinceExclusive(!startInclusive).before(end).beforeInclusive(endInclusive);
            return this;
        }

        @Override
        public Search constraint(Constraint... constraints) {
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
        public Search sortByTimestamp(boolean descending) {
            return sortBy("timestamp", descending);
        }

        @Override
        public Search sortByScore() {
            sorting.add("-score");
            return this;
        }

        @Override
        public Search sortBy(String path, boolean descending) {
            sorting.add((descending ? "-" : "") + path);
            return this;
        }

        @Override
        public Search exclude(String... paths) {
            pathFilters.addAll(Arrays.stream(paths).map(p -> "-" + p).toList());
            return this;
        }

        @Override
        public Search includeOnly(String... paths) {
            pathFilters.addAll(Arrays.asList(paths));
            return this;
        }

        @Override
        public Search skip(Integer n) {
            if (n != null) {
                this.skip = n;
            }
            return this;
        }

        @Override
        public <T> Stream<SearchHit<T>> streamHits() {
            return fetchHitStream(null, null);
        }

        @Override
        public <T> Stream<SearchHit<T>> streamHits(int fetchSize) {
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
        public <T> List<T> fetch(int maxSize) {
            return this.<T>fetchHitStream(maxSize, null).map(SearchHit::getValue).collect(toList());
        }

        @Override
        public <T> List<T> fetch(int maxSize, Class<T> type) {
            return fetchHitStream(maxSize, type).map(SearchHit::getValue).collect(toList());
        }

        protected <T> Stream<SearchHit<T>> fetchHitStream(Integer maxSize, Class<T> type) {
            return fetchHitStream(maxSize, type, maxSize == null
                    ? defaultFetchSize : Math.min(maxSize, defaultFetchSize));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        protected <T> Stream<SearchHit<T>> fetchHitStream(Integer maxSize, Class<T> type, int fetchSize) {
            SearchQuery query = queryBuilder.build();
            Stream<SearchHit<SerializedDocument>> hitStream = client.search(
                    SearchDocuments.builder().query(query).maxSize(maxSize).sorting(sorting)
                            .pathFilters(pathFilters).skip(skip).build(), fetchSize);
            if (SerializedDocument.class.equals(type)) {
                return (Stream) hitStream;
            }
            Function<SerializedDocument, T> convertFunction = type == null
                    ? serializer::fromDocument : document -> serializer.fromDocument(document, type);
            return hitStream.map(hit -> hit.map(convertFunction));
        }

        @Override
        public SearchHistogram fetchHistogram(int resolution, int maxSize) {
            return client.fetchHistogram(new GetSearchHistogram(queryBuilder.build(), resolution, maxSize));
        }

        @Override
        public GroupSearch groupBy(String... paths) {
            return new DefaultGroupSearch(Arrays.asList(paths));
        }

        @Override
        public List<FacetStats> facetStats() {
            return client.fetchFacetStats(queryBuilder.build())
                    .stream().filter(s -> !s.getName().startsWith("$metadata/")).toList();
        }

        @Override
        public CompletableFuture<Void> delete() {
            return client.delete(queryBuilder.build(), Guarantee.STORED);
        }

        @AllArgsConstructor
        protected class DefaultGroupSearch implements GroupSearch {
            private final List<String> groupBy;

            @Override
            public Map<Group, Map<String, DocumentStats.FieldStats>> aggregate(String... fields) {
                return client.fetchStatistics(queryBuilder.build(), Arrays.asList(fields), groupBy).stream()
                        .collect(toMap(DocumentStats::getGroup, DocumentStats::getFieldStats));
            }
        }
    }
}
