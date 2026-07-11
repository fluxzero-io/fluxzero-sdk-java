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
 * A specialized {@link RetryingErrorHandler} that retries failed operations indefinitely until they succeed.
 * <p>
 * This handler is useful in scenarios where failure is considered temporary and must eventually resolve before
 * processing can proceed. It ensures **no message is ever skipped or dropped**, regardless of how many attempts
 * are required.
 *
 * <p><strong>Behavior:</strong>
 * <ul>
 *     <li>Applies retry logic to all errors that match the provided {@code errorFilter} (default: non-functional errors).</li>
 *     <li>Uses capped exponential backoff by default, starting at 10 seconds and capped at 1 minute.</li>
 *     <li>Never stops the consumer or throws — tracking continues until the retry succeeds.</li>
 *     <li>Logs retry attempts and outcomes via inherited {@link RetryConfiguration}.</li>
 * </ul>
 *
 * <p><strong>Use Cases:</strong>
 * <ul>
 *     <li>Systems that rely on eventual consistency and cannot tolerate message loss</li>
 *     <li>Recoverable infrastructure failures (e.g., DB outages, network timeouts)</li>
 *     <li>Replaying old messages into strict projections</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 * <pre>{@code
 * @Consumer(name = "criticalProjection", errorHandler = ForeverRetryingErrorHandler.class)
 * public class StrictProjectionHandler {
 *     @HandleEvent
 *     void on(FinancialTransaction event) {
 *         // Will retry forever on failure until the event is handled successfully
 *     }
 * }
 * }</pre>
 *
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
     * @param errorMapper         maps the final error into a result (though retries never exhaust)
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
