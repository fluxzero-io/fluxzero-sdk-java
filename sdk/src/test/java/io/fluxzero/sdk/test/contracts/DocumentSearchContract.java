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
package io.fluxzero.sdk.test.contracts;

import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.search.SearchExclude;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.persisting.search.SearchHit;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.fluxzero.common.api.search.constraints.AnyConstraint.any;
import static io.fluxzero.common.api.search.constraints.NotConstraint.not;
import static io.fluxzero.common.api.search.constraints.ContainsConstraint.contains;
import static io.fluxzero.common.api.search.constraints.ExistsConstraint.exists;
import static io.fluxzero.common.api.search.constraints.LookAheadConstraint.lookAhead;
import static io.fluxzero.common.api.search.constraints.MatchConstraint.match;
import static io.fluxzero.common.api.search.constraints.QueryConstraint.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ordinary document search contract, intentionally independent of Model/Graph APIs. */
@Timeout(30)
public abstract class DocumentSearchContract {
    protected abstract Client client(String namespace);

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void keepsSearchExcludedContentOutOfFiltering(boolean async) {
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(client("search-contract-" + UUID.randomUUID()))) {
            app.apply(fc -> {
                var store = fc.documentStore();
                var value = new SearchDocument("visiblevalue", "hiddenvalue");
                store.index(value, "one", "documents").join();
                for (var constraint : List.of(match("hiddenvalue"), match("hiddenvalue", "secret"),
                        lookAhead("hiddenvalue"), lookAhead("hiddenvalue", "secret"),
                        lookAhead("hi"), lookAhead("hi", "secret"),
                        contains("idden", true, true), contains("idden", true, true, "secret"),
                        query("hiddenvalue"), query("hiddenvalue", "secret"))) {
                    var search = store.search("documents").constraint(constraint);
                    var found = async ? search.fetchAsync(10, SearchDocument.class).join()
                            : search.fetchAll(SearchDocument.class);
                    assertTrue(found.isEmpty(), constraint::toString);
                }
                var search = store.search("documents").match("visiblevalue", "name");
                assertEquals(List.of(value), async ? search.fetchAsync(10, SearchDocument.class).join()
                        : search.fetchAll(SearchDocument.class));
                return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void excludesPathsEvenWhenSearchableValuesOverlap(boolean async) {
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(client("search-contract-" + UUID.randomUUID()))) {
            app.apply(fc -> {
                var store = fc.documentStore();
                var value = new SearchDocument("sharedvalue", "sharedvalue");
                store.index(value, "one", "documents").join();
                for (var constraint : List.of(match("sharedvalue", "secret"),
                        match("sharedvalue", true, "secret"), contains("sharedvalue", "secret"),
                        lookAhead("sh", "secret"), contains("ared", true, true, "secret"),
                        lookAhead("shared", "secret"), query("sharedvalue", "secret"),
                        any(match("sharedvalue", "secret"), match("absent", "name")))) {
                    var search = store.search("documents").constraint(constraint);
                    assertTrue((async ? search.fetchAsync(10).join() : search.fetchAll()).isEmpty(),
                               constraint::toString);
                }
                for (var constraint : List.of(match("sharedvalue"), match("sharedvalue", "name"),
                        exists("secret"), not(match("sharedvalue", "secret")))) {
                    var search = store.search("documents").constraint(constraint);
                    assertEquals(List.of(value), async ? search.fetchAsync(10, SearchDocument.class).join()
                            : search.fetchAll(SearchDocument.class));
                }
                return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pagesOrdinaryDocumentsWithAnInitialOffset(boolean async) {
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(client("search-contract-" + UUID.randomUUID()))) {
            app.apply(fc -> {
                for (int i = 0; i < 9; i++) {
                    fc.documentStore().index(new SearchDocument("value-" + i, "hiddenvalue"),
                            "order-" + i, "documents", Instant.ofEpochSecond(i)).join();
                }
                var search = SearchDocuments.builder()
                        .query(SearchQuery.builder().collection("documents").build())
                        .sorting(List.of("timestamp")).skip(1).maxSize(5).build();
                var searchClient = fc.client().getSearchClient();
                assertEquals(IntStream.range(1, 6).mapToObj(i -> "order-" + i).toList(),
                        ids(searchClient, search, async));
                SerializedDocument cursor;
                try (var hits = searchClient.search(search.toBuilder().skip(0).maxSize(2).build(), 2)) {
                    cursor = hits.toList().getLast().getValue();
                }
                var resumed = search.toBuilder().lastHit(cursor).skip(2).maxSize(3).build();
                assertEquals(List.of("order-4", "order-5", "order-6"), ids(searchClient, resumed, async));
                return null;
            });
        }
    }

    private static List<String> ids(SearchClient client, SearchDocuments search, boolean async) {
        if (async) { return client.searchAsync(search, 2).join().stream().map(SearchHit::getId).toList(); }
        try (var hits = client.search(search, 2)) { return hits.map(SearchHit::getId).toList(); }
    }

    public record SearchDocument(String name, @SearchExclude String secret) {}
}
