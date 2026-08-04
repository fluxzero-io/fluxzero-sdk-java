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

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.CommitModelActionResult;

import java.util.Objects;

/**
 * Decides what the SDK should do after the runtime has rejected and rolled back a model action.
 * <p>
 * Returning {@link Resolution#RETRY} only has effect when the runtime marked the conflict retryable and the configured
 * retry bound has not been exhausted. Throwing an application exception maps the conflict directly to that error.
 */
@FunctionalInterface
public interface ModelConflictResolver {

    /**
     * Resolves one rolled-back conflict.
     */
    Resolution resolve(Context context);

    /**
     * Fails every conflict with {@link ModelActionConflictException}.
     */
    static ModelConflictResolver fail() {
        return ignored -> Resolution.FAIL;
    }

    /**
     * Silently retries conflicts whose action policy permits retry and whose retry bound is not exhausted.
     */
    static ModelConflictResolver retryIfAllowed() {
        return context -> context.canRetry() ? Resolution.RETRY : Resolution.FAIL;
    }

    /**
     * Resolver outcome.
     */
    enum Resolution {
        RETRY,
        FAIL
    }

    /**
     * Immutable conflict context supplied after runtime rollback.
     *
     * @param result completed runtime conflict result
     * @param retries number of retries already performed
     * @param maxRetries maximum retries permitted for this action
     */
    record Context(CommitModelActionResult result, int retries, int maxRetries) {
        public Context {
            Objects.requireNonNull(result, "result");
            if (result.isAccepted()) {
                throw new IllegalArgumentException("A conflict resolver requires a rejected result");
            }
            if (retries < 0 || maxRetries < 0) {
                throw new IllegalArgumentException("Model conflict retry counts must not be negative");
            }
        }

        /**
         * Returns whether both runtime eligibility and the SDK retry bound permit another evaluation.
         */
        public boolean canRetry() {
            return result.isRetryAllowed() && retries < maxRetries;
        }
    }
}
