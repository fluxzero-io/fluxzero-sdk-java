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

/** Characterizes the current rejection; it does not endorse that rejection as the intended contract. */
class DynamicReadTargetCharacterizationTest {
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
    void absentNullableAssertionTargetIsRejectedByCollection(boolean async) {
        assertRejected(fixture(async), new Checked(id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentNullableInterceptorTargetIsRejectedByCollection(boolean async) {
        assertRejected(fixture(async), new Intercepted(id));
    }

    @Test
    void absentNullableAssertionTargetIsRejectedByDynamicResult() {
        assertRejected(fixture(false), new DynamicChecked(id));
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

    private void assertRejected(TestFixture fixture, Object command) {
        fixture.whenCommand(command)
                .expectExceptionalResult((Throwable error) ->
                        (error instanceof IllegalStateException || error instanceof TechnicalException)
                        && error.getMessage().contains("returned model 'item-new', which is not a resolved write target"),
                        "The known unresolved write-target rejection")
                .expectThat(fc -> {
                    assertNull(Fluxzero.loadModel(id).get());
                    assertEquals(0, fc.eventStore().getEvents(id.toString()).count());
                });
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
