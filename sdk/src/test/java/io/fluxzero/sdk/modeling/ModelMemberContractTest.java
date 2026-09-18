/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.PayloadParameterResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelMemberContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sealedMemberSubtypesPlanTheirHandlersAndExternalAssertions(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreatePolymorphic("poly"), new SetAvailability("availability", true))
                .whenExecuting(fc -> {
                    Fluxzero.loadGraph("poly", PolymorphicOwner.class)
                            .assertAndApply(new RenamePart("first", "availability", "one"));
                    Fluxzero.loadGraph("poly", PolymorphicOwner.class)
                            .assertAndApply(new RenamePart("second", "availability", "two"));
                    Fluxzero.loadGraph("poly", PolymorphicOwner.class).assertAndApply(new AppendPart("first"));
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("poly"));
                    assertEquals(List.of(new FirstPart("first", "one!"), new SecondPart("second", "two")),
                                 Fluxzero.loadModel("poly", PolymorphicOwner.class).get().parts());
                    Fluxzero.assertAndApply(new SetAvailability("availability", false));
                    assertThrows(IllegalArgumentException.class, () -> Fluxzero.loadGraph("poly", PolymorphicOwner.class)
                            .assertAndApply(new RenamePart("first", "availability", "rejected")));
                    assertEquals(4, fc.eventStore().getEvents("poly").count());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @Model record PolymorphicOwner(@EntityId String id, @Member List<Part> parts) {}
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS)
    sealed interface Part permits FirstPart, SecondPart {
        @EntityId String partId();
        String name();
        @Apply default Part append(AppendPart event) {
            return this instanceof FirstPart ? new FirstPart(partId(), name() + "!")
                    : new SecondPart(partId(), name() + "!");
        }
    }
    record FirstPart(String partId, String name) implements Part {
        @Apply FirstPart rename(RenamePart event) { return new FirstPart(partId, event.name()); }
        @AssertLegal void check(RenamePart event, Graph<Availability> availability) {
            if (!availability.get().active()) { throw new IllegalArgumentException("unavailable"); }
        }
    }
    record SecondPart(String partId, String name) implements Part {
        @Apply SecondPart rename(RenamePart event) { return new SecondPart(partId, event.name()); }
    }
    record CreatePolymorphic(String id) {
        @Apply PolymorphicOwner create() {
            return new PolymorphicOwner(id, List.of(new FirstPart("first", "before"), new SecondPart("second", "before")));
        }
    }
    record RenamePart(String partId, String availabilityId, String name) {}
    record AppendPart(String partId) {}
    @Model record Availability(@EntityId String availabilityId, boolean active) {}
    record SetAvailability(String availabilityId, boolean active) {
        @Apply Availability apply(@jakarta.annotation.Nullable Availability previous) {
            return new Availability(availabilityId, active);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preexistingHistoryUsesCorrectedMemberSemanticsWithoutAnOptIn(boolean async) {
        var fixture = async ? TestFixture.createAsync(Combined.class) : TestFixture.create(Combined.class);
        fixture.givenModelEvents("combined", Combined.class,
                                new CreateCombined("combined"), new RenameCombined("combined", "item", "replayed"))
                .whenExecuting(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("combined"));
                    assertEquals(new Combined("combined", List.of(new Item("item", "replayed")), 1, "replayed"),
                                 Fluxzero.loadModel("combined", Combined.class).get());
                }).expectSuccessfulResult().expectNoEvents().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void multipleOwnersCannotSilentlyChooseOneMemberInterceptor(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"), new CreateOtherOwner("other"))
                .whenExecuting(fc -> {
                    var failure = assertThrows(IllegalStateException.class, () -> Fluxzero.assertAndApply(
                            new RewriteBoth(new OwnerId("owner"), new OtherOwnerId("other"), "item")));
                    assertTrue(failure.getMessage().contains("Multiple Model owners"));
                    assertEquals(1, fc.eventStore().getEvents("owner").count());
                    assertEquals(1, fc.eventStore().getEvents("other").count());
                }).expectSuccessfulResult().expectNoEvents().expectNoErrors();
    }

    @Model
    record OtherOwner(@EntityId String id, @Member Item item) {}
    static final class OtherOwnerId extends Id<OtherOwner> { OtherOwnerId(String id) { super(id); } }
    record CreateOtherOwner(String id) {
        @Apply OtherOwner create() { return new OtherOwner(id, new Item("item", "before")); }
    }
    record RewriteBoth(OwnerId ownerId, OtherOwnerId otherOwnerId, String itemId) {}

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parameterizedSingletonKeepsItsOwnHandlers(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateGeneric("generic"))
                .whenExecuting(fc -> Fluxzero.loadGraph("generic", GenericOwner.class)
                        .assertAndApply(new RenameBox("box", "after")))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("generic"));
                    assertEquals(new Box<>("box", "after", "content"),
                                 Fluxzero.loadModel("generic", GenericOwner.class).get().box());
                });
    }

    @Model
    record GenericOwner(@EntityId String id, @Member Box<String> box) {}
    record Box<T>(@EntityId String boxId, String name, T content) {
        @Apply Box<T> rename(RenameBox event) { return new Box<>(boxId, event.name(), content); }
    }
    record CreateGeneric(String id) {
        @Apply GenericOwner create() { return new GenericOwner(id, new Box<>("box", "before", "content")); }
    }
    record RenameBox(String boxId, String name) {}

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void payloadAssertionCanInspectAMemberWithoutItsOwnHandlers(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateMap("map"))
                .given(fc -> Fluxzero.loadGraph("map", MapOwner.class).assertAndApply(new AddMapped("key", "value")))
                .whenExecuting(fc -> {
                    assertEquals("data-only member guard", assertThrows(IllegalArgumentException.class,
                            () -> Fluxzero.loadGraph("map", MapOwner.class)
                                    .assertAndApply(new GuardMapped("key"))).getMessage());
                    assertEquals("entity-wrapped member guard", assertThrows(IllegalArgumentException.class,
                            () -> Fluxzero.loadGraph("map", MapOwner.class)
                                    .assertAndApply(new GuardMappedEntity("key"))).getMessage());
                    assertEquals(2, fc.eventStore().getEvents("map").count());
                }).expectSuccessfulResult().expectNoEvents().expectNoErrors();
    }

    record GuardMapped(String key) {
        @AssertLegal void check(Mapped member) { throw new IllegalArgumentException("data-only member guard"); }
        @Apply MapOwner apply(MapOwner owner) { return new MapOwner(owner.id(), Map.of(), owner.copies()); }
    }
    record GuardMappedEntity(String key) {
        @AssertLegal void check(Entity<Mapped> member) {
            assertEquals("value", member.get().value());
            throw new IllegalArgumentException("entity-wrapped member guard");
        }
        @Apply MapOwner apply(MapOwner owner) { return new MapOwner(owner.id(), Map.of(), owner.copies()); }
    }

    @Test
    void automaticMemberFactoriesRespectModelAndApplyOverrides() {
        var compiler = new MutationPlan.Compiler(List.of(new PayloadParameterResolver()));
        var catalog = new MutationPlan.Catalog(compiler, AutomaticModelHandling.ENABLED);
        catalog.register(ManualOwner.class);
        assertFalse(catalog.get(AddManual.class).automatic());
        assertTrue(catalog.get(EnabledAddManual.class).automatic());
        assertTrue(catalog.get(AddManual.class, ManualOwner.class).commit());
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void embeddedMemberApplyUpdatesItsImmutableModelOwner(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"))
                .whenExecuting(fc -> Fluxzero.loadGraph("owner", Owner.class)
                        .assertAndApply(new RenameItem("item", "after")))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(List.of(new Item("item", "after")), Fluxzero.loadModel("owner", Owner.class).get().items());
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner"));
                    assertEquals(List.of(new Item("item", "after")), Fluxzero.loadModel("owner", Owner.class).get().items());
                    assertEquals(2, fc.eventStore().getEvents("owner").count());
                    assertEquals(0, fc.eventStore().getEvents("item").count());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memberCreationDeletionAndRootApplyComposeWithoutMutatingOldValues(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"))
                .whenExecuting(fc -> {
                    Owner original = Fluxzero.loadModel("owner", Owner.class).get();
                    Fluxzero.loadGraph("owner", Owner.class).assertAndApply(new AddItem("second", "added"));
                    assertEquals(List.of(new Item("item", "before")), original.items());
                    assertEquals(2, Fluxzero.loadModel("owner", Owner.class).get().items().size());
                    Fluxzero.loadGraph("owner", Owner.class).assertAndApply(new RemoveItem("item"));
                }).expectSuccessfulResult().expectNoErrors()
                .andThen().whenExecuting(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner"));
                    assertEquals(List.of(new Item("second", "added")), Fluxzero.loadModel("owner", Owner.class).get().items());
                    assertEquals(3, fc.eventStore().getEvents("owner").count());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memberAndRootApplyEachRunOnceAndReplayTogether(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateCombined("combined"))
                .whenExecuting(fc -> Fluxzero.loadGraph("combined", Combined.class)
                        .assertAndApply(new RenameItem("item", "after")))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    Combined expected = new Combined("combined", List.of(new Item("item", "after")), 1, "after");
                    assertEquals(expected, Fluxzero.loadModel("combined", Combined.class).get());
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("combined"));
                    assertEquals(expected, Fluxzero.loadModel("combined", Combined.class).get());
                    assertEquals(2, fc.eventStore().getEvents("combined").count());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memberBeforeAndAfterAssertionsRejectAtomically(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"))
                .whenExecuting(fc -> {
                    assertThrows(IllegalArgumentException.class, () -> Fluxzero.loadGraph("owner", Owner.class)
                            .assertAndApply(new RenameItem("item", "forbidden")));
                    assertThrows(IllegalArgumentException.class, () -> Fluxzero.loadGraph("owner", Owner.class)
                            .assertAndApply(new RenameItem("item", "invalid-after")));
                    assertEquals(List.of(new Item("item", "before")), Fluxzero.loadModel("owner", Owner.class).get().items());
                    assertEquals(1, fc.eventStore().getEvents("owner").count());
                }).expectSuccessfulResult().expectNoEvents().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mapIdPropertyAndCustomWitherSurviveColdReplay(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateMap("map"))
                .whenExecuting(fc -> Fluxzero.loadGraph("map", MapOwner.class)
                        .assertAndApply(new AddMapped("key", "value")))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    MapOwner expected = new MapOwner("map", Map.of("key", new Mapped("key", "value")), 1);
                    assertEquals(expected, Fluxzero.loadModel("map", MapOwner.class).get());
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("map"));
                    assertEquals(expected, Fluxzero.loadModel("map", MapOwner.class).get());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registeredMemberHandlersCanHandleCommandsDirectly(boolean async) {
        var fixture = async ? TestFixture.createAsync(Owner.class) : TestFixture.create(Owner.class);
        fixture.givenCommands(new CreateOwner("owner"))
                .whenCommand(new RenameItem(new OwnerId("owner"), "item", "tracked"))
                .expectSuccessfulResult().expectNoErrors()
                .expectEvents(new RenameItem(new OwnerId("owner"), "item", "tracked"))
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner"));
                    assertEquals(List.of(new Item("item", "tracked")), Fluxzero.loadModel("owner", Owner.class).get().items());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void singletonReplacementAndRemovalReconstructTheOwner(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateSingleton("single"))
                .whenExecuting(fc -> {
                    SingleOwner before = Fluxzero.loadModel("single", SingleOwner.class).get();
                    Fluxzero.loadGraph("single", SingleOwner.class).assertAndApply(new RenameItem("item", "after"));
                    assertEquals(new Item("item", "after"), Fluxzero.loadModel("single", SingleOwner.class).get().item());
                    assertEquals(new Item("item", "before"), before.item());
                    Fluxzero.loadGraph("single", SingleOwner.class).assertAndApply(new RemoveItem("item"));
                }).expectSuccessfulResult().expectNoErrors()
                .andThen().whenExecuting(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("single"));
                    assertEquals(new SingleOwner("single", null), Fluxzero.loadModel("single", SingleOwner.class).get());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentOwnerAndDuplicateMemberAreNotSuccessfulNoOps(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"))
                .whenExecuting(fc -> {
                    assertThrows(Entity.NOT_FOUND_EXCEPTION.getClass(), () -> Fluxzero.loadGraph("missing", Owner.class)
                            .assertAndApply(new RenameItem("item", "after")));
                    assertThrows(Entity.ALREADY_EXISTS_EXCEPTION.getClass(), () -> Fluxzero.loadGraph("owner", Owner.class)
                            .assertAndApply(new AddItem("item", "duplicate")));
                    assertEquals(1, fc.eventStore().getEvents("owner").count());
                }).expectSuccessfulResult().expectNoEvents().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void payloadAndMissingMemberAssertionsAreNotSkipped(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"))
                .whenExecuting(fc -> {
                    assertThrows(IllegalArgumentException.class, () -> Fluxzero.loadGraph("owner", Owner.class)
                            .assertAndApply(new ForbiddenRename("item")));
                    assertThrows(IllegalArgumentException.class, () -> Fluxzero.loadGraph("owner", Owner.class)
                            .assertAndApply(new AddItem("blocked", "new")));
                    assertEquals(List.of(new Item("item", "before")), Fluxzero.loadModel("owner", Owner.class).get().items());
                    assertEquals(1, fc.eventStore().getEvents("owner").count());
                }).expectSuccessfulResult().expectNoEvents().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void embeddedInterceptorRewritesBeforeValidationAndApply(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"))
                .whenExecuting(fc -> Fluxzero.loadGraph("owner", Owner.class).assertAndApply(new Rewrite("item")))
                .expectSuccessfulResult().expectNoErrors()
                .expectEvents(new RenameItem("item", "intercepted"))
                .expectThat(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner"));
                    assertEquals(List.of(new Item("item", "intercepted")), Fluxzero.loadModel("owner", Owner.class).get().items());
                });
    }

    @Model
    record Owner(@EntityId String id, @Member List<Item> items) {}

    record Item(@EntityId String itemId, String name) {
        @Apply
        Item apply(RenameItem event) { return new Item(itemId, event.name()); }
        @Apply Item apply(RenameCombined event) { return new Item(itemId, event.name()); }
        @Apply Item apply(RemoveItem event) { return null; }
        @InterceptApply RenameItem rewrite(Rewrite event) { return new RenameItem(itemId, "intercepted"); }
        @InterceptApply RenameItem rewrite(RewriteBoth event) { return new RenameItem(itemId, "intercepted"); }
        @AssertLegal static void createAllowed(AddItem event) {
            if (event.itemId().equals("blocked")) { throw new IllegalArgumentException("missing member guard"); }
        }
        @AssertLegal void before(RenameItem event) {
            if (event.name().equals("forbidden")) { throw new IllegalArgumentException("before"); }
        }
        @AssertLegal(afterHandler = true) void after(RenameItem event) {
            if (name.equals("invalid-after")) { throw new IllegalArgumentException("after"); }
        }
    }

    @Model
    record Combined(@EntityId String id, @Member List<Item> items, int updates, String seen) {
        @Apply Combined apply(RenameItem event) { return new Combined(id, items, updates + 1, items.getFirst().name()); }
        @Apply Combined apply(RenameCombined event) { return new Combined(id, items, updates + 1, items.getFirst().name()); }
    }
    record RenameCombined(String id, String itemId, String name) {}
    record CreateCombined(String id) {
        @Apply Combined apply() { return new Combined(id, List.of(new Item("item", "before")), 0, "before"); }
    }

    record AddItem(String itemId, String name) {
        @Apply Item apply() { return new Item(itemId, name); }
    }
    record RemoveItem(String itemId) {}
    record Rewrite(String itemId) {}
    record ForbiddenRename(String itemId) {
        @AssertLegal void check(Item item) { throw new IllegalArgumentException("payload member guard"); }
        @Apply Item apply(Item item) { return new Item(item.itemId(), "should-not-commit"); }
    }

    @Model
    record MapOwner(@EntityId String id, @Member(idProperty = "key", wither = "replaceEntries") Map<String, Mapped> entries,
                    int copies) {
        MapOwner replaceEntries(Map<String, Mapped> entries) { return new MapOwner(id, entries, copies + 1); }
    }
    record Mapped(String key, String value) {}
    record AddMapped(String key, String value) {
        @Apply Mapped apply() { return new Mapped(key, value); }
    }
    record CreateMap(String id) {
        @Apply MapOwner apply() { return new MapOwner(id, Map.of(), 0); }
    }

    @Model(automaticHandling = AutomaticModelHandling.DISABLED)
    record ManualOwner(@EntityId String id, @Member List<Mapped> entries) {}
    record AddManual(String id, String key) {
        @Apply Mapped apply() { return new Mapped(key, "value"); }
    }
    record EnabledAddManual(String id, String key) {
        @Apply(automaticHandling = AutomaticModelHandling.ENABLED)
        Mapped apply() { return new Mapped(key, "value"); }
    }

    static final class OwnerId extends Id<Owner> { OwnerId(String id) { super(id); } }
    record RenameItem(OwnerId ownerId, String itemId, String name) {
        RenameItem(String itemId, String name) { this(null, itemId, name); }
    }
    @Model
    record SingleOwner(@EntityId String id, @Member Item item) {}
    record CreateSingleton(String id) {
        @Apply SingleOwner apply() { return new SingleOwner(id, new Item("item", "before")); }
    }
    record CreateOwner(String id) {
        @Apply
        Owner apply() { return new Owner(id, List.of(new Item("item", "before"))); }
    }
}
