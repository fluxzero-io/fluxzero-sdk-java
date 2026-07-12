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

package io.fluxzero.common.handling;

import java.util.Objects;

/**
 * The outcome of trying to prepare a handler method for repeated invocation.
 *
 * <p>A preparation distinguishes a successful match, a definitive non-match, and a case that cannot be prepared
 * safely. This distinction lets callers cache non-matches while retaining the normal handling path for unsupported
 * cases.</p>
 *
 * @param status the outcome of the preparation
 * @param plan the reusable invocation plan when {@code status} is {@link Status#PREPARED}; otherwise {@code null}
 * @param <M> the message type used by the handling pipeline
 */
public record HandlerMethodPreparation<M>(Status status, HandlerMethodPlan<M> plan) {
    private static final HandlerMethodPreparation<?> noMatch = new HandlerMethodPreparation<>(Status.NO_MATCH, null);
    private static final HandlerMethodPreparation<?> unsupported =
            new HandlerMethodPreparation<>(Status.UNSUPPORTED, null);

    public HandlerMethodPreparation {
        if ((status == Status.PREPARED) != (plan != null)) {
            throw new IllegalArgumentException("Only a prepared outcome can contain a handler plan");
        }
    }

    /**
     * Creates a successful preparation.
     *
     * @param plan the reusable invocation plan
     * @return a successful preparation containing the plan
     */
    public static <M> HandlerMethodPreparation<M> prepared(HandlerMethodPlan<M> plan) {
        return new HandlerMethodPreparation<>(Status.PREPARED, Objects.requireNonNull(plan));
    }

    @SuppressWarnings("unchecked")
    public static <M> HandlerMethodPreparation<M> noMatch() {
        return (HandlerMethodPreparation<M>) noMatch;
    }

    @SuppressWarnings("unchecked")
    public static <M> HandlerMethodPreparation<M> unsupported() {
        return (HandlerMethodPreparation<M>) unsupported;
    }

    /**
     * Returns whether preparation produced an invocation plan.
     *
     * @return {@code true} when {@link #plan()} can be invoked
     */
    public boolean isPrepared() {
        return status == Status.PREPARED;
    }

    /**
     * Returns whether the normal per-message handling path must be used.
     *
     * @return {@code true} when safe preparation was not possible
     */
    public boolean isUnsupported() {
        return status == Status.UNSUPPORTED;
    }

    /** Possible outcomes of preparing a handler method. */
    public enum Status {
        /** The handler matches and {@link HandlerMethodPreparation#plan()} contains its invocation plan. */
        PREPARED,
        /** The handler definitely does not match inputs represented by the associated cache key. */
        NO_MATCH,
        /** The result cannot be prepared safely; normal per-message handler selection must be used. */
        UNSUPPORTED
    }
}
    /**
     * Returns a result indicating that the handler definitely does not match the associated cache key.
     *
     * @return a definitive non-match
     */
    /**
     * Returns a result indicating that this input cannot be prepared safely.
     *
     * <p>The caller should use normal per-message handler selection instead.</p>
     *
     * @return an unsupported result
     */
