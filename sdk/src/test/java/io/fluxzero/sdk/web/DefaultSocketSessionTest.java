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

package io.fluxzero.sdk.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.exception.FluxzeroErrors;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.publishing.ResultGateway;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import io.fluxzero.sdk.tracking.handling.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultSocketSessionTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(DefaultSocketSession.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final ResultGateway gateway = mock(ResultGateway.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final DefaultSocketSession session = new DefaultSocketSession(
            "session", "", "target", "/socket", Map.of(), null, gateway, scheduler, (s, code) -> {});

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void technicalFailureIsPrivateButKeepsCauseAndCorrelation(boolean async) {
        var failure = new IllegalStateException("diagnostic database failure");
        SocketResponse response = handle(failure, async);
        assertEquals(42, response.getRequestId());
        assertEquals("Request failed", response.getError());
        assertNull(response.getResult());
        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.getFirst();
        assertTrue(event.getFormattedMessage().contains("42"));
        assertFalse(event.getFormattedMessage().contains("private payload"));
        assertEquals(failure.getMessage(), event.getThrowableProxy().getMessage());
        assertEquals(failure.getClass().getName(), event.getThrowableProxy().getClassName());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void technicalReportRemainsInDiagnosticsWithStableLogPattern(boolean async) {
        var cause = new IllegalStateException("diagnostic detail");
        var failure = new TechnicalException(
                FluxzeroErrors.handlerInvocationFailed("ExampleHandler.handle", "ExampleRequest", cause), cause);
        SocketResponse response = handle(failure, async);
        assertEquals(42, response.getRequestId());
        assertEquals("Request failed", response.getError());
        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.getFirst();
        assertEquals("Socket request {} failed", event.getMessage());
        assertEquals("Socket request 42 failed", event.getFormattedMessage());
        assertSame(failure, ((ThrowableProxy) event.getThrowableProxy()).getThrowable());
        assertSame(cause, failure.getCause());
        assertEquals(failure.getMessage(), event.getThrowableProxy().getMessage());
        assertTrue(event.getThrowableProxy().getMessage().contains("FZ-SDK-0003"));
        assertTrue(event.getThrowableProxy().getMessage().contains(failure.getDocumentationUrl()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void functionalFailureKeepsExplicitApplicationText(boolean async) {
        SocketResponse response = handle(new IllegalCommandException("Please choose another delivery date"), async);
        assertEquals(42, response.getRequestId());
        assertEquals("Please choose another delivery date", response.getError());
        assertTrue(logs.list.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successKeepsPayloadAndCorrelation(boolean async) {
        SocketResponse response = handle("success", async);
        assertEquals(42, response.getRequestId());
        assertEquals("success", response.getResult().asText());
        assertNull(response.getError());
        assertTrue(logs.list.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wrappedFunctionalFailureKeepsApplicationMessage(boolean async) {
        SocketResponse response = handle(new CompletionException(new IllegalCommandException("Custom rejection")), async);
        assertEquals("Custom rejection", response.getError());
        assertTrue(logs.list.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wrapperWithoutCauseKeepsItsOwnDiagnostic(boolean async) {
        SocketResponse response = handle(new CompletionException("diagnostic detail", null), async);
        assertEquals("Request failed", response.getError());
        assertEquals("diagnostic detail", logs.list.getFirst().getThrowableProxy().getMessage());
    }

    @Test
    void unmatchedResponseIsLoggedWithoutItsBody() {
        session.tryCompleteRequest(message(SocketResponse.success(77, "private response"))).orElseThrow().invoke();
        assertEquals(1, logs.list.size());
        assertTrue(logs.list.getFirst().getFormattedMessage().contains("77"));
        assertFalse(logs.list.getFirst().getFormattedMessage().contains("private response"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedSendIsLoggedWithoutRequestBody(boolean nullRequest) {
        when(scheduler.orTimeout(any(CompletableFuture.class), any(Duration.class)))
                .thenAnswer(i -> i.getArgument(0));
        var failure = new IllegalStateException("transport failed");
        doThrow(failure).when(gateway).respond(any(), any(), any(), any(), any());
        var result = session.sendRequest(nullRequest ? null : new PrivateRequest("private payload"), Duration.ofSeconds(1));
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
        assertEquals(1, logs.list.size());
        if (!nullRequest) {
            assertTrue(logs.list.getFirst().getFormattedMessage().contains(PrivateRequest.class.getName()));
        }
        assertFalse(logs.list.getFirst().getFormattedMessage().contains("private payload"));
        assertEquals(failure.getMessage(), logs.list.getFirst().getThrowableProxy().getMessage());
    }

    @SuppressWarnings("unchecked")
    private SocketResponse handle(Object result, boolean async) {
        Handler<DeserializingMessage> handler = mock(Handler.class);
        HandlerInvoker invoker = mock(HandlerInvoker.class);
        when(handler.getInvoker(any())).thenReturn(Optional.of(invoker));
        if (result instanceof Throwable failure) {
            if (async) {
                when(invoker.invoke()).thenReturn(CompletableFuture.failedFuture(failure));
            } else {
                when(invoker.invoke()).thenThrow(failure);
            }
        } else {
            when(invoker.invoke()).thenReturn(async ? CompletableFuture.completedFuture(result) : result);
        }
        Object response = session.tryHandleRequest(
                message(JsonUtils.fromJson("{\"requestId\":42,\"request\":\"private payload\"}", SocketRequest.class)), handler).orElseThrow().invoke();
        return (SocketResponse) (response instanceof CompletableFuture<?> future ? future.join() : response);
    }

    private DeserializingMessage message(Object payload) {
        return new DeserializingMessage(new Message(payload), WEBREQUEST, new JacksonSerializer());
    }

    private record PrivateRequest(String value) implements Request<String> {}
}
