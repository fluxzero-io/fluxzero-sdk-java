/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.persisting.search.client;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.search.ModelGraphComposition;
import io.fluxzero.common.api.search.ModelRelationConstraint;
import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchDocumentsResult;
import io.fluxzero.common.api.search.SearchModelDocuments;
import io.fluxzero.common.api.search.SearchModelGraphDocuments;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.persisting.search.SearchHit;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebSocketSearchClientPaginationTest {

    @Test
    void synchronousPaginationIsIdenticalForEverySearchProtocol() {
        assertSynchronousPagination(
                SearchDocuments.class,
                (client, search) -> client.search(search, 2).toList(),
                request -> (SearchDocuments) request);
        assertSynchronousPagination(
                SearchModelDocuments.class,
                (client, search) -> client.searchModels(
                        new SearchModelDocuments(search, List.of(relation())), 2).toList(),
                request -> ((SearchModelDocuments) request).getSearch());
        assertSynchronousPagination(
                SearchModelGraphDocuments.class,
                (client, search) -> client.searchModelGraph(
                        new SearchModelGraphDocuments(
                                search, List.of(), ModelGraphComposition.builder().build()),
                        2).toList(),
                request -> ((SearchModelGraphDocuments) request).getSearch());
    }

    @Test
    void asynchronousPaginationIsIdenticalForEverySearchProtocol() {
        assertAsynchronousPagination(
                SearchDocuments.class,
                (client, search) -> client.searchAsync(search, 2).join(),
                request -> (SearchDocuments) request);
        assertAsynchronousPagination(
                SearchModelDocuments.class,
                (client, search) -> client.searchModelsAsync(
                        new SearchModelDocuments(search, List.of(relation())), 2).join(),
                request -> ((SearchModelDocuments) request).getSearch());
        assertAsynchronousPagination(
                SearchModelGraphDocuments.class,
                (client, search) -> client.searchModelGraphAsync(
                        new SearchModelGraphDocuments(
                                search, List.of(), ModelGraphComposition.builder().build()),
                        2).join(),
                request -> ((SearchModelGraphDocuments) request).getSearch());
    }

    private static void assertSynchronousPagination(
            Class<? extends Request> requestType,
            SearchInvocation invocation,
            Function<Request, SearchDocuments> searchExtractor) {
        try (StubClient client = new StubClient()) {
            List<SearchHit<SerializedDocument>> hits =
                    invocation.invoke(client, search());
            assertPagination(client, requestType, searchExtractor, hits);
        }
    }

    private static void assertAsynchronousPagination(
            Class<? extends Request> requestType,
            SearchInvocation invocation,
            Function<Request, SearchDocuments> searchExtractor) {
        try (StubClient client = new StubClient()) {
            List<SearchHit<SerializedDocument>> hits =
                    invocation.invoke(client, search());
            assertPagination(client, requestType, searchExtractor, hits);
        }
    }

    private static void assertPagination(
            StubClient client,
            Class<? extends Request> requestType,
            Function<Request, SearchDocuments> searchExtractor,
            List<SearchHit<SerializedDocument>> hits) {
        assertEquals(List.of("1", "2", "3", "4", "5"),
                     hits.stream().map(SearchHit::getId).toList());
        assertEquals(3, client.requests.size());
        client.requests.forEach(request -> assertInstanceOf(requestType, request));
        List<SearchDocuments> pages = client.requests.stream().map(searchExtractor).toList();
        assertEquals(List.of(2, 2, 1), pages.stream().map(SearchDocuments::getMaxSize).toList());
        assertNull(pages.getFirst().getLastHit());
        assertEquals("2", pages.get(1).getLastHit().getId());
        assertEquals("4", pages.get(2).getLastHit().getId());
    }

    private static ModelRelationConstraint relation() {
        return ModelRelationConstraint.builder()
                .direction(ModelRelationConstraint.Direction.ANCESTOR)
                .query(SearchQuery.builder().collection("parents").build())
                .build();
    }

    private static SearchDocuments search() {
        return SearchDocuments.builder()
                .query(SearchQuery.builder().collection("orders").build())
                .maxSize(5)
                .build();
    }

    private static SerializedDocument document(String id) {
        return new SerializedDocument(
                id, null, null, "orders",
                new Data<>(new byte[0], "Order", 0, "application/json"),
                null, Collections.emptySet(), Collections.emptySet());
    }

    @FunctionalInterface
    private interface SearchInvocation {
        List<SearchHit<SerializedDocument>> invoke(StubClient client, SearchDocuments search);
    }

    private static final class StubClient extends WebSocketSearchClient {
        private final Deque<SearchDocumentsResult> results = new ArrayDeque<>();
        private final List<Request> requests = new ArrayList<>();

        private StubClient() {
            super(
                    URI.create("ws://localhost/search"),
                    WebSocketClient.newInstance(
                            WebSocketClient.ClientConfig.builder()
                                    .runtimeBaseUrl("ws://localhost")
                                    .name("pagination-test")
                                    .build()),
                    false);
            results.add(new SearchDocumentsResult(1L, List.of(document("1"), document("2"))));
            results.add(new SearchDocumentsResult(2L, List.of(document("3"), document("4"))));
            results.add(new SearchDocumentsResult(3L, List.of(document("5"))));
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <R extends RequestResult> CompletableFuture<R> send(Request request) {
            requests.add(request);
            return CompletableFuture.completedFuture((R) results.removeFirst());
        }
    }
}
