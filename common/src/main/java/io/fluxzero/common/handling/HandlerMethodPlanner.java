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

/**
 * Selects and prepares a handler method so that the result can be reused for equivalent inputs.
 *
 * <p>This is an advanced extension point for handler matchers and parameter resolvers. A planner must account for
 * every input property that can affect method selection, argument resolution, or handler specificity. If it cannot do
 * so safely, it should return {@code null} from {@link #getCacheKey(Object)} or return
 * {@link HandlerMethodPreparation#unsupported()}; Fluxzero will then use normal per-message handler selection.</p>
 *
 * @param <M> the message type used by the handling pipeline
 */
public interface HandlerMethodPlanner<M> {

    /**
     * Determines whether a handler applies to the message and returns the complete key needed to reuse that decision.
     *
     * @param message a representative message
     * @return the prepared applicability, or an unsupported result when it cannot be cached safely
     */
    default HandlerMethodApplicability<M> prepareApplicability(M message) {
        Object cacheKey = getCacheKey(message);
        if (cacheKey == null) {
            return HandlerMethodApplicability.unsupported();
        }
        HandlerMethodPreparation<M> preparation = prepare(message);
        return preparation.isUnsupported() ? HandlerMethodApplicability.unsupported()
                : HandlerMethodApplicability.cacheable(cacheKey, false, preparation);
    }

    /**
     * Determines whether a handler applies without creating the complete message when the planner supports lazy input.
     *
     * @param input a representative handler input
     * @return the prepared applicability, or an unsupported result when it cannot be cached safely
     */
    default HandlerMethodApplicability<M> prepareApplicability(HandlerInput<M> input) {
        Object cacheKey = getCacheKey(input);
        if (cacheKey == null) {
            return HandlerMethodApplicability.unsupported();
        }
        HandlerMethodPreparation<M> preparation = prepare(input);
        if (preparation.isUnsupported()) {
            return HandlerMethodApplicability.unsupported();
        }
        boolean payloadClassKey = preparation.isPrepared()
                ? isPayloadClassKey(input) : isNoMatchPayloadClassKey(input);
        return HandlerMethodApplicability.cacheable(cacheKey, payloadClassKey, preparation);
    }

    /**
     * Returns a key containing every message property that can affect the result of {@link #prepare(Object)}.
     *
     * <p>Keys are compared using {@link Object#equals(Object)} and must remain stable after they are returned. Equal
     * keys must always produce an equivalent match decision and invocation plan.</p>
     *
     * @param message a representative message
     * @return a complete reusable key, or {@code null} when this message cannot be cached safely
     */
    Object getCacheKey(M message);

    /**
     * Returns the complete key without creating the message when the planner supports lazy input.
     *
     * <p>The default implementation delegates to {@link #getCacheKey(Object)} and therefore creates the message.</p>
     *
     * @param input a representative handler input
     * @return a complete reusable key, or {@code null} when this input cannot be cached safely
     */
    default Object getCacheKey(HandlerInput<M> input) {
        return getCacheKey(input.getMessage());
    }

    /**
     * Selects a handler method for the representative message and prepares its invocation.
     *
     * @param message a representative message
     * @return a prepared plan, a definitive non-match, or an unsupported result
     */
    HandlerMethodPreparation<M> prepare(M message);

    /**
     * Selects and prepares a handler method without creating the message when the planner supports lazy input.
     *
     * <p>The default implementation delegates to {@link #prepare(Object)} and therefore creates the message.</p>
     *
     * @param input a representative handler input
     * @return a prepared plan, a definitive non-match, or an unsupported result
     */
    default HandlerMethodPreparation<M> prepare(HandlerInput<M> input) {
        return prepare(input.getMessage());
    }

    /**
     * Returns whether the payload's runtime class alone completely determines a successful preparation for this input.
     *
     * @return {@code true} if the prepared match may be reused for every payload of the same runtime class
     */
    default boolean isPayloadClassKey(HandlerInput<M> input) {
        return false;
    }

    /**
     * Returns whether the payload's runtime class alone completely determines a non-match for this input.
     *
     * @return {@code true} if the non-match may be reused for every payload of the same runtime class
     */
    default boolean isNoMatchPayloadClassKey(HandlerInput<M> input) {
        return false;
    }
}
