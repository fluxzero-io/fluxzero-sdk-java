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

import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.IdentityProvider;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.publishing.CommandGateway;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.tracking.handling.Invocation;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.sdk.common.ClientUtils.runInLocalHandlerNamespace;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulingInterceptorTest {
    private static final Instant START = Instant.parse("2024-01-01T12:10:00Z");
    private static final Instant NEXT_HOURLY_DEADLINE = Instant.parse("2024-01-01T13:00:00Z");
    private static final Instant INTENTIONAL_DELAY_DEADLINE = Instant.parse("2024-01-01T18:00:00Z");

    @Test
    void autoStartReplacesCronScheduleCreatedWithDifferentCronConfig() {
        Schedule existingSchedule = new Schedule(
                new CurrentCronSchedule(),
                cronMetadata(periodic(PreviousCronSchedule.class)),
                CurrentCronSchedule.class.getName(), Instant.parse("2024-01-01T16:00:00Z"));

        TestFixture.create().withProperty(Periodic.USE_DEFAULT_INITIAL_DELAY_PROPERTY, true).atFixedTime(START)
                .givenSchedules(existingSchedule)
                .whenExecuting(fc -> fc.registerHandlers(new CurrentCronHandler()))
                .expectOnlyNewSchedules((Predicate<Schedule>) schedule ->
                        schedule.getPayload() instanceof CurrentCronSchedule
                        && NEXT_HOURLY_DEADLINE.equals(schedule.getDeadline())
                        && hasCronSchema(schedule.getMetadata(), periodicMethod(CurrentCronHandler.class)))
                .expectOnlySchedules((Predicate<Schedule>) schedule ->
                        schedule.getPayload() instanceof CurrentCronSchedule
                        && NEXT_HOURLY_DEADLINE.equals(schedule.getDeadline()));
    }

    @Test
    void autoStartLeavesUnmarkedSchedulesUntouched() {
        Schedule existingSchedule = new Schedule(
                new CurrentCronSchedule(), CurrentCronSchedule.class.getName(), INTENTIONAL_DELAY_DEADLINE);

        TestFixture.create().withProperty(Periodic.USE_DEFAULT_INITIAL_DELAY_PROPERTY, true).atFixedTime(START)
                .givenSchedules(existingSchedule)
                .whenExecuting(fc -> fc.registerHandlers(new CurrentCronHandler()))
                .expectNoNewSchedules()
                .expectOnlySchedules((Predicate<Schedule>) schedule ->
                        schedule.getPayload() instanceof CurrentCronSchedule
                        && INTENTIONAL_DELAY_DEADLINE.equals(schedule.getDeadline())
                        && !wasCronScheduled(schedule.getMetadata()));
    }

    @Test
    void autoStartUsesConsumerNamespace() {
        Fluxzero fluxzero = mock(Fluxzero.class);
        MessageScheduler defaultScheduler = mock(MessageScheduler.class);
        MessageScheduler namespacedScheduler = mock(MessageScheduler.class);
        when(fluxzero.messageScheduler()).thenReturn(defaultScheduler);
        when(fluxzero.clock()).thenReturn(Clock.fixed(START, ZoneOffset.UTC));
        when(defaultScheduler.forNamespace("tenant")).thenReturn(namespacedScheduler);
        when(namespacedScheduler.getSchedule(anyString())).thenReturn(Optional.empty());

        withFluxzero(fluxzero, () -> new SchedulingInterceptor()
                .initializePeriodicSchedules(new CurrentCronHandler(), "tenant"));

        verify(namespacedScheduler).schedule(any(Schedule.class), eq(true));
        verify(defaultScheduler, never()).schedule(any(Schedule.class), eq(true));
    }

    @Test
    void autoStartUsesAssignedConsumerNamespaceDuringRegistration() {
        TestFixture fixture = TestFixture.createAsync(new TenantPeriodicHandler());

        assertTrue(fixture.getFluxzero().messageScheduler().forNamespace("tenant")
                           .getSchedule(TenantPeriodicSchedule.class.getName()).isPresent());
        assertFalse(fixture.getFluxzero().messageScheduler()
                            .getSchedule(TenantPeriodicSchedule.class.getName()).isPresent());
    }

    @Test
    void explicitDurationReturnClearsCronScheduleMetadata() {
        Schedule existingSchedule = new Schedule(
                new DynamicDelaySchedule(),
                cronMetadata(periodicMethod(DynamicDelayHandler.class)),
                DynamicDelaySchedule.class.getName(), NEXT_HOURLY_DEADLINE);

        TestFixture.create(new DynamicDelayHandler())
                .withProperty(Periodic.USE_DEFAULT_INITIAL_DELAY_PROPERTY, true)
                .atFixedTime(START)
                .givenSchedules(existingSchedule)
                .whenTimeAdvancesTo(NEXT_HOURLY_DEADLINE)
                .expectOnlyNewSchedules((Predicate<Schedule>) schedule ->
                        schedule.getPayload() instanceof DynamicDelaySchedule
                        && INTENTIONAL_DELAY_DEADLINE.equals(schedule.getDeadline())
                        && !wasCronScheduled(schedule.getMetadata()));
    }

    @Test
    void reschedulesInConsumerNamespace() {
        Fluxzero fluxzero = mock(Fluxzero.class);
        MessageScheduler defaultScheduler = mock(MessageScheduler.class);
        MessageScheduler namespacedScheduler = mock(MessageScheduler.class);
        IdentityProvider identityProvider = mock(IdentityProvider.class);
        when(fluxzero.messageScheduler()).thenReturn(defaultScheduler);
        when(defaultScheduler.forNamespace("tenant")).thenReturn(namespacedScheduler);
        when(fluxzero.identityProvider()).thenReturn(identityProvider);
        when(identityProvider.nextTechnicalId()).thenReturn("next-schedule");
        DeserializingMessage message = namespacedMessage(new DynamicDelaySchedule());

        withFluxzero(fluxzero, () -> new SchedulingInterceptor().handleResult(
                Duration.ofHours(1), message, START, null));

        verify(namespacedScheduler).schedule(any(Schedule.class));
        verify(defaultScheduler, never()).schedule(any(Schedule.class));
    }

    @Test
    void localHandlerReschedulesInApplicationNamespaceWithinCustomTracker() {
        Fluxzero fluxzero = mock(Fluxzero.class);
        MessageScheduler defaultScheduler = mock(MessageScheduler.class);
        IdentityProvider identityProvider = mock(IdentityProvider.class);
        when(fluxzero.messageScheduler()).thenReturn(defaultScheduler);
        when(defaultScheduler.forNamespace(null)).thenReturn(defaultScheduler);
        when(fluxzero.identityProvider()).thenReturn(identityProvider);
        when(identityProvider.nextTechnicalId()).thenReturn("next-schedule");
        DeserializingMessage message = new DeserializingMessage(
                new Message(new DynamicDelaySchedule()), SCHEDULE, new JacksonSerializer());
        Tracker.current.set(new Tracker("tracker", SCHEDULE, null,
                                        ConsumerConfiguration.builder().name("tenant-schedules")
                                                .namespace("tenant").build(), null));
        try {
            withFluxzero(fluxzero, () -> runInLocalHandlerNamespace(() ->
                    new SchedulingInterceptor().handleResult(Duration.ofHours(1), message, START, null)));
        } finally {
            Tracker.current.remove();
        }

        verify(defaultScheduler).schedule(any(Schedule.class));
        verify(defaultScheduler, never()).forNamespace("tenant");
    }

    @Test
    void cancelsInConsumerNamespace() {
        Fluxzero fluxzero = mock(Fluxzero.class);
        MessageScheduler defaultScheduler = mock(MessageScheduler.class);
        MessageScheduler namespacedScheduler = mock(MessageScheduler.class);
        when(fluxzero.messageScheduler()).thenReturn(defaultScheduler);
        when(defaultScheduler.forNamespace("tenant")).thenReturn(namespacedScheduler);
        DeserializingMessage message = namespacedMessage(new DynamicDelaySchedule())
                .withMetadata(Metadata.of(Schedule.scheduleIdMetadataKey, "periodic"));

        withFluxzero(fluxzero, () -> new SchedulingInterceptor().handleExceptionalResult(
                new CancelPeriodic(), message, START, null));

        verify(namespacedScheduler).cancelSchedule("periodic");
        verify(defaultScheduler, never()).cancelSchedule("periodic");
    }

    @Test
    void scheduledCommandIsDispatchedInScheduleNamespace() {
        Fluxzero fluxzero = mock(Fluxzero.class);
        CommandGateway defaultGateway = mock(CommandGateway.class);
        CommandGateway namespacedGateway = mock(CommandGateway.class);
        JacksonSerializer serializer = new JacksonSerializer();
        when(fluxzero.commandGateway()).thenReturn(defaultGateway);
        when(defaultGateway.forNamespace("tenant")).thenReturn(namespacedGateway);
        when(fluxzero.serializer()).thenReturn(serializer);
        when(fluxzero.clock()).thenReturn(Clock.fixed(START, ZoneOffset.UTC));
        ScheduledCommand command = new ScheduledCommand(new Message("command").serialize(serializer));

        withFluxzero(fluxzero, () -> new ScheduledCommandHandler().handle(command, namespacedMessage(command)));

        verify(namespacedGateway).sendAndForget(any(Object[].class));
        verify(defaultGateway, never()).sendAndForget(any(Object[].class));
    }

    @Test
    void asynchronousResultCompletionReinstatesFullRequestContext() throws Exception {
        Fluxzero fluxzero = mock(Fluxzero.class);
        User user = mock(User.class);
        ConsumerConfiguration configuration = ConsumerConfiguration.builder()
                .name("tenant-schedules").namespace("tenant").build();
        Tracker tracker = new Tracker("tracker", SCHEDULE, null, configuration, null);
        DeserializingMessage message = new DeserializingMessage(
                new Message(new DynamicDelaySchedule()), SCHEDULE, new JacksonSerializer());
        CompletableFuture<Object> handlerResult = new CompletableFuture<>();
        CompletableFuture<HandlerContext> callbackContext = new CompletableFuture<>();
        AtomicReference<Invocation> invocation = new AtomicReference<>();
        SchedulingInterceptor interceptor = new SchedulingInterceptor() {
            @Override
            protected Object handleResult(Object result, DeserializingMessage schedule, Instant now,
                                          Periodic periodic) {
                if (result instanceof CompletableFuture<?>) {
                    return super.handleResult(result, schedule, now, periodic);
                }
                callbackContext.complete(new HandlerContext(
                        Fluxzero.instance.get(), Tracker.current().orElse(null), User.getCurrent(),
                        DeserializingMessage.getCurrent(), Invocation.getCurrent()));
                return result;
            }
        };

        Tracker.current.set(tracker);
        User.current.set(user);
        try {
            withFluxzero(fluxzero, () -> message.run(ignored -> Invocation.performInvocation(() -> {
                invocation.set(Invocation.getCurrent());
                interceptor.handleResult(handlerResult, message, START, null);
                return null;
            })));
        } finally {
            Tracker.current.remove();
            User.current.remove();
        }

        CompletableFuture.runAsync(() -> handlerResult.complete(Duration.ofHours(1))).get();
        HandlerContext context = callbackContext.get(1, TimeUnit.SECONDS);
        assertSame(fluxzero, context.fluxzero());
        assertSame(tracker, context.tracker());
        assertSame(user, context.user());
        assertSame(message, context.message());
        assertSame(invocation.get(), context.invocation());
    }

    private static DeserializingMessage namespacedMessage(Object payload) {
        return new DeserializingMessage(new Message(payload), SCHEDULE, new JacksonSerializer())
                .putContext(ConsumerConfiguration.class, ConsumerConfiguration.builder()
                        .name("tenant-schedules").namespace("tenant").build());
    }

    private static void withFluxzero(Fluxzero fluxzero, Runnable task) {
        Fluxzero previous = Fluxzero.instance.get();
        try {
            Fluxzero.instance.set(fluxzero);
            task.run();
        } finally {
            if (previous == null) {
                Fluxzero.instance.remove();
            } else {
                Fluxzero.instance.set(previous);
            }
        }
    }

    private static Periodic periodic(Class<?> type) {
        return type.getAnnotation(Periodic.class);
    }

    private static Periodic periodicMethod(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map(method -> method.getAnnotation(Periodic.class))
                .filter(annotation -> annotation != null)
                .findFirst()
                .orElseThrow();
    }

    private static Metadata cronMetadata(Periodic periodic) {
        return Metadata.of(PeriodicSchedulingDefaults.CRON_SCHEMA_METADATA_KEY,
                           PeriodicSchedulingDefaults.cronSchema(periodic));
    }

    private static boolean wasCronScheduled(Metadata metadata) {
        return metadata.containsKey(PeriodicSchedulingDefaults.CRON_SCHEMA_METADATA_KEY);
    }

    private static boolean hasCronSchema(Metadata metadata, Periodic periodic) {
        return PeriodicSchedulingDefaults.cronSchema(periodic)
                .equals(metadata.get(PeriodicSchedulingDefaults.CRON_SCHEMA_METADATA_KEY));
    }

    @Periodic(cron = "0 */4 * * *")
    static class PreviousCronSchedule {
    }

    static class CurrentCronSchedule {
        public CurrentCronSchedule() {
        }
    }

    static class CurrentCronHandler {
        @HandleSchedule
        @Periodic(cron = "0 * * * *")
        void handle(CurrentCronSchedule schedule) {
            Fluxzero.publishEvent(schedule);
        }
    }

    static class DynamicDelaySchedule {
        public DynamicDelaySchedule() {
        }
    }

    static class DynamicDelayHandler {
        @HandleSchedule
        @Periodic(cron = "0 * * * *", autoStart = false)
        Duration handle(DynamicDelaySchedule schedule) {
            return Duration.ofHours(5);
        }
    }

    static class TenantPeriodicSchedule {
        public TenantPeriodicSchedule() {
        }
    }

    @Consumer(name = "tenant-periodic", namespace = "tenant")
    static class TenantPeriodicHandler {
        @HandleSchedule
        @Periodic(cron = "0 * * * *")
        void handle(TenantPeriodicSchedule schedule) {
        }
    }

    private record HandlerContext(Fluxzero fluxzero, Tracker tracker, User user, DeserializingMessage message,
                                  Invocation invocation) {
    }
}
