/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTrackingShutdownTest {

    @Test
    void unregisteringHandlersDoesNotSkipRemainingMessagesInCurrentBatch() throws Exception {
        SlowEventHandler handler = new SlowEventHandler();
        TestFixture testFixture = TestFixture.createAsync(
                        DefaultFluxzero.builder().configureDefaultConsumer(
                                MessageType.EVENT,
                                c -> c.toBuilder().batchInterceptor(
                                        StallingBatchInterceptor.builder()
                                                .desiredBatchSize(2)
                                                .maximumStallingDuration(Duration.ofSeconds(5))
                                                .retryFrequency(Duration.ofMillis(10))
                                                .build()).build()))
                .consumerTimeout(Duration.ofSeconds(2))
                .suppressPendingConsumerWarning();
        Registration registration = testFixture.getFluxzero().registerHandlers(handler);

        try {
            CompletableFuture<Void> processBatch = CompletableFuture.runAsync(() ->
                    testFixture.whenEventsAreApplied("aggregate", Object.class, 1, 2).expectNoErrors());

            handler.awaitFirstEventStarted();
            CompletableFuture<Void> unregister = CompletableFuture.runAsync(registration::cancel);
            // Give deregistration time to enter tracker cancellation while the first message is still handled.
            TimeUnit.MILLISECONDS.sleep(100L);

            handler.releaseFirstEvent();
            unregister.get(2, TimeUnit.SECONDS);
            processBatch.get(2, TimeUnit.SECONDS);

            assertEquals(List.of(1, 2), handler.handledEvents);
        } finally {
            handler.releaseFirstEvent();
            registration.cancel();
            testFixture.getFluxzero().close(true);
        }
    }

    private static class SlowEventHandler {
        private final CountDownLatch firstEventStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstEvent = new CountDownLatch(1);
        private final List<Integer> handledEvents = new CopyOnWriteArrayList<>();

        private void awaitFirstEventStarted() throws InterruptedException {
            assertTrue(firstEventStarted.await(1, TimeUnit.SECONDS));
        }

        private void releaseFirstEvent() {
            releaseFirstEvent.countDown();
        }

        @HandleEvent
        void handle(Integer event) throws InterruptedException {
            handledEvents.add(event);
            if (event == 1) {
                firstEventStarted.countDown();
                assertTrue(releaseFirstEvent.await(5, TimeUnit.SECONDS));
            }
        }
    }
}
