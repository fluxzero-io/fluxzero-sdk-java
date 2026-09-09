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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils.ConsumerNamespaceContext;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.MessageType.COMMAND;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class LocalOnlyDispatchTest {

    @Test
    void handlesCommandsSynchronouslyAsynchronouslyInBulkAndFireAndForget() {
        CommandHandler handler = new CommandHandler("handled");
        try (Fluxzero fluxzero = createFluxzero()) {
            fluxzero.commandGateway().registerHandler(handler);

            assertEquals("handled-one", fluxzero.commandGateway().sendAndWait(new LocalCommand("one")));
            assertEquals("handled-two", fluxzero.commandGateway().<String>send(new LocalCommand("two")).join());
            List<CompletableFuture<String>> bulk = fluxzero.commandGateway().send(
                    new LocalCommand("three"), new LocalCommand("four"));
            assertEquals(List.of("handled-three", "handled-four"), bulk.stream().map(CompletableFuture::join).toList());
            fluxzero.commandGateway().sendAndForget(new LocalCommand("five"));

            assertEquals(5, handler.invocations.get());
        }
    }

    @Test
    void handlesQueriesAndPreservesTheirAsyncResult() {
        try (Fluxzero fluxzero = createFluxzero()) {
            fluxzero.queryGateway().registerHandler(new QueryHandler());

            assertEquals("answer", fluxzero.queryGateway().sendAndWait(new LocalQuery()));
            assertEquals("answer", fluxzero.queryGateway().<String>send(new LocalQuery()).join());
        }
    }

    @Test
    void supportsMetaAnnotatedSelfHandlingPayloads() {
        try (Fluxzero fluxzero = createFluxzero()) {
            assertEquals("self", fluxzero.commandGateway().sendAndWait(new SelfHandlingCommand()));
        }
    }

    @Test
    void registrationRemovalAndAmbiguityRemainFailClosed() {
        try (Fluxzero fluxzero = createFluxzero()) {
            CommandHandler first = new CommandHandler("first");
            Registration registration = fluxzero.commandGateway().registerHandler(first);
            assertEquals("first-value", fluxzero.commandGateway().sendAndWait(new LocalCommand("value")));

            registration.cancel();
            LocalOnlyDispatchException missing = assertThrows(
                    LocalOnlyDispatchException.class,
                    () -> fluxzero.commandGateway().sendAndWait(new LocalCommand("missing")));
            assertTrue(missing.getMessage().contains("no local request handler matched"));

            CommandHandler second = new CommandHandler("second");
            CommandHandler third = new CommandHandler("third");
            fluxzero.commandGateway().registerHandler(second);
            fluxzero.commandGateway().registerHandler(third);
            LocalOnlyDispatchException ambiguous = assertThrows(
                    LocalOnlyDispatchException.class,
                    () -> fluxzero.commandGateway().sendAndWait(new LocalCommand("ambiguous")));
            assertTrue(ambiguous.getMessage().contains("multiple local request handlers matched"));
            assertEquals(0, second.invocations.get());
            assertEquals(0, third.invocations.get());
        }
    }

    @Test
    void missingHandlerFailsDirectlyForAsyncBulkAndFireAndForgetCalls() {
        try (Fluxzero fluxzero = createFluxzero()) {
            assertThrows(LocalOnlyDispatchException.class,
                         () -> fluxzero.commandGateway().send(new LocalCommand("async")));
            assertThrows(LocalOnlyDispatchException.class,
                         () -> fluxzero.commandGateway().send(
                                 new LocalCommand("first"), new LocalCommand("second")));
            assertThrows(LocalOnlyDispatchException.class,
                         () -> fluxzero.commandGateway().sendAndForget(new LocalCommand("forget")));
        }
    }

    @Test
    void preservesExplicitNamespaceForTheSelectedLocalHandler() {
        try (Fluxzero fluxzero = createFluxzero()) {
            fluxzero.commandGateway().registerHandler(new NamespaceHandler());

            assertEquals("tenant", fluxzero.commandGateway().forNamespace("tenant")
                    .sendAndWait(new NamespacedLocalCommand()));
        }
    }

    @Test
    void restrictionSurvivesInterceptorReplacementAndAppliesToMarkedReplacement() {
        FluxzeroBuilder builder = DefaultFluxzero.builder().addDispatchInterceptor((message, type, topic) -> {
            if (message.getPayload() instanceof MarkedOriginal) {
                return message.withPayload(new UnmarkedReplacement());
            }
            if (message.getPayload() instanceof UnmarkedOriginal) {
                return message.withPayload(new MarkedReplacement());
            }
            return message;
        }, COMMAND);
        try (Fluxzero fluxzero = createFluxzero(builder)) {
            fluxzero.commandGateway().registerHandler(new ReplacementHandler());

            assertEquals("unmarked", fluxzero.commandGateway().sendAndWait(new MarkedOriginal()));
            assertEquals("marked", fluxzero.commandGateway().sendAndWait(new UnmarkedOriginal()));
        }
    }

    @Test
    void interceptorMaySuppressMarkedPayload() {
        FluxzeroBuilder builder = DefaultFluxzero.builder().addDispatchInterceptor(
                (message, type, topic) -> message.getPayload() instanceof SuppressedCommand ? null : message,
                COMMAND);
        try (Fluxzero fluxzero = createFluxzero(builder)) {
            assertNull(fluxzero.commandGateway().sendAndWait(new SuppressedCommand()));
        }
    }

    @Test
    void rejectsLocalHandlerThatWouldAlsoPublishTheMessage() {
        PublishingHandler handler = new PublishingHandler();
        try (Fluxzero fluxzero = createFluxzero()) {
            fluxzero.commandGateway().registerHandler(handler);

            LocalOnlyDispatchException error = assertThrows(
                    LocalOnlyDispatchException.class,
                    () -> fluxzero.commandGateway().sendAndWait(new LocalCommand("value")));

            assertTrue(error.getMessage().contains("configured to publish the message externally"));
            assertEquals(0, handler.invocations.get());
        }
    }

    @Test
    void permitsPassiveLocalObserversAlongsideTheSingleRequestHandler() {
        PassiveHandler passive = new PassiveHandler();
        try (Fluxzero fluxzero = createFluxzero()) {
            fluxzero.commandGateway().registerHandler(new CommandHandler("active"));
            fluxzero.commandGateway().registerHandler(passive);

            assertEquals("active-value", fluxzero.commandGateway().sendAndWait(new LocalCommand("value")));
            assertEquals(1, passive.invocations.get());
        }
    }

    @Test
    void ordinaryPayloadStillFallsBackToExternalDispatch() {
        try (Fluxzero fluxzero = createFluxzero()) {
            fluxzero.commandGateway().sendAndForget(new OrdinaryCommand());

            assertEquals(1, ((LocalClient) fluxzero.client()).getTrackingClient(COMMAND)
                    .readFromIndex(0, 10).size());
        }
    }

    private static Fluxzero createFluxzero() {
        return createFluxzero(DefaultFluxzero.builder());
    }

    private static Fluxzero createFluxzero(FluxzeroBuilder builder) {
        return builder.build(LocalClient.newInstance(null));
    }

    @LocalOnly
    private record LocalCommand(String value) {
    }

    @LocalOnly
    private record LocalQuery() {
    }

    @LocalOnly
    private record NamespacedLocalCommand() {
    }

    @LocalOnly
    private record MarkedOriginal() {
    }

    private record UnmarkedOriginal() {
    }

    private record UnmarkedReplacement() {
    }

    @LocalOnly
    private record MarkedReplacement() {
    }

    @LocalOnly
    private record SuppressedCommand() {
    }

    private record OrdinaryCommand() {
    }

    @SensitiveLocal
    private record SelfHandlingCommand() {
        @HandleCommand
        String handle() {
            return "self";
        }
    }

    @LocalOnly
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    private @interface SensitiveLocal {
    }

    @LocalHandler
    private static class CommandHandler {
        private final String result;
        private final AtomicInteger invocations = new AtomicInteger();

        private CommandHandler(String result) {
            this.result = result;
        }

        @HandleCommand
        String handle(LocalCommand command) {
            invocations.incrementAndGet();
            return result + "-" + command.value();
        }
    }

    @LocalHandler
    private static class QueryHandler {
        @HandleQuery
        CompletableFuture<String> handle(LocalQuery ignored) {
            return completedFuture("answer");
        }
    }

    @LocalHandler
    private static class NamespaceHandler {
        @HandleCommand
        String handle(NamespacedLocalCommand ignored) {
            return DeserializingMessage.getCurrent().getContext(ConsumerNamespaceContext.class)
                    .map(ConsumerNamespaceContext::namespace).orElse(null);
        }
    }

    @LocalHandler
    private static class ReplacementHandler {
        @HandleCommand
        String handle(UnmarkedReplacement ignored) {
            return "unmarked";
        }

        @HandleCommand
        String handle(MarkedReplacement ignored) {
            return "marked";
        }
    }

    @LocalHandler(logMessage = true)
    private static class PublishingHandler {
        private final AtomicInteger invocations = new AtomicInteger();

        @HandleCommand
        String handle(LocalCommand command) {
            invocations.incrementAndGet();
            return command.value();
        }
    }

    @LocalHandler
    private static class PassiveHandler {
        private final AtomicInteger invocations = new AtomicInteger();

        @HandleCommand(passive = true)
        void handle(LocalCommand ignored) {
            invocations.incrementAndGet();
        }
    }
}
