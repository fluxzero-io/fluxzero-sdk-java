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
 *
 */

package io.fluxzero.common;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static io.fluxzero.common.ObjectUtils.newVirtualThreadFactory;
import static io.fluxzero.common.ObjectUtils.rethrow;
import static io.fluxzero.common.ObjectUtils.supportsVirtualThreadWorkers;

/**
 * Utility for running tasks with a maximum execution duration.
 * <p>
 * If a task does not complete within the configured duration, it is cancelled using interruption.
 * <p>
 * By default, the executor depends on the runtime version:
 * <ul>
 *     <li>Java 25 or newer: virtual thread-per-task executor</li>
 *     <li>Older Java versions: cached thread pool</li>
 * </ul>
 */
@AllArgsConstructor
@Slf4j
public final class TimeboxedExecutor implements AutoCloseable {
    @NonNull
    private final ExecutorService executor;

    public TimeboxedExecutor() {
        this(defaultExecutor());
    }

    /**
     * Executes the given task and waits for completion up to the given maximum duration.
     * <p>
     * If the task fails, false is returned without logging the failure.
     *
     * @param task        the task to execute
     * @param maxDuration the maximum duration to wait
     * @return {@code true} if the task completed in time, {@code false} if it timed out
     */
    public boolean runAndWaitSafely(ThrowingRunnable task, Duration maxDuration) {
        return runAndWaitSafely(task, maxDuration, false);
    }

    /**
     * Executes the given task and waits for completion up to the given maximum duration.
     * <p>
     * If the task fails, a warning is optionally logged and false is returned.
     *
     * @param task        the task to execute
     * @param maxDuration the maximum duration to wait
     * @return {@code true} if the task completed in time, {@code false} if it timed out
     */
    public boolean runAndWaitSafely(ThrowingRunnable task, Duration maxDuration, boolean logFailure) {
        try {
            return runAndWait(task, maxDuration);
        } catch (Exception e) {
            if (logFailure) {
                log.warn("Timeboxed task failed after {}", maxDuration, e);
            }
            return false;
        }
    }

    /**
     * Executes the given task and waits for completion up to the given maximum duration.
     *
     * @param task        the task to execute
     * @param maxDuration the maximum duration to wait
     * @return {@code true} if the task completed in time, {@code false} if it timed out
     */
    public boolean runAndWait(ThrowingRunnable task, Duration maxDuration) {
        try {
            callAndWait(() -> {
                task.run();
                return null;
            }, maxDuration);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /**
     * Executes the given task within the specified maximum duration. If the task times out, the fallback value is
     * returned.
     *
     * @param task        the task to execute
     * @param maxDuration the maximum duration to wait
     * @param fallback    supplies the fallback value in case of timeout
     * @param <T>         the task result type
     * @return the task result, or the fallback result if the task timed out
     */
    @SuppressWarnings("UnusedReturnValue")
    public <T> T callAndWait(Callable<T> task, Duration maxDuration, Supplier<? extends T> fallback) {
        if (fallback == null) {
            throw new IllegalArgumentException("fallback may not be null");
        }
        try {
            return callAndWait(task, maxDuration);
        } catch (TimeoutException e) {
            return fallback.get();
        }
    }

    /**
     * Executes the given task within the specified maximum duration.
     *
     * @param task        the task to execute
     * @param maxDuration the maximum duration to wait
     * @param <T>         the task result type
     * @return the task result
     * @throws TimeoutException if the task does not complete within the given duration
     */
    public <T> T callAndWait(@NonNull Callable<T> task, Duration maxDuration) throws TimeoutException {
        Future<T> future = executor.submit(task);
        try {
            return future.get(maxDuration.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for task completion", e);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    private static ExecutorService defaultExecutor() {
        if (supportsVirtualThreadWorkers()) {
            return Executors.newThreadPerTaskExecutor(newVirtualThreadFactory("timeboxed-"));
        }
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("timeboxed-" + t.threadId());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
