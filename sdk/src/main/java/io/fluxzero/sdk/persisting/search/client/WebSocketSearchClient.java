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

package io.fluxzero.sdk.persisting.search.client;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.api.BooleanResult;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.search.*;
import io.fluxzero.sdk.common.websocket.AbstractWebsocketClient;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.persisting.search.SearchHit;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * WebSocket-based implementation of the {@link SearchClient} that connects to the Fluxzero Runtime.
 * <p>
 * All operations (indexing, searching, deletion, statistics, etc.) are executed via a WebSocket protocol
 * using a standardized API. This is the default production implementation used in deployed applications.
 * <p>
 * Requires an active connection to the Fluxzero Runtime's search module.
 *
 * @see WebSocketClient
 */
public class WebSocketSearchClient extends AbstractWebsocketClient implements SearchClient {

    public WebSocketSearchClient(String endPointUrl, WebSocketClient client) {
        this(URI.create(endPointUrl), client);
    }

    public WebSocketSearchClient(URI endpointUri, WebSocketClient client) {
        this(endpointUri, client, true);
    }

    public WebSocketSearchClient(URI endpointUri, WebSocketClient client, boolean sendMetrics) {
        super(endpointUri, client, sendMetrics, client.getClientConfig().getSearchSessions());
    }

    @Override
    public List<SearchCollection> getSearchCollections() {
        return this.<GetSearchCollectionsResult>sendAndWait(new GetSearchCollections()).getSearchCollections();
    }

    @Override
    public CompletableFuture<Void> index(List<SerializedDocument> documents, Guarantee guarantee, boolean ifNotExists) {
        return sendCommand(new IndexDocuments(documents, ifNotExists, guarantee));
    }

    @Override
    public CompletableFuture<Void> bulkUpdate(Collection<DocumentUpdate> batch, Guarantee guarantee) {
        return sendCommand(new BulkUpdateDocuments(batch, guarantee));
    }

    @Override
    public Stream<SearchHit<SerializedDocument>> search(SearchDocuments searchDocuments, int fetchSize) {
        return search(searchDocuments, fetchSize, request -> request);
    }

    @Override
    public CompletableFuture<List<SearchHit<SerializedDocument>>> searchAsync(SearchDocuments searchDocuments,
                                                                              int fetchSize) {
        return searchAsync(searchDocuments, fetchSize, request -> request);
    }

    @Override
    public Stream<SearchHit<SerializedDocument>> searchModels(
            SearchModelDocuments searchDocuments,
            int fetchSize) {
        return search(
                searchDocuments.getSearch(), fetchSize,
                request -> new SearchModelDocuments(request, searchDocuments.getRelations()));
    }

    @Override
    public CompletableFuture<List<SearchHit<SerializedDocument>>>
    searchModelsAsync(
            SearchModelDocuments searchDocuments,
            int fetchSize) {
        return searchAsync(
                searchDocuments.getSearch(), fetchSize,
                request -> new SearchModelDocuments(request, searchDocuments.getRelations()));
    }

    @Override
    public Stream<SearchHit<SerializedDocument>> searchModelGraph(
            SearchModelGraphDocuments searchDocuments,
            int fetchSize) {
        return search(
                searchDocuments.getSearch(), fetchSize,
                request -> graphRequest(searchDocuments, request));
    }

    @Override
    public CompletableFuture<List<SearchHit<SerializedDocument>>>
    searchModelGraphAsync(
            SearchModelGraphDocuments searchDocuments,
            int fetchSize) {
        return searchAsync(
                searchDocuments.getSearch(), fetchSize,
                request -> graphRequest(searchDocuments, request));
    }

    private static SearchModelGraphDocuments graphRequest(
            SearchModelGraphDocuments template,
            SearchDocuments search) {
        return new SearchModelGraphDocuments(
                search, template.getRelations(),
                template.getComposition(),
                template.getPathOverrides());
    }

    private Stream<SearchHit<SerializedDocument>> search(
            SearchDocuments searchDocuments,
            int fetchSize,
            Function<SearchDocuments, ? extends Request> requestFactory) {
        AtomicInteger count = new AtomicInteger();
        Integer maxSize = searchDocuments.getMaxSize();
        int maxFetchSize = maxSize == null ? fetchSize : Math.min(maxSize, fetchSize);
        SearchDocuments request = searchDocuments.toBuilder().maxSize(maxFetchSize).build();
        Stream<SerializedDocument> documents = ObjectUtils.<SearchDocumentsResult>iterate(
                        sendAndWait(requestFactory.apply(request)),
                        result -> sendAndWait(requestFactory.apply(
                                request.toBuilder()
                                        .maxSize(maxSize == null
                                                         ? maxFetchSize
                                                         : Math.min(maxSize - count.get(), maxFetchSize))
                                        .lastHit(result.lastMatch())
                                        .build())),
                        result -> result.size() < maxFetchSize
                                  || maxSize != null
                                     && count.addAndGet(result.size()) >= maxSize)
                .flatMap(result -> result.getMatches().stream());
        if (maxSize != null) {
            documents = documents.limit(maxSize);
        }
        return documents.map(SearchHit::fromDocument);
    }

