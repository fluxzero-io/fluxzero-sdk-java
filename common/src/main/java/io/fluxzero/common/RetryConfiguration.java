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
 *
 */

package io.fluxzero.common;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Configuration for retry behavior when executing a task using {@link TimingUtils#retryOnFailure}.
 * <p>
 * This class encapsulates options such as retry delay, maximum number of retries, error filtering, and logging
 * callbacks to be invoked on success or failure.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * RetryConfiguration config = RetryConfiguration.builder()
 *     .delay(Duration.ofSeconds(2))
 *     .maxRetries(5)
 *     .errorTest(e -> e instanceof IOException)
 *     .build();
 * }</pre>
 *
 * @see TimingUtils#retryOnFailure(java.util.concurrent.Callable, RetryConfiguration)
 * @see RetryStatus
 */
@Value
@Builder(builderClassName = "Builder", toBuilder = true)
@Slf4j
public class RetryConfiguration {
    /**
     * The delay between retry attempts if the {@link #delayFunction} is not overridden.
     * <p>
     * Defaults to 1 second.
     */
    @Default
    @NonNull
    Duration delay = Duration.ofSeconds(1);

    /**
     * A function defining a strategy for determining the next delay duration between operation retries.
     * <p>
     * If the delay function is or returns {@code null}, {@link #getDelay()} will be used.
     *
     * @see RetryStatus
     */
    Function<RetryStatus, Duration> delayFunction;

    /**
     * The maximum number of retries allowed before failing permanently.
     * <p>
     * A value of {@code -1} means unlimited retries.
     */
    @Default
    int maxRetries = -1;

    /**
     * A predicate that determines whether a caught exception is eligible for retry.
     * <p>
     * If the predicate returns {@code false}, the retry loop will break (unless {@link #throwOnFailingErrorTest} is set
     * to true).
     * <p>
     * Defaults to always returning true (retry any exception).
     */
    @Default
    Predicate<Throwable> errorTest = e -> !(e instanceof Error);

    /**
     * Whether to throw the exception if it fails the {@link #errorTest}.
     * <p>
     * If {@code false}, the method will return {@code null} on ineligible exceptions.
     */
    @Default
    boolean throwOnFailingErrorTest = false;

    /**
     * Callback invoked if the task eventually succeeds after one or more retries.
     * <p>
     * This can be used to log success, metrics, etc.
     */
    @Default
    Consumer<RetryStatus> successLogger = status -> log.info("Task {} completed successfully after {} {}",
                                                             status.getTask(),
                                                             status.getNumberOfTimesRetried(),
                                                             status.getNumberOfTimesRetried() == 1 ? "retry" : "retries");

    /**
     * Whether the first attempt in this retry cycle should already count as a retry for success logging purposes.
     * <p>
     * Defaults to {@code false}, so direct success remains silent unless a caller explicitly indicates that entering
     * this retry cycle already implies an earlier failure.
     */
    @Default
    boolean firstAttemptCountsAsRetry = false;

    /**
     * Callback invoked when a retryable exception is caught.
     * <p>
     * Logs the initial retry attempt and, if applicable, the permanent failure after exceeding {@link #maxRetries}.
     */
    @Default
    Consumer<RetryStatus> exceptionLogger = status -> {
        if (status.getNumberOfTimesRetried() == 0) {
            log.error("Task {} failed. Retrying every {} ms...",
                      status.getTask(), status.getRetryConfiguration().resolveDelay(status).toMillis(), status.getException());
        } else if (status.getNumberOfTimesRetried() >= status.getRetryConfiguration().getMaxRetries()) {
            log.error("Task {} failed permanently. Not retrying.", status.getTask(), status.getException());
        }
    };

    /**
     * An optional mapper that can be used to convert a {@link Throwable} to a custom representation or response. This
     * is not used by default in the retry mechanism but can be plugged into advanced error handling.
     */
    @Default
    Function<Throwable, ?> errorMapper = e -> e;

    /**
     * Resolves the delay duration for the next retry attempt based on the provided {@link RetryStatus}.
     * If a delay function has been defined, it applies this function to the given status to compute
     * the delay. If the computed delay is null, or if no delay function is defined, it returns the
     * default delay.
     *
     * @param status the current status of the retry operation containing retry attempt details.
     * @return the delay duration to apply before the next retry attempt.
     */
    public Duration resolveDelay(RetryStatus status) {
        if (delayFunction == null) {
            return delay;
        }
        Duration computed = delayFunction.apply(status);
        return computed != null ? computed : delay;
    }
}
