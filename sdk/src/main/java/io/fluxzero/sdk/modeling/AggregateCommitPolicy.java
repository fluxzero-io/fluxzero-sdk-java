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

package io.fluxzero.sdk.modeling;

/**
 * Controls when aggregate changes are committed and whether multiple commits in the same completion phase may run
 * concurrently.
 */
public enum AggregateCommitPolicy {

    /**
     * Resolve the policy from the active Fluxzero defaults version or explicit application properties.
     */
    DEFAULT,

    /**
     * Commit after every handler and wait for that commit before the handler completion phase finishes.
     */
    SYNC_AFTER_HANDLER,

    /**
     * Start commits after every handler and wait for all started commits at the end of the handler completion phase.
     */
    ASYNC_AFTER_HANDLER,

    /**
     * Commit at the end of the current message batch and wait for each commit before continuing.
     */
    SYNC_AFTER_BATCH,

    /**
     * Start commits at the end of the current message batch and wait for all started commits before the batch
     * completion phase finishes.
     */
    ASYNC_AFTER_BATCH;

    /**
     * Returns whether this policy commits at batch completion rather than handler completion.
     */
    public boolean afterBatch() {
        return this == SYNC_AFTER_BATCH || this == ASYNC_AFTER_BATCH;
    }

    /**
     * Returns whether this policy allows commits from the same completion phase to run concurrently.
     */
    public boolean async() {
        return this == ASYNC_AFTER_HANDLER || this == ASYNC_AFTER_BATCH;
    }
}
