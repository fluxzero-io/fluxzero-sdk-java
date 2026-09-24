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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTaskTest {
    @Test
    void observesBlockingAndReleasesWorkerOnScopeExit() throws Exception {
        var release = new CountDownLatch(1);
        var completed = new AtomicBoolean();
        try (var task = new TestTask(() -> { block(release); completed.set(true); }, release::countDown)) {
            task.awaitBlockedIn(TestTaskTest.class, "block", Duration.ofSeconds(1));
            assertFalse(completed.get());
        }
        assertTrue(completed.get());
    }

    @Test
    void earlyCompletionIsNotEvidenceOfBlockingAndWorkerFailuresPropagate() throws Exception {
        try (var task = new TestTask(() -> {}, () -> {})) {
            task.awaitCompletion(Duration.ofSeconds(1));
            assertThrows(AssertionError.class,
                    () -> task.awaitBlockedIn(TestTaskTest.class, "block", Duration.ofSeconds(1)));
        }
        var failure = new IllegalStateException("worker failed");
        try (var task = new TestTask(() -> { throw failure; }, () -> {})) {
            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> task.awaitCompletion(Duration.ofSeconds(1))).getCause());
        }
    }

    private static void block(CountDownLatch release) {
        ObjectUtils.run(release::await);
    }
}
