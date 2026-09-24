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

package io.fluxzero.common.api.modeling;

/**
 * Runtime behavior when a commit-scoped model changed after the commit's {@code readStateIndex}.
 * <p>
 * {@link #RETRY} is the default. Explicit {@link #ACCEPT} preserves the original decision:
 * the original event is accepted, while stale derived documents, snapshots, and relationships are rebased
 * by reapplying it to the latest values of the models actually read by the commit. Rejecting policies roll back the
 * complete runtime commit; any retry is a new SDK evaluation against freshly loaded models.
 */
public enum ModelConflictPolicy {
    /**
     * Inherit from the next broader model-commit scope, falling back to {@link #RETRY}.
     */
    DEFAULT,

    /**
     * Accept the original event and silently rebase stale derived state. This is an explicit opt-in.
     */
    ACCEPT,

    /**
     * Reject a stale commit and let the client map the conflict to an application decision.
     */
    FAIL,

    /**
     * Reject a stale commit and permit a bounded complete reevaluation against freshly loaded models and relations.
     */
    RETRY;

    /**
     * Resolves {@link #DEFAULT} to {@link #RETRY}. A missing wire value retains the legacy
     * {@link #ACCEPT} interpretation; SDKs send their resolved policy explicitly.
     */
    public static ModelConflictPolicy resolve(ModelConflictPolicy policy) {
        return policy == null ? ACCEPT : policy == DEFAULT ? RETRY : policy;
    }
}
