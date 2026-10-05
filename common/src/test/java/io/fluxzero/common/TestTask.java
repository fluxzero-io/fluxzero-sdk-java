/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/** Owns a test worker and its release gate; observes blocking directly instead of sleeping before a negative assertion. */
public final class TestTask implements AutoCloseable {
    private final FutureTask<Void> result;
    private final Thread worker;
    private final Runnable release;
    private boolean completionObserved;

    public TestTask(Runnable task, Runnable release) {
        this.release = release;
        result = new FutureTask<>(task, null);
        // A waiting JUnit ForkJoin worker must not steal and execute a task whose release belongs to that same test.
        worker = Thread.ofPlatform().name("test-owned-task").daemon().start(result);
    }

    /** Waits until the worker is blocked inside the named operation (including that owner's nested classes). */
    public void awaitBlockedIn(Class<?> owner, String method, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (result.isDone()) {
                completionObserved = true;
                result.get();
                fail("Operation completed instead of waiting in " + owner.getName() + "." + method);
            }
            Thread.State state = worker.getState();
            if ((state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING || state == Thread.State.BLOCKED)
                    && Arrays.stream(worker.getStackTrace()).anyMatch(frame ->
                    (frame.getClassName().equals(owner.getName()) || frame.getClassName().startsWith(owner.getName() + "$"))
                            && frame.getMethodName().equals(method))) {
                return;
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("Worker did not reach " + owner.getName() + "." + method + ": "
                + Arrays.toString(worker.getStackTrace()));
    }

    /** Waits for completion and propagates any worker failure. */
    public void awaitCompletion(Duration timeout) throws Exception {
        try {
            result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            completionObserved = result.isDone();
        }
    }

    @Override
    public void close() throws Exception {
        try {
            release.run();
        } finally {
            if (!worker.join(Duration.ofSeconds(5))) {
                worker.interrupt();
                worker.join(Duration.ofSeconds(1));
                fail("Test worker did not terminate after releasing its gate");
            }
            assertFalse(worker.isAlive());
            if (!completionObserved) {
                result.get();
            }
        }
    }
}
