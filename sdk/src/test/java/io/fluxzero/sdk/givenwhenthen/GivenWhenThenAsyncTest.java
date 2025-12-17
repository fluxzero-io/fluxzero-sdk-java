/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.sdk.givenwhenthen;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.MockException;
import io.fluxzero.sdk.common.IgnoringErrorHandler;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ForeverRetryingErrorHandler;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import lombok.Value;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.ObjectUtils.newPlatformThreadFactory;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GivenWhenThenAsyncTest {

    private final TestFixture testFixture = TestFixture.createAsync(
            new MixedHandler(), new AsyncCommandHandler(), new ScheduleHandler()).resultTimeout(Duration.ofSeconds(1));

    @Test
    void testExpectCommandsAndIndirectEvents() {
        testFixture.whenEvent(123).expectNoResult().expectNoErrors()
                .expectCommands(new YieldsEventAndResult())
                .expectEvents(new YieldsEventAndResult());
    }

    @Test
    void testExpectFunctionalException() {
        testFixture.whenCommand(new YieldsException()).expectExceptionalResult(MockException.class);
    }

    @Test
    void testExceptionFromGiven() {
        assertThrows(Exception.class, () -> testFixture.givenCommands(new YieldsException()));
        testFixture.ignoringErrors().givenCommands(new YieldsException());
    }

    @Test
    void testExpectTechnicalException() {
        testFixture.whenCommand(new YieldsRuntimeException()).expectExceptionalResult(TechnicalException.class);
    }

    @Test
    void testAsyncCommandHandling() {
        testFixture.whenCommand(new YieldsAsyncResult()).expectResult("test");
    }

    @Test
    void testAsyncExceptionHandling() {
        testFixture.whenCommand(new YieldsAsyncException()).expectExceptionalResult(IllegalCommandException.class);
    }

    @Test
    void testAsyncExceptionHandling2() {
        testFixture.whenCommand(new YieldsAsyncExceptionSecondHand())
                .expectExceptionalResult(IllegalCommandException.class);
    }

    @Test
    void testExpectPassiveHandling() {
        testFixture.whenCommand(new PassivelyHandled()).expectExceptionalResult(TimeoutException.class);
    }

    @Test
    void testExpectSchedule() {
        testFixture.whenCommand(new YieldsSchedule("test")).expectNewSchedules("test");
    }

    @Test
    void testScheduledCommand() {
        Instant deadline = testFixture.getCurrentTime().plusSeconds(1);
        testFixture.givenSchedules(new Schedule(new DelayedCommand(), "test", deadline))
                .whenTimeAdvancesTo(deadline).expectOnlyCommands(new DelayedCommand()).expectNoNewSchedules();
    }

    @Test
    void testErrorFromCompletableFutureResult() {
        TestFixture.createAsync(new Object() {
            @HandleCommand
            CompletableFuture<Void> handle(String command) {
                return CompletableFuture.failedFuture(new MockException());
            }
        }).whenCommand("test").expectExceptionalResult(MockException.class);
    }

    @Nested
    class MakeAsyncLater {
        final TestFixture syncFixture = TestFixture.create(
                new MixedHandler(), new AsyncCommandHandler(), new ScheduleHandler());

        @Test
        void afterHandlerRegistration() {
            syncFixture.async().whenCommand(new YieldsAsyncResult()).expectResult("test");
        }

        @Test
        void afterGivenCommand() {
            syncFixture.givenCommands(new YieldsSchedule("test")).async()
                    .whenExecuting(fc -> {}).expectSchedules(String.class);
        }

        @Test
        void noEffectIfFixtureIsAsyncAlready() {
            assertNotSame(syncFixture, syncFixture.async());
            assertSame(testFixture, testFixture.async());
            assertSame(syncFixture, syncFixture.sync());
        }
    }

    @Nested
    class MakeSyncLater {
        @Test
        void afterHandlerRegistration() {
            testFixture.sync().whenCommand(new YieldsEventAndResult()).expectResult("result");
        }

        @Test
        void afterGivenCommand() {
            testFixture.givenCommands(new YieldsSchedule("test")).sync()
                    .whenExecuting(fc -> {}).expectSchedules(String.class);
        }
    }

    @Test
    void errorHandlerIsUsedAfterBatchCompletes() {
        TestFixture.createAsync(DefaultFluxzero.builder().configureDefaultConsumer(
                        COMMAND, c -> c.toBuilder().errorHandler(new ForeverRetryingErrorHandler()).build()),
                                new Object() {
                                    volatile boolean retried;

                                    @HandleCommand
                                    void handle(String payload) {
                                        DeserializingMessage.whenBatchCompletes(e -> {
                                            if (retried) {
                                                Fluxzero.publishEvent(payload);
                                            } else {
                                                retried = true;
                                                throw new IllegalStateException();
                                            }
                                        });
                                    }
                                })
                .whenCommand("test")
                .expectNoErrors().expectEvents("test");
    }

    @Nested
    class NamespaceTests {

        private final TestFixture testFixture = TestFixture.createAsync(new DefaultNameSpaceHandler(),
                                                                        new FooNameSpaceHandler());

        @Test
        void sendToOtherNameSpace() {
            testFixture.whenApplying(fz -> fz.commandGateway().forNamespace("foo").send(new YieldsEventAndResult()))
                    .expectNoResultLike("default")
                    .expectResult("foo");
        }

        @Consumer(name = "DefaultNameSpaceHandler")
        private static class DefaultNameSpaceHandler {
            @HandleCommand
            public String handle(YieldsEventAndResult command) {
                Fluxzero.publishEvent(command);
                return "default";
            }
        }

        @Consumer(name = "FooNameSpaceHandler", namespace = "foo")
        private static class FooNameSpaceHandler {
            @HandleCommand
            public String handle(YieldsEventAndResult command) {
                Fluxzero.publishEvent(command);
                return "foo";
            }
        }
    }

    @Nested
    class PropertySubstitutionTests {

        private final TestFixture testFixture = TestFixture.createAsync()
                .withProperty("defaultConsumer", "DefaultNameSpaceHandler")
                .withProperty("fooNamespace", "foo")
                .registerHandlers(new DefaultNameSpaceHandler(), new FooNameSpaceHandler());

        @Test
        void sendToOtherNameSpace() {
            testFixture.whenApplying(fz -> fz.commandGateway().forNamespace("foo").send(new YieldsEventAndResult()))
                    .expectNoResultLike("default")
                    .expectResult("foo");
        }

        @Consumer(name = "${defaultConsumer}")
        private static class DefaultNameSpaceHandler {
            @HandleCommand
            public String handle(YieldsEventAndResult command) {
                Fluxzero.publishEvent(command);
                return "default";
            }
        }

        @Consumer(name = "${fooConsumer:FooNameSpaceHandler}", namespace = "${fooNamespace}")
        private static class FooNameSpaceHandler {
            @HandleCommand
            public String handle(YieldsEventAndResult command) {
                Fluxzero.publishEvent(command);
                assert Tracker.current().orElseThrow().getName().equals("FooNameSpaceHandler");
                return "foo";
            }
        }
    }

    @Consumer(name = "MixedHandler", errorHandler = IgnoringErrorHandler.class)
    private static class MixedHandler {
        @HandleCommand
        public String handle(YieldsEventAndResult command) {
            Fluxzero.publishEvent(command);
            return "result";
        }

        @HandleCommand
        public void handle(YieldsException command) {
            throw new MockException("expected");
        }

        @HandleCommand
        public void handle(YieldsRuntimeException command) {
            throw new IllegalStateException("expected");
        }

        @HandleCommand(passive = true)
        public String handle(PassivelyHandled command) {
            return "this will be ignored";
        }

        @HandleCommand
        public void handle(YieldsSchedule command) {
            Fluxzero.get().messageScheduler().schedule(command.getSchedule(), Duration.ofSeconds(10));
        }

        @HandleEvent
        public void handle(Integer event) throws Exception {
            Fluxzero.sendCommand(new YieldsEventAndResult()).get();
        }
    }

    private static class ScheduleHandler {
        @HandleSchedule
        public void handle(DelayedCommand schedule) {
            Fluxzero.sendAndForgetCommand(schedule);
        }
    }

    @Consumer(name = "MixedHandler", errorHandler = IgnoringErrorHandler.class)
    private static class AsyncCommandHandler {

        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                newPlatformThreadFactory("AsyncCommandHandler"));

        @HandleCommand
        public CompletableFuture<String> handle(YieldsAsyncResult command) {
            CompletableFuture<String> result = new CompletableFuture<>();
            scheduler.schedule(() -> result.complete("test"), 10, TimeUnit.MILLISECONDS);
            return result;
        }

        @HandleCommand
        public CompletableFuture<?> handle(YieldsAsyncException command) {
            CompletableFuture<String> result = new CompletableFuture<>();
            scheduler.schedule(() -> result.completeExceptionally(new IllegalCommandException("test")), 10,
                               TimeUnit.MILLISECONDS);
            return result;
        }

        @HandleCommand
        public CompletableFuture<?> handle(YieldsAsyncExceptionSecondHand command) {
            return Fluxzero.sendCommand(new YieldsAsyncException());
        }
    }

    @Value
    private static class YieldsEventAndResult {
    }

    @Value
    private static class YieldsAsyncResult {
    }

    @Value
    private static class YieldsException {
    }

    @Value
    private static class YieldsAsyncException {
    }

    @Value
    private static class YieldsAsyncExceptionSecondHand {
    }

    @Value
    private static class YieldsSchedule {
        Object schedule;
    }

    @Value
    private static class YieldsRuntimeException {
    }

    @Value
    private static class PassivelyHandled {
    }

    @Value
    private static class DelayedCommand {
    }

}
