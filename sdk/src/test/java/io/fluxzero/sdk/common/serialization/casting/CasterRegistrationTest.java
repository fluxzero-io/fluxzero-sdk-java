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

import io.fluxzero.common.api.Data;
import io.fluxzero.sdk.common.serialization.DeserializationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CasterRegistrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictingBatchLeavesExistingAndNewInputRevisionsUnchanged(boolean down) {
        var chain = chain(down, new ExistingCaster());
        var input = new Data<>("input", "fresh", 0);

        assertThrows(DeserializationException.class,
                () -> chain.registerCasterCandidates(new AddedCaster(), new ConflictingCaster()));

        assertSame(input, chain.castFirstOrNull(input, null));
        assertEquals("existing", chain.castFirstOrNull(new Data<>("input", "existing", 0), null).getValue());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictWithinOneBatchDoesNotRegisterEitherCaster(boolean down) {
        var chain = chain(down);
        assertThrows(DeserializationException.class,
                () -> chain.registerCasterCandidates(new ExistingCaster(), new ConflictingCaster()));
        var input = new Data<>("input", "existing", 0);
        assertSame(input, chain.castFirstOrNull(input, null));
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
            boolean firstWon = first.get(2, TimeUnit.SECONDS);
            assertNotEquals(firstWon, second.get(2, TimeUnit.SECONDS));
            assertEquals(firstWon ? "existing" : "conflicting",
                    chain.castFirstOrNull(new Data<>("input", "existing", 0), null).getValue());
            assertEquals(firstWon ? "added" : "input",
                    chain.castFirstOrNull(new Data<>("input", "fresh", 0), null).getValue());
            assertEquals(firstWon ? "input" : "other",
                    chain.castFirstOrNull(new Data<>("input", "other", 0), null).getValue());
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

}
