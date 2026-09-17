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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenMemberModelContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closedMemberBindsItsPlainModelDependenciesWithoutRequiringAFreshBoundary(boolean async) {
        var catalog = new MutationPlan.Catalog(new MutationPlan.Compiler(
                List.of(new io.fluxzero.sdk.tracking.handling.PayloadParameterResolver())), AutomaticModelHandling.ENABLED);
        org.junit.jupiter.api.Assertions.assertFalse(catalog.get(CopyLabel.class, DependentOwner.class)
                .reducer().requiresStorageBoundary());
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateDependent("owner"), new SetLabel("outer", "wrong"), new SetLabel("inner", "blocked"))
                .whenExecuting(fc -> {
                    assertThrows(IllegalArgumentException.class, () -> Fluxzero.loadGraph("owner", DependentOwner.class)
                            .assertAndApply(new GuardedRename("item", "outer", "after")));
                    Fluxzero.loadGraph("owner", DependentOwner.class).assertAndApply(new CopyLabel("item", "outer"));
                    Fluxzero.assertAndApply(new SetLabel("inner", "now"));
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner", "inner"));
                    assertEquals("blocked", Fluxzero.loadModel("owner", DependentOwner.class).get().items().getFirst().name());
                }).expectSuccessfulResult().expectNoErrors();
    }
    @Model record DependentOwner(@EntityId String id, @Member List<DependentItem> items) {}
    record DependentItem(@EntityId String itemId, String labelId, String name) {
        @AssertLegal void check(GuardedRename event, Label label) {
            if (label.name().equals("blocked")) { throw new IllegalArgumentException("blocked"); }
        }
        @Apply DependentItem rename(GuardedRename event) { return new DependentItem(itemId, labelId, event.name()); }
        @Apply DependentItem copy(CopyLabel event, Label label) { return new DependentItem(itemId, labelId, label.name()); }
    }
    record CreateDependent(String id) {
        @Apply DependentOwner create() { return new DependentOwner(id, List.of(new DependentItem("item", "inner", "before"))); }
    }
    @org.junit.jupiter.api.Test
    void declaredMemberWithoutLateDependenciesKeepsItsCachedReadPath() {
        var catalog = new MutationPlan.Catalog(new MutationPlan.Compiler(
                List.of(new io.fluxzero.sdk.tracking.handling.PayloadParameterResolver())), AutomaticModelHandling.ENABLED);
        var plan = catalog.get(Rename.class, ClosedOwner.class);
        org.junit.jupiter.api.Assertions.assertFalse(plan.reducer().requiresStorageBoundary());
        org.junit.jupiter.api.Assertions.assertFalse(plan.requiresReplayDependencies());
    }
    @Model record ClosedOwner(@EntityId String id, @Member List<ClosedItem> items) {}
    record ClosedItem(@EntityId String itemId, String name) {
        @Apply ClosedItem apply(Rename event) { return new ClosedItem(itemId, event.name()); }
    }

    @org.junit.jupiter.api.Test
    void openPlaceholderDoesNotTurnAssertionsIntoAnAutomaticCommandHandler() {
        var catalog = new MutationPlan.Catalog(new MutationPlan.Compiler(
                List.of(new io.fluxzero.sdk.tracking.handling.PayloadParameterResolver())), AutomaticModelHandling.ENABLED);
        org.junit.jupiter.api.Assertions.assertFalse(catalog.get(GuardOnly.class).automatic());
    }
    @Model record GuardOwner(@EntityId GuardOwnerId id, @Member List<GuardPart> items) {}
    static class GuardOwnerId extends Id<GuardOwner> { GuardOwnerId(String id) { super(id); } }
    interface GuardPart { @AssertLegal default void check(GuardOnly update) {} }
    record GuardOnly(GuardOwnerId id) {}
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void splitUpcastBindsEachOutputAgainstTheStateProducedByItsPredecessor(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new SetLabel("label", "historical"), new LegacyOwner("owner"))
                .whenExecuting(fc -> {
                    assertEquals(1, fc.eventStore().getEvents("owner").count());
                    fc.serializer().registerCasters(new SplitOwner());
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner"));
                    assertEquals(new OtherItem("item", "historical"),
                            Fluxzero.loadModel("owner", Owner.class).get().items().getFirst());
                    assertEquals(2, fc.eventStore().getEvents("owner").count()); // Deserialized split outputs.
                }).expectSuccessfulResult().expectNoErrors();
    }
    record LegacyOwner(String id) {
        @Apply Owner create() { return new Owner(id, List.of(new ConcreteItem("item", "legacy"))); }
    }
    static class SplitOwner {
        @io.fluxzero.sdk.common.serialization.casting.Upcast(
                type = "io.fluxzero.sdk.modeling.OpenMemberModelContractTest$LegacyOwner", revision = 0)
        java.util.stream.Stream<io.fluxzero.common.api.Data<com.fasterxml.jackson.databind.JsonNode>> split(
                io.fluxzero.common.api.Data<com.fasterxml.jackson.databind.JsonNode> input) {
            var create = input.getValue().deepCopy();
            var replace = ((com.fasterxml.jackson.databind.node.ObjectNode) input.getValue()).deepCopy();
            replace.put("itemId", "item").put("labelId", "label");
            return java.util.stream.Stream.of(
                    new io.fluxzero.common.api.Data<>(create, CreateOwner.class.getName(), 0, input.getFormat()),
                    new io.fluxzero.common.api.Data<>(replace, Replace.class.getName(), 0, input.getFormat()));
        }
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subtypeCanRequireExplicitHandlingEvenWhenPayloadHasAutomaticApply(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"))
                .whenCommand(new ExplicitOnly("owner", "item"))
                .expectExceptionalResult(async ? io.fluxzero.sdk.common.exception.TechnicalException.class
                        : IllegalStateException.class)
                .expectThat(fc -> assertEquals(1, fc.eventStore().getEvents("owner").count()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitHandlingAndReplayHonorActualSubtypePublicationOverrides(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"))
                .whenExecuting(fc -> {
                    Fluxzero.loadGraph("owner", Owner.class).assertAndApply(new ExplicitOnly("owner", "item"));
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner"));
                    assertEquals(new ConcreteItem("item", "explicit"),
                            Fluxzero.loadModel("owner", Owner.class).get().items().getFirst());
                }).expectSuccessfulResult().expectNoEvents().expectNoErrors();
    }
    record ExplicitOnly(String id, String itemId) {
        @Apply Owner unchanged(Owner owner) { return owner; }
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingReadOnlyOwnerDoesNotBecomeAWrite(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.whenCommand(new ReadAbsentOwner(new OwnerId("missing"), "label"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> assertEquals("created", Fluxzero.loadModel("label", Label.class).get().name()));
    }

    record ReadAbsentOwner(OwnerId ownerId, String labelId) {
        @AssertLegal void check(@jakarta.annotation.Nullable Owner owner) { assertEquals(null, owner); }
        @Apply Label create() { return new Label(labelId, "created"); }
    }
    static class OwnerId extends Id<Owner> { OwnerId(String value) { super(value); } }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unhandledStoredEventHonorsIgnoreUnknownEvents(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenModelEvents("lenient", LenientOwner.class, new CreateLenient("lenient"), new Unknown("lenient"))
                .whenExecuting(fc -> {
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("lenient"));
                    assertEquals(1, Fluxzero.loadModel("lenient", LenientOwner.class).get().items().size());
                }).expectSuccessfulResult().expectNoErrors();
    }
    @Model(ignoreUnknownEvents = true) record LenientOwner(@EntityId String id, @Member List<Item> items) {}
    record CreateLenient(String id) {
        @Apply LenientOwner create() { return new LenientOwner(id, List.of(new ConcreteItem("item", "before"))); }
    }
    record Unknown(String id) {}

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memberReferenceShadowsPayloadReferenceInLiveExecutionAndReplay(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateScoped("owner"), new SetLabel("outer", "wrong"), new SetLabel("inner", "right"))
                .whenExecuting(fc -> {
                    Fluxzero.loadGraph("owner", Owner.class).assertAndApply(new CopyLabel("item", "outer"));
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner"));
                    assertEquals(new ScopedItem("item", "inner", "right"),
                            Fluxzero.loadModel("owner", Owner.class).get().items().getFirst());
                }).expectSuccessfulResult().expectNoErrors();
    }
    record ScopedItem(String itemId, String labelId, String name) implements Item {
        @Apply ScopedItem copy(CopyLabel event, Label label) { return new ScopedItem(itemId, labelId, label.name()); }
    }
    record CreateScoped(String id) {
        @Apply Owner create() { return new Owner(id, List.of(new ScopedItem("item", "inner", "before"))); }
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subtypeIntroducedByPayloadApplyLoadsItsOwnDependencyAndReplays(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"), new SetLabel("label", "then"))
                .whenExecuting(fc -> {
                    Fluxzero.loadGraph("owner", Owner.class).assertAndApply(new Replace("owner", "item", "label"));
                    Fluxzero.assertAndApply(new SetLabel("label", "now"));
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner", "label"));
                    assertEquals(new OtherItem("item", "then"),
                            Fluxzero.loadModel("owner", Owner.class).get().items().getFirst());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subtypeInterceptorLoadsDependencyBeforeSelectingReplacement(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"), new SetLabel("label", "intercepted"))
                .whenExecuting(fc -> {
                    Fluxzero.loadGraph("owner", Owner.class).assertAndApply(new Redirect("item", "label"));
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner"));
                    assertEquals(new ConcreteItem("item", "intercepted"),
                            Fluxzero.loadModel("owner", Owner.class).get().items().getFirst());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subtypeAfterAssertionRejectsChangedState(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"))
                .whenExecuting(fc -> {
                    assertThrows(IllegalArgumentException.class, () -> Fluxzero.loadGraph("owner", Owner.class)
                            .assertAndApply(new Rename("item", "forbidden")));
                    assertEquals(1, fc.eventStore().getEvents("owner").count());
                }).expectSuccessfulResult().expectNoErrors();
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subtypeApplyLoadsExternalModelAndReplaysHistoricalValue(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"), new SetLabel("label", "historical"))
                .whenExecuting(fc -> {
                    Fluxzero.loadGraph("owner", Owner.class).assertAndApply(new CopyLabel("item", "label"));
                    Fluxzero.assertAndApply(new SetLabel("label", "current"));
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner", "label"));
                    assertEquals(new ConcreteItem("item", "historical"),
                            Fluxzero.loadModel("owner", Owner.class).get().items().getFirst());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subtypeAssertionsLoadDependenciesAndRejectWithoutWriting(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"), new SetLabel("label", "blocked"))
                .whenExecuting(fc -> {
                    assertThrows(IllegalArgumentException.class, () -> Fluxzero.loadGraph("owner", Owner.class)
                            .assertAndApply(new GuardedRename("item", "label", "after")));
                    assertEquals(new ConcreteItem("item", "before"),
                            Fluxzero.loadModel("owner", Owner.class).get().items().getFirst());
                    assertEquals(1, fc.eventStore().getEvents("owner").count());
                }).expectSuccessfulResult().expectNoErrors();
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subtypeOnlyApplyUpdatesAndReplaysItsModelOwner(boolean async) {
        var fixture = async ? TestFixture.createAsync() : TestFixture.create();
        fixture.givenCommands(new CreateOwner("owner"))
                .whenExecuting(fc -> Fluxzero.loadGraph("owner", Owner.class).assertAndApply(new Rename("item", "after")))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(new ConcreteItem("item", "after"), Fluxzero.loadModel("owner", Owner.class).get().items().getFirst());
                    ((DefaultModelRepository) fc.modelRepository()).invalidateModels(List.of("owner"));
                    assertEquals(new ConcreteItem("item", "after"), Fluxzero.loadModel("owner", Owner.class).get().items().getFirst());
                    assertEquals(2, fc.eventStore().getEvents("owner").count());
                });
    }

    @Model record Owner(@EntityId String id, @Member List<Item> items) {}
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS)
    interface Item { @EntityId String itemId(); }
    record ConcreteItem(String itemId, String name) implements Item {
        @Apply ConcreteItem rename(Rename event) { return new ConcreteItem(itemId, event.name()); }
        @Apply ConcreteItem copy(CopyLabel event, Label label) { return new ConcreteItem(itemId, label.name()); }
        @Apply ConcreteItem guarded(GuardedRename event) { return new ConcreteItem(itemId, event.name()); }
        @AssertLegal void check(GuardedRename event, Graph<Label> label) {
            if (label.get().name().equals("blocked")) { throw new IllegalArgumentException("blocked"); }
        }
        @AssertLegal(afterHandler = true) void check(Rename event) {
            if (name.equals("forbidden")) { throw new IllegalArgumentException("forbidden"); }
        }
        @InterceptApply Rename redirect(Redirect event, Label label) { return new Rename(itemId, label.name()); }
        @Apply(automaticHandling = AutomaticModelHandling.DISABLED, publicationStrategy = EventPublicationStrategy.STORE_ONLY)
        ConcreteItem explicit(ExplicitOnly event) { return new ConcreteItem(itemId, "explicit"); }
    }
    record OtherItem(String itemId, String name) implements Item {
        @Apply OtherItem finish(Replace event, Label label) { return new OtherItem(itemId, label.name()); }
    }
    record Replace(String id, String itemId, String labelId) {
        @Apply Owner replace(Owner previous) { return new Owner(id, List.of(new OtherItem(itemId, "intermediate"))); }
    }
    record Redirect(String itemId, String labelId) {}
    record Rename(String itemId, String name) {}
    record CopyLabel(String itemId, String labelId) {}
    record GuardedRename(String itemId, String labelId, String name) {}
    @Model record Label(@EntityId String labelId, String name) {}
    record SetLabel(String labelId, String name) {
        @Apply Label apply(@jakarta.annotation.Nullable Label previous) { return new Label(labelId, name); }
    }
    record CreateOwner(String id) {
        @Apply Owner create() { return new Owner(id, List.of(new ConcreteItem("item", "before"))); }
    }
}
