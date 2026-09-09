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

package io.fluxzero.sdk.tracking.handling;

import lombok.NonNull;

import java.util.function.Supplier;

/**
 * Side-effect-free selection of exactly one result-producing local handler.
 *
 * <p>The selected invocation is kept separate from selection so a gateway can reject an invalid local-only dispatch
 * before invoking any handler, monitoring the dispatch, serializing the message, or publishing it externally.</p>
 */
public final class LocalHandlerSelection {
    private static final LocalHandlerSelection noMatch = new LocalHandlerSelection(Outcome.NO_MATCH, null);
    private static final LocalHandlerSelection ambiguous = new LocalHandlerSelection(Outcome.AMBIGUOUS, null);
    private static final LocalHandlerSelection externalPublication =
            new LocalHandlerSelection(Outcome.EXTERNAL_PUBLICATION, null);
    private static final LocalHandlerSelection unsupported = new LocalHandlerSelection(Outcome.UNSUPPORTED, null);

    private final Outcome outcome;
    private final Supplier<LocalHandlerResult> handler;

    private LocalHandlerSelection(Outcome outcome, Supplier<LocalHandlerResult> handler) {
        this.outcome = outcome;
        this.handler = handler;
    }

    /**
     * Creates a selection containing exactly one result-producing local handler.
     *
     * @param handler callback that invokes the already selected handler and any applicable passive local handlers
     * @return a successful selection
     */
    public static LocalHandlerSelection selected(@NonNull Supplier<LocalHandlerResult> handler) {
        return new LocalHandlerSelection(Outcome.SELECTED, handler);
    }

    /** Returns a selection for which no result-producing local handler matched. */
    public static LocalHandlerSelection noMatch() {
        return noMatch;
    }

    /** Returns a selection for which more than one result-producing local handler matched. */
    public static LocalHandlerSelection ambiguous() {
        return ambiguous;
    }

    /** Returns a selection whose local handler configuration would also publish the message externally. */
    public static LocalHandlerSelection externalPublication() {
        return externalPublication;
    }

    /** Returns a selection for a registry that cannot guarantee exact local selection without invoking a handler. */
    public static LocalHandlerSelection unsupported() {
        return unsupported;
    }

    /**
     * Merges exact selections from two registries without invoking either selection.
     *
     * @param first selection from the first registry
     * @param second selection from the second registry
     * @return the sole selected handler, or a fail-closed outcome
     */
    public static LocalHandlerSelection merge(LocalHandlerSelection first, LocalHandlerSelection second) {
        if (first.outcome == Outcome.UNSUPPORTED || second.outcome == Outcome.UNSUPPORTED) {
            return unsupported;
        }
        if (first.outcome == Outcome.AMBIGUOUS || second.outcome == Outcome.AMBIGUOUS
            || first.isSelected() && second.isSelected()) {
            return ambiguous;
        }
        if (first.outcome == Outcome.EXTERNAL_PUBLICATION || second.outcome == Outcome.EXTERNAL_PUBLICATION) {
            return externalPublication;
        }
        return first.isSelected() ? first : second;
    }

    /**
     * Returns the selection outcome.
     *
     * @return the selection outcome
     */
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * Returns whether exactly one exclusively local result-producing handler was selected.
     *
     * @return {@code true} when {@link #invoke()} may be called
     */
    public boolean isSelected() {
        return outcome == Outcome.SELECTED;
    }

    /**
     * Invokes the selected local handler and applicable passive handlers.
     *
     * @return the local handler result
     * @throws IllegalStateException when this selection is not successful
     */
    public LocalHandlerResult invoke() {
        if (!isSelected()) {
            throw new IllegalStateException("Cannot invoke local handler selection: " + outcome);
        }
        return handler.get();
    }

    /** Outcome of exact local request-handler selection. */
    public enum Outcome {
        SELECTED("exactly one exclusively local request handler was selected"),
        NO_MATCH("no local request handler matched"),
        AMBIGUOUS("multiple local request handlers matched"),
        EXTERNAL_PUBLICATION("a matching local handler is configured to publish the message externally"),
        UNSUPPORTED("the configured handler registry cannot guarantee exact local selection"),
        INVALID_SELECTION("the selected local handler did not accept the message");

        private final String description;

        Outcome(String description) {
            this.description = description;
        }

        /**
         * Returns a human-readable explanation of this outcome.
         *
         * @return the outcome description
         */
        public String description() {
            return description;
        }
    }
}
