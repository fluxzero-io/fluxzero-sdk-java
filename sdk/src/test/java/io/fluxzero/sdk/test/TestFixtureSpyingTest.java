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

package io.fluxzero.sdk.test;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

class TestFixtureSpyingTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryFixturesDoNotCreateComponentSpies(boolean async) {
        TestFixture fixture = TestFixture.create(new Handler());
        if (async) {
            fixture = fixture.async();
        }
        fixture.whenCommand(new InspectSpies()).expectResult(false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handlerInteractionsReachTheFixtureSpies(boolean async) {
        TestFixture fixture = fixture(async);

        fixture.whenCommand(new ReportError())
                .expectNoErrors()
                .expectThat(fc -> verify(fc.errorGateway()).report("reported"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handlersUseStubbedStoresAndRestoreTheCallingContext(boolean async) {
        TestFixture fixture = fixture(async);

        fixture.whenApplying(fc -> {
                    doReturn("stubbed").when(fc.keyValueStore()).get("key");
                    String result = Fluxzero.sendCommandAndWait(new ReadValue());
                    assertSame(fc, Fluxzero.get());
                    return result;
                }).expectResult("stubbed")
                .expectThat(fc -> verify(fc.keyValueStore()).get("key"));
    }

    private TestFixture fixture(boolean async) {
        TestFixture fixture = TestFixture.create(new Handler()).spy();
        return async ? fixture.async() : fixture;
    }

    private static class Handler {
        @HandleCommand
        boolean handle(InspectSpies ignored) {
            Fluxzero fc = Fluxzero.get();
            return Stream.of(fc.commandGateway(), fc.errorGateway(), fc.keyValueStore(), fc.aggregateRepository(),
                             fc.client().getEventStoreClient())
                    .anyMatch(component -> mockingDetails(component).isSpy());
        }

        @HandleCommand
        void handle(ReportError ignored) {
            Fluxzero.get().errorGateway().report("reported");
        }

        @HandleCommand
        String handle(ReadValue ignored) {
            return Fluxzero.get().keyValueStore().get("key");
        }
    }

    private record InspectSpies() {
    }

    private record ReportError() {
    }

    private record ReadValue() {
    }
}
