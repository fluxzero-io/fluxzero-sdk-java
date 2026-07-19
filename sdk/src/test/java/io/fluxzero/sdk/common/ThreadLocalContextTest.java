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

package io.fluxzero.sdk.common;

import io.fluxzero.common.MessageType;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.AdhocDispatchInterceptor;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.tracking.handling.Invocation;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ThreadLocalContextTest {

    private static final ThreadLocal<String> first = ThreadLocalContext.create();
    private static final ThreadLocal<Integer> second = ThreadLocalContext.create();

    @Test
    void capturesAllParticipatingValuesAndRestoresWorkerContext() throws Exception {
        first.set("request");
        second.set(42);
        ThreadLocalContext.Snapshot snapshot = ThreadLocalContext.capture();
        first.remove();
        second.remove();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> first.set("worker")).get();

            String observed = CompletableFuture.supplyAsync(snapshot.wrap(
                    () -> first.get() + ":" + second.get()), executor).get();

            assertEquals("request:42", observed);
            assertEquals("worker", executor.submit(first::get).get());
            executor.submit(first::remove).get();
        }
        assertTrue(ThreadLocalContext.capture().isEmpty());
    }

    @Test
    void removesSharedContextWhenLastChildIsCleared() {
        first.set("one");
        second.set(2);

        first.remove();
        assertEquals(2, second.get());
        second.remove();

        assertTrue(ThreadLocalContext.capture().isEmpty());
    }

    @Test
    void nestedActivationRestoresOuterValues() {
        first.set("captured");
        ThreadLocalContext.Snapshot snapshot = ThreadLocalContext.capture();
        first.set("outer");

        snapshot.run(() -> {
            assertEquals("captured", first.get());
            first.remove();
            assertTrue(ThreadLocalContext.capture().isEmpty());
        });

        assertEquals("outer", first.get());
        first.set(null);
        assertNull(first.get());
        assertTrue(ThreadLocalContext.capture().isEmpty());
    }

    @Test
    void wrappedFunctionRestoresOuterValues() {
        first.set("captured");
        ThreadLocalContext.Snapshot snapshot = ThreadLocalContext.capture();
        first.set("outer");

        assertEquals("captured-result", snapshot.wrap(value -> first.get() + value).apply("-result"));

        assertEquals("outer", first.get());
        first.remove();
    }

    @Test
    void allWrapperTypesRestoreOuterContextAfterFailure() {
        RuntimeException failure = new RuntimeException("failed");
        first.set("captured");
        ThreadLocalContext.Snapshot snapshot = ThreadLocalContext.capture();
        first.set("outer");

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> snapshot.wrap((Runnable) () -> {
                    assertEquals("captured", first.get());
                    throw failure;
                }).run()));
        assertEquals("outer", first.get());

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> snapshot.wrap((Supplier<Object>) () -> {
                    assertEquals("captured", first.get());
                    throw failure;
                }).get()));
        assertEquals("outer", first.get());

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> snapshot.wrap(ignored -> {
                    assertEquals("captured", first.get());
                    throw failure;
                }).apply(null)));
        assertEquals("outer", first.get());

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> snapshot.wrap((firstArgument, secondArgument) -> {
                    assertEquals("captured", first.get());
                    throw failure;
                }).accept(null, null)));
        assertEquals("outer", first.get());

        first.remove();
        assertTrue(ThreadLocalContext.capture().isEmpty());
    }

    @Test
    void exceptionalAsyncCompletionRestoresCompleteRequestContext() {
        Fluxzero fluxzero = mock(Fluxzero.class);
        User user = mock(User.class);
        Tracker tracker = new Tracker(
                "tracker", MessageType.EVENT, null,
                ConsumerConfiguration.builder().name("consumer").build(), null);
        DeserializingMessage message = new DeserializingMessage(
                new Message("payload"), MessageType.EVENT, null);
        AtomicReference<ThreadLocalContext.Snapshot> captured = new AtomicReference<>();

        Fluxzero.instance.set(fluxzero);
        Tracker.current.set(tracker);
        User.current.set(user);
        try {
            AdhocDispatchInterceptor.runWithAdhocInterceptor(() -> message.apply(ignored ->
                    Invocation.performInvocation(() -> {
                        AsyncCompletionScope.runAndAwait(() -> captured.set(ThreadLocalContext.capture()));
                        return null;
                    })), DispatchInterceptor.noOp, MessageType.EVENT);
        } finally {
            Fluxzero.instance.remove();
            Tracker.current.remove();
            User.current.remove();
        }
        assertRequestContextIsEmpty();

        RuntimeException failure = new RuntimeException("async failure");
        CompletableFuture<Void> source = new CompletableFuture<>();
        AtomicBoolean callbackInvoked = new AtomicBoolean();
        CompletableFuture<Void> completion = source.whenComplete(captured.get().wrap((result, error) -> {
            callbackInvoked.set(true);
            assertSame(failure, error);
            assertSame(fluxzero, Fluxzero.instance.get());
            assertSame(tracker, Tracker.current().orElse(null));
            assertSame(user, User.getCurrent());
            assertSame(message, DeserializingMessage.getCurrent());
            assertTrue(Invocation.getCurrent() != null);
            assertTrue(AsyncCompletionScope.isActive());
            assertSame(DispatchInterceptor.noOp,
                       AdhocDispatchInterceptor.captureAdhocInterceptors().get(MessageType.EVENT));
        }));

        source.completeExceptionally(failure);

        assertSame(failure, assertThrows(CompletionException.class, completion::join).getCause());
        assertTrue(callbackInvoked.get());
        assertRequestContextIsEmpty();
    }

    private static void assertRequestContextIsEmpty() {
        assertNull(Fluxzero.instance.get());
        assertTrue(Tracker.current().isEmpty());
        assertNull(User.getCurrent());
        assertTrue(DeserializingMessage.getOptionally().isEmpty());
        assertNull(Invocation.getCurrent());
        assertTrue(!AsyncCompletionScope.isActive());
        assertTrue(AdhocDispatchInterceptor.captureAdhocInterceptors().isEmpty());
        assertTrue(ThreadLocalContext.capture().isEmpty());
    }
}
