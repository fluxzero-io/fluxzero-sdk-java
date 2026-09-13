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

package io.fluxzero.sdk.modeling;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fluxzero.common.search.SearchExclude;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static io.fluxzero.common.api.search.constraints.MatchConstraint.match;
import static io.fluxzero.sdk.modeling.ModelPersistence.DOCUMENT;
import static io.fluxzero.sdk.modeling.ModelPersistence.EVENT_SOURCED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executable examples for the Model query decision guide; all writes use normal Model commands. */
class ModelQueryContractTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pathlessRelationshipsAllowNavigationButDoNotCreateSearchDocuments(boolean async) {
        fixture(async).whenExecuting(ignored -> {
            assertEquals(new PlainChild("plain-child", "plain-root", "open"),
                         Fluxzero.loadModel("plain-child", PlainChild.class).get());
            assertEquals(1, Fluxzero.loadGraph("plain-root", PlainRoot.class)
                    .children(PlainChild.class).size());
            assertFalse(Fluxzero.hasDocument("plain-child", PlainChild.class));
            assertTrue(Fluxzero.search(PlainChild.class).fetchAll().isEmpty());
            assertThrows(IllegalArgumentException.class,
                         () -> Fluxzero.search(PlainChild.class).whereParent("plain-root", PlainRoot.class));
            assertThrows(IllegalArgumentException.class, () -> Fluxzero.searchGraph(PlainRoot.class));
        }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compositionPathEnablesScopedContentSearchWithoutDocumentPersistence(boolean async) {
        fixture(async).whenExecuting(ignored -> {
            assertTrue(Fluxzero.search(Component.class).fetchAll().isEmpty());
            var expected = List.of(new Component("component", "plain-root", "open"));
            assertEquals(expected, Fluxzero.search(Component.class)
                    .whereParent(new PlainRootId("plain-root")).match("open", "status").fetchAll());
            assertEquals(expected, Fluxzero.search(Component.class)
                    .whereAncestor("plain-root", PlainRoot.class).match("open", "status").fetchAsync(10).join());
            assertThrows(IllegalArgumentException.class,
                         () -> Fluxzero.search(Component.class).whereParent(PlainRoot.class, match("root", "label")));
            assertThrows(IllegalArgumentException.class,
                         () -> Fluxzero.search(PlainRoot.class).whereChild(Component.class, match("open", "status")));
            // A component can itself be the root of a live document-composed Graph search.
            assertEquals("component", Fluxzero.searchGraph(Component.class).match("open", "status")
                    .fetchAll().getFirst().get().id());
        }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void documentPersistenceEnablesDirectAndScopedSearchButDoesNotInventACompositionPath(boolean async) {
        fixture(async).whenExecuting(ignored -> {
            var expected = List.of(new DirectChild("direct-child", "document-root", "open"));
            assertEquals(expected, Fluxzero.search(DirectChild.class).match("open", "status").fetchAll());
            assertEquals(expected, Fluxzero.search(DirectChild.class)
                    .whereParent("document-root", DocumentRoot.class).fetchAll());
            assertEquals(expected, Fluxzero.search(DirectChild.class)
                    .whereAncestor(DocumentRoot.class, match("root", "label")).fetchAll());
            assertEquals(List.of(new DocumentRoot("document-root", "root")),
                         Fluxzero.search(DocumentRoot.class)
                                 .whereChild(DirectChild.class, match("open", "status")).fetchAll());
            assertEquals(1, Fluxzero.loadGraph("document-root", DocumentRoot.class)
                    .children(DirectChild.class).size());
            assertTrue(Fluxzero.searchGraph(DocumentRoot.class).fetchAll().getFirst()
                               .children(DirectChild.class).isEmpty());
            assertEquals("document-only", Fluxzero.loadModel("document-only", DocumentOnly.class).get().id());
            assertEquals(1, Fluxzero.search(DocumentOnly.class).match("open", "status").fetchAll().size());
        }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void referenceOnlyDocumentsRemainRelationLoadableWithoutContentIndexes(boolean async) {
        fixture(async).whenExecuting(ignored -> {
            var expected = List.of(new ReferenceChild("reference-child", "document-root", "open"));
            assertTrue(Fluxzero.hasDocument("reference-child", ReferenceChild.class));
            assertTrue(Fluxzero.search(ReferenceChild.class).fetchAll().isEmpty());
            assertEquals(expected, Fluxzero.search(ReferenceChild.class)
                    .whereParent("document-root", DocumentRoot.class).fetchAll());
            assertTrue(Fluxzero.search(ReferenceChild.class)
                               .whereParent("document-root", DocumentRoot.class)
                               .match("open", "status").fetchAll().isEmpty());
            assertTrue(Fluxzero.search(DocumentRoot.class)
                               .whereChild(ReferenceChild.class, match("open", "status")).fetchAll().isEmpty());
            assertEquals(expected.getFirst(), Fluxzero.loadCurrentModelState("reference-child", ReferenceChild.class)
                    .value());
        }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void graphParticipationRetainsContentIndexesForReferenceOnlyDocuments(boolean async) {
        fixture(async).whenExecuting(ignored -> {
            assertTrue(Fluxzero.search(ReferenceComponent.class).fetchAll().isEmpty());
            assertEquals(1, Fluxzero.search(ReferenceComponent.class)
                    .whereParent("document-root", DocumentRoot.class).match("open", "status").fetchAll().size());
            assertEquals(1, Fluxzero.search(DocumentRoot.class)
                    .whereChild(ReferenceComponent.class, match("open", "status")).fetchAll().size());
            assertEquals(1, Fluxzero.searchGraph(DocumentRoot.class)
                    .match("open", "references/status").fetchAll().size());
        }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void materializationNeedsNoPublicRootDocumentAndSupportsWholeGraphFilters(boolean async) {
        fixture(async).whenExecuting(ignored -> {
            assertTrue(Fluxzero.search(MaterializedRoot.class).fetchAll().isEmpty());
            for (boolean forceLive : List.of(false, true)) {
                List<Graph<MaterializedRoot>> graphs = Fluxzero.searchGraph(MaterializedRoot.class, forceLive)
                        .match("open", "children/status").fetchAll();
                assertEquals(1, graphs.size());
                assertEquals(new MaterializedRoot("materialized-root"), graphs.getFirst().get());
                // The predicate selects roots; it does not prune the non-matching child.
                assertEquals(2, graphs.getFirst().children(MaterializedChild.class).size());
                assertEquals(1, Fluxzero.searchGraph(MaterializedRoot.class, forceLive)
                        .whereDescendant(MaterializedChild.class, match("open", "status"))
                        .fetchAsync(10).join().size());
                assertThrows(IllegalStateException.class,
                             () -> Fluxzero.searchGraph(MaterializedRoot.class, forceLive)
                                     .includeOnly("children").fetchAll());
                ObjectNode json = Fluxzero.searchGraph(MaterializedRoot.class, forceLive)
                        .includeOnly("children").fetch(1, ObjectNode.class).getFirst();
                assertEquals(2, json.get("children").size());
                assertFalse(json.has("id"));
            }
        }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneRelatedPredicateCorrelatesFieldsOnOneChildWhileSeparatePredicatesMayMatchDifferentChildren(boolean async) {
        fixture(async).whenExecuting(ignored -> {
            for (boolean forceLive : List.of(false, true)) {
                assertTrue(Fluxzero.searchGraph(MaterializedRoot.class, forceLive)
                                   .whereChild(MaterializedChild.class, match("open", "status"), match("b", "label"))
                                   .fetchAll().isEmpty());
                assertEquals(1, Fluxzero.searchGraph(MaterializedRoot.class, forceLive)
                        .whereChild(MaterializedChild.class, match("open", "status"))
                        .whereChild(MaterializedChild.class, match("b", "label")).fetchAll().size());
            }
        }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void relationAndLiveGraphSearchRejectUnsupportedStatistics(boolean async) {
        fixture(async).whenExecuting(ignored -> {
            assertThrows(UnsupportedOperationException.class, () -> Fluxzero.search(Component.class)
                    .whereParent("plain-root", PlainRoot.class).facetStats());
            assertThrows(UnsupportedOperationException.class, () -> Fluxzero.search(Component.class)
                    .whereParent("plain-root", PlainRoot.class).count());
            assertThrows(UnsupportedOperationException.class,
                         () -> Fluxzero.searchGraph(MaterializedRoot.class, true).count());
            assertThrows(UnsupportedOperationException.class,
                         () -> Fluxzero.searchGraph(MaterializedRoot.class, true).groupBy("id"));
            // An ordinary materialized projection query has no live composition step.
            Fluxzero.searchGraph(MaterializedRoot.class).facetStats();
            assertEquals(1L, Fluxzero.searchGraph(MaterializedRoot.class).count());
        }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void liveEntryFiltersCanInspectValuesExcludedFromTheStoredTextSummary(boolean async) {
        fixture(async).whenExecuting(ignored -> {
            assertTrue(Fluxzero.searchGraph(MaterializedRoot.class)
                               .match("hiddenvalue", "children/secret").fetchAll().isEmpty());
            assertEquals(1, Fluxzero.searchGraph(MaterializedRoot.class, true)
                    .match("hiddenvalue", "children/secret").fetchAll().size());
            assertTrue(Fluxzero.searchGraph(MaterializedRoot.class, true)
                               .whereChild(MaterializedChild.class, match("hiddenvalue", "secret"))
                               .fetchAll().isEmpty());
        }).expectNoErrors();
    }

    private TestFixture fixture(boolean async) {
        TestFixture fixture = TestFixture.create(DefaultFluxzero.builder()
                .configureGraphProjectionCompletion(GraphProjectionCompletion.AWAIT));
        return (async ? fixture.async() : fixture).givenCommands(
                new CreatePlainRoot("plain-root"), new CreatePlainChild("plain-child"),
                new CreateComponent("component"), new CreateDocumentRoot("document-root"),
                new CreateDirectChild("direct-child"), new CreateDocumentOnly("document-only"),
                new CreateReferenceChild("reference-child"), new CreateReferenceComponent("reference-component"),
                new CreateMaterializedRoot("materialized-root"),
                new CreateMaterializedChild("materialized-a", "open", "a"),
                new CreateMaterializedChild("materialized-b", "closed", "b"));
    }

    @Model
    record PlainRoot(@EntityId String id, String label) {}

    static class PlainRootId extends Id<PlainRoot> {
        PlainRootId(String id) { super(id); }
    }

    @Model
    record PlainChild(@EntityId String id, @Parent(PlainRoot.class) String rootId, String status) {}

    @Model
    record Component(@EntityId String id,
                     @Parent(value = PlainRoot.class, pathInParent = "components") String rootId,
                     String status) {}

    @Model(persistence = {EVENT_SOURCED, DOCUMENT})
    record DocumentRoot(@EntityId String id, String label) {}

    @Model(persistence = {EVENT_SOURCED, DOCUMENT})
    record DirectChild(@EntityId String id, @Parent(DocumentRoot.class) String rootId, String status) {}

    @Model(persistence = DOCUMENT)
    record DocumentOnly(@EntityId String id, String status) {}

    @Model(persistence = DOCUMENT, document = @DocumentProjection(searchable = false))
    record ReferenceChild(@EntityId String id, @Parent(DocumentRoot.class) String rootId, String status) {}

    @Model(persistence = DOCUMENT, document = @DocumentProjection(searchable = false))
    record ReferenceComponent(@EntityId String id,
                              @Parent(value = DocumentRoot.class, pathInParent = "references") String rootId,
                              String status) {}

    @Model(materializeGraph = true)
    record MaterializedRoot(@EntityId String id) {}

    @Model
    record MaterializedChild(@EntityId String id,
                             @Parent(value = MaterializedRoot.class, pathInParent = "children") String rootId,
                             String status, String label, @SearchExclude String secret) {}

    record CreatePlainRoot(String id) {
        @Apply PlainRoot apply() { return new PlainRoot(id, "root"); }
    }
    record CreatePlainChild(String id) {
        @Apply PlainChild apply() { return new PlainChild(id, "plain-root", "open"); }
    }
    record CreateComponent(String id) {
        @Apply Component apply() { return new Component(id, "plain-root", "open"); }
    }
    record CreateDocumentRoot(String id) {
        @Apply DocumentRoot apply() { return new DocumentRoot(id, "root"); }
    }
    record CreateDirectChild(String id) {
        @Apply DirectChild apply() { return new DirectChild(id, "document-root", "open"); }
    }
    record CreateDocumentOnly(String id) {
        @Apply DocumentOnly apply() { return new DocumentOnly(id, "open"); }
    }
    record CreateReferenceChild(String id) {
        @Apply ReferenceChild apply() { return new ReferenceChild(id, "document-root", "open"); }
    }
    record CreateReferenceComponent(String id) {
        @Apply ReferenceComponent apply() { return new ReferenceComponent(id, "document-root", "open"); }
    }
    record CreateMaterializedRoot(String id) {
        @Apply MaterializedRoot apply() { return new MaterializedRoot(id); }
    }
    record CreateMaterializedChild(String id, String status, String label) {
        @Apply MaterializedChild apply() {
            return new MaterializedChild(id, "materialized-root", status, label, "hiddenvalue");
        }
    }
}
