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

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerDescriptor;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.publishing.ResultGateway;
import io.fluxzero.sdk.tracking.handling.HandlerFactory;
import io.fluxzero.sdk.web.WebRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultTrackingAsyncResultTest {

    @Test
    void asyncResultsAreNotAwaitedByDefault() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<String> handlerResult = new CompletableFuture<>();

        CompletionStage<Void> completion = tracking.report(
                handlerResult, descriptor(), message(serializer), ConsumerConfiguration.builder().name("web").build());

        assertTrue(completion.toCompletableFuture().isDone());
        verify(resultGateway, never()).respond("ok", "benchmark-app", 7);

        handlerResult.complete("ok");

        verify(resultGateway).respond("ok", "benchmark-app", 7);
        tracking.close();
    }

    @Test
    void asyncResultsCanBeAwaitedBeforeBatchCompletion() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<String> handlerResult = new CompletableFuture<>();

        CompletionStage<Void> completion = tracking.report(
                handlerResult,
                descriptor(),
                message(serializer),
                ConsumerConfiguration.builder().name("web").awaitAsyncResults(true).build());

        assertFalse(completion.toCompletableFuture().isDone());
        handlerResult.complete("ok");
        assertTrue(completion.toCompletableFuture().isDone());
        verify(resultGateway).respond("ok", "benchmark-app", 7);
        tracking.close();
    }

    @Test
    void batchCompletionWaitsForAsyncResultsWhenConfigured() throws Exception {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<String> handlerResult = new CompletableFuture<>();

        CompletableFuture<Void> batchCompletion = CompletableFuture.runAsync(() -> tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(handlerResult)),
                ConsumerConfiguration.builder().name("web").awaitAsyncResults(true).build(),
                true));

        TimeUnit.MILLISECONDS.sleep(50L);
        assertFalse(batchCompletion.isDone());
        verify(resultGateway, never()).respond("ok", "benchmark-app", 7);

        handlerResult.complete("ok");

        assertDoesNotThrow(() -> batchCompletion.get(1, TimeUnit.SECONDS));
        verify(resultGateway).respond("ok", "benchmark-app", 7);
        tracking.close();
    }

    @Test
    void batchCompletionDoesNotWaitForAsyncResultsByDefault() {
        JacksonSerializer serializer = new JacksonSerializer();
        ResultGateway resultGateway = mock(ResultGateway.class);
        when(resultGateway.forNamespace(null)).thenReturn(resultGateway);
        TestTracking tracking = tracking(resultGateway, serializer);
        CompletableFuture<String> handlerResult = new CompletableFuture<>();

        tracking.handleBatch(
                List.of(message(serializer)),
                List.of(handler(handlerResult)),
                ConsumerConfiguration.builder().name("web").build(),
                true);

        verify(resultGateway, never()).respond("ok", "benchmark-app", 7);

        handlerResult.complete("ok");

        verify(resultGateway).respond("ok", "benchmark-app", 7);
        tracking.close();
    }

    private static TestTracking tracking(ResultGateway resultGateway, JacksonSerializer serializer) {
        return new TestTracking(resultGateway, serializer);
    }

    private static HandlerDescriptor descriptor() {
        HandlerDescriptor descriptor = mock(HandlerDescriptor.class);
        when(descriptor.isPassive()).thenReturn(false);
        return descriptor;
    }

    private static DeserializingMessage message(JacksonSerializer serializer) {
        SerializedMessage message = new SerializedMessage(
                serializer.serialize("request"),
                Metadata.of(WebRequest.methodKey, "POST", WebRequest.urlKey, "/benchmark"),
                "message-1",
                System.currentTimeMillis());
        message.setSource("benchmark-app");
        message.setRequestId(7);
        return new DeserializingMessage(message, type -> "request", MessageType.WEBREQUEST, null, serializer);
    }

    private static Handler<DeserializingMessage> handler(CompletableFuture<String> result) {
        HandlerInvoker invoker = mock(HandlerInvoker.class);
        when(invoker.invoke()).thenReturn(result);
        when(invoker.isPassive()).thenReturn(false);
        Handler<DeserializingMessage> handler = mock(Handler.class);
        when(handler.getInvokerOrNull(org.mockito.ArgumentMatchers.any())).thenReturn(invoker);
        when(handler.getInvoker(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(invoker));
        return handler;
    }

    private static class TestTracking extends DefaultTracking {

        TestTracking(ResultGateway resultGateway, JacksonSerializer serializer) {
            super(MessageType.WEBREQUEST, resultGateway, List.of(), List.of(), serializer, mock(HandlerFactory.class));
        }

        CompletionStage<Void> report(Object result, HandlerDescriptor descriptor, DeserializingMessage message,
                                     ConsumerConfiguration config) {
            return reportResult(result, descriptor, message, config);
        }
    }
}
