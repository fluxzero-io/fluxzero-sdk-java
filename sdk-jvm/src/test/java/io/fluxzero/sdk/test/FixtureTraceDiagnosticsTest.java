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

import ch.qos.logback.classic.Level;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.modeling.Aggregate;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.publishing.DefaultGenericGateway;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleCustom;
import io.fluxzero.sdk.tracking.handling.HandleDocument;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleMetrics;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.WebRequest;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class FixtureTraceDiagnosticsTest {

    @Test
    void assertionFailureIncludesMessageAndHandlerTrace() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new PublishingHandler())
                        .whenCommand("command")
                        .expectOnlyEvents("other"));

        String trace = traceFrom(error);
        assertTrue(trace.contains("- COMMAND String"));
        assertTrue(trace.contains("└ handler PublishingHandler [returned null]"));
        assertTrue(trace.contains("- EVENT String"));
        assertFalse(trace.contains("\"command\""));
        assertFalse(trace.contains("PublishingHandler.handle"));
        assertFalse(trace.contains("[namespace=public]"));
        assertFalse(trace.contains("[id="));
        assertFalse(trace.contains("messageId"));
        assertFalse(trace.contains("| COMMAND"));
        assertFalse(trace.contains("`--"));
        assertFalse(trace.contains("METRICS"));
        assertFalse(trace.contains("ERROR"));
        assertFalse(trace.contains("RESULT"));
    }

    @Test
    void assertionFailureListsExpectedMessagesThatWereMissed() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new ReviewFlowHandler())
                        .whenCommand(new ReviewWhenCommand())
                        .expectOnlyEvents(new ReviewWhenEvent(), new MissingReviewEvent()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""

                missed:
                - EVENT MissingReviewEvent
                """));
    }

    @Test
    void assertionFailureListsUnexpectedMessagesThatWereObserved() {
        Predicate<ReviewWhenEvent> anyReviewWhenEvent = event -> true;

        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new ReviewFlowHandler())
                        .whenCommand(new ReviewWhenCommand())
                        .expectNoEventsLike(anyReviewWhenEvent));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""

                unexpected:
                - EVENT ReviewWhenEvent
                """));
        assertFalse(trace.contains("$$Lambda"));
    }

    @Test
    void assertionFailureUsesReadablePredicateAndMatcherDescriptionsForMissedMessages() {
        Predicate<ReviewWhenEvent> anyReviewWhenEvent = event -> true;

        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create()
                        .whenNothingHappens()
                        .expectEvents(anyReviewWhenEvent, Matchers.instanceOf(AdvancedEndedEvent.class)));

        String trace = traceFrom(error);
        assertTrue(trace.contains("- EVENT matching predicate"));
        assertTrue(trace.contains("- EVENT matching an instance of "));
        assertTrue(trace.contains("AdvancedEndedEvent"));
        assertFalse(trace.contains("$$Lambda"));
    }

    @Test
    void asyncTraceIncludesConsumerName() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.createAsync(new PublishingHandler())
                        .resultTimeout(Duration.ofMillis(100))
                        .whenCommand("command")
                        .expectOnlyEvents("other"));

        String trace = traceFrom(error);
        assertTrue(trace.contains("handler PublishingHandler [consumer="));
    }

    @Test
    void hidesInfrastructureMessagesUnlessTheyAreAsserted() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new InfrastructurePublishingHandler())
                        .whenCommand(new InfrastructureCommand())
                        .expectOnlyEvents(new MissingReviewEvent()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("COMMAND InfrastructureCommand"));
        assertTrue(trace.contains("EVENT ReviewWhenEvent"));
        assertFalse(trace.contains("METRICS"));
        assertFalse(trace.contains("ReviewMetric"));
        assertFalse(trace.contains("MetricHandledEvent"));
    }

    @Test
    void showsMetricsWhenMetricsAreAsserted() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new InfrastructurePublishingHandler())
                        .whenCommand(new InfrastructureCommand())
                        .expectOnlyMetrics(new MissingReviewMetric()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("METRICS ReviewMetric"));
        assertTrue(trace.contains("EVENT MetricHandledEvent"));
    }

    @Test
    void traceCollectionLimitDoesNotCrowdOutLaterPhases() {
        FixtureTrace trace = new FixtureTrace();

        trace.startPhase("given");
        for (int i = 0; i < 600; i++) {
            trace.monitorDispatch(new Message("given-" + i), MessageType.COMMAND, null, null);
        }

        trace.startPhase("when");
        trace.monitorDispatch(new Message(new ReviewWhenCommand()), MessageType.COMMAND, null, null);

        String body = trace.renderBody();
        assertTrue(body.contains("given"));
        assertTrue(body.contains("trace collection truncated"));
        assertTrue(body.contains("when\n- COMMAND ReviewWhenCommand"));
    }

    @Test
    void givenFailureIdentifiesRequestWithoutHandler() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TestFixture.createAsync()
                        .resultTimeout(Duration.ofMillis(25))
                        .givenCommands(new MissingGivenHandlerCommand()));

        assertFalse(error.getMessage().contains("No handler invocation was observed for:"));
        assertFalse(error.getMessage().contains(MissingGivenHandlerCommand.class.getName()));
        assertTrue(error.getMessage().contains("COMMAND MissingGivenHandlerCommand"));
        assertTrue(error.getMessage().contains("(no handler invocation observed)"));
        assertTrue(error.getMessage().contains("Test trace:"));
        traceFrom(error);
    }

    @Test
    void whenAssertionFailureIdentifiesRequestWithoutHandler() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.createAsync()
                        .resultTimeout(Duration.ofMillis(25))
                        .whenCommand(new MissingWhenHandlerCommand())
                        .expectResult("result"));

        assertFalse(error.getMessage().contains("No handler invocation was observed for:"));
        assertFalse(error.getMessage().contains(MissingWhenHandlerCommand.class.getName()));
        assertTrue(error.getMessage().contains("COMMAND MissingWhenHandlerCommand"));
        assertTrue(error.getMessage().contains("(no handler invocation observed)"));
        assertTrue(error.getMessage().contains("Test trace:"));
        traceFrom(error);
    }

    @Test
    void timeShiftTraceNestsExpiredSchedules() {
        Instant start = Instant.parse("2024-01-01T12:00:00Z");
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new TimeShiftHandler())
                        .atFixedTime(start)
                        .givenSchedules(new Schedule(new TimeShiftSchedule(), "time-shift",
                                                     start.plusSeconds(5)))
                        .givenElapsedTime(Duration.ofSeconds(5))
                        .whenNothingHappens()
                        .expectOnlyEvents(new MissingReviewEvent()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                given
                - 5s elapsed
                    - SCHEDULE TimeShiftSchedule
                        └ handler TimeShiftHandler
                            - EVENT TimeShiftEvent
                """));
    }

    @Test
    void passiveHandlerInvocationsHideSuccessfulReturnStatus() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new AdvancedTraceHandler(), new WebIngressHandler())
                        .atFixedTime(Instant.parse("2024-01-01T12:00:00Z"))
                        .whenCommand(new AdvancedTraceCommand())
                        .expectOnlyEvents(new MissingReviewEvent()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("└ handler AdvancedTraceHandler [returned String]"));
        assertTrue(trace.contains("        - EVENT AdvancedStartedEvent\n"
                                  + "            └ handler AdvancedTraceHandler"));
        assertFalse(trace.contains("handler AdvancedTraceHandler [returned null]"));
    }

    @Test
    void andThenTraceSeparatesSteps() {
        Instant start = Instant.parse("2024-01-01T12:00:00Z");
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new AndThenTraceHandler())
                        .atFixedTime(start)
                        .whenCommand(new AndThenStartCommand(start.plusSeconds(5)))
                        .expectOnlyEvents(new AndThenStartedEvent())
                        .expectNewSchedules(AndThenFollowUpSchedule.class)
                        .andThen()
                        .whenTimeElapses(Duration.ofSeconds(5))
                        .expectOnlyEvents(new MissingReviewEvent()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                when
                - COMMAND AndThenStartCommand
                    └ handler AndThenTraceHandler [returned null]
                        - EVENT AndThenStartedEvent
                        - SCHEDULE AndThenFollowUpSchedule
                and then when
                - 5s elapsed
                    - SCHEDULE AndThenFollowUpSchedule
                """));
    }

    @Test
    void queryTraceRendersAsRequestMessageWithReturnStatus() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new OtherFixtureActionHandler())
                        .whenQuery(new QueryTraceRequest())
                        .expectResult("wrong"));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                when
                - QUERY QueryTraceRequest
                    └ handler OtherFixtureActionHandler [returned String]
                """));
    }

    @Test
    void customTraceRendersTopicAndReturnStatus() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new OtherFixtureActionHandler())
                        .whenCustom("review-custom", new CustomTraceRequest())
                        .expectResult("wrong"));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                when
                - CUSTOM CustomTraceRequest [topic=review-custom]
                    └ handler OtherFixtureActionHandler [returned String]
                """));
    }

    @Test
    void documentTraceRendersCollectionAsTopic() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new OtherFixtureActionHandler())
                        .givenDocument(new DocumentTraceEntry(), "document-1", "review-documents")
                        .whenNothingHappens()
                        .expectOnlyEvents(new MissingReviewEvent()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                given
                - DOCUMENT DocumentTraceEntry [topic=review-documents]
                    └ handler OtherFixtureActionHandler
                        - EVENT DocumentTraceIndexedEvent
                """));
    }

    @Test
    void appliedEventsRenderAsEvents() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create()
                        .whenEventsAreApplied("review-aggregate", TraceAggregate.class, new AppliedTraceEvent())
                        .expectOnlyEvents(new MissingReviewEvent()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                when
                - applying events to TraceAggregate
                    - EVENT AppliedTraceEvent
                """));
    }

    @Test
    void scheduleExpiryRendersThroughElapsedTime() {
        Instant start = Instant.parse("2024-01-01T12:00:00Z");
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create(new TimeShiftHandler())
                        .atFixedTime(start)
                        .whenScheduleExpires(new Schedule(new TimeShiftSchedule(), "time-shift",
                                                          start.plusSeconds(5)))
                        .expectOnlyEvents(new MissingReviewEvent()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                when
                - 5s elapsed
                    - SCHEDULE TimeShiftSchedule
                        └ handler TimeShiftHandler
                """));
    }

    @Test
    void directWhenApplyingRendersFixtureAction() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create()
                        .withHeader("X-Trace", "yes")
                        .withProperty("trace.property", "value")
                        .whenApplying(fc -> "actual")
                        .expectResult("wrong"));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                when
                - applying [returned String]
                """));
        assertFalse(trace.contains("X-Trace"));
        assertFalse(trace.contains("trace.property"));
    }

    @Test
    void directWhenExecutingRendersFixtureActionWithNestedMessages() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create()
                        .whenExecuting(fc -> Fluxzero.publishEvent(new ReviewWhenEvent()))
                        .expectOnlyEvents(new MissingReviewEvent()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                when
                - custom task
                    - EVENT ReviewWhenEvent
                """));
    }

    @Test
    void genericGivenRendersFixtureActionWithNestedMessages() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create()
                        .given(fc -> Fluxzero.publishEvent(new ReviewGivenEvent()))
                        .whenNothingHappens()
                        .expectOnlyEvents(new MissingReviewEvent()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                given
                - custom task
                    - EVENT ReviewGivenEvent
                when
                - nothing happens
                """));
    }

    @Test
    void givenAppliedEventsRenderAsFixtureAction() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create()
                        .givenAppliedEvents("review-aggregate", TraceAggregate.class, new AppliedTraceEvent())
                        .whenNothingHappens()
                        .expectOnlyEvents(new MissingReviewEvent()));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                given
                - applied events to TraceAggregate
                    - EVENT AppliedTraceEvent
                when
                - nothing happens
                """));
    }

    @Test
    void whenSearchingRendersFixtureAction() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create()
                        .givenDocument(new DocumentTraceEntry(), "document-1", "review-documents")
                        .whenSearching("review-documents", s -> s)
                        .expectResult("wrong"));

        String trace = traceFrom(error);
        assertTrue(trace.contains("when\n- searching review-documents [returned "));
    }

    @Test
    void whenUpcastingRendersFixtureAction() {
        GivenWhenThenAssertionError error = assertThrows(GivenWhenThenAssertionError.class,
                () -> TestFixture.create()
                        .whenUpcasting(new AppliedTraceEvent())
                        .expectResult("wrong"));

        String trace = traceFrom(error);
        assertTrue(trace.contains("""
                when
                - upcasting [returned AppliedTraceEvent]
                """));
    }

    @Nested
    class ReviewExamples {
        @Test
        void basicPublicationAndHandlerInvocation() {
            logReviewExample("basic publication and handler invocation",
                             () -> TestFixture.create(new PublishingHandler())
                                     .whenCommand("command")
                                     .expectOnlyEvents("other"));
        }

        @Test
        void givenAndWhenPhases() {
            logReviewExample("given and when phases",
                             () -> TestFixture.create(new ReviewFlowHandler())
                                     .givenCommands(new ReviewGivenCommand())
                                     .whenCommand(new ReviewWhenCommand())
                                     .expectOnlyEvents(new MissingReviewEvent()));
        }

        @Test
        void asyncConsumerName() {
            logReviewExample("async consumer name",
                             () -> TestFixture.createAsync(new ReviewFlowHandler())
                                     .resultTimeout(Duration.ofMillis(100))
                                     .whenCommand(new ReviewWhenCommand())
                                     .expectOnlyEvents(new MissingReviewEvent()));
        }

        @Test
        void fixedTime() {
            logReviewExample("fixed time",
                             () -> TestFixture.create(new TimeReviewHandler())
                                     .atFixedTime(Instant.parse("2024-01-01T12:00:00Z"))
                                     .whenCommand(new TimeReviewCommand())
                                     .expectResult("wrong"));
        }

        @Test
        void webIngress() {
            logReviewExample("web ingress",
                             () -> TestFixture.create(new WebIngressHandler())
                                     .whenGet("/review")
                                     .expectResult("wrong"));
        }

        @Test
        void webEgress() {
            logReviewExample("web egress",
                             () -> TestFixture.create(new WebEgressHandler())
                                     .whenCommand(new WebEgressCommand())
                                     .expectOnlyEvents(new MissingReviewEvent()));
        }

        @Test
        void handlerError() {
            logReviewExample("handler error",
                             () -> withoutGatewayErrorLogs(() -> TestFixture.create(new FailingEventHandler())
                                     .whenCommand(new FailingReviewCommand())
                                     .expectNoErrors()));
        }

        @Test
        void noHandlerInvocation() {
            logReviewExample("no handler invocation",
                             () -> TestFixture.createAsync()
                                     .resultTimeout(Duration.ofMillis(25))
                                     .whenCommand(new MissingWhenHandlerCommand())
                                     .expectResult("result"));
        }

        @Test
        void timeShiftWithSchedule() {
            logReviewExample("time shift with schedule",
                             () -> {
                                 Instant start = Instant.parse("2024-01-01T12:00:00Z");
                                 TestFixture.create(new TimeShiftHandler())
                                         .atFixedTime(start)
                                         .givenSchedules(new Schedule(new TimeShiftSchedule(), "time-shift",
                                                                      start.plusSeconds(5)))
                                         .givenElapsedTime(Duration.ofSeconds(5))
                                         .whenNothingHappens()
                                         .expectOnlyEvents(new MissingReviewEvent());
                             });
        }

        @Test
        void advancedNestedFlow() {
            logReviewExample("advanced nested flow",
                             () -> TestFixture.create(new AdvancedTraceHandler(), new WebIngressHandler())
                                     .atFixedTime(Instant.parse("2024-01-01T12:00:00Z"))
                                     .whenCommand(new AdvancedTraceCommand())
                                     .expectOnlyEvents(new MissingReviewEvent()));
        }

        @Test
        void andThenSteps() {
            logReviewExample("andThen steps",
                             () -> {
                                 Instant start = Instant.parse("2024-01-01T12:00:00Z");
                                 TestFixture.create(new AndThenTraceHandler())
                                         .atFixedTime(start)
                                         .whenCommand(new AndThenStartCommand(start.plusSeconds(5)))
                                         .expectOnlyEvents(new AndThenStartedEvent())
                                         .expectNewSchedules(AndThenFollowUpSchedule.class)
                                         .andThen()
                                         .whenTimeElapses(Duration.ofSeconds(5))
                                         .expectOnlyEvents(new MissingReviewEvent());
                             });
        }

        @Test
        void directFixtureActions() {
            logReviewExample("direct fixture actions",
                             () -> TestFixture.create()
                                     .given(fc -> Fluxzero.publishEvent(new ReviewGivenEvent()))
                                     .whenExecuting(fc -> Fluxzero.publishEvent(new ReviewWhenEvent()))
                                     .expectOnlyEvents(new MissingReviewEvent()));
        }

        @Test
        void appliedEventsAndSearching() {
            logReviewExample("applied events and searching",
                             () -> TestFixture.create()
                                     .givenAppliedEvents("review-aggregate", TraceAggregate.class,
                                                         new AppliedTraceEvent())
                                     .givenDocument(new DocumentTraceEntry(), "document-1", "review-documents")
                                     .whenSearching("review-documents", s -> s)
                                     .expectResult("wrong"));
        }
    }

    private static void logReviewExample(String title, Executable executable) {
        Throwable error = assertThrows(Throwable.class, executable);
        log.debug("Fixture trace review - {}:\n{}", title, diagnosticsFrom(error));
    }

    private static void withoutGatewayErrorLogs(Executable executable) throws Throwable {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultGenericGateway.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            executable.execute();
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    private static String traceFrom(Throwable error) {
        String trace = traceFromMessage(error.getMessage());
        if (trace == null) {
            for (Throwable suppressed : error.getSuppressed()) {
                trace = traceFromMessage(suppressed.getMessage());
                if (trace == null && isTraceDetails(suppressed)) {
                    trace = suppressed.getMessage();
                }
                if (trace != null) {
                    break;
                }
            }
        }
        assertTrue(trace != null);
        log.debug("Fixture trace diagnostics:\n{}", trace);
        return trace;
    }

    private static String diagnosticsFrom(Throwable error) {
        String message = error.getMessage();
        String trace = traceFromMessage(message);
        if (trace != null) {
            return message;
        }
        for (Throwable suppressed : error.getSuppressed()) {
            trace = traceFromMessage(suppressed.getMessage());
            if (trace == null && isTraceDetails(suppressed)) {
                trace = suppressed.getMessage();
            }
            if (trace != null) {
                return message + "\n\n" + trace;
            }
        }
        return message;
    }

    private static String traceFromMessage(String message) {
        if (message == null) {
            return null;
        }
        int start = message.indexOf("Test trace:");
        return start < 0 ? null : message.substring(start);
    }

    private static boolean isTraceDetails(Throwable error) {
        return error.getClass().getName().endsWith("$TraceDetails");
    }

    private static class PublishingHandler {
        @HandleCommand
        void handle(String command) {
            Fluxzero.publishEvent("event");
        }
    }

    private static class ReviewFlowHandler {
        @HandleCommand
        void handle(ReviewGivenCommand command) {
            Fluxzero.publishEvent(new ReviewGivenEvent());
        }

        @HandleCommand
        void handle(ReviewWhenCommand command) {
            Fluxzero.publishEvent(new ReviewWhenEvent());
        }
    }

    private static class TimeReviewHandler {
        @HandleCommand
        Instant handle(TimeReviewCommand command, Instant timestamp) {
            return timestamp;
        }
    }

    private static class WebIngressHandler {
        @HandleGet("/review")
        String handle() {
            return "ok";
        }
    }

    private static class WebEgressHandler {
        @HandleCommand
        void handle(WebEgressCommand command) throws Exception {
            Fluxzero.get().webRequestGateway()
                    .sendAndForget(Guarantee.NONE, WebRequest.get("https://example.test/review").build())
                    .get();
        }
    }

    private static class FailingEventHandler {
        @HandleCommand
        void handle(FailingReviewCommand command) {
            Fluxzero.publishEvent(new FailingReviewEvent());
        }

        @HandleEvent
        void handle(FailingReviewEvent event) {
            throw new NullPointerException();
        }
    }

    private static class InfrastructurePublishingHandler {
        @HandleCommand
        void handle(InfrastructureCommand command) {
            Fluxzero.publishMetrics(new ReviewMetric());
            Fluxzero.publishEvent(new ReviewWhenEvent());
        }

        @HandleMetrics
        void handle(ReviewMetric metric) {
            Fluxzero.publishEvent(new MetricHandledEvent());
        }
    }

    private static class TimeShiftHandler {
        @HandleSchedule
        void handle(TimeShiftSchedule schedule) {
            Fluxzero.publishEvent(new TimeShiftEvent());
        }

        @HandleEvent
        void handle(TimeShiftEvent event) {
            Fluxzero.publishEvent(new TimeShiftProjectionUpdated());
        }
    }

    private static class AdvancedTraceHandler {
        @HandleCommand
        String handle(AdvancedTraceCommand command) {
            Fluxzero.publishEvent(new AdvancedStartedEvent());
            Fluxzero.get().messageScheduler()
                    .schedule(new Schedule(new AdvancedFollowUpSchedule(), Fluxzero.currentTime().plusSeconds(30)));
            Fluxzero.sendWebRequestAndWait(WebRequest.get("/review").build());
            return "advanced";
        }

        @HandleEvent
        void handle(AdvancedStartedEvent event) {
            Fluxzero.publishEvent(new AdvancedProjectionEvent());
            Fluxzero.publishEvent(new AdvancedAuditEvent());
        }

        @HandleEvent
        void handle(AdvancedProjectionEvent event) {
            Fluxzero.publishEvent(new AdvancedProjectionIndexedEvent());
        }
    }

    private static class AndThenTraceHandler {
        @HandleCommand
        void handle(AndThenStartCommand command) {
            Fluxzero.publishEvent(new AndThenStartedEvent());
            Fluxzero.get().messageScheduler().schedule(new Schedule(
                    new AndThenFollowUpSchedule(), "and-then-follow-up", command.followUpAt()));
        }

        @HandleSchedule
        void handle(AndThenFollowUpSchedule schedule) {
            Fluxzero.publishEvent(new AndThenFollowedUpEvent());
        }
    }

    private static class OtherFixtureActionHandler {
        @HandleQuery
        String handle(QueryTraceRequest request) {
            return "query-result";
        }

        @HandleCustom("review-custom")
        String handle(CustomTraceRequest request) {
            return "custom-result";
        }

        @HandleDocument("review-documents")
        void handle(DocumentTraceEntry entry) {
            Fluxzero.publishEvent(new DocumentTraceIndexedEvent());
        }
    }

    @Aggregate
    private static class TraceAggregate {
        @Apply
        TraceAggregate(AppliedTraceEvent event) {
        }
    }

    private record MissingGivenHandlerCommand() {
    }

    private record MissingWhenHandlerCommand() {
    }

    private record ReviewGivenCommand() {
    }

    private record ReviewGivenEvent() {
    }

    private record ReviewWhenCommand() {
    }

    private record ReviewWhenEvent() {
    }

    private record MissingReviewEvent() {
    }

    private record MissingReviewMetric() {
    }

    private record TimeReviewCommand() {
    }

    private record WebEgressCommand() {
    }

    private record FailingReviewCommand() {
    }

    private record FailingReviewEvent() {
    }

    private record InfrastructureCommand() {
    }

    private record ReviewMetric() {
    }

    private record MetricHandledEvent() {
    }

    private record TimeShiftSchedule() {
    }

    private record TimeShiftEvent() {
    }

    private record TimeShiftProjectionUpdated() {
    }

    private record AdvancedTraceCommand() {
    }

    private record AdvancedStartedEvent() {
    }

    private record AdvancedProjectionEvent() {
    }

    private record AdvancedProjectionIndexedEvent() {
    }

    private record AdvancedAuditEvent() {
    }

    private record AdvancedEndedEvent() {
    }

    private record AdvancedFollowUpSchedule() {
    }

    private record AndThenStartCommand(Instant followUpAt) {
    }

    private record AndThenStartedEvent() {
    }

    private record AndThenFollowUpSchedule() {
    }

    private record AndThenFollowedUpEvent() {
    }

    private record QueryTraceRequest() {
    }

    private record CustomTraceRequest() {
    }

    private record DocumentTraceEntry() {
    }

    private record DocumentTraceIndexedEvent() {
    }

    private record AppliedTraceEvent() {
    }
}
