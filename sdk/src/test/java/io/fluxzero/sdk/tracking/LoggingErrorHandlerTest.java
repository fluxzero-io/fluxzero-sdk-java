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

package io.fluxzero.sdk.tracking;

import ch.qos.logback.classic.Logger;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.MockException;
import io.fluxzero.sdk.common.exception.FluxzeroErrors;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.common.logging.FluxzeroLogbackAppender;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

class LoggingErrorHandlerTest {

    private final ThrowingHandler handler = new ThrowingHandler();
    private final TestFixture testFixture = TestFixture.createAsync(handler).spy();

    @Test
    void loggedHandlerErrorHasTraceId() {
        testFixture.whenCommand("error")
                .expectThat(fc -> verify(fc.client().getGatewayClient(MessageType.ERROR)).append(
                        any(), ArgumentMatchers.<SerializedMessage>argThat(
                                message -> message.getMetadata().containsKey("$consumer")
                                           && message.getMetadata().containsKey("$correlationId")
                                           && message.getMetadata().containsKey("$traceId"))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void technicalReportRetainsCodeAndDocumentationInPublishedDiagnostics(boolean malformedWebRequest) {
        var failure = new TechnicalException(FluxzeroErrors.handlerInvocationFailed(
                "ExampleHandler.handle", "ExampleRequest", new IllegalStateException("diagnostic detail")));
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingErrorHandler.class);
        var appender = new FluxzeroLogbackAppender(false) {};
        appender.start();
        logger.addAppender(appender);
        try {
            TestFixture.createAsync(new Object() {
                        @HandleCommand
                        void handle(String command) {
                            new DeserializingMessage(new Message("request", Metadata.of("diagnostic", "context")),
                                    malformedWebRequest ? MessageType.WEBREQUEST : MessageType.COMMAND,
                                    new JacksonSerializer()).run(m -> new LoggingErrorHandler().handleError(
                                            failure, "Request 42 failed", () -> null));
                        }
                    }).spy().whenCommand("error")
                    .expectThat(fc -> verify(fc.client().getGatewayClient(MessageType.ERROR)).append(
                            any(), ArgumentMatchers.<SerializedMessage>argThat(message -> {
                                var metadata = message.getMetadata();
                                return metadata.containsKey("diagnostic") != malformedWebRequest
                                       && "{}. Continuing...".equals(metadata.get("messageTemplate"))
                                       && failure.getMessage().equals(metadata.get("errorMessage"))
                                       && metadata.get("stackTrace").contains("FZ-SDK-0003")
                                       && metadata.get("stackTrace").contains(failure.getDocumentationUrl());
                            })));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static class ThrowingHandler {
        @HandleCommand
        private void handle(String command) {
            Fluxzero.loadAggregate("test", Aggregate.class).apply(command);
        }
    }

    private static class Aggregate {
        @Apply
        static Aggregate create(String event) {
            throw new MockException();
        }
    }

}