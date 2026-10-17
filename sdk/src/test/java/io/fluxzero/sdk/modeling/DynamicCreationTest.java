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
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.test.TestFixture;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DynamicCreationTest {
    private final ItemId id = new ItemId("new");
    private TestFixture fixture;

    @AfterEach
    void close() {
        if (fixture != null) { fixture.getFluxzero().close(); }
    }

    private TestFixture fixture(boolean async) {
        return fixture = async ? TestFixture.createAsync() : TestFixture.create();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unreadIdentityCanBeCreatedByCollection(boolean async) {
        fixture(async).whenCommand(new Unread(id))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(new Item(id, 1), Fluxzero.loadModel(id).get());
                }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentNullableAssertionTargetCanBeCreatedByCollection(boolean async) {
        assertCreated(fixture(async), new Checked(id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentNullableInterceptorTargetCanBeCreatedByCollection(boolean async) {
        assertCreated(fixture(async), new Intercepted(id));
    }

    @Test
    void absentNullableAssertionTargetCanBeCreatedByDynamicResult() {
        assertCreated(fixture(false), new DynamicChecked(id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentNullableAssertionTargetCanBeCreatedBySingularResult(boolean async) {
        fixture(async).whenCommand(new SingularChecked(id))
                .expectSuccessfulResult()
                .expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(new Item(id, 1), Fluxzero.loadModel(id).get());
                }).expectNoErrors();
    }

    private void assertCreated(TestFixture fixture, Object command) {
        fixture.whenCommand(command).expectSuccessfulResult().expectThat(fc -> {
            fc.cache().clear();
            assertEquals(new Item(id, 1), Fluxzero.loadModel(id).get());
        }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletedNullableTargetCanBeRecreatedWithItsHistory(boolean async) {
        fixture(async).givenCommands(new Unread(id), new Delete(id))
                .whenCommand(new Checked(id)).expectSuccessfulResult().expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(new Item(id, 1), Fluxzero.loadModel(id).get());
                    assertEquals(3, fc.eventStore().getEvents(id.toString()).count());
                }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void earlierSubstepDeletionCanBeRecreatedAtomically(boolean async) {
        fixture(async).givenCommands(new Unread(id))
                .whenCommand(new Recreate(id)).expectSuccessfulResult().expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(new Item(id, 1), Fluxzero.loadModel(id).get());
                    assertEquals(3, fc.eventStore().getEvents(id.toString()).count());
                }).expectNoErrors();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingReadOnlyTargetIsNotPromotedToAWrite(boolean async) {
        fixture(async).givenCommands(new Unread(id))
                .whenCommand(new ReadOnlyChecked(id))
                .expectExceptionalResult((Throwable error) ->
                        (error instanceof IllegalStateException || error instanceof TechnicalException)
                        && error.getMessage().contains("not a resolved write target"), "Read-only target rejected")
                .expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(new Item(id, 1), Fluxzero.loadModel(id).get());
                    assertEquals(1, fc.eventStore().getEvents(id.toString()).count());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedGraphUpdateAndCheckedCreationRetainBothRevisions(boolean async) {
        var existingId = new ItemId("existing");
        fixture(async).givenCommands(new Unread(existingId))
                .whenCommand(new Mixed(existingId, id)).expectSuccessfulResult().expectThat(fc -> {
                    fc.cache().clear();
                    assertEquals(new Item(existingId, 2), Fluxzero.loadModel(existingId).get());
                    assertEquals(new Item(id, 1), Fluxzero.loadModel(id).get());
                }).expectNoErrors();
    }

    record Delete(ItemId itemId) {
        @Apply Item apply(Item item) { return null; }
    }

    record Recreate(ItemId itemId) {
        @InterceptApply List<Object> intercept() { return List.of(new Delete(itemId), new Checked(itemId)); }
    }

    record ReadOnlyChecked(ItemId itemId) {
        @AssertLegal void check(@Nullable Item item) {}
        @Apply List<Item> apply() { return List.of(new Item(itemId, 2)); }
    }

    record Mixed(ItemId existingId, ItemId itemId) {
        @AssertLegal void check(@io.fluxzero.sdk.tracking.handling.Association("itemId") @Nullable Item item) {
            assertNull(item);
        }
        @Apply List<Item> apply(@io.fluxzero.sdk.tracking.handling.Association("existingId") Graph<Item> existing) {
            return List.of(new Item(existingId, existing.get().value() + 1), new Item(itemId, 1));
        }
    }

    @Model
    record Item(@EntityId ItemId itemId, int value) {}

    static final class ItemId extends Id<Item> {
        ItemId(String id) { super(id, "item-"); }
    }

    record Unread(ItemId itemId) {
        @Apply List<Item> apply() { return List.of(new Item(itemId, 1)); }
    }

    record Checked(ItemId itemId) {
        @AssertLegal void check(@Nullable Item item) { assertNull(item); }
        @Apply List<Item> apply() { return List.of(new Item(itemId, 1)); }
    }

    record Intercepted(ItemId itemId) {
        @InterceptApply Object intercept(@Nullable Item item) { return item == null ? this : null; }
        @Apply List<Item> apply() { return List.of(new Item(itemId, 1)); }
    }

    record DynamicChecked(ItemId itemId) {
        @AssertLegal void check(@Nullable Item item) { assertNull(item); }
        @Apply Object apply() { return new Item(itemId, 1); }
    }

    record SingularChecked(ItemId itemId) {
        @AssertLegal void check(@Nullable Item item) { assertNull(item); }
        @Apply Item apply() { return new Item(itemId, 1); }
    }
}
