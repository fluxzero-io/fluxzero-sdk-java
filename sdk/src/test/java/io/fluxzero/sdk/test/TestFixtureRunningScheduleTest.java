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

package io.fluxzero.sdk.test;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.TestTask;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestFixtureRunningScheduleTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(60);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void waitsForRunningAttemptAfterRescheduling() throws Exception {
        var handler = new PausedHandler(
                () -> Fluxzero.schedule("next attempt", "retry", Duration.ofHours(1)));
        var fixture = fixture(handler);

        assertWaitsForHandler(fixture, handler).expectOnlySchedules("next attempt");
    }

    @Test
    void waitsForRunningAttemptAfterCancellation() throws Exception {
        var handler = new PausedHandler(() -> Fluxzero.cancelSchedule("retry"));
        var fixture = fixture(handler);

        assertWaitsForHandler(fixture, handler)
                .andThen().whenTimeElapses(Duration.ofHours(1)).expectNoEvents().expectNoSchedules();
    }

    @Test
    void waitsForRunningAttemptWithoutScheduleChanges() throws Exception {
        var handler = new PausedHandler(() -> {});
        assertWaitsForHandler(fixture(handler), handler);
    }

    @Test
    void waitsForConfiguredAsyncResultAfterRescheduling() throws Exception {
        var handler = new AwaitedAsyncHandler();
        var fixture = fixture(handler);
        try (var execution = new TestTask(
                () -> fixture.whenTimeAdvancesTo(DEADLINE).expectNoErrors(),
                () -> handler.result.complete(null))) {
            assertTrue(handler.entered.await(2, SECONDS));
            execution.awaitBlockedIn(TestFixture.class, "waitForConsumers", TIMEOUT);
            assertFalse(fixture.checkConsumers());
            handler.result.complete(null);
            execution.awaitCompletion(TIMEOUT);
        }
        assertTrue(fixture.checkConsumers());
    }

    @Test
    void doesNotAwaitDetachedAsyncResult() {
        var handler = new AsyncHandler();
        var fixture = fixture(handler);
        try {
            fixture.whenTimeAdvancesTo(DEADLINE).expectNoErrors().expectOnlySchedules("next attempt");
            assertFalse(handler.result.isDone());
            assertTrue(fixture.checkConsumers());
        } finally {
            handler.result.complete(null);
        }
    }

    @Test
    void reportsFailureAndProcessesTheRescheduledAttempt() {
        var fixture = fixture(new Object() {
            @HandleSchedule
            void handle(String attempt) {
                if (attempt.equals("current attempt")) {
                    Fluxzero.schedule("next attempt", "retry", Duration.ofMinutes(1));
                    throw new IllegalStateException("retry later");
                }
                Fluxzero.publishEvent("retried");
            }
        });

        fixture.whenTimeAdvancesTo(DEADLINE).expectError(IllegalStateException.class)
                .andThen().whenTimeElapses(Duration.ofMinutes(1)).expectOnlyEvents("retried").expectNoErrors();

        assertTrue(fixture.checkConsumers());
    }

    @Test
    void completingOneTrackerKeepsAnotherRunningTrackerPending() throws Exception {
        var fixture = TestFixture.createAsync();
        var interceptor = new TestFixture.GivenWhenThenInterceptor(fixture);
        var tracker = scheduleTracker();
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var first = interceptor.intercept(batch -> {
            entered.countDown();
            release.join();
        }, tracker);
        var second = interceptor.intercept(batch -> {}, tracker);

        try (var execution = new TestTask(() -> first.accept(emptyBatch()), () -> release.complete(null))) {
            assertTrue(entered.await(2, SECONDS));
            second.accept(emptyBatch());
            assertFalse(fixture.checkConsumers());
            release.complete(null);
            execution.awaitCompletion(TIMEOUT);
        }
        assertTrue(fixture.checkConsumers());
    }

    @Test
    void failingBatchPropagatesFailureAndReleasesRunningTracker() {
        var fixture = TestFixture.createAsync();
        var failure = new IllegalStateException("batch failure");
        var consumer = new TestFixture.GivenWhenThenInterceptor(fixture).intercept(batch -> {
            throw failure;
        }, scheduleTracker());

        assertSame(failure, assertThrows(IllegalStateException.class, () -> consumer.accept(emptyBatch())));
        assertTrue(fixture.checkConsumers());
    }

    private static Tracker scheduleTracker() {
        var tracker = mock(Tracker.class);
        when(tracker.getMessageType()).thenReturn(MessageType.SCHEDULE);
        when(tracker.getConfiguration()).thenReturn(ConsumerConfiguration.builder().name("schedules").build());
        return tracker;
    }

    private static MessageBatch emptyBatch() {
        return new MessageBatch(new int[]{0, 128}, List.of(), null, Position.newPosition(), true);
    }

    private static TestFixture fixture(Object handler) {
        return TestFixture.createAsync(handler).atFixedTime(NOW)
                .givenSchedules(new Schedule("current attempt", "retry", DEADLINE));
    }

    private static Then<?> assertWaitsForHandler(TestFixture fixture, PausedHandler handler) throws Exception {
        var result = new AtomicReference<Then<?>>();
        try (var execution = new TestTask(
                () -> result.set(fixture.whenTimeAdvancesTo(DEADLINE).expectOnlyEvents("finished").expectNoErrors()),
                handler.release::countDown)) {
            assertTrue(handler.entered.await(2, SECONDS));
            execution.awaitBlockedIn(TestFixture.class, "waitForConsumers", TIMEOUT);
            assertFalse(fixture.checkConsumers());
            handler.release.countDown();
            execution.awaitCompletion(TIMEOUT);
        }
        assertTrue(fixture.checkConsumers());
        return result.get();
    }

    private static class PausedHandler {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Runnable duringHandling;

        PausedHandler(Runnable duringHandling) {
            this.duringHandling = duringHandling;
        }

        @HandleSchedule
        void handle(String value) throws InterruptedException {
            duringHandling.run();
            entered.countDown();
            assertTrue(release.await(5, SECONDS));
            Fluxzero.publishEvent("finished");
        }
    }

    private static class AsyncHandler {
        final CountDownLatch entered = new CountDownLatch(1);
        final CompletableFuture<Void> result = new CompletableFuture<>();

        @HandleSchedule
        CompletableFuture<Void> handle(String value) {
            Fluxzero.schedule("next attempt", "retry", Duration.ofHours(1));
            entered.countDown();
            return result;
        }
    }

    @Consumer(name = "async-schedules", awaitAsyncResults = true)
    private static class AwaitedAsyncHandler extends AsyncHandler {}
}
