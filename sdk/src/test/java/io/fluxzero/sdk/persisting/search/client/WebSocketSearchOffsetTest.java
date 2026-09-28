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
package io.fluxzero.sdk.persisting.search.client;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Request;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchDocumentsResult;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.persisting.search.SearchHit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketSearchOffsetTest {
    @ParameterizedTest(name = "async={0}, skip={1}, fetchSize={2}, maxSize={3}")
    @MethodSource("pages")
    void appliesOffsetOnlyBeforeTheFirstPage(boolean async, int skip, int fetchSize, Integer maxSize) {
        WebSocketClient owner = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost").name("pagination-test").disableMetrics(true).build());
        try (CursorClient client = new CursorClient(owner)) {
            SearchDocuments search = SearchDocuments.builder()
                    .query(SearchQuery.builder().collection("orders").build())
                    .sorting(List.of("timestamp")).skip(skip).maxSize(maxSize).build();
            List<SearchHit<SerializedDocument>> hits;
            if (async) {
                hits = client.searchAsync(search, fetchSize).join();
            } else {
                try (var results = client.search(search, fetchSize)) { hits = results.toList(); }
            }
            assertEquals(client.documents.stream().skip(skip).limit(maxSize == null ? Long.MAX_VALUE : maxSize)
                                 .map(SerializedDocument::getId).toList(), hits.stream().map(SearchHit::getId).toList());
            assertEquals(skip, client.requests.getFirst().getSkip());
            assertEquals(search.getLastHit(), client.requests.getFirst().getLastHit());
            client.requests.stream().skip(1).forEach(request -> assertEquals(0, request.getSkip()));
            client.requests.forEach(request -> {
                assertEquals(search.getQuery(), request.getQuery());
                assertEquals(search.getSorting(), request.getSorting());
                assertEquals(search.getPathFilters(), request.getPathFilters());
            });
        } finally {
            owner.shutDown();
        }
    }

    static Stream<Arguments> pages() {
        return Stream.of(false, true).flatMap(async -> IntStream.of(0, 1, 3).boxed().flatMap(skip ->
                IntStream.of(1, 3).boxed().flatMap(fetchSize -> Stream.of(null, 4, 20).map(maxSize ->
                        Arguments.of(async, skip, fetchSize, maxSize)))));
    }

    /** Simulates the database's cursor-then-offset semantics, without transport or timing dependencies. */
    private static final class CursorClient extends WebSocketSearchClient {
        private final List<SerializedDocument> documents = IntStream.range(0, 9)
                .mapToObj(i -> new SerializedDocument("order-" + i, (long) i, null, "orders",
                        new Data<>(new byte[0], "Order", 0, "application/json"), null, Set.of(), Set.of())).toList();
        private final List<SearchDocuments> requests = new ArrayList<>();

        private CursorClient(WebSocketClient owner) { super(URI.create("ws://localhost/search"), owner, false); }

        @Override
        @SuppressWarnings("unchecked")
        protected <R extends RequestResult> CompletableFuture<R> send(Request request) {
            SearchDocuments search = (SearchDocuments) request;
            requests.add(search);
            List<SerializedDocument> selected = documents.stream()
                    .filter(document -> search.getLastHit() == null
                            || document.getTimestamp() > search.getLastHit().getTimestamp())
                    .skip(search.getSkip()).limit(search.getMaxSize()).toList();
            return CompletableFuture.completedFuture((R) new SearchDocumentsResult(request.getRequestId(), selected));
        }
    }
}
