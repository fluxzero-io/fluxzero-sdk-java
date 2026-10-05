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

package io.fluxzero.sdk.tracking;

import io.fluxzero.common.RetryConfiguration;
import io.fluxzero.common.RetryStatus;
import io.fluxzero.sdk.common.exception.FunctionalException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A {@link RetryingErrorHandler} with no retry-count limit for eligible failures.
 *
 * <p>Start with the default {@link LoggingErrorHandler}. Choose unlimited retries when an effect must survive a
 * recoverable outage before processing can advance, and repeating the operation is safe. Examples include replacing
 * a search document by stable ID or reconciling schedules from durable current intent. Every completed side effect
 * must be idempotent; an uncertain remote outcome can still have taken effect.
 *
 * <p><strong>Operational cost:</strong> the affected tracker/batch waits while retries continue. A permanent failure
 * can therefore block progress indefinitely and grow the backlog. Monitor lag and retry failures, and provide a way to
 * repair a poison message or its cause. This policy does not itself guarantee delivery or exactly-once effects.
 *
 * <p><strong>Retry boundaries:</strong>
 * <ul>
 *     <li>The initial {@code errorFilter} defaults to excluding {@link FunctionalException}. An excluded initial error
 *     is logged and returned without retry; tracking may continue.</li>
 *     <li>The first retry is immediate. After failed retries, default backoff starts at 10 seconds and caps at 1 minute.</li>
 *     <li>The initial filter is not reapplied to later failures. The separate {@link RetryConfiguration} error test
 *     controls failures during retries and excludes {@link Error} by default. A functional failure on a later attempt
 *     can therefore keep retrying.</li>
 *     <li>Interruption or a rejected retry failure can end the loop. A rejected retry failure returns {@code null}
 *     by default; interruption normally returns the mapped original error. These paths do not deliberately stop the
 *     consumer. Custom filters, mappers or logging can themselves throw.</li>
 * </ul>
 *
 * <p><strong>Explicit opt-in:</strong>
 * <pre>{@code
 * @Consumer(name = "reconciled-projection", errorHandler = ForeverRetryingErrorHandler.class)
 * public class ReconciledProjection {
 *     @HandleEvent
 *     void on(ItemChanged event) {
 *         // Replace the projection by stable ID; alert on sustained retry/consumer lag
 *     }
 * }
 * }</pre>
 *
 * @see LoggingErrorHandler
 * @see RetryingErrorHandler
 * @see ErrorHandler
 * @see FunctionalException
 */
@Slf4j
public class ForeverRetryingErrorHandler extends RetryingErrorHandler {

    /**
     * Constructs a {@code ForeverRetryingErrorHandler} with capped exponential backoff, starting at 10 seconds and
     * capped at 1 minute, retrying non-functional errors and logging both functional and technical failures.
     */
    public ForeverRetryingErrorHandler() {
        this(defaultRetryConfiguration(), e -> !(e instanceof FunctionalException), true);
    }

    /**
     * Constructs a {@code ForeverRetryingErrorHandler} with custom delay, error filtering, logging, and error mapping.
     *
     * @param delay               the delay between retries
     * @param errorFilter         predicate to select which errors should trigger retries
     * @param logFunctionalErrors whether to log functional errors
     * @param errorMapper         maps an excluded initial error or an error after the retry loop terminates into a result
     */
    public ForeverRetryingErrorHandler(Duration delay, Predicate<Throwable> errorFilter, boolean logFunctionalErrors,
                                       Function<Throwable, ?> errorMapper) {
        this(fixedRetryConfiguration(delay, errorMapper), errorFilter, logFunctionalErrors);
    }

    /**
     * Constructs a {@code ForeverRetryingErrorHandler} with a custom retry configuration, retrying non-functional
     * errors and logging both functional and technical failures.
     *
     * @param retryConfiguration retry delay strategy, logging callbacks, and error mapping
     */
    public ForeverRetryingErrorHandler(RetryConfiguration retryConfiguration) {
        this(retryConfiguration, e -> !(e instanceof FunctionalException), true);
    }

    /**
     * Constructs a {@code ForeverRetryingErrorHandler} with a custom retry configuration. The configured maximum
     * number of retries is ignored and changed to unlimited to preserve the contract of this handler.
     *
     * @param retryConfiguration  retry delay strategy, logging callbacks, and error mapping
     * @param errorFilter         predicate to select which errors should trigger retries
     * @param logFunctionalErrors whether to log functional errors
     */
    public ForeverRetryingErrorHandler(RetryConfiguration retryConfiguration, Predicate<Throwable> errorFilter,
                                       boolean logFunctionalErrors) {
        super(errorFilter, false, logFunctionalErrors, retryConfiguration.toBuilder().maxRetries(-1).build());
    }

    private static RetryConfiguration defaultRetryConfiguration() {
        return RetryConfiguration.builder()
                .delayFunction(RetryConfiguration.exponentialBackoff(Duration.ofSeconds(10), Duration.ofMinutes(1)))
                .successLogger(ForeverRetryingErrorHandler::logRetrySuccess)
                .exceptionLogger(status -> {})
                .build();
    }

    private static RetryConfiguration fixedRetryConfiguration(Duration delay, Function<Throwable, ?> errorMapper) {
        return RetryConfiguration.builder()
                .delay(delay)
                .errorMapper(errorMapper)
                .successLogger(ForeverRetryingErrorHandler::logRetrySuccess)
                .exceptionLogger(status -> {})
                .build();
    }

    private static void logRetrySuccess(RetryStatus status) {
        log.info("Message handling was successful after {} {}", status.getNumberOfTimesRetried(),
                 status.getNumberOfTimesRetried() == 1 ? "retry" : "retries");
    }
}
