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

package io.fluxzero.sdk.test;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.search.BulkUpdate;
import io.fluxzero.common.api.search.CreateAuditTrail;
import io.fluxzero.common.api.search.DocumentStats;
import io.fluxzero.common.api.search.DocumentUpdate;
import io.fluxzero.common.api.search.FacetStats;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.GetDocuments;
import io.fluxzero.common.api.search.GetSearchHistogram;
import io.fluxzero.common.api.search.HasDocument;
import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchHistogram;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.sdk.persisting.search.SearchHit;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

@RequiredArgsConstructor
class DocumentTrackingSearchClient implements SearchClient {
    private final SearchClient delegate;
    private final TestFixture.GivenWhenThenInterceptor interceptor;

    @Override
    public CompletableFuture<Void> index(List<SerializedDocument> documents, Guarantee guarantee,
                                         boolean ifNotExists) {
        List<SerializedDocument> expectedUpdates = updatedDocuments(documents, ifNotExists);
        monitorDocumentUpdates(expectedUpdates);
        try {
            return delegate.index(documents, guarantee, ifNotExists)
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            interceptor.cancelDocumentDispatch(expectedUpdates);
                        }
                    });
        } catch (Throwable e) {
            return cancelAndRethrow(expectedUpdates, e);
        }
    }

    @Override
    public CompletableFuture<Void> bulkUpdate(Collection<DocumentUpdate> updates, Guarantee guarantee) {
        List<SerializedDocument> expectedUpdates = updatedDocuments(updates);
        monitorDocumentUpdates(expectedUpdates);
        try {
            return delegate.bulkUpdate(updates, guarantee)
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            interceptor.cancelDocumentDispatch(expectedUpdates);
                        }
                    });
        } catch (Throwable e) {
            return cancelAndRethrow(expectedUpdates, e);
        }
    }

    private void monitorDocumentUpdates(List<SerializedDocument> documents) {
        documents.forEach(interceptor::monitorDocumentDispatch);
    }

    @SneakyThrows
    private CompletableFuture<Void> cancelAndRethrow(List<SerializedDocument> documents, Throwable e) {
        try {
            interceptor.cancelDocumentDispatch(documents);
        } catch (Throwable cancellationFailure) {
            e.addSuppressed(cancellationFailure);
        }
        throw e;
    }

    private List<SerializedDocument> updatedDocuments(List<SerializedDocument> documents, boolean ifNotExists) {
        Stream<SerializedDocument> result = documents.stream()
                .collect(toMap(this::identifier, identity(), (a, b) -> b, LinkedHashMap::new))
                .values().stream();
        if (ifNotExists) {
            result = result.filter(document -> !delegate.documentExists(new HasDocument(document.getId(),
                                                                                       document.getCollection())));
        }
        return result.toList();
    }

    private List<SerializedDocument> updatedDocuments(Collection<DocumentUpdate> updates) {
        Collection<DocumentUpdate> deduplicatedUpdates = updates.stream().filter(Objects::nonNull)
                .collect(toMap(update -> format("%s_%s", update.getCollection(), update.getId()),
                               Function.identity(), (a, b) -> b, LinkedHashMap::new))
                .values();
        List<SerializedDocument> indexedDocuments = deduplicatedUpdates.stream()
                .filter(update -> update.getType() == BulkUpdate.Type.index)
                .map(DocumentUpdate::getObject)
                .filter(Objects::nonNull)
                .toList();
        List<SerializedDocument> newDocuments = deduplicatedUpdates.stream()
                .filter(update -> update.getType() == BulkUpdate.Type.indexIfNotExists)
                .map(DocumentUpdate::getObject)
                .filter(Objects::nonNull)
                .filter(document -> !delegate.documentExists(new HasDocument(document.getId(),
                                                                             document.getCollection())))
                .toList();
        return Stream.concat(indexedDocuments.stream(), newDocuments.stream()).toList();
    }

    private String identifier(SerializedDocument document) {
        return format("%s_%s", document.getCollection(), document.getId());
    }

    @Override
    public Stream<SearchHit<SerializedDocument>> search(SearchDocuments searchDocuments, int fetchSize) {
        return delegate.search(searchDocuments, fetchSize);
    }

    @Override
    public boolean documentExists(HasDocument request) {
        return delegate.documentExists(request);
    }

    @Override
    public Optional<SerializedDocument> fetch(GetDocument request) {
        return delegate.fetch(request);
    }

    @Override
    public Collection<SerializedDocument> fetch(GetDocuments request) {
        return delegate.fetch(request);
    }

    @Override
    public CompletableFuture<Void> delete(SearchQuery query, Guarantee guarantee) {
        return delegate.delete(query, guarantee);
    }

    @Override
    public CompletableFuture<Void> move(SearchQuery query, String targetCollection, Guarantee guarantee) {
        return delegate.move(query, targetCollection, guarantee);
    }

    @Override
    public CompletableFuture<Void> delete(String documentId, String collection, Guarantee guarantee) {
        return delegate.delete(documentId, collection, guarantee);
    }

    @Override
    public CompletableFuture<Void> move(String documentId, String collection, String targetCollection,
                                        Guarantee guarantee) {
        return delegate.move(documentId, collection, targetCollection, guarantee);
    }

    @Override
    public CompletableFuture<Void> createAuditTrail(CreateAuditTrail request) {
        return delegate.createAuditTrail(request);
    }

    @Override
    public CompletableFuture<Void> deleteCollection(String collection, Guarantee guarantee) {
        return delegate.deleteCollection(collection, guarantee);
    }

    @Override
    public List<DocumentStats> fetchStatistics(SearchQuery query, List<String> fields, List<String> groupBy) {
        return delegate.fetchStatistics(query, fields, groupBy);
    }

    @Override
    public SearchHistogram fetchHistogram(GetSearchHistogram request) {
        return delegate.fetchHistogram(request);
    }

    @Override
    public List<FacetStats> fetchFacetStats(SearchQuery query) {
        return delegate.fetchFacetStats(query);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
