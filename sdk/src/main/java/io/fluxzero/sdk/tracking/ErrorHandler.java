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

import io.fluxzero.common.ObjectUtils;

import java.util.concurrent.Callable;

/**
 * Handles failures during tracked message processing, optionally retrying the failed operation.
 *
 * <p>Start with {@link LoggingErrorHandler}, the consumer default: it logs failures and lets processing continue
 * without retrying. Select a different policy only when the consumer's effect and recovery requirements call for it.
 * Continuing can advance the position past a failed effect; it does not repair that effect.
 *
 * <p><strong>Warning:</strong> {@link ThrowingErrorHandler} does not retry. It can stop the affected tracker until
 * explicit restart, typically application restart or redeployment after repair. Even a functional rejection can stop
 * it. Other trackers or application instances may continue; this is not a global consumer pause.
 *
 * <table border="1">
 *   <caption>Built-in consumer error policies</caption>
 *   <thead><tr><th>Implementation</th><th>Behavior</th><th>Choose when</th></tr></thead>
 *   <tbody>
 *     <tr><td>{@link LoggingErrorHandler}</td><td>Log and continue; no retry</td>
 *         <td>Recommended starting point; continued failures are observable and recoverable if needed</td></tr>
 *     <tr><td>{@link RetryingErrorHandler}</td><td>Retry eligible failures; default: five retries, then continue</td>
 *         <td>Bounded recovery from transient failures with repeatable effects</td></tr>
 *     <tr><td>{@link ForeverRetryingErrorHandler}</td><td>No retry-count limit for eligible failures</td>
 *         <td>Recoverable failures must hold up progress, with idempotency, lag alerts and operator recovery</td></tr>
 *     <tr><td>{@link ThrowingErrorHandler}</td><td>Rethrow immediately; stop the affected tracker</td>
 *         <td>A deliberate operator-controlled stop, with a repair and restart procedure</td></tr>
 *     <tr><td>{@link SilentErrorHandler}</td><td>Continue without retry; configurable or no logging</td>
 *         <td>Best-effort work with separate observability</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>The supplied retry operation can cover a handler or a batch, depending on where the failure occurred.
 * Completed effects are not rolled back and may repeat. Unlimited retries are not an exactly-once or delivery
 * guarantee; see {@link ForeverRetryingErrorHandler} for filtering and interruption boundaries.
 */
@FunctionalInterface
public interface ErrorHandler {
    /**
     * Handles an error encountered during message processing and provides an option to retry the operation.
     *
     * @param error         the Throwable instance representing the error that occurred
     * @param errorMessage  a descriptive message providing context about the error
     * @param retryFunction a Callable representing the operation to retry in case of failure
     * @return an Object which represents the result of the error handling or retry operation. In case an exception is
     * thrown, the affected tracker can stop until explicitly restarted; throwing does not schedule a retry. If an error
     * is returned rather than thrown, tracking may continue and the error may be published as a Result message.
     * Any other return value may also be published as a Result message.
     */
    Object handleError(Throwable error, String errorMessage, Callable<?> retryFunction);

    /**
     * Handles an error encountered during message processing and provides an option to retry the operation. Invoked
     * when the return value of the error handler (even if the return value is an exception) is not relevant.
     *
     * @param error         the Throwable instance representing the error that occurred
     * @param errorMessage  a descriptive message providing context about the error
     * @param retryFunction a Callable representing the operation to retry in case of failure
     */
    default void handleError(Throwable error, String errorMessage, Runnable retryFunction) {
        handleError(error, errorMessage, ObjectUtils.asCallable(retryFunction));
    }
}
