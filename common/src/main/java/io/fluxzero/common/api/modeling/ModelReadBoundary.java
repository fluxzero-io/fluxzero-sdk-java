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
 * One current, state, commit or event boundary shared by every Model read.
 *
 * @param stateIndex namespace-wide state boundary, or {@code null} for an unpinned or opaque boundary
 * @param commitId commit that defines the boundary, or {@code null}
 * @param substep zero-based substep within {@code commitId}, or {@code null}
 * @param eventIndex event that defines the boundary, or {@code null}
 * @param before whether the state immediately before the selected boundary is requested
 * @param includeMessageBatch whether staged values from the active message batch are visible
 * @param fallbackToCurrent whether an unmapped {@code eventIndex} resolves to current state instead of failing
 */
public record ModelReadBoundary(
        Long stateIndex, String commitId, Integer substep, Long eventIndex,
        boolean before, boolean includeMessageBatch, boolean fallbackToCurrent) {

    /** Unpinned current-state boundary including active message-batch values. */
    public static final ModelReadBoundary CURRENT =
            new ModelReadBoundary(null, null, null, null, false, true, false);
    private static final ModelReadBoundary REQUEST_CURRENT =
            new ModelReadBoundary(null, null, null, null, false, false, false);

    public ModelReadBoundary {
        int opaqueSelectors = (commitId == null ? 0 : 1) + (eventIndex == null ? 0 : 1);
        if (opaqueSelectors > 1) {
            throw new IllegalArgumentException("Specify one model state, commit, or event boundary");
        }
        if ((commitId == null) != (substep == null)
            || commitId != null && (commitId.isBlank() || substep < 0)) {
            throw new IllegalArgumentException(
                    "Model commit boundary requires a non-blank commitId and non-negative substep");
        }
        if (stateIndex != null && stateIndex < -1L) {
            throw new IllegalArgumentException("Model stateIndex must be at least -1");
        }
        if (eventIndex != null && eventIndex < 0L) {
            throw new IllegalArgumentException("Model eventIndex must be non-negative");
        }
        if (includeMessageBatch && (before || commitId != null || eventIndex != null)) {
            throw new IllegalArgumentException(
                    "Before, commit and event boundaries cannot include pending message-batch state");
        }
        if (fallbackToCurrent && (eventIndex == null || before)) {
            throw new IllegalArgumentException(
                    "Only a non-before event boundary may fall back to current state");
        }
    }

    /** Returns the shared current-state boundary. */
    public static ModelReadBoundary current() {
        return CURRENT;
    }

    /** Returns a current boundary for {@code null}, otherwise an exact state boundary. */
    public static ModelReadBoundary at(Long stateIndex) {
        return stateIndex == null ? CURRENT : state(stateIndex, false);
    }

    /** Creates an exact state boundary with optional active message-batch visibility. */
    public static ModelReadBoundary state(long stateIndex, boolean includeMessageBatch) {
        return new ModelReadBoundary(
                stateIndex, null, null, null, false, includeMessageBatch, false);
    }

    /** Creates a boundary at a commit substep. */
    public static ModelReadBoundary commit(String commitId, int substep) {
        return new ModelReadBoundary(null, commitId, substep, null, false, false, false);
    }

    /** Creates a boundary at an event index. */
    public static ModelReadBoundary event(long eventIndex) {
        return new ModelReadBoundary(null, null, null, eventIndex, false, false, false);
    }

    /** Creates an event boundary that retains current-state behavior when the event has no Model mapping. */
    public static ModelReadBoundary eventOrCurrent(long eventIndex) {
        return new ModelReadBoundary(null, null, null, eventIndex, false, false, true);
    }

    /** Returns the boundary immediately before this selection. */
    public ModelReadBoundary asBefore() {
        return before ? this : new ModelReadBoundary(
                stateIndex, commitId, substep, eventIndex, true, false, false);
    }

    /** Pins this selection to the durable state returned by storage. */
    public ModelReadBoundary resolved(long resolvedStateIndex) {
        return resolved(resolvedStateIndex, false);
    }

    /** Pins this selection while explicitly retaining or excluding active message-batch values. */
    public ModelReadBoundary resolved(long resolvedStateIndex, boolean includeMessageBatch) {
        if (stateIndex != null && stateIndex == resolvedStateIndex
            && this.includeMessageBatch == includeMessageBatch) {
            return this;
        }
        return new ModelReadBoundary(
                resolvedStateIndex, commitId, substep, eventIndex,
                before, includeMessageBatch, fallbackToCurrent);
    }

    /** Returns this boundary without active message-batch visibility. */
    public ModelReadBoundary withoutMessageBatch() {
        return includeMessageBatch
                ? new ModelReadBoundary(
                        stateIndex, commitId, substep, eventIndex,
                        before, false, fallbackToCurrent)
                : this;
    }

    /** Indicates whether this selection refers to a durable historical boundary. */
    public boolean historical() {
        return stateIndex != null || commitId != null || eventIndex != null;
    }

    /** Returns the durable selector form carried by a storage request. */
    public ModelReadBoundary forRequest() {
        Long requestedState = commitId == null && eventIndex == null ? stateIndex : null;
        if (!before && !includeMessageBatch && requestedState == stateIndex) {
            return this;
        }
        if (requestedState == null && commitId == null && eventIndex == null) {
            return REQUEST_CURRENT;
        }
        return new ModelReadBoundary(
                requestedState, commitId, substep, eventIndex,
                false, false, fallbackToCurrent);
    }

}
