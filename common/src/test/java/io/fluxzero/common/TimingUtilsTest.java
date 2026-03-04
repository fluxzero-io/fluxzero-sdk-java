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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimingUtilsTest {

    @Test
    void retryOnFailureWithRetryConfigurationRetriesAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger successLogCalls = new AtomicInteger();
        AtomicInteger errorLogCalls = new AtomicInteger();

        RetryConfiguration configuration = RetryConfiguration.builder()
                .delay(Duration.ofMillis(1))
                .delayFunction(status -> Duration.ofMillis(1))
                .maxRetries(5)
                .successLogger(status -> successLogCalls.incrementAndGet())
                .exceptionLogger(status -> errorLogCalls.incrementAndGet())
                .build();

        String result = TimingUtils.retryOnFailure(() -> {
            if (attempts.getAndIncrement() < 2) {
                throw new IllegalStateException("not yet");
            }
            return "ok";
        }, configuration);

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
        assertEquals(2, errorLogCalls.get());
        assertEquals(1, successLogCalls.get());
    }

    @Test
    void retryOnFailureWithRetryConfigurationThrowsWhenMaxRetriesReached() {
        AtomicInteger attempts = new AtomicInteger();

        RetryConfiguration configuration = RetryConfiguration.builder()
                .delay(Duration.ofMillis(1))
                .delayFunction(status -> Duration.ofMillis(1))
                .maxRetries(1)
                .build();

        assertThrows(IllegalStateException.class, () -> TimingUtils.retryOnFailure(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("always fails");
        }, configuration));

        assertEquals(2, attempts.get());
    }

    @Test
    void retryOnFailureReturnsNullWhenErrorDoesNotPassErrorTest() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger errorLogCalls = new AtomicInteger();

        RetryConfiguration configuration = RetryConfiguration.builder()
                .delay(Duration.ofMillis(1))
                .delayFunction(status -> Duration.ofMillis(1))
                .errorTest(error -> false)
                .exceptionLogger(status -> errorLogCalls.incrementAndGet())
                .build();

        String result = TimingUtils.retryOnFailure(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("not retryable");
        }, configuration);

        assertNull(result);
        assertEquals(1, attempts.get());
        assertEquals(0, errorLogCalls.get());
    }

    @Test
    void retryOnFailureThrowsWhenErrorDoesNotPassErrorTestAndThrowOnFailingErrorTestIsEnabled() {
        AtomicInteger attempts = new AtomicInteger();

        RetryConfiguration configuration = RetryConfiguration.builder()
                .delay(Duration.ofMillis(1))
                .delayFunction(status -> Duration.ofMillis(1))
                .errorTest(error -> false)
                .throwOnFailingErrorTest(true)
                .build();

        assertThrows(IllegalStateException.class, () -> TimingUtils.retryOnFailure(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("not retryable");
        }, configuration));

        assertEquals(1, attempts.get());
    }

    @Test
    void retryOnFailureFallsBackToDefaultDelayWhenDelayFunctionReturnsNull() {
        AtomicInteger attempts = new AtomicInteger();

        RetryConfiguration configuration = RetryConfiguration.builder()
                .delay(Duration.ofMillis(1))
                .delayFunction(status -> null)
                .maxRetries(3)
                .build();

        String result = TimingUtils.retryOnFailure(() -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("first attempt fails");
            }
            return "ok";
        }, configuration);

        assertEquals("ok", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void retryOnFailureDoesNotCallSuccessLoggerWhenTaskSucceedsImmediately() {
        AtomicInteger successLogCalls = new AtomicInteger();

        RetryConfiguration configuration = RetryConfiguration.builder()
                .delay(Duration.ofMillis(1))
                .delayFunction(status -> Duration.ofMillis(1))
                .successLogger(status -> successLogCalls.incrementAndGet())
                .build();

        String result = TimingUtils.retryOnFailure(() -> "ok", configuration);

        assertEquals("ok", result);
        assertEquals(0, successLogCalls.get());
    }
}
