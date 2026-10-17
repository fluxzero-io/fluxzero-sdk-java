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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectionParentIntegrationTest {
    final GroupId first = new GroupId("first");
    final GroupId second = new GroupId("second");

    TestFixture fixture(boolean async) {
        var fixture = TestFixture.create(DefaultFluxzero.builder()
                .configureGraphProjectionCompletion(GraphProjectionCompletion.AWAIT));
        return (async ? fixture.async() : fixture).givenCommands(
                new PutGroup(first, false), new PutGroup(second, false));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void replayCompositionReparentingAndNonOwningDeletion(boolean async) {
        fixture(async).givenCommands(new PutItem("item", List.of(first, second, first), "before"))
                .whenExecuting(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("item"));
                    assertEquals(List.of(first, second, first), Fluxzero.loadModel("item", Item.class).get().parents());
                    assertEquals(2, Fluxzero.loadGraph("item", Item.class).parents().size());
                    assertThrows(IllegalStateException.class, () -> Fluxzero.loadGraph("item", Item.class).parent(Group.class));
                    for (var id : List.of(first, second)) {
                        assertEquals(1, Fluxzero.loadCurrentGraph(id).children("items", Item.class).size());
                    }
                    assertEquals(2, Fluxzero.searchGraph(Group.class).fetchAll().stream()
                            .filter(g -> g.children("items", Item.class).size() == 1).count());
                }).expectSuccessfulResult().expectNoErrors()
                .andThen().whenCommand(new PutItem("item", List.of(second), "after"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertTrue(Fluxzero.loadCurrentGraph(first).children("items", Item.class).isEmpty());
                    assertEquals("after", Fluxzero.loadCurrentGraph(second).childModels("items", Item.class).getFirst().label());
                })
                .andThen().whenCommand(new DeleteGroup(second)).expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertFalse(Fluxzero.loadModel("item", Item.class).isEmpty());
                    assertEquals(2L, fc.eventStore().getEvents("item").count());
                });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anyOwningParentDeletionCascades(boolean deleteFirst) {
        fixture(false).givenCommands(new PutOwned("owned", List.of(first, second)))
                .whenCommand(new DeleteGroup(deleteFirst ? first : second))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertTrue(Fluxzero.loadModel("owned", Owned.class).isEmpty()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void everyParentParticipatesInCascadingAssertions(boolean closeFirst) {
        fixture(false).givenCommands(new PutItem("item", List.of(first, second), "before"),
                        new PutGroup(closeFirst ? first : second, true))
                .whenCommand(new PutItem("item", List.of(first, second), "forbidden"))
                .expectExceptionalResult(IllegalCommandException.class)
                .expectThat(fc -> assertEquals("before", Fluxzero.loadModel("item", Item.class).get().label()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void creationCanInjectExplicitCollectionParents(boolean async) {
        fixture(async).whenCommand(new CreateWithParent("new-item", List.of(second)))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals(new Item("new-item", List.of(second), "second"),
                        Fluxzero.loadModel("new-item", Item.class).get()));
    }
    record CreateWithParent(String itemId, List<GroupId> parents) {
        @Apply Item apply(@io.fluxzero.sdk.tracking.handling.Association("parents") List<Graph<Group>> selected) {
            return new Item(itemId, parents, selected.getFirst().get().id().toString());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void collectionCyclesAreRejectedAtomically(boolean async) {
        (async ? TestFixture.createAsync() : TestFixture.create())
                .givenCommands(new PutNode("a", List.of()), new PutNode("b", List.of("a")))
                .whenCommand(new PutNode("a", List.of("b")))
                .expectExceptionalResult((Throwable error) ->
                        (async ? error instanceof io.fluxzero.sdk.common.exception.TechnicalException
                                : error instanceof io.fluxzero.common.api.modeling.ModelRelationshipCycleValidator.ValidationException)
                        && error.getMessage().contains("Model relationship cycle detected: a -> b -> a"),
                        "Concrete collection cycle rejected")
                .expectNoEvents()
                .expectThat(fc -> assertTrue(Fluxzero.loadModel("a", Node.class).get().parents().isEmpty()));
    }
    @Model(searchable = false)
    record Node(@EntityId String nodeId, @Parent(Node.class) List<String> parents) {}
    record PutNode(String nodeId, List<String> parents) {
        @Apply Node apply(@jakarta.annotation.Nullable Node existing) { return new Node(nodeId, parents); }
    }

    @Model(searchable = true, cached = false)
    record Group(@EntityId GroupId id, boolean closed) {
        @AssertLegal(cascade = true, allowedClasses = PutItem.class)
        void open() { if (closed) { throw new IllegalCommandException("Group closed"); } }
    }
    static class GroupId extends Id<Group> {
        GroupId(String id) { super(id); }
    }
    @Model(searchable = false, cached = false)
    record Item(@EntityId String itemId,
                @Parent(pathInParent = "items", deleteOnParentDeletion = false) List<GroupId> parents, String label) {}
    @Model(searchable = false)
    record Owned(@EntityId String ownedId, @Parent List<GroupId> parents) {}
    record PutGroup(GroupId id, boolean closed) {
        @Apply Group apply(@jakarta.annotation.Nullable Group existing) { return new Group(id, closed); }
    }
    record DeleteGroup(GroupId id) { @Apply Group apply(Group group) { return null; } }
    record PutItem(String itemId, List<GroupId> parents, String label) {
        @Apply Item apply(@jakarta.annotation.Nullable Item existing) { return new Item(itemId, parents, label); }
    }
    record PutOwned(String ownedId, List<GroupId> parents) {
        @Apply Owned apply() { return new Owned(ownedId, parents); }
    }
}
