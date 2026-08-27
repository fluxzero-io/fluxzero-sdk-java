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

import io.fluxzero.sdk.configuration.ApplicationProperties;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Controls when an automatic independent-model commit starts and when its completion is awaited.
 * <p>
 * One command produces one atomic model commit, even when it affects multiple models. The policy therefore applies to
 * the complete consistency boundary rather than to every affected model separately.
 */
public enum ModelCommitPolicy implements CommitPolicy {

    /** Resolve the policy from model properties and the active Fluxzero defaults version. */
    DEFAULT(false, false, false),

    /** Commit after the model apply handlers and wait before handler completion finishes. */
    SYNC_AFTER_HANDLER(false, false, false),

    /** Start the commit after the model apply handlers and await it in the handler completion phase. */
    ASYNC_AFTER_HANDLER(false, false, true),

    /** Start after the model apply handlers and await all started commits at batch completion. */
    ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH(false, true, true),

    /** Commit at batch completion and process commits in that completion phase sequentially. */
    SYNC_AFTER_BATCH(true, true, false),

    /** Start commits concurrently at batch completion and await all of them there. */
    ASYNC_AFTER_BATCH(true, true, true);

    /** Property that overrides {@link #DEFAULT} for independent models. */
    public static final String PROPERTY = "fluxzero.model.commitPolicy";

    /**
     * Controls whether request results wait for commits started after model apply handlers and awaited at batch end.
     */
    public static final String AWAIT_AFTER_HANDLER_COMMITS_BEFORE_RESULTS_PROPERTY =
            "fluxzero.model.awaitAfterHandlerCommitsBeforeResults";

    private final boolean commitAfterBatch;
    private final boolean awaitAfterBatch;
    private final boolean async;

    ModelCommitPolicy(boolean commitAfterBatch, boolean awaitAfterBatch, boolean async) {
        this.commitAfterBatch = commitAfterBatch;
        this.awaitAfterBatch = awaitAfterBatch;
        this.async = async;
    }

    @Override
    public boolean commitAfterBatch() {
        return commitAfterBatch;
    }

    @Override
    public boolean awaitAfterBatch() {
        return awaitAfterBatch;
    }

    @Override
    public boolean async() {
        return async;
    }

    /**
     * Resolves a declared policy using the model override property and the independent-model default.
     * <p>
     * Independent models were introduced with {@link #ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH} as their default, so this
     * does not depend on the defaults version. {@link #ASYNC_AFTER_BATCH} remains available as an explicit choice.
     */
    public static ModelCommitPolicy resolve(ModelCommitPolicy declared) {
        Objects.requireNonNull(declared, "declared");
        if (declared != DEFAULT) {
            return declared;
        }
        String configured = ApplicationProperties.getProperty(PROPERTY);
        if (configured != null && !configured.isBlank()) {
            ModelCommitPolicy parsed = parse(configured);
            if (parsed != DEFAULT) {
                return parsed;
            }
        }
        return ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH;
    }

    static ModelCommitPolicy merge(Iterable<ModelCommitPolicy> policies) {
        ModelCommitPolicy effective = null;
        int effectivePriority = Integer.MIN_VALUE;
        for (ModelCommitPolicy policy : policies) {
            int priority = mergePriority(policy);
            if (priority > effectivePriority) {
                effective = policy;
                effectivePriority = priority;
            }
        }
        return effective == null ? resolve(DEFAULT) : effective;
    }

    private static int mergePriority(ModelCommitPolicy policy) {
        return switch (policy) {
            case DEFAULT -> throw new IllegalArgumentException(
                    "Model commit policies must be resolved before merging");
            case SYNC_AFTER_HANDLER -> 5;
            case ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH -> 4;
            case ASYNC_AFTER_HANDLER -> 3;
            case SYNC_AFTER_BATCH -> 2;
            case ASYNC_AFTER_BATCH -> 1;
        };
    }

    private static ModelCommitPolicy parse(String value) {
        try {
            return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Property `%s` must be one of %s, but found `%s`.".formatted(
                            PROPERTY, Arrays.toString(values()), value), e);
        }
    }
}
