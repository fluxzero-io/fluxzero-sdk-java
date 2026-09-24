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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.ModelRelationshipCycleValidator;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecursiveModelRelationshipTest {

    @Test
    void supportsDeepNavigationMoveBoundsAndMaterializedProjection() {
        RecursiveFolderId firstRoot = new RecursiveFolderId("first-root");
        RecursiveFolderId secondRoot = new RecursiveFolderId("second-root");
        RecursiveFolderId child = new RecursiveFolderId("child");
        RecursiveFolderId grandchild = new RecursiveFolderId("grandchild");

        TestFixture.create(
                        DefaultFluxzero.builder().configureGraphProjectionCompletion(
                                GraphProjectionCompletion.AWAIT))
                .givenCommands(
                        new CreateRecursiveFolder(firstRoot, null, "first"),
                        new CreateRecursiveFolder(secondRoot, null, "second"),
                        new CreateRecursiveFolder(child, firstRoot, "child"),
                        new CreateRecursiveFolder(grandchild, child, "grandchild"))
                .whenCommand(new MoveRecursiveFolder(child, secondRoot))
                .expectThat(fluxzero -> {
                    Graph<RecursiveFolder> oldTree = fluxzero.modelRepository().loadGraph(firstRoot);
                    Graph<RecursiveFolder> newTree = fluxzero.modelRepository().loadGraph(secondRoot);

                    assertEquals(List.of(), oldTree.descendantModels(RecursiveFolder.class));
                    assertEquals(
                            List.of(child, grandchild),
                            folderIds(newTree.descendantModels(RecursiveFolder.class)));

                    Graph<RecursiveFolder> leaf = Fluxzero.loadGraph(grandchild);
                    Graph<RecursiveFolder> directParent = leaf.parent(RecursiveFolder.class).orElseThrow();
                    Graph<RecursiveFolder> outerRoot = directParent.parent(RecursiveFolder.class).orElseThrow();
                    assertEquals(child, directParent.get().folderId());
                    assertEquals(secondRoot, outerRoot.get().folderId());

                    Graph<RecursiveFolder> bounded = fluxzero.modelRepository().loadGraph(
                            secondRoot.toString(), RecursiveFolder.class,
                            new Graph.Options(1, 100));
                    assertEquals(
                            List.of(child),
                            folderIds(bounded.descendantModels(RecursiveFolder.class)));

                    Graph<RecursiveFolder> projected = Fluxzero.searchGraph(RecursiveFolder.class)
                            .stream()
                            .filter(graph -> graph.get() != null
                                             && secondRoot.equals(graph.get().folderId()))
                            .findFirst().orElseThrow();
                    assertEquals(
                            List.of(child, grandchild),
                            folderIds(projected.descendantModels(RecursiveFolder.class)));
                });
    }

    @Test
    void rejectsConcreteRecursiveCycleAtomically() {
        RecursiveFolderId root = new RecursiveFolderId("cycle-root");
        RecursiveFolderId child = new RecursiveFolderId("cycle-child");
        RecursiveFolderId grandchild = new RecursiveFolderId("cycle-grandchild");

        TestFixture.create()
                .givenCommands(
                        new CreateRecursiveFolder(root, null, "root"),
                        new CreateRecursiveFolder(child, root, "child"),
                        new CreateRecursiveFolder(grandchild, child, "grandchild"))
                .whenCommand(new MoveRecursiveFolder(root, grandchild))
                .expectExceptionalResult(ModelRelationshipCycleValidator.ValidationException.class)
                .expectNoEvents()
                .expectThat(fluxzero -> {
                    assertEquals(
                            new RecursiveFolder(root, null, "root"),
                            fluxzero.modelRepository().load(root).get());
                    assertEquals(
                            List.of(child, grandchild),
                            folderIds(fluxzero.modelRepository().loadGraph(root)
                                              .descendantModels(RecursiveFolder.class)));
                });
    }

    @Test
    void cascadesRecursiveDescendantsAndRemovesMaterializedTrees() {
        RecursiveFolderId root = new RecursiveFolderId("delete-root");
        RecursiveFolderId child = new RecursiveFolderId("delete-child");
        RecursiveFolderId grandchild = new RecursiveFolderId("delete-grandchild");

        TestFixture.create(
                        DefaultFluxzero.builder().configureGraphProjectionCompletion(
                                GraphProjectionCompletion.AWAIT))
                .givenCommands(
                        new CreateRecursiveFolder(root, null, "root"),
                        new CreateRecursiveFolder(child, root, "child"),
                        new CreateRecursiveFolder(grandchild, child, "grandchild"))
                .whenCommand(new DeleteRecursiveFolder(root))
                .expectThat(fluxzero -> {
                    ((DefaultModelRepository) fluxzero.modelRepository()).invalidateModels(
                            List.of(root.toString(), child.toString(), grandchild.toString()));
                    assertTrue(fluxzero.modelRepository().load(root).isEmpty());
                    assertTrue(fluxzero.modelRepository().load(child).isEmpty());
                    assertTrue(fluxzero.modelRepository().load(grandchild).isEmpty());
                    assertTrue(Fluxzero.searchGraph(RecursiveFolder.class).fetchAll().isEmpty());
                    assertTrue(fluxzero.eventStore().getEvents(grandchild.toString())
                                       .anyMatch(event -> event.getPayload() instanceof CascadedModelDeletion));
                });
    }

    private static List<RecursiveFolderId> folderIds(List<RecursiveFolder> folders) {
        return folders.stream().map(RecursiveFolder::folderId).toList();
    }

    @Model(persistence = {ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT}, materializeGraph = true,
            graphProjection = @GraphProjection(collection = "recursiveFolderGraphs"))
    private record RecursiveFolder(
            @EntityId RecursiveFolderId folderId,
            @Parent(pathInParent = "children") RecursiveFolderId parentId,
            String name) {
    }

    private static final class RecursiveFolderId extends Id<RecursiveFolder> {
        private RecursiveFolderId(String id) {
            super(id);
        }
    }

    private record CreateRecursiveFolder(
            RecursiveFolderId folderId,
            RecursiveFolderId parentId,
            String name) {
        @Apply
        RecursiveFolder apply() {
            return new RecursiveFolder(folderId, parentId, name);
        }
    }

    private record MoveRecursiveFolder(
            RecursiveFolderId folderId,
            RecursiveFolderId parentId) {
        @Apply
        RecursiveFolder apply(RecursiveFolder current) {
            return new RecursiveFolder(current.folderId(), parentId, current.name());
        }
    }

    private record DeleteRecursiveFolder(RecursiveFolderId folderId) {
        @Apply
        RecursiveFolder apply(RecursiveFolder current) {
            return null;
        }
    }
}
