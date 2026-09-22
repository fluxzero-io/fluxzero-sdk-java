/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static io.fluxzero.common.api.modeling.ModelDeletionCascade.DESCENDANTS;
import static org.junit.jupiter.api.Assertions.*;

class PolymorphicParentIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replayCompositionRoutingAndDeletionPreservePolymorphicParents(boolean async) {
        var fixture = TestFixture.create(DefaultFluxzero.builder()
                                                 .configureGraphProjectionCompletion(GraphProjectionCompletion.AWAIT));
        verifyLifecycle(async ? fixture.async() : fixture);
    }

    private static void verifyLifecycle(TestFixture fixture) {
        var project = new ProjectId("same");
        var folder = new FolderId("same");
        fixture
                .givenCommands(new CreateProject(project), new CreateFolder(folder),
                               new CreateItem("item-project", project), new CreateItem("item-folder", folder))
                .whenExecuting(fc -> {
                    var repository = (DefaultModelRepository) fc.modelRepository();
                    repository.invalidateModels(List.of(project.toString(), folder.toString(), "item-project", "item-folder"));
                    assertEquals(new Item("item-project", project), Fluxzero.loadModel("item-project", Item.class).get());
                    assertEquals(new Item("item-folder", folder), Fluxzero.loadModel("item-folder", Item.class).get());
                    assertEquals(List.of(new Item("item-project", project)),
                                 Fluxzero.loadCurrentGraph(project).childModels("items", Item.class));
                    assertEquals(List.of(new Item("item-folder", folder)),
                                 Fluxzero.loadCurrentGraph(folder).childModels("items", Item.class));
                    var graph = Fluxzero.searchGraph(Project.class).fetchAll().getFirst();
                    assertEquals(List.of(new Item("item-project", project)), graph.childModels("items", Item.class));
                }).expectSuccessfulResult().expectNoErrors()
                .andThen().whenCommand(new DeleteProject(project))
                .expectSuccessfulResult().expectNoErrors()
                .andThen().whenExecuting(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("item-project", "item-folder"));
                    assertTrue(Fluxzero.loadModel("item-project", Item.class).isEmpty());
                    assertEquals(new Item("item-folder", folder), Fluxzero.loadModel("item-folder", Item.class).get());
                    assertFalse(fc.eventStore().getEvents("item-project").toList().isEmpty(), "logical deletion retains history");
                    var plan = fc.modelRepository().planDeletion(project, DESCENDANTS);
                    assertEquals(Set.of(project.toString(), "item-project"), Set.copyOf(plan.getSampleModelIds()));
                    assertEquals(2, fc.modelRepository().deleteModel(plan).join().getDeletedModelCount());
                    assertTrue(fc.eventStore().getEvents("item-project").toList().isEmpty());
                    assertFalse(fc.eventStore().getEvents("item-folder").toList().isEmpty());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @Model(name = "wire-project", materializeGraph = true, cached = false)
    record Project(@EntityId ProjectId id) { }
    @Model(name = "wire-folder", cached = false)
    record Folder(@EntityId FolderId id) { }
    @Model(name = "wire-item", cached = false)
    record Item(@EntityId String itemId,
                @Parent(types = {Project.class, Folder.class}, pathInParent = "items") Id<?> parentId) { }
    record CreateProject(ProjectId id) {
        @Apply Project create() { return new Project(id); }
    }
    record CreateFolder(FolderId id) {
        @Apply Folder create() { return new Folder(id); }
    }
    record CreateItem(String itemId, @Parent(types = {Project.class, Folder.class}) Id<?> parentId) {
        @Apply Item create() { return new Item(itemId, parentId); }
    }
    record DeleteProject(ProjectId id) {
        @Apply Project delete(Project project) { return null; }
    }
    static final class ProjectId extends Id<Project> {
        ProjectId(String id) { super(id, "wire-project-"); }
    }
    static final class FolderId extends Id<Folder> {
        FolderId(String id) { super(id, "wire-folder-"); }
    }
}
