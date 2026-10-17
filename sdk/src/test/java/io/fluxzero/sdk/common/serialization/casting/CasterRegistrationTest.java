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

package io.fluxzero.sdk.common.serialization.casting;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import io.fluxzero.sdk.common.serialization.UnknownSerializedTypeException;
import io.fluxzero.sdk.common.serialization.UnknownTypeStrategy;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.registry.ConsumerBlueprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class CasterRegistrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictingBatchLeavesExistingAndNewInputRevisionsUnchanged(boolean down) {
        var chain = chain(down, new ExistingCaster());
        var before = chain.inputRevisions();
        var input = new Data<>("input", "fresh", 0);

        assertThrows(DeserializationException.class,
                () -> chain.registerCasterCandidates(new AddedCaster(), new ConflictingCaster()));

        assertEquals(before, chain.inputRevisions());
        assertSame(input, chain.castFirstOrNull(input, null));
        assertTrue(chain.canSkipCast(input, null));
        assertEquals("existing", chain.castFirstOrNull(new Data<>("input", "existing", 0), null).getValue());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictWithinOneBatchDoesNotRegisterEitherCaster(boolean down) {
        var chain = chain(down);
        assertThrows(DeserializationException.class,
                () -> chain.registerCasterCandidates(new ExistingCaster(), new ConflictingCaster()));
        assertTrue(chain.inputRevisions().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateRegistrationDoesNotOwnOrCancelTheOriginalCaster(boolean down) {
        var chain = chain(down);
        var caster = new AddedCaster();
        var owner = chain.registerCasterCandidates(caster);
        chain.registerCasterCandidates(caster).cancel();
        var input = new Data<>("input", "fresh", 0);
        assertEquals("added", chain.castFirstOrNull(input, null).getValue());
        owner.cancel();
        assertSame(input, chain.castFirstOrNull(input, null));
        var replacement = chain.registerCasterCandidates(caster);
        owner.cancel();
        assertEquals("added", chain.castFirstOrNull(input, null).getValue());
        replacement.cancel();
        assertSame(input, chain.castFirstOrNull(input, null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentConflictingBatchesPublishOnlyTheWinningBatch(boolean down) throws Exception {
        var chain = chain(down);
        var start = new CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> registerAfter(start, chain, new AddedCaster(), new ExistingCaster()));
            var second = executor.submit(() -> registerAfter(start, chain, new OtherCaster(), new ConflictingCaster()));
            start.countDown();
            assertNotEquals(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
            assertEquals(2, chain.inputRevisions().size());
            assertTrue(chain.inputRevisions().stream().anyMatch(input -> input.name().equals("existing")));
        }
    }

    @Test
    void failedUpcasterRegistrationCannotLeaveAnActiveCasterOutsideThePublishedBlueprint() {
        var serializer = new JacksonSerializer();
        serializer.registerUpcasters(new ExistingJsonCaster());
        var input = new SerializedMessage(new Data<>("\"accepted\"".getBytes(UTF_8), "legacy.New", 0, Data.JSON_FORMAT),
                Metadata.empty(), "legacy-message", 0L);
        try (var blueprint = new ConsumerBlueprint(serializer,
                () -> ConsumerFilter.select(null, 0L, ConsumerFilter.VERSION, null,
                        List.of(new ConsumerFilter.PayloadType(String.class.getName(), null)), null, List.of()),
                new AtomicLong()::incrementAndGet)) {
            var before = blueprint.getFilter();
            assertFalse(blueprint.mayHandle(input));
            assertThrows(DeserializationException.class,
                    () -> serializer.registerUpcasters(new AddedJsonCaster(), new ConflictingJsonCaster()));
            assertSame(before, blueprint.getFilter());
            assertFalse(blueprint.mayHandle(input));
            assertThrows(UnknownSerializedTypeException.class,
                    () -> serializer.deserialize(Stream.of(input), UnknownTypeStrategy.FAIL).toList());

            var registration = serializer.registerUpcasters(new AddedJsonCaster());
            assertTrue(blueprint.mayHandle(input));
            assertEquals("accepted", serializer.deserialize(input));
            registration.cancel();
            assertFalse(blueprint.mayHandle(input));
        }
    }

    private static CasterChain<Data<String>, Data<String>> chain(boolean down, Object... candidates) {
        return down ? DefaultCasterChain.createDowncaster(List.of(candidates), String.class)
                : DefaultCasterChain.createUpcaster(List.of(candidates), String.class);
    }

    private static boolean registerAfter(CountDownLatch start, CasterChain<?, ?> chain, Object... candidates)
            throws InterruptedException {
        assertTrue(start.await(2, TimeUnit.SECONDS));
        try {
            chain.registerCasterCandidates(candidates);
            return true;
        } catch (DeserializationException expectedConflict) {
            return false;
        }
    }

    static class ExistingCaster {
        @Upcast(type = "existing", revision = 0)
        @Downcast(type = "existing", revision = 0)
        String cast(String ignored) { return "existing"; }
    }

    static class ConflictingCaster {
        @Upcast(type = "existing", revision = 0)
        @Downcast(type = "existing", revision = 0)
        String cast(String ignored) { return "conflicting"; }
    }

    static class AddedCaster {
        @Upcast(type = "fresh", revision = 0)
        @Downcast(type = "fresh", revision = 0)
        String cast(String ignored) { return "added"; }
    }

    static class OtherCaster {
        @Upcast(type = "other", revision = 0)
        @Downcast(type = "other", revision = 0)
        String cast(String ignored) { return "other"; }
    }

    static class ExistingJsonCaster {
        @Upcast(type = "legacy.Existing", revision = 0)
        JsonNode cast(JsonNode input) { return input; }
    }

    static class ConflictingJsonCaster {
        @Upcast(type = "legacy.Existing", revision = 0)
        JsonNode cast(JsonNode input) { return input; }
    }

    static class AddedJsonCaster {
        @Upcast(type = "legacy.New", revision = 0)
        Data<JsonNode> cast(JsonNode input) { return new Data<>(input, String.class.getName(), 0); }
    }
}
