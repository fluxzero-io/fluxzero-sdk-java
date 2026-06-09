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
    DEFAULT(false, false, false),

    /**
     * Commit after every handler and wait for that commit before the handler completion phase finishes.
     */
    SYNC_AFTER_HANDLER(false, false, false),

    /**
     * Start commits after every handler and wait for all started commits at the end of the handler completion phase.
     */
    ASYNC_AFTER_HANDLER(false, false, true),

    /**
     * Start commits after every handler, keep the aggregate active for the rest of the current batch, and wait for all
     * started commits at the end of the batch completion phase.
     */
    ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH(false, true, true),

    /**
     * Commit at the end of the current message batch and wait for each commit before continuing.
     */
    SYNC_AFTER_BATCH(true, true, false),

    /**
     * Start commits at the end of the current message batch and wait for all started commits before the batch
     * completion phase finishes.
     */
    ASYNC_AFTER_BATCH(true, true, true);

    /**
     * Controls whether request results wait for asynchronous aggregate commits that were started after a handler and
     * are awaited at batch completion.
     * <p>
     * This applies to {@link #ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH}. It does not delay results for
     * {@link #SYNC_AFTER_BATCH}, because those commits only start at batch completion.
     * <p>
     * Set {@code fluxzero.aggregate.awaitAfterHandlerCommitsBeforeResults=false} to publish results immediately while
     * preserving the aggregate commit policy's batch-completion wait. Defaults to {@code true}.
     */
    public static final String AWAIT_AFTER_HANDLER_COMMITS_BEFORE_RESULTS_PROPERTY =
            "fluxzero.aggregate.awaitAfterHandlerCommitsBeforeResults";

    private final boolean commitAfterBatch;
    private final boolean awaitAfterBatch;
    private final boolean async;

    AggregateCommitPolicy(boolean commitAfterBatch, boolean awaitAfterBatch, boolean async) {
        this.commitAfterBatch = commitAfterBatch;
        this.awaitAfterBatch = awaitAfterBatch;
        this.async = async;
    }

    /**
     * Returns whether this policy commits at batch completion rather than handler completion.
     */
    public boolean commitAfterBatch() {
        return commitAfterBatch;
    }

    /**
     * Returns whether this policy commits at batch completion rather than handler completion.
     */
    public boolean afterBatch() {
        return commitAfterBatch();
    }

    /**
     * Returns whether this policy waits for commit completion at batch completion rather than handler completion.
     * <p>
     * When this is {@code true}, active thread-local aggregates are retained until batch completion as well, so later
     * handlers in the same batch keep seeing the in-memory aggregate until pending commits have completed.
     */
    public boolean awaitAfterBatch() {
        return awaitAfterBatch;
    }

    /**
     * Returns whether this policy allows commits from the same completion phase to run concurrently.
     */
    public boolean async() {
        return async;
    }
}
