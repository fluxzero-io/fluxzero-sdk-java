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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.tracking.handling.authentication.MockUser;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.tracking.handling.contentfiltering.ContentFilterInterceptor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentFilterInterceptorTest {
    private final Serializer serializer = mock(Serializer.class);
    private final DeserializingMessage message = new DeserializingMessage(new Message("request"),
                                                                         MessageType.QUERY, serializer);

    @Test
    void filtersDeferredValueInRequestContextAndRestoresCompletionThread() throws Exception {
        var requester = new MockUser("requester");
        var workerUser = new MockUser("worker");
        var application = mock(Fluxzero.class, CALLS_REAL_METHODS);
        var workerApplication = mock(Fluxzero.class, CALLS_REAL_METHODS);
        var source = new CompletableFuture<String>();
        when(serializer.filterContent("secret", requester)).thenAnswer(__ -> {
            assertSame(requester, User.getCurrent());
            assertSame(application, Fluxzero.get());
            assertSame(message, DeserializingMessage.getCurrent());
            return "filtered";
        });
        var result = application.apply(__ -> requester.apply(() -> invoke(source)));
        assertFalse(result.isDone());
        verifyNoInteractions(serializer);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> workerApplication.apply(__ -> workerUser.apply(() -> {
                source.complete("secret");
                assertSame(workerUser, User.getCurrent());
                assertSame(workerApplication, Fluxzero.get());
                assertNull(DeserializingMessage.getCurrent());
                return null;
            }))).get(5, TimeUnit.SECONDS);
        }
        assertEquals("filtered", result.join());
        verify(serializer).filterContent("secret", requester);
    }

    @Test
    void preservesFailureAndCancellationWithoutFiltering() throws Exception {
        var failure = new IllegalStateException("failed");
        assertSame(failure, assertThrows(CompletionException.class,
                () -> invoke(CompletableFuture.failedFuture(failure)).join()).getCause());
        var source = new CompletableFuture<>();
        var result = invoke(source);
        source.cancel(false);
        assertInstanceOf(CancellationException.class,
                         assertThrows(CompletionException.class, result::join).getCause());
        verifyNoInteractions(serializer);
    }

    @Test
    void preservesNullAndPropagatesFilterFailure() throws Exception {
        assertNull(invoke(CompletableFuture.completedFuture(null)).join());
        verify(serializer).filterContent(null, null);
        var failure = new IllegalArgumentException("filter failed");
        when(serializer.filterContent("secret", null)).thenThrow(failure);
        assertSame(failure, assertThrows(CompletionException.class,
                () -> invoke(CompletableFuture.completedFuture("secret")).join()).getCause());
    }

    @Test
    void unannotatedHandlerKeepsItsOriginalFuture() throws Exception {
        HandlerInvoker invoker = mock(HandlerInvoker.class);
        doReturn(Endpoint.class).when(invoker).getTargetClass();
        when(invoker.getMethod()).thenReturn(Endpoint.class.getDeclaredMethod("unfiltered"));
        var source = new CompletableFuture<>();
        assertSame(source, new ContentFilterInterceptor(serializer)
                .interceptHandling(__ -> source, invoker).apply(message));
        verifyNoInteractions(serializer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CompletableFuture<?> invoke(CompletableFuture<?> response) throws Exception {
        HandlerInvoker invoker = mock(HandlerInvoker.class);
        when(invoker.getTargetClass()).thenReturn((Class) Endpoint.class);
        when(invoker.getMethod()).thenReturn(Endpoint.class.getDeclaredMethod("handle"));
        return (CompletableFuture<?>) new ContentFilterInterceptor(serializer)
                .interceptHandling(__ -> response, invoker).apply(message);
    }

    static class Endpoint {
        @FilterContent
        CompletableFuture<String> handle() { return null; }

        CompletableFuture<String> unfiltered() { return null; }
    }
}