    private CompletableFuture<List<SearchHit<SerializedDocument>>> searchAsync(
            SearchDocuments searchDocuments,
            int fetchSize,
            Function<SearchDocuments, ? extends Request> requestFactory) {
        Integer maxSize = searchDocuments.getMaxSize();
        int maxFetchSize = maxSize == null ? fetchSize : Math.min(maxSize, fetchSize);
        SearchDocuments request = searchDocuments.toBuilder().maxSize(maxFetchSize).build();
        return searchAsync(request, maxSize, maxFetchSize, new ArrayList<>(), requestFactory);
    }

    private CompletableFuture<List<SearchHit<SerializedDocument>>> searchAsync(
            SearchDocuments request,
            Integer maxSize,
            int maxFetchSize,
            List<SearchHit<SerializedDocument>> hits,
            Function<SearchDocuments, ? extends Request> requestFactory) {
        return this.<SearchDocumentsResult>send(requestFactory.apply(request)).thenCompose(result -> {
            result.getMatches().stream().map(SearchHit::fromDocument).forEach(hits::add);
            if (result.size() < maxFetchSize || (maxSize != null && hits.size() >= maxSize)) {
                return CompletableFuture.completedFuture(maxSize == null || hits.size() <= maxSize
                        ? hits : hits.subList(0, maxSize));
            }
            int nextMaxSize = maxSize == null ? maxFetchSize : Math.min(maxSize - hits.size(), maxFetchSize);
            if (nextMaxSize <= 0) {
                return CompletableFuture.completedFuture(hits);
            }
            return searchAsync(request.toBuilder().maxSize(nextMaxSize).lastHit(result.lastMatch()).build(),
                               maxSize, maxFetchSize, hits, requestFactory);
        });
    }

    @Override
    public boolean documentExists(HasDocument request) {
        return this.<BooleanResult>sendAndWait(request).isSuccess();
    }

    @Override
    public Optional<SerializedDocument> fetch(GetDocument request) {
        return Optional.ofNullable(this.<GetDocumentResult>sendAndWait(request).getDocument());
    }

    @Override
    public Collection<SerializedDocument> fetch(GetDocuments request) {
        return this.<GetDocumentsResult>sendAndWait(request).getDocuments();
    }

    @Override
    public List<DocumentStats> fetchStatistics(SearchQuery query, List<String> fields, List<String> groupBy) {
        GetDocumentStatsResult result = sendAndWait(new GetDocumentStats(query, fields, groupBy));
        return result.getDocumentStats();
    }

    @Override
    public CompletableFuture<List<DocumentStats>> fetchStatisticsAsync(SearchQuery query, List<String> fields,
                                                                       List<String> groupBy) {
        return this.<GetDocumentStatsResult>send(new GetDocumentStats(query, fields, groupBy))
                .thenApply(GetDocumentStatsResult::getDocumentStats);
    }

    @Override
    public SearchHistogram fetchHistogram(GetSearchHistogram request) {
        GetSearchHistogramResult result = sendAndWait(request);
        return result.getHistogram();
    }

    @Override
    public CompletableFuture<SearchHistogram> fetchHistogramAsync(GetSearchHistogram request) {
        return this.<GetSearchHistogramResult>send(request).thenApply(GetSearchHistogramResult::getHistogram);
    }

    @Override
    public List<FacetStats> fetchFacetStats(SearchQuery query) {
        GetFacetStatsResult result = sendAndWait(new GetFacetStats(query));
        return result.getStats();
    }

    @Override
    public CompletableFuture<List<FacetStats>> fetchFacetStatsAsync(SearchQuery query) {
        return this.<GetFacetStatsResult>send(new GetFacetStats(query)).thenApply(GetFacetStatsResult::getStats);
    }

    @Override
    public CompletableFuture<Void> delete(SearchQuery query, Guarantee guarantee, int batchSize) {
        return sendCommand(new DeleteDocuments(query, guarantee, batchSize));
    }

    @Override
    public CompletableFuture<Void> move(SearchQuery query, String targetCollection, Guarantee guarantee) {
        return sendCommand(new MoveDocuments(query, targetCollection, guarantee));
    }

    @Override
    public CompletableFuture<Void> delete(String documentId, String collection, Guarantee guarantee) {
        return sendCommand(new DeleteDocumentById(collection, documentId, guarantee));
    }

    @Override
    public CompletableFuture<Void> move(String documentId, String collection, String targetCollection,
                                        Guarantee guarantee) {
        return sendCommand(new MoveDocumentById(collection, documentId, targetCollection, guarantee));
    }

    @Override
    public CompletableFuture<Void> deleteCollection(String collection, Guarantee guarantee) {
        return sendCommand(new DeleteCollection(collection, guarantee));
    }

    @Override
    public CompletableFuture<Void> createAuditTrail(CreateAuditTrail request) {
        return sendCommand(request);
    }
}
