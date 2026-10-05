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

package io.fluxzero.sdk.scheduling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.ThrowingRunnable;
import io.fluxzero.common.TestTask;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelDeadlineUpdate;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultMessageSchedulerDeadlineTest {
    final Instant due = Instant.parse("2030-01-01T00:00:00Z");
    final SchedulingClient transport = mock(SchedulingClient.class);
    final TaskScheduler timer = mock(TaskScheduler.class);
    final HandlerRegistry handlers = mock(HandlerRegistry.class);
    final JacksonSerializer serializer = new JacksonSerializer();
    final List<Timer> timers = new ArrayList<>();
    final AtomicReference<SerializedSchedule> stored = new AtomicReference<>();
    Fluxzero app;
    DefaultMessageScheduler subject;
    record Timer(ThrowingRunnable action, Registration cancellation) { @lombok.SneakyThrows void fire() { action.run(); } }

    @BeforeEach void setUp() {
        app = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive().makeApplicationInstance(false)
                .build(LocalClient.newInstance());
        Client client = mock(Client.class);
        when(client.getSchedulingClient()).thenReturn(transport);
        when(handlers.hasLocalHandlers()).thenReturn(true);
        when(handlers.canHandle(any())).thenReturn(true);
        when(handlers.handle(any())).thenReturn(Optional.of(CompletableFuture.completedFuture(null)));
        when(timer.clock()).thenReturn(Clock.systemUTC());
        when(timer.schedule(any(Instant.class), any())).thenAnswer(call -> {
            var cancellation = mock(Registration.class);
            timers.add(new Timer(call.getArgument(1), cancellation));
            return cancellation;
        });
        when(transport.getSchedule("public")).thenAnswer(call -> stored.get());
        when(transport.cancelSchedule(eq("public"), any())).thenAnswer(call -> {
            stored.set(null); return CompletableFuture.completedFuture(null);
        });
        subject = new DefaultMessageScheduler(client, serializer, DispatchInterceptor.noOp, DispatchInterceptor.noOp,
                UnaryOperator.identity(), timer, handlers);
    }
    @AfterEach void close() { app.close(); }

    SerializedSchedule schedule(String message) {
        Schedule schedule = new Schedule(message, "public", due).withMessageId(message);
        return new SerializedSchedule("public", due.toEpochMilli(), schedule.serialize(serializer), false);
    }
    Consumer<CommitModelsResult> reserve(String message) {
        return app.apply(fc -> subject.registerDeadlineCommit(List.of(new ModelDeadlineUpdate("owner", "default",
                "public", message == null ? null : schedule(message), false, null))));
    }
    CommitModelsResult accepted(long index) {
        return CommitModelsResult.acceptedSingleTarget(index, "commit" + index, index, null, "owner", index, true);
    }

    @Test void explicitCancellationClearsManagedTimerAndFinishedReservations() {
        reserve("managed").accept(accepted(1));
        subject.cancelSchedule("public");
        verify(timers.getFirst().cancellation()).cancel();
        assertTrue(io.fluxzero.common.reflection.ReflectionUtils.<Map<?, ?>>getFieldValue("deadlineSlots", subject)
                .orElseThrow().isEmpty());
        reserve("failure").accept(null);
        assertTrue(io.fluxzero.common.reflection.ReflectionUtils.<Map<?, ?>>getFieldValue("deadlineSlots", subject)
                .orElseThrow().isEmpty());
    }

    @Test void storageClockAdjustmentIsLearnedOnlyAtDelivery() throws Exception {
        when(timer.clock()).thenReturn(Clock.fixed(due, java.time.ZoneOffset.UTC));
        reserve("managed").accept(accepted(1));
        verify(transport, never()).getSchedule(anyString());
        var clamped = schedule("managed");
        stored.set(new SerializedSchedule("public", due.plusMillis(10).toEpochMilli(), clamped.getMessage(), false));
        timers.getFirst().fire();
        assertEquals(2, timers.size());
        verify(handlers, never()).handle(any());
        when(timer.clock()).thenReturn(Clock.fixed(due.plusMillis(10), java.time.ZoneOffset.UTC));
        timers.getLast().fire();
        verify(handlers).handle(any());
        assertTrue(io.fluxzero.common.reflection.ReflectionUtils.<Map<?, ?>>getFieldValue("deadlineSlots", subject)
                .orElseThrow().isEmpty());
        assertTrue(io.fluxzero.common.reflection.ReflectionUtils.<Map<?, ?>>getFieldValue("localDeliveryLocks", subject)
                .orElseThrow().isEmpty());
    }

    @Test void newerTimerSurvivesLateReplacementAndCancellationWithoutReads() throws Exception {
        var older = reserve("old"); var cancellation = reserve(null); var newer = reserve("new");
        newer.accept(accepted(3)); older.accept(accepted(1)); cancellation.accept(accepted(2));
        verify(transport, never()).getSchedule(anyString());
        assertEquals(1, timers.size());
        verify(timers.getFirst().cancellation(), never()).cancel();
        stored.set(schedule("new")); timers.getFirst().fire();
        verify(handlers).handle(argThat(m -> "new".equals(m.getPayload())));
    }

    @Test void cancellationAndFiringKeepOrderUntilOlderCallbacksFinish() throws Exception {
        var old = reserve("old"); var cancellation = reserve(null);
        cancellation.accept(accepted(2)); old.accept(accepted(1));
        assertTrue(timers.isEmpty());
        var older = reserve("older"); var active = reserve("active");
        active.accept(accepted(4)); stored.set(schedule("active")); timers.getFirst().fire();
        older.accept(accepted(3));
        assertEquals(1, timers.size());
        verify(handlers, times(1)).handle(any());
    }

    @Test void failedAndDuplicateCommitsReleaseReservationsWithoutActivating() {
        reserve("failed").accept(null);
        reserve("duplicate").accept(accepted(1).asDuplicateForRequest(2));
        assertTrue(timers.isEmpty());
        reserve("new").accept(accepted(3));
        assertEquals(1, timers.size());
        verify(transport, never()).getSchedule(anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void overlappingOrdinaryAndDeadlineTimersClaimTheSameEnvelopeOnce(boolean ordinaryFirst) throws Exception {
        reserve("managed").accept(accepted(1));
        stored.set(schedule("managed"));
        // The ordinary callback sees the newer managed row and registers that exact same envelope.
        subject.scheduleLocalDelivery(new Schedule("ordinary", "public", due), false, app);
        assertEquals(2, timers.size());
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        CountDownLatch claiming = new CountDownLatch(1);
        when(transport.cancelSchedule(eq("public"), eq(Guarantee.STORED))).thenAnswer(call -> {
            claiming.countDown(); return cancelled.thenRun(() -> stored.set(null));
        });
        try (var first = new TestTask(() -> timers.get(ordinaryFirst ? 1 : 0).fire(), () -> cancelled.complete(null))) {
            assertTrue(claiming.await(2, TimeUnit.SECONDS));
            try (var second = new TestTask(() -> timers.get(ordinaryFirst ? 0 : 1).fire(), () -> cancelled.complete(null))) {
                second.awaitBlockedIn(DefaultMessageScheduler.class, "withLocalDeliveryLock", Duration.ofSeconds(2));
                cancelled.complete(null);
                first.awaitCompletion(Duration.ofSeconds(2)); second.awaitCompletion(Duration.ofSeconds(2));
            }
        }
        verify(handlers, times(1)).handle(any());
    }

    @Test void deadlineCancellationDoesNotRemoveAnOrdinaryReplacementTimer() throws Exception {
        reserve("managed").accept(accepted(1));
        stored.set(schedule("ordinary"));
        subject.scheduleLocalDelivery(new Schedule("ordinary", "public", due), false, app);
        reserve(null).accept(accepted(2));
        timers.getLast().fire();
        verify(handlers).handle(argThat(m -> "ordinary".equals(m.getPayload())));
    }
}
