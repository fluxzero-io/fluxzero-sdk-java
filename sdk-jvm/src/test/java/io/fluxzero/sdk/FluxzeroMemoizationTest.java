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

package io.fluxzero.sdk;

import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FluxzeroMemoizationTest {
    TestFixture fixture = TestFixture.create();

    @Test
    void memoizeScopesEntriesToCallingClassByDefault() {
        fixture.whenExecuting(fz -> FirstScope.memoize("key", "value"))
               .expectThat(fz -> {
                    assertEquals("value", FirstScope.get("key"));
                    assertNull(SecondScope.get("key"));
               });
    }

    @Test
    void memoizeGlobalSharesEntriesAcrossClasses() {
        fixture.whenExecuting(fz -> FirstScope.memoizeGlobally("key", "value"))
               .expectThat(fz -> {
                   assertEquals("value", FirstScope.getGlobal("key"));
                   assertEquals("value", SecondScope.getGlobal("key"));
               });
    }

    @Test
    void memoizeScopesEntriesToCurrentFluxzeroInstance() {
        TestFixture firstFixture = TestFixture.create();
        TestFixture secondFixture = TestFixture.create();

        firstFixture.whenExecuting(fz -> FirstScope.memoize("key", "first"))
                    .expectThat(fz -> assertEquals("first", FirstScope.get("key")));

        secondFixture.whenExecuting(fz -> {
                        assertNull(FirstScope.get("key"));
                        FirstScope.memoize("key", "second");
                    })
                    .expectThat(fz -> assertEquals("second", FirstScope.get("key")));

        firstFixture.whenNothingHappens()
                    .expectThat(fz -> assertEquals("first", FirstScope.get("key")));
    }

    @Test
    void memoizeIfAbsentRecomputesAfterExpiry() {
        AtomicInteger counter = new AtomicInteger();

        fixture.whenExecuting(fz -> assertEquals(1,
                                                 Fluxzero.<String, Integer>memoizeIfAbsent("key",
                                                                                           k -> counter.incrementAndGet(),
                                                                                           Duration.ofSeconds(10))))
               .andThen()
               .givenElapsedTime(Duration.ofSeconds(11))
               .whenExecuting(fz -> assertEquals(2,
                                                 Fluxzero.<String, Integer>memoizeIfAbsent("key",
                                                                                           k -> counter.incrementAndGet(),
                                                                                           Duration.ofSeconds(10))));
    }

    @Test
    void memoizeSuppliesCurrentValue() {
        assertEquals(1,
                     Fluxzero.<String, Integer>memoize("key",
                                                       (key, current) -> current == null ? 1 : current + 1));
        assertEquals(2,
                     Fluxzero.<String, Integer>memoize("key",
                                                       (key, current) -> current == null ? 1 : current + 1));
    }

    @Test
    void removeMemoizedRemovesScopedEntry() {
        fixture.whenExecuting(fz -> FirstScope.memoize("key", "value"))
               .expectThat(fz -> {
                   assertEquals("value", fz.memoization().remove(new Fluxzero.MemoizationKey(FirstScope.class, "key")));
                   assertNull(FirstScope.get("key"));
               });
    }

    private static class FirstScope {
        static void memoize(String key, String value) {
            Fluxzero.memoize(key, value);
        }

        static void memoizeGlobally(String key, String value) {
            Fluxzero.memoizeGlobally(key, value);
        }

        static String get(String key) {
            return Fluxzero.getMemoized(key);
        }

        static String getGlobal(String key) {
            return Fluxzero.getGloballyMemoized(key);
        }
    }

    private static class SecondScope {
        static String get(String key) {
            return Fluxzero.getMemoized(key);
        }

        static String getGlobal(String key) {
            return Fluxzero.getGloballyMemoized(key);
        }
    }
}
