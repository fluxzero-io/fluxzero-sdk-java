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

package io.fluxzero.sdk.common.serialization;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.registry.ConsumerBlueprint;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerFilterNotificationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failingRefreshStillUpdatesEveryConsumerAndEveryPeer(boolean alias) {
        var serializer = new JacksonSerializer();
        try (var first = blueprint(serializer); var second = blueprint(serializer)) {
            var firstPeer = new AtomicReference<ConsumerFilter>();
            var secondPeer = new AtomicReference<ConsumerFilter>();
            var firstFailure = new IllegalStateException("first peer failed");
            var secondFailure = new IllegalArgumentException("second peer failed");
            var lastFailure = new IllegalStateException("another serializer listener failed");
            first.observe(filter -> { throw firstFailure; });
            first.observe(filter -> { throw secondFailure; });
            first.observe(firstPeer::set);
            second.observe(secondPeer::set);
            serializer.observeConsumerFilters(() -> { throw lastFailure; });
            var input = legacyMessage();
            assertFalse(first.mayHandle(input));
            assertFalse(second.mayHandle(input));

            var thrown = assertThrows(IllegalStateException.class, () -> register(serializer, alias));

            assertSame(firstFailure, thrown);
            assertArrayEquals(new Throwable[]{secondFailure, lastFailure}, thrown.getSuppressed());
            assertSame(first.getFilter(), firstPeer.get());
            assertSame(second.getFilter(), secondPeer.get());
            assertTrue(first.mayHandle(input));
            assertTrue(second.mayHandle(input));
            assertEquals("accepted", serializer.deserialize(input));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failingCancellationStillUpdatesEveryConsumerAndEveryPeer(boolean alias) {
        var serializer = new JacksonSerializer();
        var registration = register(serializer, alias);
        try (var first = blueprint(serializer); var second = blueprint(serializer)) {
            var firstPeer = new AtomicReference<ConsumerFilter>();
            var secondPeer = new AtomicReference<ConsumerFilter>();
            var failure = new IllegalStateException("peer failed");
            first.observe(filter -> { throw failure; });
            first.observe(firstPeer::set);
            second.observe(secondPeer::set);
            var input = legacyMessage();
            assertTrue(first.mayHandle(input));
            assertTrue(second.mayHandle(input));

            assertSame(failure, assertThrows(IllegalStateException.class, registration::cancel));

            assertSame(first.getFilter(), firstPeer.get());
            assertSame(second.getFilter(), secondPeer.get());
            assertFalse(first.mayHandle(input));
            assertFalse(second.mayHandle(input));
        }
    }

    private static ConsumerBlueprint blueprint(JacksonSerializer serializer) {
        return new ConsumerBlueprint(serializer,
                () -> ConsumerFilter.select(null, 0L, ConsumerFilter.VERSION, null,
                        List.of(new ConsumerFilter.PayloadType(String.class.getName(), null)), null, List.of()),
                new AtomicLong()::incrementAndGet);
    }

    private static Registration register(JacksonSerializer serializer, boolean alias) {
        return alias ? serializer.registerTypeAlias("legacy.New", String.class.getName())
                : serializer.registerUpcasters(new RenamingCaster());
    }

    private static SerializedMessage legacyMessage() {
        return new SerializedMessage(new Data<>("\"accepted\"".getBytes(UTF_8), "legacy.New", 0, Data.JSON_FORMAT),
                Metadata.empty(), "legacy-message", 0L);
    }

    static class RenamingCaster {
        @Upcast(type = "legacy.New", revision = 0)
        Data<JsonNode> rename(JsonNode input) {
            return new Data<>(input, String.class.getName(), 0);
        }
    }
}
