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

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.util.Objects;
import java.util.function.Function;

/**
 * Optional extension of {@link ParameterResolver} for resolving handler parameters from a {@link HandlerInput}.
 *
 * <p>Implementing this interface allows Fluxzero to select a handler method once and reuse its argument-resolution
 * functions for later inputs. This is primarily useful for parameter resolvers on frequently invoked local handlers.
 * Regular parameter resolvers do not need to implement it; Fluxzero will use the normal message-based handling path
 * when a method cannot be prepared safely.</p>
 *
 * <p>The cache key is part of the correctness contract. Two inputs with equal keys must always produce the same match
 * decision, and every function returned by {@link #prepareInput(Parameter, Annotation, HandlerInput)} must remain
 * valid for both inputs.</p>
 *
 * @param <M> the message type used by the handling pipeline
 */
public interface HandlerInputResolver<M> extends ParameterResolver<M> {

    /**
     * Returns a key containing every input property that can affect whether this resolver matches the parameter and
     * how the parameter value is resolved.
     *
     * <p>Return {@code null} when no safe reusable key can be provided. Fluxzero will then fall back to normal
     * per-message resolution. Keys are compared using {@link Object#equals(Object)} and must be stable after they are
     * returned.</p>
     *
     * @param parameter the handler parameter to resolve
     * @param methodAnnotation the annotation that marks the handler method, if present
     * @param representative an input representative of the group to be cached
     * @return a stable, complete cache key, or {@code null} to disable prepared resolution for this input
     */
    Object getInputCacheKey(Parameter parameter, Annotation methodAnnotation, HandlerInput<M> representative);

    /**
     * Returns whether the payload's runtime class alone determines this resolver's successful result for the supplied
     * parameter.
     *
     * <p>Return {@code true} only if every payload of that class produces the same match decision and may use the same
     * prepared resolver. This enables a faster class-based lookup.</p>
     *
     * @return {@code true} if the result can safely be reused for every payload of the same runtime class
     */
    default boolean isPayloadClassKey(Parameter parameter, Annotation methodAnnotation,
                                      HandlerInput<M> representative) {
        return false;
    }

    /**
     * Returns whether an unmatched result is guaranteed to remain unmatched for every payload with the same runtime
     * class.
     *
     * <p>This is separate from {@link #isPayloadClassKey(Parameter, Annotation, HandlerInput)} because matching and
     * non-matching inputs may require different cache guarantees.</p>
     *
     * @return {@code true} if the non-match can safely be reused for the entire payload class
     */
    default boolean isNoMatchPayloadClassKey(Parameter parameter, Annotation methodAnnotation,
                                             HandlerInput<M> representative) {
        return false;
    }

    /**
     * Determines whether this resolver handles the parameter and, when it does, creates the function that supplies the
     * parameter value during invocation.
     *
     * <p>The result must agree with normal {@link ParameterResolver} selection, including any contribution the resolver
     * makes to handler specificity.</p>
     *
     * @param parameter the handler parameter to resolve
     * @param methodAnnotation the annotation that marks the handler method, if present
     * @param representative an input representative of the cache key returned for this parameter
     * @return an unmatched, rejected, or successfully resolved result
     */
    Resolution<M> prepareInput(Parameter parameter, Annotation methodAnnotation, HandlerInput<M> representative);

    /**
     * Describes what a resolver decided for one handler parameter.
     *
     * @param matched whether this resolver claims the parameter
     * @param resolver function that obtains the argument from each input, or {@code null} for an unmatched or rejected
     *                 result
     * @param <M> the message type used by the handling pipeline
     */
    record Resolution<M>(boolean matched, Function<? super HandlerInput<M>, Object> resolver) {
        public Resolution {
            if (!matched && resolver != null) {
                throw new IllegalArgumentException("An unmatched resolution cannot provide a resolver");
            }
        }

        /**
         * Returns a result indicating that this resolver does not handle the parameter. The next resolver may be tried.
         */
        public static <M> Resolution<M> unmatched() {
            return new Resolution<>(false, null);
        }

        /**
         * Returns a result indicating that this resolver claims the parameter but rejects the representative input.
         * No later resolver should be tried for that parameter.
         */
        public static <M> Resolution<M> rejected() {
            return new Resolution<>(true, null);
        }

        /**
         * Returns a successful result with a function that supplies the argument for each compatible input.
         *
         * @param resolver the reusable argument-resolution function
         */
        public static <M> Resolution<M> resolved(Function<? super HandlerInput<M>, Object> resolver) {
            return new Resolution<>(true, Objects.requireNonNull(resolver));
        }
    }
}
