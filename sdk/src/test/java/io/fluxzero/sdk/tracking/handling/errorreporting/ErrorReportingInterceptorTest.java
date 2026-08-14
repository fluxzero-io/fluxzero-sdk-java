/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.tracking.handling.errorreporting;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerMethod;
import io.fluxzero.sdk.MockException;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.ErrorGateway;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import io.fluxzero.sdk.web.WebRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorReportingInterceptorTest {

    @Test
    void reportsErrorsFromReusableHandlerMethod() {
        RecordingErrorGateway errorGateway = new RecordingErrorGateway();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new ThrowingHandler(), HandleEvent.class, List.of(new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new ErrorReportingInterceptor(errorGateway).wrap(handler);
        DeserializingMessage firstMessage = message("first");
        DeserializingMessage secondMessage = message("second");

        HandlerMethod<DeserializingMessage> firstMethod = wrapped.getHandlerMethodOrNull(firstMessage);
        HandlerMethod<DeserializingMessage> secondMethod = wrapped.getHandlerMethodOrNull(secondMessage);

        assertNotNull(firstMethod);
        assertSame(firstMethod, secondMethod);
        assertThrows(IllegalStateException.class, () -> firstMethod.invoke(firstMessage));
        assertEquals(1, errorGateway.errors.size());
        assertInstanceOf(TechnicalException.class, errorGateway.errors.getFirst().getPayload());
    }

    @Test
    void reportsAsyncErrorsFromReusableHandlerMethod() {
        RecordingErrorGateway errorGateway = new RecordingErrorGateway();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new AsyncThrowingHandler(), HandleEvent.class, List.of(new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new ErrorReportingInterceptor(errorGateway).wrap(handler);
        DeserializingMessage message = message("payload");

        HandlerMethod<DeserializingMessage> method = wrapped.getHandlerMethodOrNull(message);
        assertNotNull(method);

        CompletionStage<?> result = (CompletionStage<?>) method.invoke(message);
        assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());
        assertEquals(1, errorGateway.errors.size());
        assertInstanceOf(TechnicalException.class, errorGateway.errors.getFirst().getPayload());
    }

    @Test
    void redactsWebRequestMetadataFromSynchronousErrors() {
        RecordingErrorGateway errorGateway = new RecordingErrorGateway();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new ThrowingHandler(), HandleEvent.class, List.of(new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new ErrorReportingInterceptor(errorGateway).wrap(handler);
        DeserializingMessage message = webMessage();

        assertThrows(IllegalStateException.class, () -> wrapped.getHandlerMethodOrNull(message).invoke(message));

        Message error = errorGateway.errors.getFirst();
        assertEquals("POST", error.getMetadata().get(WebRequest.methodKey));
        assertEquals("/auth/flow/registration/profile", error.getMetadata().get(WebRequest.urlKey));
        assertTrue(error.getMetadata().containsKey("stackTrace"));
        assertFalse(error.getMetadata().containsKey(WebRequest.headersKey));
        assertFalse(error.getMetadata().containsKey(WebRequest.sessionIdKey));
        assertFalse(error.getMetadata().containsKey("unsafe"));
        String serializedError = error.getPayload().toString();
        for (String secret : List.of("flow-secret", "cookie-secret", "authorization-secret", "context-secret",
                                     "signature-secret", "session-secret", "metadata-secret", "payload-secret")) {
            assertFalse(serializedError.contains(secret));
        }
    }

    @Test
    void redactsWebRequestMetadataFromFunctionalErrors() {
        RecordingErrorGateway errorGateway = new RecordingErrorGateway();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new FunctionalThrowingHandler(), HandleEvent.class, List.of(new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new ErrorReportingInterceptor(errorGateway).wrap(handler);
        DeserializingMessage message = webMessage();

        assertThrows(MockException.class, () -> wrapped.getHandlerMethodOrNull(message).invoke(message));

        Message error = errorGateway.errors.getFirst();
        assertInstanceOf(MockException.class, error.getPayload());
        assertEquals("/auth/flow/registration/profile", error.getMetadata().get(WebRequest.urlKey));
        assertFalse(error.getMetadata().containsKey("stackTrace"));
        assertFalse(error.getMetadata().containsKey(WebRequest.headersKey));
        assertFalse(error.getMetadata().containsKey("unsafe"));
    }

    @Test
    void redactsWebRequestMetadataFromAsyncErrors() {
        RecordingErrorGateway errorGateway = new RecordingErrorGateway();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new AsyncThrowingHandler(), HandleEvent.class, List.of(new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new ErrorReportingInterceptor(errorGateway).wrap(handler);
        DeserializingMessage message = webMessage();

        CompletionStage<?> result = (CompletionStage<?>) wrapped.getHandlerMethodOrNull(message).invoke(message);
        assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());

        Message error = errorGateway.errors.getFirst();
        assertEquals("/auth/flow/registration/profile", error.getMetadata().get(WebRequest.urlKey));
        assertFalse(error.getMetadata().containsKey(WebRequest.headersKey));
        assertFalse(error.getMetadata().containsKey("unsafe"));
    }

    @Test
    void preservesMetadataForNonWebRequestErrors() {
        RecordingErrorGateway errorGateway = new RecordingErrorGateway();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new ThrowingHandler(), HandleEvent.class, List.of(new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new ErrorReportingInterceptor(errorGateway).wrap(handler);
        DeserializingMessage message = new DeserializingMessage(
                new Message("payload", Metadata.of("custom", "value")), MessageType.EVENT, null);

        assertThrows(IllegalStateException.class, () -> wrapped.getHandlerMethodOrNull(message).invoke(message));

        assertEquals("value", errorGateway.errors.getFirst().getMetadata().get("custom"));
    }

    @Test
    void asyncErrorRestoresMessageContext() {
        CompletableFuture<Void> handlerResult = new CompletableFuture<>();
        RecordingErrorGateway errorGateway = new RecordingErrorGateway();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new DeferredThrowingHandler(handlerResult), HandleEvent.class,
                List.of(new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new ErrorReportingInterceptor(errorGateway).wrap(handler);
        DeserializingMessage message = message("payload");

        wrapped.getHandlerMethodOrNull(message).invoke(message);
        assertTrue(DeserializingMessage.getOptionally().isEmpty());

        handlerResult.completeExceptionally(new IllegalStateException("boom"));

        assertSame(message, errorGateway.currentMessageDuringReport);
        assertTrue(DeserializingMessage.getOptionally().isEmpty());
    }

    @Test
    void consumerNamespaceDoesNotChangeApplicationErrorGateway() {
        RecordingErrorGateway applicationGateway = new RecordingErrorGateway();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new ThrowingHandler(), HandleEvent.class, List.of(new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new ErrorReportingInterceptor(applicationGateway).wrap(handler);
        DeserializingMessage message = message("payload").putContext(
                ConsumerConfiguration.class, ConsumerConfiguration.builder()
                        .name("customer-consumer").namespace("customer").build());

        assertThrows(IllegalStateException.class, () -> wrapped.getHandlerMethodOrNull(message).invoke(message));

        assertEquals(1, applicationGateway.errors.size());
        assertEquals(List.of(), applicationGateway.requestedNamespaces);
    }

    @Test
    void localReusableHandlerMethodErrorsAreNotReported() {
        RecordingErrorGateway errorGateway = new RecordingErrorGateway();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new LocalThrowingHandler(), HandleEvent.class, List.of(new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new ErrorReportingInterceptor(errorGateway).wrap(handler);
        DeserializingMessage message = message("payload");

        HandlerMethod<DeserializingMessage> method = wrapped.getHandlerMethodOrNull(message);
        assertNotNull(method);

        assertThrows(IllegalStateException.class, () -> method.invoke(message));
        assertEquals(0, errorGateway.errors.size());
    }

    @Test
    void selfTrackingReusableHandlerMethodErrorsAreNotReportedForSelfMessages() {
        RecordingErrorGateway errorGateway = new RecordingErrorGateway();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new SelfTrackingPayload(), HandleEvent.class, List.of(new MessageParameterResolver()));
        Handler<DeserializingMessage> wrapped = new ErrorReportingInterceptor(errorGateway).wrap(handler);
        DeserializingMessage message = message(new SelfTrackingPayload());

        HandlerMethod<DeserializingMessage> method = wrapped.getHandlerMethodOrNull(message);
        assertNotNull(method);

        assertThrows(IllegalStateException.class, () -> method.invoke(message));
        assertEquals(0, errorGateway.errors.size());
    }

    private static DeserializingMessage message(Object payload) {
        return new DeserializingMessage(new Message(payload), MessageType.EVENT, null);
    }

    private static DeserializingMessage webMessage() {
        WebRequest request = WebRequest.post(
                        "https://auth.example.test/auth/flow/registration/profile?flow=flow-secret")
                .header("Cookie", "session=cookie-secret")
                .header("Authorization", "Bearer authorization-secret")
                .header("Fzc", "context-secret")
                .header("Fzc-Sig", "signature-secret")
                .metadata(Metadata.of(WebRequest.sessionIdKey, "session-secret", "unsafe", "metadata-secret"))
                .body("payload-secret")
                .build();
        return new DeserializingMessage(request, MessageType.WEBREQUEST, null);
    }

    private static class ThrowingHandler {
        @HandleEvent
        void handle(DeserializingMessage ignored) {
            throw new IllegalStateException("boom");
        }
    }

    private static class FunctionalThrowingHandler {
        @HandleEvent
        void handle(DeserializingMessage ignored) {
            throw new MockException();
        }
    }

    private static class AsyncThrowingHandler {
        @HandleEvent
        CompletionStage<Void> handle(DeserializingMessage ignored) {
            return CompletableFuture.failedFuture(new IllegalStateException("boom"));
        }
    }

    private record DeferredThrowingHandler(CompletableFuture<Void> result) {
        @HandleEvent
        CompletionStage<Void> handle(DeserializingMessage ignored) {
            return result;
        }
    }

    private static class LocalThrowingHandler {
        @HandleEvent
        @LocalHandler
        void handle(DeserializingMessage ignored) {
            throw new IllegalStateException("boom");
        }
    }

    @TrackSelf
    private static class SelfTrackingPayload {
        @HandleEvent
        void handle(DeserializingMessage ignored) {
            throw new IllegalStateException("boom");
        }
    }

    private static class RecordingErrorGateway implements ErrorGateway {
        private final List<Message> errors = new ArrayList<>();
        private final List<String> requestedNamespaces = new ArrayList<>();
        private DeserializingMessage currentMessageDuringReport;

        @Override
        public CompletableFuture<Void> report(Guarantee guarantee, Message... errors) {
            currentMessageDuringReport = DeserializingMessage.getOptionally().orElse(null);
            this.errors.addAll(List.of(errors));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> report(Object payload, Metadata metadata, Guarantee guarantee) {
            currentMessageDuringReport = DeserializingMessage.getOptionally().orElse(null);
            errors.add(new Message(payload, metadata));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public ErrorGateway forNamespace(String namespace) {
            requestedNamespaces.add(namespace);
            return this;
        }

        @Override
        public boolean hasLocalHandlers() {
            return false;
        }

        @Override
        public void setSelfHandlerFilter(HandlerFilter selfHandlerFilter) {
        }

        @Override
        public Registration registerHandler(Object target, HandlerFilter handlerFilter) {
            return Registration.noOp();
        }
    }
}
