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

package io.fluxzero.sdk.test;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class TestFixtureScheduleWaitTest {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant DEADLINE = NOW.plusSeconds(60);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedOldLifetimeDoesNotLeavePendingScheduledCommand(boolean spying) {
        RootId id = new RootId("root");
        AtomicInteger calls = new AtomicInteger();
        Object handler = new Object() {
            @HandleCommand
            void handle(RunRoot command) {
                calls.incrementAndGet();
            }
        };
        TestFixture fixture = TestFixture.createAsync(handler);
        fixture = (spying ? fixture.spy() : fixture).atFixedTime(NOW)
                .givenCommands(new CreateRoot(id))
                .given(fc -> {
                    fc.messageScheduler().scheduleCommand(new Schedule(new RunRoot(id), "owned", DEADLINE));
                    var old = fc.messageScheduler().getSchedule("owned").orElseThrow();
                    Fluxzero.sendCommandAndWait(new DeleteRoot(id));
                    Fluxzero.sendCommandAndWait(new CreateRoot(id));
                    fc.messageScheduler().scheduleCommand(new Schedule(new RunRoot(id), "owned", DEADLINE));
                    var current = fc.messageScheduler().getSchedule("owned").orElseThrow();
                    assertThrows(Exception.class, () -> fc.messageScheduler().schedule(old));
                    assertEquals(current.getMessageId(),
                                 fc.messageScheduler().getSchedule("owned").orElseThrow().getMessageId());
                });

        fixture.whenTimeAdvancesTo(DEADLINE).expectNoErrors().expectOnlyActiveScheduledCommands();

        assertEquals(1, calls.get());
        assertTrue(fixture.checkConsumers(), "A rejected attempt is not pending consumer work");
    }

    @ParameterizedTest
    @CsvSource({"public,false", "public,true", "isolated,true"})
    void ignoredIfAbsentAttemptDoesNotDisplaceWorkStillBeingHandled(String namespace, boolean initializedClient)
            throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Object handler = new Object() {
            @HandleSchedule
            void handle(String value) throws InterruptedException {
                assertEquals("accepted", value);
                calls.incrementAndGet();
                entered.countDown();
                assertTrue(release.await(5, SECONDS));
            }
        };
        var client = LocalClient.newInstance(null).forNamespace(namespace);
        if (initializedClient) {
            client.getSchedulingClient();
        }
        var builder = DefaultFluxzero.builder().addConsumerConfiguration(
                ConsumerConfiguration.builder().name("fixture-schedules").namespace(namespace).build(),
                MessageType.SCHEDULE);
        TestFixture fixture = TestFixture.createAsync(builder, client, handler).atFixedTime(NOW)
                .given(fc -> {
                    fc.messageScheduler().schedule(new Schedule("accepted", "same", DEADLINE));
                    fc.messageScheduler().schedule(new Schedule("ignored", "same", DEADLINE),
                                                   true, Guarantee.STORED).join();
                });

        try {
            fixture.atFixedTime(DEADLINE);
            assertTrue(entered.await(5, SECONDS));
            assertFalse(fixture.checkConsumers(), "The accepted handler has not finished yet");
        } finally {
            release.countDown();
        }
        fixture.whenExecuting(fc -> {}).expectNoErrors();

        assertEquals(1, calls.get());
        assertTrue(fixture.checkConsumers(), "The ignored attempt must not remain pending");
    }

    @Test
    void lateConsumerStartsWithAcceptedScheduleRatherThanIgnoredAttempt() {
        List<String> calls = new CopyOnWriteArrayList<>();
        TestFixture fixture = TestFixture.createAsync().atFixedTime(NOW)
                .given(fc -> {
                    fc.messageScheduler().schedule(new Schedule("accepted", "same", DEADLINE));
                    fc.messageScheduler().schedule(new Schedule("ignored", "same", DEADLINE),
                                                   true, Guarantee.STORED).join();
                });
        fixture.registerHandlers(List.of(new Object() {
            @HandleSchedule
            void handle(String value) { calls.add(value); }
        }));

        fixture.whenTimeAdvancesTo(DEADLINE).expectNoErrors();

        assertEquals(List.of("accepted"), calls);
        assertTrue(fixture.checkConsumers());
    }

    @Test
    void acceptedReplacementAndRawClientDispatchRemainObservable() {
        List<String> calls = new CopyOnWriteArrayList<>();
        TestFixture fixture = TestFixture.createAsync(new Object() {
            @HandleSchedule
            void handle(String value) { calls.add(value); }
        }).atFixedTime(NOW).given(fc ->
                fc.messageScheduler().schedule(new Schedule("replaced", "same", DEADLINE)));

        fixture.whenExecuting(fc -> {
            var replacement = new Schedule("raw", "same", DEADLINE)
                    .addMetadata(Schedule.scheduleIdMetadataKey, "same");
            fc.client().getSchedulingClient().schedule(Guarantee.STORED,
                    new SerializedSchedule("same", DEADLINE.toEpochMilli(), replacement.serialize(fc.serializer()), false))
                    .join();
        }).expectOnlySchedules("raw").expectNoErrors();
        assertTrue(fixture.checkConsumers(), "Future schedules must not block completion");

        fixture.whenTimeAdvancesTo(DEADLINE).expectNoErrors();

        assertEquals(List.of("raw"), calls);
        assertTrue(fixture.checkConsumers());
    }

    @Test
    void dispatchAssertionsStillObserveIgnoredAttemptsButActiveAssertionsReadStorage() {
        TestFixture fixture = TestFixture.createAsync().atFixedTime(NOW)
                .givenScheduledCommands(new Schedule("accepted", "same", DEADLINE));

        fixture.whenExecuting(fc -> fc.messageScheduler().scheduleCommand(
                        new Schedule("ignored", "same", DEADLINE), true))
                .expectOnlyScheduledCommands("ignored")
                .expectOnlyActiveScheduledCommands("accepted")
                .expectNoErrors();

        assertTrue(fixture.checkConsumers());
    }

    @Test
    void copiedFixtureDoesNotMonitorReusedCustomSchedulingClientTwice() {
        var local = LocalClient.newInstance(null);
        local.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        Client client = mock(Client.class, delegatesTo(local));
        doReturn(client).when(client).unwrap();
        TestFixture fixture = TestFixture.createAsync(DefaultFluxzero.builder(), client).spy().atFixedTime(NOW);

        fixture.whenExecuting(fc -> fc.messageScheduler().scheduleCommand(
                        new Schedule("accepted", "same", DEADLINE)))
                .expectOnlyScheduledCommands("accepted")
                .expectOnlyActiveScheduledCommands("accepted")
                .expectNoErrors();
    }

    @Model
    record Root(@EntityId RootId id) {}

    static class RootId extends Id<Root> {
        RootId(String value) { super(value); }
    }

    record CreateRoot(RootId id) {
        @Apply
        Root create() { return new Root(id); }
    }

    record DeleteRoot(RootId id) {
        @Apply
        Root delete(Root root) { return null; }
    }

    record RunRoot(@Parent RootId id) {}
}
