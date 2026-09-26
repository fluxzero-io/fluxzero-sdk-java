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

package io.fluxzero.sdk.search;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.persisting.search.Search;
import io.fluxzero.sdk.persisting.search.Searchable;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeterogeneousSearchTest {

    // Keep the processing lambdas: their inferred parameter type exposed the erroneous first-class cast.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void processesDocumentsFromDifferentClasses(boolean async) {
        (async ? TestFixture.createAsync() : TestFixture.create())
                .givenDocument(new Alpha("alpha"))
                .givenDocument(new Beta("beta"))
                .whenExecuting(fc -> assertEquals(List.of("Alpha", "Beta"),
                        Fluxzero.search(Alpha.class, Beta.class).fetchAll().stream()
                                .map(value -> describe(value)).sorted().toList()))
                .expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesHeterogeneousResultsAcrossTerminals(boolean async) {
        (async ? TestFixture.createAsync() : TestFixture.create())
                .givenDocument(new Alpha("alpha"))
                .givenDocument(new Beta("beta"))
                .whenExecuting(fc -> {
                    var expected = List.of("Alpha", "Beta");
                    assertEquals(expected, Fluxzero.search(Beta.class, Alpha.class).fetchAll().stream()
                            .map(value -> describe(value)).sorted().toList());
                    assertEquals(expected, Fluxzero.search(Alpha.class, Beta.class).fetch(10).stream()
                            .map(value -> describe(value)).sorted().toList());
                    assertEquals(expected, Fluxzero.search(Alpha.class, Beta.class).fetchAsync(10).join().stream()
                            .map(value -> describe(value)).sorted().toList());
                    assertEquals(expected, Fluxzero.search(Alpha.class, Beta.class).stream(1)
                            .map(value -> describe(value)).sorted().toList());
                    assertEquals(expected, Fluxzero.search(Alpha.class, Beta.class).streamHits(1)
                            .map(hit -> hit.getValue()).map(value -> describe(value)).sorted().toList());
                    assertEquals("Beta", Fluxzero.search(Alpha.class, Beta.class).match("beta", "id")
                            .fetchFirst().map(value -> describe(value)).orElseThrow());
                    assertEquals("Beta", describe(Fluxzero.search(Alpha.class, Beta.class).match("beta", "id")
                            .fetchFirstOrNull()));
                    assertEquals(expected, Fluxzero.search(Alpha.class, "beta").fetchAll().stream()
                            .map(value -> describe(value)).sorted().toList());
                    assertEquals(expected, Fluxzero.search("alpha", Beta.class).fetchAll().stream()
                            .map(value -> describe(value)).sorted().toList());
                    assertEquals(expected, Fluxzero.search("alpha", "beta").fetchAll().stream()
                            .map(value -> describe(value)).sorted().toList());
                    assertEquals(expected, Fluxzero.search(List.of(Alpha.class, Beta.class)).fetchAll().stream()
                            .map(value -> describe(value)).sorted().toList());
                    assertEquals(expected, fc.documentStore().search(List.of(Alpha.class, Beta.class))
                            .fetchAll().stream().map(value -> describe(value)).sorted().toList());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesTypedSearchAndExplicitResults(boolean async) {
        (async ? TestFixture.createAsync() : TestFixture.create())
                .givenDocument(new Alpha("alpha"))
                .givenDocument(new Beta("beta"))
                .whenExecuting(fc -> {
                    List<Alpha> values = Fluxzero.search(Alpha.class).match("alpha", "id").fetchAll();
                    assertEquals(List.of(new Alpha("alpha")), values);
                    assertEquals("alpha", Fluxzero.search(Alpha.class).fetchFirstOrNull().id());
                    assertEquals("alpha", fc.documentStore().search(Alpha.class).fetchFirstOrNull().id());
                    assertEquals(List.of("Alpha"), Fluxzero.search(Alpha.class, new Object[0]).fetchAll().stream()
                            .map(value -> describe(value)).toList());
                    Search<Alpha> explicitlyTyped = Fluxzero.<Alpha>search(Alpha.class, "archive");
                    assertEquals(List.of("alpha"), explicitlyTyped.fetchAsync(10).join().stream()
                            .map(Alpha::id).toList());
                    Search<Alpha> targetTyped = Fluxzero.search(Alpha.class, "archive");
                    assertEquals(List.of("alpha"), targetTyped.stream().map(Alpha::id).toList());
                    assertEquals(List.of("Alpha", "Beta"), Fluxzero.search(Alpha.class, Beta.class)
                            .fetchAll(Object.class).stream().map(value -> describe(value)).sorted().toList());
                    assertEquals(List.of("alpha"), Fluxzero.search("alpha", "archive")
                            .fetchAll(Alpha.class).stream().map(Alpha::id).toList());
                }).expectSuccessfulResult().expectNoErrors();
    }

    private static String describe(Object value) {
        return value.getClass().getSimpleName();
    }

    @Searchable(collection = "alpha")
    record Alpha(@EntityId String id) { }

    @Searchable(collection = "beta")
    record Beta(@EntityId String id) { }
}
