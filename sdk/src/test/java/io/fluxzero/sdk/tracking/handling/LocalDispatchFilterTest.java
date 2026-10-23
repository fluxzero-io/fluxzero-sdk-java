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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.MessageFilter;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.common.MessageType.COMMAND;
import static org.junit.jupiter.api.Assertions.*;

class LocalDispatchFilterTest {
    @Test
    void overlappingRestrictedDispatchesOfOneEnvelopeKeepIndependentScopes() throws Exception {
        var message = new DeserializingMessage(new Message("value"), COMMAND, new JacksonSerializer());
        var filter = new LocalDispatchFilter(HandlerFilter.ALWAYS_HANDLE, (m, e, a, t) -> false)
                .restrict(MessageFilter.allowAll());
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var firstExited = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                try {
                    LocalDispatchFilter.inDispatch(message, false, () -> {
                        firstEntered.countDown();
                        await(secondEntered);
                        assertFalse(filter.test(message, null, null, String.class));
                        return null;
                    });
                } finally {
                    firstExited.countDown();
                }
            });
            var second = executor.submit(() -> {
                await(firstEntered);
                LocalDispatchFilter.inDispatch(message, true, () -> {
                    assertTrue(filter.test(message, null, null, String.class),
                               "An independent ordinary dispatch must not inherit another thread's restriction");
                    return null;
                });
                LocalDispatchFilter.inDispatch(message, false, () -> {
                    secondEntered.countDown();
                    await(firstExited);
                    assertFalse(filter.test(message, null, null, String.class),
                                "The first dispatch must not clear the second dispatch's restriction");
                    return null;
                });
                assertTrue(filter.test(message, null, null, String.class));
            });
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertTrue(filter.test(message, null, null, String.class));
    }

    @Test
    void derivedEnvelopesShareRestrictionWhileNestedIndependentEnvelopesKeepTheirOwnScope() {
        var message = new DeserializingMessage(new Message("value"), COMMAND, new JacksonSerializer());
        var independent = new DeserializingMessage(new Message("other"), COMMAND, new JacksonSerializer());
        var filter = new LocalDispatchFilter(HandlerFilter.ALWAYS_HANDLE, (m, e, a, t) -> false)
                .restrict(MessageFilter.allowAll());
        var derived = LocalDispatchFilter.inDispatch(message, false, () -> {
            var view = message.withMetadata(Metadata.of("view", "derived"));
            assertFalse(filter.test(view, null, null, String.class));
            LocalDispatchFilter.inDispatch(view, false, () -> {
                assertFalse(filter.test(message, null, null, String.class));
                return null;
            });
            assertFalse(filter.test(view, null, null, String.class));
            LocalDispatchFilter.inDispatch(independent, true, () -> {
                assertTrue(filter.test(independent, null, null, String.class));
                return null;
            });
            LocalDispatchFilter.inDispatch(independent, false, () -> {
                assertFalse(filter.test(independent, null, null, String.class));
                assertFalse(filter.test(view, null, null, String.class));
                return null;
            });
            assertTrue(filter.test(independent, null, null, String.class));
            assertFalse(filter.test(message, null, null, String.class));
            return view;
        });
        assertTrue(filter.test(derived, null, null, String.class));
        assertTrue(filter.test(message, null, null, String.class));
        assertTrue(filter.test(independent, null, null, String.class));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Concurrent dispatch did not reach its coordination point");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating concurrent dispatch", e);
        }
    }

    @Test
    void restrictionIsScopedAndSurvivesNestedRestrictedCallsAndFailures() {
        var message = new DeserializingMessage(new Message("value"), COMMAND, new JacksonSerializer());
        var filter = new LocalDispatchFilter(HandlerFilter.ALWAYS_HANDLE, (m, e, a, t) -> false)
                .restrict(MessageFilter.allowAll());
        assertTrue(filter.test(message, null, null, String.class));
        assertThrows(IllegalStateException.class, () -> LocalDispatchFilter.inDispatch(message, false, () -> {
            assertFalse(filter.test(message, null, null, String.class));
            LocalDispatchFilter.inDispatch(message, false, () -> {
                assertFalse(filter.test(message, null, null, String.class));
                return null;
            });
            assertFalse(filter.test(message, null, null, String.class));
            throw new IllegalStateException("handler failed");
        }));
        assertTrue(filter.test(message, null, null, String.class));
        LocalDispatchFilter.inDispatch(message, true, () -> {
            assertTrue(filter.test(message, null, null, String.class));
            return null;
        });
    }
}
