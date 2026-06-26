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

package io.fluxzero.sdk.givenwhenthen;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.publishing.RecursivePublicationException;
import io.fluxzero.sdk.publishing.RecursivePublicationGuard;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static io.fluxzero.common.MessageType.EVENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GivenWhenThenRecursivePublicationGuardTest {

    @Test
    void sameMessageTypeMayBePublishedTwiceInOneChain() {
        FiniteRecursiveHandler handler = new FiniteRecursiveHandler(2);

        TestFixture.create(handler)
                .whenEvent(new RecursiveEvent())
                .expectOnlyEvents((Predicate<Message>) event -> event.getPayload() instanceof RecursiveEvent
                                                        && depth(event) == 1)
                .expectNoErrors();

        assertEquals(2, handler.invocations.get());
    }

    @Test
    void recursiveEventPublicationIsBlockedAtConfiguredDepth() {
        FiniteRecursiveHandler handler = new FiniteRecursiveHandler(10);

        TestFixture.create(DefaultFluxzero.builder().setMaxPublicationDepth(2), handler)
                .whenEvent(new RecursiveEvent())
                .expectEvents((Predicate<Message>) event -> event.getPayload() instanceof RecursiveEvent
                                                    && depth(event) == 1)
                .expectEvents((Predicate<Message>) event -> event.getPayload() instanceof RecursiveEvent
                                                    && depth(event) == 2)
                .expectError(RecursivePublicationException.class);

        assertEquals(3, handler.invocations.get());
    }

    @Test
    void publicationDepthDoesNotOverflow() {
        RecursivePublicationGuard guard = new RecursivePublicationGuard(100);

        assertOverflowIsBlocked(guard, Integer.toString(Integer.MAX_VALUE));
        assertOverflowIsBlocked(guard, "2147483648");
    }

    private static void assertOverflowIsBlocked(RecursivePublicationGuard guard, String depth) {
        Message currentMessage = new Message(
                new RecursiveEvent(),
                Metadata.of(RecursivePublicationGuard.PUBLICATION_DEPTH_METADATA_KEY, depth));
        new DeserializingMessage(currentMessage, EVENT, null).apply(__ -> assertThrows(
                RecursivePublicationException.class,
                () -> guard.interceptDispatch(new Message(new RecursiveEvent()), EVENT, null)));
    }

    @Test
    void recursiveEventPublicationIsBlockedAtConfiguredDepth_async() {
        FiniteRecursiveHandler handler = new FiniteRecursiveHandler(10);

        TestFixture.createAsync(DefaultFluxzero.builder().setMaxPublicationDepth(2), handler)
                .whenEvent(new RecursiveEvent())
                .expectError(RecursivePublicationException.class);

        assertEquals(3, handler.invocations.get());
    }

    @Test
    void recursivePublicationGuardCanBeDisabled() {
        FiniteRecursiveHandler handler = new FiniteRecursiveHandler(2);

        TestFixture.create(DefaultFluxzero.builder().setMaxPublicationDepth(-1), handler)
                .whenEvent(new RecursiveEvent())
                .expectNoErrors();

        assertEquals(2, handler.invocations.get());
    }

    @Test
    void rescheduledScheduleStartsANewRecursionBranch() {
        TestFixture.create(new ReschedulingHandler())
                .whenScheduleExpires(new ScheduledRecursiveEvent(0))
                .expectEvents((Predicate<Message>) event -> event.getPayload().equals(new ScheduleObserved(0))
                                                    && depth(event) == 1)
                .andThen()
                .whenScheduleExpires(ScheduledRecursiveEvent.class)
                .expectEvents((Predicate<Message>) event -> event.getPayload().equals(new ScheduleObserved(1))
                                                    && depth(event) == 1)
                .expectNoErrors();
    }

    private static int depth(Message message) {
        return Integer.parseInt(message.getMetadata().get(RecursivePublicationGuard.PUBLICATION_DEPTH_METADATA_KEY));
    }

    private static class FiniteRecursiveHandler {
        private final AtomicInteger invocations = new AtomicInteger();
        private final int maxInvocations;

        private FiniteRecursiveHandler(int maxInvocations) {
            this.maxInvocations = maxInvocations;
        }

        @HandleEvent
        void handle(RecursiveEvent event) {
            if (invocations.incrementAndGet() < maxInvocations) {
                Fluxzero.publishEvent(new RecursiveEvent());
            }
        }
    }

    private static class ReschedulingHandler {
        @HandleSchedule
        void handle(ScheduledRecursiveEvent event, Schedule schedule) {
            Fluxzero.publishEvent(new ScheduleObserved(event.iteration()));
            if (event.iteration() == 0) {
                Fluxzero.schedule(schedule.withPayload(new ScheduledRecursiveEvent(1))
                                          .reschedule(Duration.ofSeconds(1)));
            }
        }
    }

    private record RecursiveEvent() {
    }

    private record ScheduledRecursiveEvent(int iteration) {
    }

    private record ScheduleObserved(int iteration) {
    }
}
