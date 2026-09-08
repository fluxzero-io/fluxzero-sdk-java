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

package io.fluxzero.sdk.publishing;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.configuration.client.Client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DefaultRequestHandlerResponseCallbackTest {

    @Test
    void firstTerminalResponseWinsBeforeItsCompletionWorkerRuns() {
        CompletableFuture<SerializedMessage> result = new CompletableFuture<>();
        DefaultRequestHandler.ResponseCallback callback = new DefaultRequestHandler.ResponseCallback(null, result);
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        SerializedMessage first = message("first");

        callback.process(first, work::add);
        callback.process(message("duplicate"), work::add);
        while (!work.isEmpty()) {
            work.removeLast().run();
        }

        assertSame(first, result.join());
    }

    @Test
    void cancelledResponseKeepsItsQueuedWorkerIndependent() {
        CompletableFuture<SerializedMessage> cancelled = new CompletableFuture<>();
        CompletableFuture<SerializedMessage> other = new CompletableFuture<>();
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        DefaultRequestHandler.ResponseCallback callback = new DefaultRequestHandler.ResponseCallback(null, cancelled);
        callback.process(message("cancelled"), work::add);
        new DefaultRequestHandler.ResponseCallback(null, other).process(message("other"), work::add);

        assertTrue(cancelled.cancel(false));
        callback.process(message("late"), work::add);
        assertEquals(2, work.size());
        work.removeFirst().run();
        assertTrue(cancelled.isCancelled());
        assertFalse(other.isDone());
        work.removeFirst().run();
        assertEquals("other", other.join().getMessageId());
    }

    @Test
    void rejectedTerminalWorkerCompletesExceptionally() {
        CompletableFuture<SerializedMessage> result = new CompletableFuture<>();
        DefaultRequestHandler.ResponseCallback callback = new DefaultRequestHandler.ResponseCallback(null, result);
        RejectedExecutionException failure = new RejectedExecutionException("closed");
        callback.process(message("rejected"), ignored -> { throw failure; });

        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
        callback.process(message("late"), ignored -> { throw new AssertionError("already terminal"); });
    }

    @Test
    void executorFailureAfterSubmissionCannotPublishAnEmptyResponse() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        CompletableFuture<SerializedMessage> result = new CompletableFuture<>() {
            @Override
            public boolean completeExceptionally(Throwable error) {
                // A decorating executor can fail after its delegate has already accepted the task.
                work.removeFirst().run();
                return super.completeExceptionally(error);
            }
        };
        SerializedMessage response = message("submitted");
        DefaultRequestHandler.ResponseCallback callback = new DefaultRequestHandler.ResponseCallback(null, result);
        RejectedExecutionException failure = new RejectedExecutionException("after submission");
        callback.process(response, task -> {
            work.add(task);
            throw failure;
        });

        if (result.isCompletedExceptionally()) {
            assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
        } else {
            assertSame(response, result.join());
        }
        assertTrue(work.isEmpty());
    }

    @Test
    void terminalWorkerPreservesTheBatchAndCallbackContextBoundaries() throws Exception {
        InheritableThreadLocal<Integer> inherited = new InheritableThreadLocal<>() {
            @Override
            protected Integer childValue(Integer parentValue) {
                return parentValue == null ? null : parentValue + 1;
            }
        };
        ThreadLocal<String> ordinary = new ThreadLocal<>();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        DefaultRequestHandler handler = new DefaultRequestHandler(
                mock(Client.class), MessageType.RESULT, Duration.ofSeconds(-1), "context", executor);
        try {
            inherited.set(10);
            ordinary.set("caller");
            SerializedMessage response = message("context");
            CompletableFuture<SerializedMessage> result = handler.prepareRequest(response, null, null);
            CompletableFuture<Integer> observed = result.thenApply(ignored -> {
                assertNull(ordinary.get());
                assertTrue(Thread.currentThread().isVirtual());
                return inherited.get();
            });
            handler.handleResults(List.of(response));

            assertEquals(12, observed.get(2, TimeUnit.SECONDS));
        } finally {
            inherited.remove();
            ordinary.remove();
            handler.close();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockedCallerDoesNotBlockIndependentResponses(boolean separateBatches) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        DefaultRequestHandler handler = new DefaultRequestHandler(
                mock(Client.class), MessageType.RESULT, Duration.ofSeconds(-1), "independent-results", executor);
        SerializedMessage first = message("first");
        SerializedMessage second = message("second");
        CompletableFuture<SerializedMessage> firstResult = handler.prepareRequest(first, null, null);
        CompletableFuture<SerializedMessage> secondResult = handler.prepareRequest(second, null, null);
        CountDownLatch callerStarted = new CountDownLatch(1);
        CountDownLatch releaseCaller = new CountDownLatch(1);
        CompletableFuture<Void> caller = firstResult.thenAccept(ignored -> {
            callerStarted.countDown();
            try {
                releaseCaller.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });
        try {
            if (separateBatches) {
                handler.handleResults(List.of(first));
                assertTrue(callerStarted.await(2, TimeUnit.SECONDS));
                handler.handleResults(List.of(second));
            } else {
                handler.handleResults(List.of(first, second));
                assertTrue(callerStarted.await(2, TimeUnit.SECONDS));
            }
            assertSame(second, secondResult.get(2, TimeUnit.SECONDS));
        } finally {
            releaseCaller.countDown();
            caller.get(2, TimeUnit.SECONDS);
            handler.close();
            executor.shutdownNow();
        }
    }

    private static SerializedMessage message(String id) {
        return new SerializedMessage(new Data<>(new byte[0], "result", 0), Metadata.empty(), id, 1L);
    }

    @Test
    void evaluatesLastChunkOnceOnSynchronousFastPath() {
        AtomicInteger evaluations = new AtomicInteger();
        SerializedMessage response = new SerializedMessage(
                new Data<>(new byte[0], "result", 0),
                Metadata.empty(), "message", 1L) {
            @Override
            public boolean lastChunk() {
                evaluations.incrementAndGet();
                return true;
            }
        };
        CompletableFuture<SerializedMessage> result = new CompletableFuture<>();
        DefaultRequestHandler.ResponseCallback callback =
                new DefaultRequestHandler.ResponseCallback(null, result);

        callback.process(response, Runnable::run);

        assertSame(response, result.join());
        assertEquals(1, evaluations.get());
    }
}
