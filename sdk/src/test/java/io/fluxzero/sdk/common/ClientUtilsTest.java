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

package io.fluxzero.sdk.common;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.MemoizingSupplier;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientUtilsTest {

    @Test
    void handleOnlyTracking() throws Exception {
        assertTrue(
                ClientUtils.isTrackingHandler(Handler.class, Handler.class.getDeclaredMethod("handle", String.class)));
        assertFalse(ClientUtils.getLocalHandlerAnnotation(Handler.class,
                                                          Handler.class.getDeclaredMethod("handle", String.class))
                            .isPresent());
    }

    @Test
    void handleOnlyLocal() throws Exception {
        assertTrue(ClientUtils.getLocalHandlerAnnotation(Handler.class,
                                                         Handler.class.getDeclaredMethod("handleOnlyLocal", int.class))
                           .isPresent());
        assertFalse(ClientUtils.isTrackingHandler(Handler.class,
                                                  Handler.class.getDeclaredMethod("handleOnlyLocal", int.class)));
    }

    @Test
    void handleLocalAndExternal() throws Exception {
        assertTrue(ClientUtils.getLocalHandlerAnnotation(Handler.class,
                                                         Handler.class.getDeclaredMethod("handleLocalOrExternal",
                                                                                         double.class)).isPresent());
        assertTrue(ClientUtils.isTrackingHandler(Handler.class, Handler.class.getDeclaredMethod("handleLocalOrExternal",
                                                                                                double.class)));
    }

    @Test
    void localHandlerNamespaceIsClearedAfterFailure() {
        DeserializingMessage message = new DeserializingMessage(
                new Message("local"), MessageType.COMMAND, new JacksonSerializer());
        Tracker.current.set(new Tracker("tracker", MessageType.EVENT, null,
                                        ConsumerConfiguration.builder().name("customer-events")
                                                .namespace("customer").build(), null));
        try {
            assertThrows(IllegalStateException.class, () -> ClientUtils.runInLocalHandlerNamespace(() -> {
                assertNull(ClientUtils.getConsumerNamespace(message));
                throw new IllegalStateException("failed local handler");
            }));
            assertEquals("customer", ClientUtils.getConsumerNamespace(message));
        } finally {
            Tracker.current.remove();
        }
    }

    @Nested
    class MemoizeTests {
        final TestFixture testFixture = TestFixture.create(new Object() {
            @HandleCommand
            int handle() {
                return supplier.get();
            }
        });

        final AtomicInteger counter = new AtomicInteger();
        final MemoizingSupplier<Integer> supplier =
                ClientUtils.memoize(counter::incrementAndGet, Duration.ofSeconds(10));

        @Test
        void memoizeWithLifespan_refreshValueAfterLifespan() {
            testFixture.givenCommands(new Object()).givenElapsedTime(Duration.ofSeconds(15))
                    .whenCommand(new Object()).expectResult(2);
        }

        @Test
        void memoizeWithLifespan_dontRefreshBeforeLifespan() {
            testFixture.givenCommands(new Object()).givenElapsedTime(Duration.ofSeconds(5))
                    .whenCommand(new Object()).expectResult(1);
        }

        @Test
        void memoizeWithLifespan_cleared() {
            testFixture.givenCommands(new Object()).givenElapsedTime(Duration.ofSeconds(5))
                    .given(fc -> supplier.clear())
                    .whenCommand(new Object()).expectResult(2);
        }
    }

    private static class Handler {
        @HandleCommand
        String handle(String command) {
            return command;
        }

        @HandleCommand
        @LocalHandler
        int handleOnlyLocal(int command) {
            return command;
        }

        @HandleCommand
        @LocalHandler(allowExternalMessages = true)
        double handleLocalOrExternal(double command) {
            return command;
        }
    }
}
