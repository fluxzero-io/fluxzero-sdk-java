/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

package io.fluxzero.common;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeboxedExecutorTest {

    @Test
    void callAndWaitInterruptsAnActiveTaskOnTimeout() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);
        ExecutorService executor = new StartAwaitingExecutor(taskStarted);
        try (TimeboxedExecutor timeboxedExecutor = new TimeboxedExecutor(executor)) {
            assertThrows(TimeoutException.class, () -> timeboxedExecutor.callAndWait(() -> {
                taskStarted.countDown();
                try {
                    taskRelease.await();
                } catch (InterruptedException e) {
                    taskInterrupted.countDown();
                    throw e;
                }
                return null;
            }, Duration.ZERO));

            assertTrue(taskInterrupted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void callAndWaitManagedBlocksWhenCalledFromForkJoinPool() throws Exception {
        ForkJoinPool forkJoinPool = new ForkJoinPool(1);
        TimeboxedExecutor timeboxedExecutor =
                new TimeboxedExecutor(Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory()));
        try {
            CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
                try {
                    return timeboxedExecutor.callAndWait(() -> {
                        CompletableFuture<String> nested = new CompletableFuture<>();
                        forkJoinPool.execute(() -> nested.complete("ok"));
                        return nested.get(2, TimeUnit.SECONDS);
                    }, Duration.ofSeconds(2));
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, forkJoinPool);

            assertEquals("ok", result.get(5, TimeUnit.SECONDS));
        } finally {
            timeboxedExecutor.close();
            forkJoinPool.shutdownNow();
        }
    }

    private static class StartAwaitingExecutor extends AbstractExecutorService {
        private final CountDownLatch taskStarted;
        private final ExecutorService delegate =
                Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());

        private StartAwaitingExecutor(CountDownLatch taskStarted) {
            this.taskStarted = taskStarted;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
            try {
                if (!taskStarted.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Task did not start");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting task start", e);
            }
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
