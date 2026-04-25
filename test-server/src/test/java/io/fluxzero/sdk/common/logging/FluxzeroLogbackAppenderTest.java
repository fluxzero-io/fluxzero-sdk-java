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
 *
 */

package io.fluxzero.sdk.common.logging;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.ClientUtils;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.testserver.TestServer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentMatcher;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@Slf4j
@Isolated
class FluxzeroLogbackAppenderTest {
    private static final int port = 9127;

    private final Fluxzero fluxzero = TestFixture.create().spy().getFluxzero();

    @BeforeAll
    static void beforeAll() {
        TestServer.start(port);
    }

    @BeforeEach
    void setUp() {
        FluxzeroLogbackAppender.attach();
    }

    @AfterEach
    void tearDown() {
        FluxzeroLogbackAppender.detach();
    }

    @Test
    void testConsoleError() {
        log.error("mock error");
        verify(fluxzero.client().getGatewayClient(MessageType.ERROR), timeout(1_000)).append(
                any(Guarantee.class), argThat((ArgumentMatcher<SerializedMessage>) message ->
                        ConsoleError.class.getName().equals(message.getData().getType())));
    }

    @Test
    void programmaticAttachPublishesSynchronously() {
        log.error("mock synchronous error");
        verify(fluxzero.client().getGatewayClient(MessageType.ERROR)).append(
                any(Guarantee.class), argThat((ArgumentMatcher<SerializedMessage>) message ->
                        ConsoleError.class.getName().equals(message.getData().getType())));
    }

    @Test
    void testConsoleErrorWithIgnoreMarker() {
        log.error(ClientUtils.ignoreMarker, "mock error");
        verify(fluxzero.client().getGatewayClient(MessageType.ERROR), after(250).never()).append(
                any(Guarantee.class), argThat((ArgumentMatcher<SerializedMessage>) message ->
                        ConsoleError.class.getName().equals(message.getData().getType())));
    }

    @Test
    void testConsoleErrorWithoutFluxzeroInstanceIsIgnored() {
        Fluxzero previousApplicationInstance = Fluxzero.applicationInstance.get();
        Fluxzero.instance.remove();
        Fluxzero.applicationInstance.set(null);
        try {
            log.error("mock error without fluxzero");
            verify(fluxzero.client().getGatewayClient(MessageType.ERROR), after(250).never()).append(
                    any(Guarantee.class), argThat((ArgumentMatcher<SerializedMessage>) message ->
                            ConsoleError.class.getName().equals(message.getData().getType())));
        } finally {
            Fluxzero.applicationInstance.set(previousApplicationInstance);
            Fluxzero.instance.set(fluxzero);
        }
    }

    @Test
    void testConsoleWarning() {
        String messageTemplate = "mock warning {}";
        log.warn(messageTemplate, "foo");
        verify(fluxzero.client().getGatewayClient(MessageType.ERROR), timeout(1_000)).append(
                any(Guarantee.class), argThat((ArgumentMatcher<SerializedMessage>) message ->
                        ConsoleWarning.class.getName().equals(message.getData().getType())
                        && messageTemplate.equals(message.getMetadata().get("messageTemplate"))));
    }

    @Test
    void testConsoleErrorPreservesCorrelationData() {
        Message trigger = new Message(
                "trigger", Metadata.empty().withTrace("user", "rene"), "trigger-message", Instant.now());

        new DeserializingMessage(trigger, MessageType.COMMAND, fluxzero.serializer()).run(
                __ -> log.error("correlated error"));

        verify(fluxzero.client().getGatewayClient(MessageType.ERROR), timeout(1_000)).append(
                any(Guarantee.class), argThat((ArgumentMatcher<SerializedMessage>) message ->
                        ConsoleError.class.getName().equals(message.getData().getType())
                        && "trigger-message".equals(message.getMetadata().get("$correlationId"))
                        && "trigger-message".equals(message.getMetadata().get("$traceId"))
                        && String.class.getName().equals(message.getMetadata().get("$trigger"))
                        && MessageType.COMMAND.name().equals(message.getMetadata().get("$triggerType"))
                        && "rene".equals(message.getMetadata().get("$trace.user"))));
    }

    @Test
    void logbackAppenderCanPublishErrorLogToWebsocketErrorGateway() throws Exception {
        FluxzeroLogbackAppender.detach();
        FluxzeroLogbackAppender.attach(true);
        TestFixture fixture = TestFixture.createAsync(
                DefaultFluxzero.builder().disableScheduledCommandHandler(),
                WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                        .runtimeBaseUrl("ws://localhost:" + port)
                        .namespace("logback-appender")
                        .name("Logback Appender Test")
                        .disableMetrics(true)
                        .build()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> logged = executor.submit(() -> fixture.getFluxzero().execute(
                fc -> log.error("mock error for websocket appender reproduction")));
        try {
            assertDoesNotThrow(() -> logged.get(2, TimeUnit.SECONDS));
        } finally {
            if (!logged.isDone()) {
                logged.cancel(true);
            }
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
            fixture.getFluxzero().close(true);
            Fluxzero.instance.set(fluxzero);
        }
    }
}
