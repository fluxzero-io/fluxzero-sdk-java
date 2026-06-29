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

import io.fluxzero.common.Registration;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.MockException;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.validation.ValidationException;
import io.fluxzero.sdk.tracking.metrics.HandleMessageEvent;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandleSelfTest {

    final TestFixture testFixture = TestFixture.create();

    @Test
    void query() {
        testFixture.whenQuery(new SelfQuery()).expectResult("foo").expectNoMetrics();
    }

    @Test
    void command() {
        Object payload = new SelfCommand();
        testFixture.whenCommand(payload).expectNoResult().expectEvents(payload);
    }

    @Test
    void disabled() {
        testFixture.registerHandlers(new ObjectCommandHandler())
                .whenCommand(new DisabledHandleSelf()).expectOnlyEvents(true).expectNoErrors();
    }

    @Test
    void handleSelfIgnoredIfEvent() {
        Object payload = new SelfEvent();
        testFixture.whenEvent(payload).expectNoErrors().expectNoEvents();
    }

    @Test
    void logHandlerMetricsIfExplicitlyEnabled() {
        testFixture.whenQuery(new MetricsSelfQuery()).expectResult("foo").expectMetrics(HandleMessageEvent.class);
    }

    @Test
    void logNoHandlerMetricsByDefault() {
        testFixture.whenQuery(new SelfQuery()).expectResult("foo").expectNoMetricsLike(HandleMessageEvent.class);
    }

    @Test
    void triggersException() {
        testFixture.whenQuery(new FailingSelfQuery()).expectExceptionalResult(MockException.class);
    }

    @Test
    void triggersValidationException() {
        testFixture.whenQuery(new InvalidSelfQuery()).expectExceptionalResult(ValidationException.class);
    }

    @Test
    void syncTrackSelfHandledWithoutRegistration() {
        testFixture.whenQuery(new SyncSelfTracked("foo")).expectResult("bar");
    }

    @Test
    void registeringLocalSelfHandlerClassDoesNotHandleOtherMessages() {
        testFixture.registerHandlers(LocalSelfCommand.class, new OtherCommandHandler())
                .whenCommand(new OtherCommand()).expectOnlyEvents("other").expectNoErrors();
    }

    @Test
    void registeringLocalSelfHandlerClassDoesNotHandleSelfTwice() {
        testFixture.registerHandlers(LocalSelfCommand.class)
                .whenCommand(new LocalSelfCommand("foo"))
                .expectOnlyEvents("foo")
                .expectNoErrors();
    }

    static class SelfQuery {
        @HandleQuery
        String handleSelf() {
            return "foo";
        }
    }

    static class MetricsSelfQuery {
        @HandleQuery
        @LocalHandler(logMetrics = true)
        String handleSelf() {
            return "foo";
        }
    }

    static class FailingSelfQuery {
        @HandleQuery
        String handleSelf() {
            throw new MockException();
        }
    }

    static class InvalidSelfQuery {
        @NotBlank
        private final String foo = null;

        @HandleQuery
        String handleSelf() {
            return "bar";
        }
    }

    static class SelfCommand {
        @HandleCommand
        void handleSelf() {
            Fluxzero.publishEvent(this);
        }
    }

    static class DisabledHandleSelf {
        @HandleCommand(disabled = true)
        void handle() {
            Fluxzero.publishEvent("foo");
        }
    }

    static class SelfEvent {
        @HandleEvent
        void handleSelf() {
            Fluxzero.publishEvent("foo");
        }
    }

    @TrackSelf
    @AllArgsConstructor
    static class SyncSelfTracked {
        String input;

        @HandleQuery
        String handleSelf() {
            return "bar";
        }
    }

    record LocalSelfCommand(String input) {
        @HandleCommand
        void handleSelf() {
            Fluxzero.publishEvent(input);
        }
    }

    record OtherCommand() {
    }

    static class OtherCommandHandler {
        @HandleCommand
        void handle(OtherCommand command) {
            Fluxzero.publishEvent("other");
        }
    }

    static class ObjectCommandHandler {
        @HandleCommand
        void handleCommand(Object command) {
            Fluxzero.publishEvent(true);
        }
    }

    @Nested
    class AsyncTests {

        final TestFixture testFixture = TestFixture.createAsync();

        @Test
        void logMessage() {
            testFixture.registerHandlers(new ObjectCommandHandler())
                    .whenCommand(new MessageLoggingHandleSelf()).expectEvents("foo", true);
        }

        @Test
        void doNotLogMessage() {
            testFixture.registerHandlers(new ObjectCommandHandler())
                    .whenCommand(new EventPublishingHandleSelf()).expectEvents("foo").expectNoEventsLike(true);
        }

        static class EventPublishingHandleSelf {
            @HandleCommand
            void handle() {
                Fluxzero.publishEvent("foo");
            }
        }

        static class MessageLoggingHandleSelf {
            @HandleCommand
            @LocalHandler(logMessage = true)
            void handle() {
                Fluxzero.publishEvent("foo");
            }
        }
    }

    @Nested
    class TrackSelfTests {

        final TestFixture testFixture = TestFixture.createAsync();

        @TrackSelf
        @Consumer(name = "SelfTracked")
        @Value
        static class SelfTracked {
            String input;

            @HandleQuery
            String handleSelf() {
                if (Tracker.current().isEmpty()) {
                    return "no tracker";
                }
                if (Tracker.current().isPresent()
                    && "SelfTracked".equals(Tracker.current().get().getConfiguration().getName())) {
                    return input;
                }
                return "wrong consumer";
            }
        }

        @TrackSelf
        @Consumer(name = "SelfTracked")
        interface SelfTrackedInterface {
            String getInput();

            @HandleQuery
            default String handleSelf() {
                if (Tracker.current().isEmpty()) {
                    return "no tracker";
                }
                if (Tracker.current().isPresent()
                    && "SelfTracked".equals(Tracker.current().get().getConfiguration().getName())) {
                    return getInput();
                }
                return "wrong consumer";
            }
        }

        @Value
        static class SelfTrackedConcrete implements SelfTrackedInterface {
            String input;
        }

        @Test
        void queryTracked() {
            testFixture.registerHandlers(SelfTracked.class)
                    .whenQuery(new SelfTracked("foo")).expectResult("foo");
        }

        @Test
        void queryTrackedInterface() {
            testFixture.registerHandlers(SelfTrackedInterface.class)
                    .whenQuery(new SelfTrackedConcrete("foo")).expectResult("foo");
        }

        @Test
        void queryTrackedWithoutRegistration() {
            testFixture.whenQuery(new AutomaticallySelfTracked("foo"))
                    .expectResult("AutomaticallySelfTracked:foo");
        }

        @Test
        void multipleTrackedHandlersCanBeDiscoveredIncrementally() {
            testFixture.whenQuery(new FirstSelfTracked("foo")).expectResult("first:foo")
                    .andThen()
                    .whenQuery(new SecondSelfTracked("bar")).expectResult("second:bar");
        }

        @Test
        void cancellingEarlierRegistrationKeepsLaterSharedTrackerRunning() {
            Fluxzero fluxzero = DefaultFluxzero.builder().build(LocalClient.newInstance(null));
            Registration firstRegistration = fluxzero.registerHandlers(FirstSelfTracked.class);
            Registration secondRegistration = fluxzero.registerHandlers(SecondSelfTracked.class);
            try {
                firstRegistration.cancel();
                assertEquals("second:bar", fluxzero.queryGateway().sendAndWait(new SecondSelfTracked("bar")));
            } finally {
                firstRegistration.cancel();
                secondRegistration.cancel();
                fluxzero.close(true);
            }
        }

        @TrackSelf
        @Consumer(name = "AutomaticallySelfTracked")
        record AutomaticallySelfTracked(String input) {
            @HandleQuery
            String handleSelf() {
                return Tracker.current()
                        .map(Tracker::getName)
                        .map(name -> name + ":" + input)
                        .orElse("no tracker");
            }
        }

        @TrackSelf
        record FirstSelfTracked(String input) {
            @HandleQuery
            String handleSelf() {
                return "first:" + input;
            }
        }

        @TrackSelf
        record SecondSelfTracked(String input) {
            @HandleQuery
            String handleSelf() {
                return "second:" + input;
            }
        }
    }
}
