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
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.fluxzero.sdk.modeling.AutomaticModelHandling.DISABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InheritedModelHandlerTest {
    private static final IllegalCommandException NEGATIVE = new IllegalCommandException("Negative increment");
    private TestFixture fixture;

    @AfterEach
    void close() {
        fixture.getFluxzero().close();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void selectedModelReceivesItsInheritedUpdate(boolean async, boolean reverseOrder) {
        givenCounters(async, reverseOrder).whenCommand(new IncrementFirst(1))
                .expectSuccessfulResult().expectOnlyEvents(new Increment("first", 1))
                .expectThat(fc -> assertCounters(fc, 11, 2));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void selectedModelRunsItsInheritedAssertion(boolean async, boolean reverseOrder) {
        givenCounters(async, reverseOrder).whenCommand(new IncrementFirst(-1))
                .expectExceptionalResult(NEGATIVE).expectNoEvents()
                .expectThat(fc -> assertCounters(fc, 10, 1));
    }

    private TestFixture givenCounters(boolean async, boolean reverseOrder) {
        fixture = async ? TestFixture.createAsync(new Commands()) : TestFixture.create(new Commands());
        fixture.registerHandlers(reverseOrder ? Second.class : First.class, reverseOrder ? First.class : Second.class);
        return fixture.given(fc -> {
            Fluxzero.loadCurrentGraph("first", First.class).update(ignored -> new First("first", 10)).commit();
            Fluxzero.loadCurrentGraph("second", Second.class).update(ignored -> new Second("second", 20)).commit();
        });
    }

    private void assertCounters(Fluxzero fc, int firstValue, int firstEvents) {
        assertEquals(new First("first", firstValue), Fluxzero.loadCurrentGraph("first", First.class).get());
        assertEquals(new Second("second", 20), Fluxzero.loadCurrentGraph("second", Second.class).get());
        assertEquals(firstEvents, fc.eventStore().getEvents("first").count());
        assertEquals(1, fc.eventStore().getEvents("second").count());
    }

    record IncrementFirst(int amount) {}
    record Increment(String id, int amount) {}

    static class Commands {
        @HandleCommand
        void handle(IncrementFirst command) {
            Fluxzero.loadCurrentGraph("first", First.class).assertAndApply(new Increment("first", command.amount()));
        }
    }

    interface Counter<T> {
        int value();
        T withValue(int value);

        @Apply
        default T increment(Increment update) {
            return withValue(value() + update.amount());
        }

        @AssertLegal
        default void check(Increment update) {
            if (update.amount() < 0) {
                throw NEGATIVE;
            }
        }
    }

    @Model(cached = false, automaticHandling = DISABLED)
    record First(@EntityId String id, int value) implements Counter<First> {
        public First withValue(int value) { return new First(id, value); }
    }

    @Model(cached = false, automaticHandling = DISABLED)
    record Second(@EntityId String id, int value) implements Counter<Second> {
        public Second withValue(int value) { return new Second(id, value); }
    }
}
