/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyEntityConsumerFilterTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unannotatedEntityArgumentsRemainAvailableForDifferentPayloadTypes(boolean async) throws Exception {
        var handler = new Handler();
        var fixture = async ? TestFixture.createAsync(handler) : TestFixture.create(handler);
        try (var app = fixture.getFluxzero()) {
            fixture.givenAppliedEvents("state", State.class, new Created("stored state"))
                    .whenEvent(new Message(new Notice()).addMetadata(Metadata.of(
                            Entity.AGGREGATE_ID_METADATA_KEY, "state",
                            Entity.AGGREGATE_TYPE_METADATA_KEY, State.class.getName())))
                    .expectNoErrors();
            assertEquals(new State("stored state"), handler.result.get(2, TimeUnit.SECONDS));
        }
    }

    record Created(String value) {}
    record Notice() {}
    // Legacy aggregates deliberately do not require @Aggregate or @Model.
    record State(String value) {
        @Apply static State create(Created event) { return new State(event.value()); }
    }
    static class Handler {
        final CompletableFuture<State> result = new CompletableFuture<>();
        @HandleEvent void handle(State state, DeserializingMessage message) {
            if (Notice.class.getName().equals(message.getType())) result.complete(state);
        }
    }
}
