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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCompletionScopeTest {

    @Test
    void runAndAwaitStartsAllRegisteredFuturesBeforeWaiting() throws Exception {
        CountDownLatch callbacksStarted = new CountDownLatch(2);
        CompletableFuture<Void> first = new CompletableFuture<>();
        CompletableFuture<Void> second = new CompletableFuture<>();

        CompletableFuture<Void> scope = CompletableFuture.runAsync(() -> AsyncCompletionScope.runAndAwait(() -> {
            AsyncCompletionScope.register(first);
            callbacksStarted.countDown();
            AsyncCompletionScope.register(second);
            callbacksStarted.countDown();
        }));

        assertTrue(callbacksStarted.await(1, TimeUnit.SECONDS));
        assertFalse(scope.isDone());

        first.complete(null);
        assertFalse(scope.isDone());

        second.complete(null);
        assertDoesNotThrow(() -> scope.get(1, TimeUnit.SECONDS));
        assertTrue(scope.isDone());
    }

    @Test
    void afterCompletionCallbackRunsAfterRegisteredFuturesComplete() throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        AtomicBoolean callbackRan = new AtomicBoolean();

        CompletableFuture<Void> scope = CompletableFuture.runAsync(() -> AsyncCompletionScope.runAndAwait(
                () -> AsyncCompletionScope.register(future, () -> {
                    assertTrue(future.isDone());
                    callbackRan.set(true);
                })));

        assertFalse(callbackRan.get());
        future.complete(null);

        assertDoesNotThrow(() -> scope.get(1, TimeUnit.SECONDS));
        assertTrue(callbackRan.get());
    }

    @Test
    void afterCompletionCallbackRunsBeforeAsyncFailureIsRethrown() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        AtomicBoolean callbackRan = new AtomicBoolean();
        RuntimeException failure = new RuntimeException("failed");

        CompletableFuture<Void> scope = CompletableFuture.runAsync(() -> AsyncCompletionScope.runAndAwait(
                () -> AsyncCompletionScope.register(future, () -> callbackRan.set(true))));

        future.completeExceptionally(failure);

        assertThrows(ExecutionException.class, () -> scope.get(1, TimeUnit.SECONDS));
        assertTrue(callbackRan.get());
    }
}
