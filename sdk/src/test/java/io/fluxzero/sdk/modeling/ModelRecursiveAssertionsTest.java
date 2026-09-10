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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import io.fluxzero.sdk.tracking.handling.authentication.FixedUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModelRecursiveAssertionsTest {
    private TestFixture fixture;
    private static final AtomicInteger outer = new AtomicInteger();
    private static final AtomicInteger inner = new AtomicInteger();
    private static final AtomicReference<Runnable> race = new AtomicReference<>();
    private static final List<String> calls = new ArrayList<>();

    @AfterEach
    void close() {
        race.set(null);
        if (fixture != null) {
            fixture.getFluxzero().close();
        }
    }

    private TestFixture fixture(boolean async) {
        outer.set(0);
        inner.set(0);
        calls.clear();
        return fixture = async ? TestFixture.createAsync() : TestFixture.create();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnedBeforeGuardRejectsWithoutPersisting(boolean async) {
        fixture(async).whenCommand(new Before("before"))
                .expectExceptionalResult(IllegalCommandException.class);
        assertRejected("before", 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnedAfterGuardRejectsWithoutPersisting(boolean async) {
        fixture(async).whenCommand(new After("after"))
                .expectExceptionalResult(IllegalCommandException.class);
        assertRejected("after", 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fieldGuardRejectsWithoutPersisting(boolean async) {
        fixture(async).whenCommand(new WithField("field"))
                .expectExceptionalResult(IllegalCommandException.class);
        assertRejected("field", 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void assertOnlyTraversesReturnedGuards(boolean async) {
        fixture(async).whenExecuting(fc -> Fluxzero.assertLegal(new Before("assert-only")))
                .expectExceptionalResult(IllegalCommandException.class);
        assertRejected("assert-only", 1);
    }

    @Test
    void collectionsNullsCyclesAndAnnotatedAccessorsAreTraversedOnce() {
        fixture(false).whenCommand(new Nested("nested")).expectSuccessfulResult();
        assertEquals(List.of("outer", "first", "second", "accessor", "leaf", "apply"), calls);
        // Reconstruction must not execute any assertions again.
        fixture.getFluxzero().modelRepository().load("nested", State.class);
        assertEquals(List.of("outer", "first", "second", "accessor", "leaf", "apply", "apply"), calls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedChecksKeepOriginalPayloadMetadataAndPhaseState(boolean async) {
        calls.clear();
        var builder = DefaultFluxzero.builder().registerUserProvider(new FixedUserProvider(new TestUser("caller")));
        fixture = async ? TestFixture.createAsync(builder) : TestFixture.create(builder);
        fixture.givenCommands(new Seed("phase", 1))
                .whenCommandByUser(new TestUser("caller"),
                                   new Message(new Phased("phase"), Metadata.of("marker", "original")))
                .expectSuccessfulResult();
        assertEquals(List.of("before:1", "after:2"), calls);
    }

    @Test
    void applicationParameterResolversAlsoApplyToNestedGuards() {
        Token token = new Token();
        inner.set(0);
        fixture = TestFixture.create(DefaultFluxzero.builder().addParameterResolver((parameter, annotation) ->
                parameter.getType() == Token.class ? message -> token : null));
        fixture.givenCommands(new SetInventory("custom", true))
                .whenExecuting(fc -> Fluxzero.assertLegal(new Custom("custom", token))).expectSuccessfulResult();
        assertEquals(1, inner.get());
    }

    @Test
    void unmatchedNestedMethodDoesNotLoadItsRequiredModels() {
        fixture(false).whenCommand(new Unmatched("unmatched")).expectSuccessfulResult();
        assertEquals(1, inner.get());
    }

    @Test
    void fieldsAndRecordComponentsDelegateInBothPhases() {
        fixture(false).whenCommand(new FieldPhases("fields"))
                .expectExceptionalResult(IllegalCommandException.class);
        assertEquals(List.of("same-name-method", "field-before", "apply", "field-after"), calls);
        assertNull(fixture.getFluxzero().modelRepository().load("fields", State.class).get());
    }

    @Test
    void explicitlyImplementedRecordAccessorsRemainDirectAssertions() {
        fixture(false).whenCommand(new ExplicitAccessor("accessor", new Leaf()))
                .expectExceptionalResult(IllegalCommandException.class);
        assertEquals(1, outer.get());
        assertNull(fixture.getFluxzero().modelRepository().load("accessor", State.class).get());
    }

    @Test
    void typedReferenceSelectsModelFieldsEvenWithoutMethods() {
        FieldModelId id = new FieldModelId("fields-only");
        fixture(false).givenCommands(new CreateFieldModel(id))
                .whenExecuting(fc -> Fluxzero.assertLegal(new CheckFieldModel(id)))
                .expectExceptionalResult(IllegalCommandException.class);
        assertEquals(1, inner.get());
    }

    @Test
    void registeredModelFieldsAlsoSupportUntypedIds() {
        inner.set(0);
        FieldModelId id = new FieldModelId("registered-fields");
        fixture = TestFixture.create(FieldModel.class);
        fixture.givenCommands(new CreateFieldModel(id))
                .whenExecuting(fc -> Fluxzero.assertLegal(new CheckRegisteredFieldModel(id.toString())))
                .expectExceptionalResult(IllegalCommandException.class);
        assertEquals(1, inner.get());
    }

    @Test
    void registeredFieldOnlyModelDoesNotAddRequiredReadsToUnrelatedCommands() {
        fixture = TestFixture.create(FieldOnly.class);
        fixture.whenCommand(new Seed("unrelated", 1)).expectSuccessfulResult();
        assertEquals(1, fixture.getFluxzero().modelRepository().load("unrelated", State.class).get().count());
        fixture.whenExecuting(fc -> Fluxzero.assertLegal(new Object())).expectSuccessfulResult();
    }

    @Test
    void retryTracksEveryModelInANestedCollectionInjection() {
        fixture(false).givenCommands(new Seed("collection", 1),
                                     new SetInventory("one", true), new SetInventory("two", true));
        race.set(() -> CompletableFuture.runAsync(() -> fixture.getFluxzero().apply(fc -> {
            Fluxzero.assertAndApply(new SetInventory("two", false));
            return null;
        })).join());
        fixture.whenExecuting(fc -> Fluxzero.assertAndApply(new WithCollection("collection", List.of("one", "two"))))
                .expectExceptionalResult(IllegalCommandException.class);
        assertEquals(2, inner.get());
        assertEquals(1, fixture.getFluxzero().modelRepository().load("collection", State.class).get().count());
    }

    @Test
    void nestedAncestorSelectionIsReevaluatedAfterReparenting() {
        fixture(false).givenCommands(new SetInventory("allowed", true), new SetInventory("denied", false),
                                     new SetChild("child", "allowed", 1));
        race.set(() -> CompletableFuture.runAsync(() -> fixture.getFluxzero().apply(fc -> {
            Fluxzero.assertAndApply(new SetChild("child", "denied", 10));
            return null;
        })).join());
        fixture.whenExecuting(fc -> Fluxzero.assertAndApply(new UpdateChild("child")))
                .expectExceptionalResult(IllegalCommandException.class);
        assertEquals(2, inner.get());
        assertEquals(10, fixture.getFluxzero().modelRepository().load("child", Child.class).get().count());
    }

    @Test
    void explicitGraphTargetAndNestedReadDependencyStayDistinct() {
        fixture(false).givenCommands(new Seed("graph", 1), new SetInventory("stock", false))
                .whenExecuting(fc -> Fluxzero.loadGraph("graph", State.class)
                        .assertAndApply(new GraphUpdate("stock")))
                .expectExceptionalResult(IllegalCommandException.class);
        assertEquals(1, inner.get());
        assertEquals(1, fixture.getFluxzero().modelRepository().load("graph", State.class).get().count());
    }

    @Test
    void splitPartsValidateTheirOwnComposedStateAndSuppressionSkipsGuards() {
        fixture(false).givenCommands(new Seed("split", 1))
                .whenCommand(new Split("split", false)).expectSuccessfulResult();
        assertEquals(List.of("part:1", "part:2"), calls);
        assertEquals(3, fixture.getFluxzero().modelRepository().load("split", State.class).get().count());
        fixture.whenCommand(new Split("split", true)).expectSuccessfulResult();
        assertEquals(List.of("part:1", "part:2"), calls);
    }

    @Test
    void aReturningMethodRunsOnlyInItsDeclaredPhase() {
        fixture(false).whenCommand(new MethodPhase("method-phase")).expectSuccessfulResult();
        assertEquals(List.of("return-before", "field-before"), calls);
    }

    @Test
    void unboundedFreshObjectNestingFailsBeforeApply() {
        fixture(false).whenCommand(new TooDeep("deep")).expectExceptionalResult(IllegalStateException.class);
        assertNull(fixture.getFluxzero().modelRepository().load("deep", State.class).get());
    }

    @Test
    void retryRechecksAChangedNestedReadDependency() {
        fixture(false).givenCommands(new Seed("retry", 1), new SetInventory("stock", true));
        race.set(() -> CompletableFuture.runAsync(() -> fixture.getFluxzero().apply(fc -> {
            Fluxzero.assertAndApply(new SetInventory("stock", false));
            return null;
        })).join());

        fixture.whenExecuting(fc -> Fluxzero.assertAndApply(new WithDependency("retry", "stock")))
                .expectExceptionalResult(IllegalCommandException.class);

        assertEquals(2, inner.get(), "RETRY must reevaluate the nested guard at the new boundary");
        assertEquals(1, fixture.getFluxzero().modelRepository().load("retry", State.class).get().count());
    }

    @Test
    void acceptRebasesChangedWritesWithoutRerunningNestedGuards() {
        fixture(false).givenCommands(new Seed("accept", 1), new SetInventory("stock", true));
        race.set(() -> CompletableFuture.runAsync(() -> fixture.getFluxzero().apply(fc -> {
            Fluxzero.assertAndApply(new SetInventory("stock", false));
            Fluxzero.assertAndApply(new Seed("accept", 10));
            return null;
        })).join());

        fixture.whenExecuting(fc -> Fluxzero.assertAndApply(new AcceptDependency("accept", "stock")))
                .expectSuccessfulResult();

        assertEquals(1, inner.get(), "ACCEPT rebase must preserve the accepted decision");
        assertEquals(11, fixture.getFluxzero().modelRepository().load("accept", State.class).get().count());
        assertEquals(1, inner.get(), "Replay must not rerun nested guards");
    }

    private void assertRejected(String id, int expectedOuter) {
        assertEquals(expectedOuter, outer.get());
        assertEquals(1, inner.get());
        assertNull(fixture.getFluxzero().modelRepository().load(id, State.class).get());
    }

    @Model(cached = false)
    record State(@EntityId String id, int count) {
    }

    record Before(String id) {
        @AssertLegal
        Object check() {
            outer.incrementAndGet();
            return new Deny();
        }

        @Apply
        State apply() {
            return new State(id, 1);
        }
    }

    record After(String id) {
        @AssertLegal(afterHandler = true)
        Object check() {
            outer.incrementAndGet();
            return new DenyAfter();
        }

        @Apply
        State apply() {
            return new State(id, 1);
        }
    }

    static class WithField {
        @JsonProperty
        final String id;
        @AssertLegal
        final Deny validation = new Deny();

        @JsonCreator
        WithField(@JsonProperty("id") String id) {
            this.id = id;
        }

        @Apply
        State apply() {
            return new State(id, 1);
        }
    }

    static class Deny {
        @AssertLegal
        void check() {
            inner.incrementAndGet();
            throw new IllegalCommandException("nested rejection");
        }
    }

    static class DenyAfter {
        @AssertLegal(afterHandler = true)
        void check() {
            inner.incrementAndGet();
            throw new IllegalCommandException("nested after rejection");
        }
    }

    record Nested(String id) {
        @AssertLegal
        Object checks() {
            calls.add("outer");
            Cycle cycle = new Cycle();
            return Arrays.asList(cycle, null, cycle);
        }

        @Apply
        State apply() {
            calls.add("apply");
            return new State(id, 1);
        }
    }

    static class Cycle {
        @AssertLegal(priority = 20)
        Object first() {
            calls.add("first");
            return this;
        }

        @AssertLegal(priority = 10)
        void second() {
            calls.add("second");
        }

        @AssertLegal
        Object getLeaf() {
            calls.add("accessor");
            return new Leaf();
        }
    }

    static class Leaf {
        @AssertLegal
        void check() {
            calls.add("leaf");
        }
    }

    record Seed(String id, int count) {
        @Apply
        State apply() {
            return new State(id, count);
        }
    }

    record Phased(String id) {
        @AssertLegal
        Object before() { return new PhaseGuard(false); }

        @AssertLegal(afterHandler = true)
        Object after() { return new PhaseGuard(true); }

        @Apply
        State apply(State state) { return new State(id, state.count() + 1); }
    }

    record PhaseGuard(boolean after) {
        @AssertLegal
        void before(Phased payload, Metadata metadata, User user, State state) {
            assertEquals(false, after);
            assertEquals("original", metadata.get("marker"));
            assertEquals("caller", user.getName());
            assertEquals(payload.id(), state.id());
            calls.add("before:" + state.count());
        }

        @AssertLegal(afterHandler = true)
        void after(Phased payload, Metadata metadata, User user, State state) {
            assertEquals(true, after);
            assertEquals("original", metadata.get("marker"));
            assertEquals("caller", user.getName());
            assertEquals(payload.id(), state.id());
            calls.add("after:" + state.count());
        }
    }

    record Custom(String inventoryId, Token expected) {
        @AssertLegal
        Object check() { return new CustomGuard(expected); }
    }

    record CustomGuard(Token expected) {
        @AssertLegal
        void check(Token actual, Inventory inventory) {
            assertSame(expected, actual);
            assertEquals(true, inventory.allowed());
            inner.incrementAndGet();
        }
    }

    static class Token { }

    record TestUser(String name) implements User {
        @Override
        public String getName() { return name; }

        @Override
        public boolean hasRole(String role) { return false; }
    }

    @Model
    record Inventory(@EntityId String inventoryId, boolean allowed) { }

    record SetInventory(String inventoryId, boolean allowed) {
        @Apply
        Inventory apply() { return new Inventory(inventoryId, allowed); }
    }

    record WithDependency(String id, String inventoryId) {
        @AssertLegal
        Object check() { return new InventoryGuard(); }

        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        State apply(State state) {
            runRace();
            return new State(id, state.count() + 1);
        }
    }

    record AcceptDependency(String id, String inventoryId) {
        @AssertLegal
        Object check() { return new InventoryGuard(); }

        @Apply(conflictPolicy = ModelConflictPolicy.ACCEPT)
        State apply(State state) {
            runRace();
            return new State(id, state.count() + 1);
        }
    }

    static class InventoryGuard {
        @AssertLegal
        void check(Inventory inventory) {
            inner.incrementAndGet();
            if (!inventory.allowed()) {
                throw new IllegalCommandException("inventory denied");
            }
        }
    }

    private static void runRace() {
        Runnable action = race.getAndSet(null);
        if (action != null) {
            action.run();
        }
    }

    record Unmatched(String id) {
        @AssertLegal
        Object check() { return new MixedGuard(); }

        @Apply
        State apply() { return new State(id, 1); }
    }

    static class MixedGuard {
        @AssertLegal(priority = 10)
        void other(Before differentPayload, Inventory missing) {
            throw new AssertionError("An unrelated guard must not be selected");
        }

        @AssertLegal
        void matching(Unmatched payload) { inner.incrementAndGet(); }
    }

    static class FieldPhases {
        final String id;
        @AssertLegal
        final GuardRecord check = new GuardRecord(new FieldGuard());

        FieldPhases(String id) { this.id = id; }

        @AssertLegal(priority = 10)
        void check() { calls.add("same-name-method"); }

        @Apply
        State apply() {
            calls.add("apply");
            return new State(id, 1);
        }
    }

    record GuardRecord(@AssertLegal FieldGuard guard) { }

    record ExplicitAccessor(String id, @AssertLegal Leaf guard) {
        @Override
        @AssertLegal
        public Leaf guard() {
            outer.incrementAndGet();
            throw new IllegalCommandException("explicit accessor denied");
        }

        @Apply
        State apply() { return new State(id, 1); }
    }

    static class FieldGuard {
        @AssertLegal
        void before() { calls.add("field-before"); }

        @AssertLegal(afterHandler = true)
        void after() {
            calls.add("field-after");
            throw new IllegalCommandException("field after rejection");
        }
    }

    static class FieldModelId extends Id<FieldModel> {
        FieldModelId(String id) { super(id); }
    }

    @Model
    record FieldModel(@EntityId FieldModelId fieldModelId, @AssertLegal Deny guard) { }

    record CreateFieldModel(FieldModelId fieldModelId) {
        @Apply
        FieldModel apply() { return new FieldModel(fieldModelId, new Deny()); }
    }

    record CheckFieldModel(FieldModelId fieldModelId) { }

    record CheckRegisteredFieldModel(String fieldModelId) { }

    @Model
    static class FieldOnly {
        @EntityId
        String otherId;
        @AssertLegal
        final Deny guard = new Deny();
    }

    record WithCollection(String id, List<String> inventoryIds) {
        @AssertLegal
        Object check() { return new CollectionGuard(); }

        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        State apply(State state) {
            runRace();
            return new State(id, state.count() + 1);
        }
    }

    static class CollectionGuard {
        @AssertLegal
        void check(@Association("inventoryIds") List<Graph<Inventory>> inventory) {
            inner.incrementAndGet();
            assertEquals(2, inventory.size());
            if (inventory.stream().anyMatch(value -> !value.get().allowed())) {
                throw new IllegalCommandException("collection denied");
            }
        }
    }

    @Model(cached = false)
    record Child(@EntityId String childId, @Parent(Inventory.class) String inventoryId, int count) { }

    record SetChild(String childId, String inventoryId, int count) {
        @Apply
        Child apply() { return new Child(childId, inventoryId, count); }
    }

    record UpdateChild(String childId) {
        @AssertLegal
        Object check() { return new InventoryGuard(); }

        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        Child apply(Child child) {
            runRace();
            return new Child(childId, child.inventoryId(), child.count() + 1);
        }
    }

    record GraphUpdate(String inventoryId) {
        @AssertLegal
        Object check() { return new GraphGuard(); }

        @Apply
        State apply(State state) { return new State(state.id(), state.count() + 1); }
    }

    static class GraphGuard {
        @AssertLegal
        void check(State selected, Inventory inventory) {
            assertEquals("graph", selected.id());
            inner.incrementAndGet();
            if (!inventory.allowed()) { throw new IllegalCommandException("graph guard denied"); }
        }
    }

    record Split(String id, boolean drop) {
        @AssertLegal
        Object check() { throw new AssertionError("Original split/suppressed payload must not be validated"); }

        @InterceptApply
        List<Part> split() { return drop ? null : List.of(new Part(id, 1), new Part(id, 2)); }
    }

    record Part(String id, int expected) {
        @AssertLegal
        Object check() { return new PartGuard(expected); }

        @Apply
        State apply(State state) { return new State(id, state.count() + 1); }
    }

    record PartGuard(int expected) {
        @AssertLegal
        void check(State state) {
            assertEquals(expected, state.count());
            calls.add("part:" + state.count());
        }
    }

    record MethodPhase(String id) {
        @AssertLegal
        Object check() {
            calls.add("return-before");
            return new FieldGuard();
        }

        @Apply
        State apply() { return new State(id, 1); }
    }

    record TooDeep(String id) {
        @AssertLegal
        Object check() { return new FreshGuard(); }

        @Apply
        State apply() { return new State(id, 1); }
    }

    static class FreshGuard {
        @AssertLegal
        Object next() { return new FreshGuard(); }
    }
}
