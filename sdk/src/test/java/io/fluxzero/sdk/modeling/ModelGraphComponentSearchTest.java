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

import io.fluxzero.common.api.modeling.ModelDocumentMutation;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.fluxzero.common.api.search.constraints.MatchConstraint.match;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGraphComponentSearchTest {

    @Test
    void materializesNonSearchableRootFromPrivateComponents() {
        String rootId = "private-projection-root";
        String childId = "private-projection-child";

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .configureGraphProjectionCompletion(GraphProjectionCompletion.AWAIT))
                .givenCommands(
                        new CreatePrivateProjectionRoot(rootId),
                        new CreatePrivateProjectionChild(childId, rootId))
                .whenApplying(ignored -> List.of(
                        Fluxzero.search(PrivateProjectionRoot.class).fetchAll().isEmpty(),
                        privateProjectionChildIds(Fluxzero.searchGraph(PrivateProjectionRoot.class)
                                                          .fetchAll()),
                        privateProjectionChildIds(Fluxzero.searchGraph(PrivateProjectionRoot.class, true)
                                                          .fetchAll())))
                .expectResult(List.of(
                        true, List.of(childId), List.of(childId)));
    }

    @Test
    void selectsParentsAndGraphsThroughTypeIsolatedPrivateComponents() {
        SearchRootId selectedRoot = new SearchRootId("selected");
        SearchRootId otherRoot = new SearchRootId("other");
        PrivateChildId selectedChild = new PrivateChildId("selected");

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .configureGraphProjectionCompletion(GraphProjectionCompletion.AWAIT))
                .givenCommands(
                        new CreateSearchRoot(selectedRoot),
                        new CreateSearchRoot(otherRoot),
                        new CreatePrivateChild(selectedChild, selectedRoot, "wanted"),
                        new CreateOtherPrivateChild(new OtherPrivateChildId("other"), otherRoot, "wanted"))
                .whenApplying(ignored -> {
                    List<SearchRoot> parents = Fluxzero.search(SearchRoot.class)
                            .whereDescendant(PrivateChild.class, match("wanted", true, "status"))
                            .fetchAll();
                    List<Graph<SearchRoot>> materialized = Fluxzero.searchGraph(SearchRoot.class)
                            .whereDescendant(PrivateChild.class, match("wanted", true, "status"))
                            .fetchAll();
                    List<Graph<SearchRoot>> live = Fluxzero.searchGraph(SearchRoot.class, true)
                            .whereDescendant(PrivateChild.class, match("wanted", true, "status"))
                            .fetchAll();
                    return List.of(
                            parents.stream().map(SearchRoot::searchRootId).toList(),
                            graphIds(materialized),
                            graphIds(live));
                })
                .expectResult(List.of(
                        List.of(selectedRoot),
                        List.of(selectedRoot),
                        List.of(selectedRoot)))
                .andThen()
                .whenApplying(ignored -> List.of(
                        Fluxzero.search(PrivateChild.class).fetchAll().isEmpty(),
                        Fluxzero.search(OtherPrivateChild.class).fetchAll().isEmpty()))
                .expectResult(List.of(true, true));
    }

    @Test
    void selectsSearchableDescendantsThroughPrivateAncestors() {
        SearchRootId firstRoot = new SearchRootId("ancestor-first");
        SearchRootId secondRoot = new SearchRootId("ancestor-second");
        PrivateChildId firstChild = new PrivateChildId("ancestor-first");
        PrivateChildId secondChild = new PrivateChildId("ancestor-second");
        SearchLeafId firstLeaf = new SearchLeafId("first");

        TestFixture.create()
                .givenCommands(
                        new CreateSearchRoot(firstRoot),
                        new CreateSearchRoot(secondRoot),
                        new CreatePrivateChild(firstChild, firstRoot, "wanted"),
                        new CreatePrivateChild(secondChild, secondRoot, "other"),
                        new CreateSearchLeaf(firstLeaf, firstChild),
                        new CreateSearchLeaf(new SearchLeafId("second"), secondChild))
                .whenApplying(ignored -> Fluxzero.search(SearchLeaf.class)
                        .whereAncestor(PrivateChild.class, match("wanted", true, "status"))
                        .fetchAll())
                .expectResult(List.of(new SearchLeaf(firstLeaf, firstChild)));
    }

    @Test
    void keepsReverseSelectionCurrentAfterMovingAndDeletingAPrivateComponent() {
        SearchRootId firstRoot = new SearchRootId("lifecycle-first");
        SearchRootId secondRoot = new SearchRootId("lifecycle-second");
        PrivateChildId child = new PrivateChildId("lifecycle");

        TestFixture.create(
                        DefaultFluxzero.builder()
                                .configureGraphProjectionCompletion(GraphProjectionCompletion.AWAIT))
                .givenCommands(
                        new CreateSearchRoot(firstRoot),
                        new CreateSearchRoot(secondRoot),
                        new CreatePrivateChild(child, firstRoot, "wanted"))
                .whenCommand(new MovePrivateChild(child, secondRoot))
                .expectTrue(ignored -> selectedRootIds().equals(List.of(
                        List.of(secondRoot), List.of(secondRoot), List.of(secondRoot))))
                .andThen()
                .whenCommand(new DeletePrivateChild(child))
                .expectTrue(ignored -> selectedRootIds().equals(List.of(
                        List.of(), List.of(), List.of())));
    }

    @Test
    void givesEveryPrivateComponentTypeItsOwnInternalCollection() {
        String first = EntityMetadata.validate(PrivateChild.class)
                .modelDocumentCollection().orElseThrow();
        String second = EntityMetadata.validate(OtherPrivateChild.class)
                .modelDocumentCollection().orElseThrow();

        assertNotEquals(first, second);
        assertTrue(first.startsWith(ModelDocumentMutation.GRAPH_COMPONENT_COLLECTION_PREFIX), first);
        assertTrue(second.startsWith(ModelDocumentMutation.GRAPH_COMPONENT_COLLECTION_PREFIX), second);
    }

    private static List<SearchRootId> graphIds(List<Graph<SearchRoot>> graphs) {
        return graphs.stream().map(graph -> graph.get().searchRootId()).toList();
    }

    private static List<String> privateProjectionChildIds(
            List<Graph<PrivateProjectionRoot>> graphs) {
        return graphs.getFirst().childModels("children", PrivateProjectionChild.class)
                .stream().map(PrivateProjectionChild::id).toList();
    }

    private static List<List<SearchRootId>> selectedRootIds() {
        return List.of(
                Fluxzero.search(SearchRoot.class)
                        .whereDescendant(PrivateChild.class, match("wanted", true, "status"))
                        .fetchAll().stream().map(SearchRoot::searchRootId).toList(),
                graphIds(Fluxzero.searchGraph(SearchRoot.class)
                                 .whereDescendant(PrivateChild.class, match("wanted", true, "status"))
                                 .fetchAll()),
                graphIds(Fluxzero.searchGraph(SearchRoot.class, true)
                                 .whereDescendant(PrivateChild.class, match("wanted", true, "status"))
                                 .fetchAll()));
    }

    @Model(searchable = true, materializeGraph = true)
    private record SearchRoot(@EntityId SearchRootId searchRootId) {
    }

    private static final class SearchRootId extends Id<SearchRoot> {
        private SearchRootId(String id) {
            super(id, "component-search-root-");
        }
    }

    private record CreateSearchRoot(SearchRootId searchRootId) {
        @Apply
        SearchRoot apply() {
            return new SearchRoot(searchRootId);
        }
    }

    @Model(materializeGraph = true)
    private record PrivateProjectionRoot(@EntityId String id) {
    }

    private record CreatePrivateProjectionRoot(String id) {
        @Apply
        PrivateProjectionRoot apply() {
            return new PrivateProjectionRoot(id);
        }
    }

    @Model
    private record PrivateProjectionChild(
            @EntityId String id,
            @Parent(value = PrivateProjectionRoot.class, pathInParent = "children")
            String rootId) {
    }

    private record CreatePrivateProjectionChild(String id, String rootId) {
        @Apply
        PrivateProjectionChild apply() {
            return new PrivateProjectionChild(id, rootId);
        }
    }

    @Model
    private record PrivateChild(
            @EntityId PrivateChildId privateChildId,
            @Parent(pathInParent = "privateChildren") SearchRootId searchRootId,
            String status) {
    }

    private static final class PrivateChildId extends Id<PrivateChild> {
        private PrivateChildId(String id) {
            super(id, "private-child-");
        }
    }

    private record CreatePrivateChild(
            PrivateChildId privateChildId,
            SearchRootId searchRootId,
            String status) {
        @Apply
        PrivateChild apply() {
            return new PrivateChild(privateChildId, searchRootId, status);
        }
    }

    private record MovePrivateChild(
            PrivateChildId privateChildId,
            SearchRootId searchRootId) {
        @Apply
        PrivateChild apply(PrivateChild child) {
            return new PrivateChild(privateChildId, searchRootId, child.status());
        }
    }

    private record DeletePrivateChild(PrivateChildId privateChildId) {
        @Apply
        PrivateChild apply(PrivateChild child) {
            return null;
        }
    }

    @Model
    private record OtherPrivateChild(
            @EntityId OtherPrivateChildId otherPrivateChildId,
            @Parent(pathInParent = "otherPrivateChildren") SearchRootId searchRootId,
            String status) {
    }

    private static final class OtherPrivateChildId extends Id<OtherPrivateChild> {
        private OtherPrivateChildId(String id) {
            super(id, "other-private-child-");
        }
    }

    private record CreateOtherPrivateChild(
            OtherPrivateChildId otherPrivateChildId,
            SearchRootId searchRootId,
            String status) {
        @Apply
        OtherPrivateChild apply() {
            return new OtherPrivateChild(otherPrivateChildId, searchRootId, status);
        }
    }

    @Model(searchable = true)
    private record SearchLeaf(
            @EntityId SearchLeafId searchLeafId,
            @Parent(pathInParent = "searchLeaves") PrivateChildId privateChildId) {
    }

    private static final class SearchLeafId extends Id<SearchLeaf> {
        private SearchLeafId(String id) {
            super(id, "component-search-leaf-");
        }
    }

    private record CreateSearchLeaf(
            SearchLeafId searchLeafId,
            PrivateChildId privateChildId) {
        @Apply
        SearchLeaf apply() {
            return new SearchLeaf(searchLeafId, privateChildId);
        }
    }
}
