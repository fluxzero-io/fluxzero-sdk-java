/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.common.handling;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.util.function.Function;

/**
 * Optional extension for dynamic parameter resolvers whose applicability can be safely reused for messages with the
 * same low-cardinality key.
 *
 * <p>The key must fully describe every message-dependent fact used to decide whether this resolver matches or rejects
 * the parameter. The returned {@link Resolution#resolver()} must be thread-safe and reusable for every message that
 * produces an equal key; it must never capture the representative message or a value obtained from it. Returning
 * {@code null} from {@link #getCacheKey(Object)} disables caching for that message.</p>
 *
 * <p>Resolved parameter values themselves are never cached. Handler matching only caches the resolution recipe and
 * applies its value resolver to the current message.</p>
 *
 * @param <M> message type accepted by the resolver
 */
public interface KeyedParameterResolver<M> extends ParameterResolver<M> {

    /**
     * Returns an immutable, equality-stable and preferably low-cardinality applicability key.
     *
     * @param value current message
     * @return cache key, or {@code null} when this message must be evaluated dynamically
     */
    Object getCacheKey(M value);

    /**
     * Returns whether this resolver's key can affect applicability for the supplied parameter.
     *
     * <p>Returning {@code false} promises that {@link #resolveForKey} always returns
     * {@link Resolution#unmatched()} for this parameter. This lets handler-level selection caches omit irrelevant
     * resolver keys.</p>
     */
    default boolean isCacheKeyRelevant(Parameter parameter, Annotation methodAnnotation) {
        return true;
    }

    /**
     * Computes a reusable resolution recipe for the supplied key.
     *
     * @param parameter        handler method parameter
     * @param methodAnnotation handler method annotation
     * @param value            representative message for the key
     * @param cacheKey         key returned by {@link #getCacheKey(Object)}
     * @return reusable resolution outcome
     */
    Resolution<M> resolveForKey(Parameter parameter, Annotation methodAnnotation, M value, Object cacheKey);

    /**
     * Cached outcome of evaluating one resolver for one handler parameter.
     *
     * <p>An unmatched resolution allows the next resolver to be considered. A rejected resolution claims the
     * parameter but makes the handler inapplicable. A resolved outcome supplies a reusable value resolver.</p>
     *
     * @param matched  whether this resolver claims the parameter
     * @param resolver reusable current-message value resolver; {@code null} for unmatched or rejected outcomes
     * @param <M>      message type
     */
    record Resolution<M>(boolean matched, Function<? super M, Object> resolver) {
        public Resolution {
            if (!matched && resolver != null) {
                throw new IllegalArgumentException("An unmatched resolution cannot provide a resolver");
            }
        }

        /**
         * Returns an outcome that allows the next parameter resolver to be considered.
         */
        public static <M> Resolution<M> unmatched() {
            return new Resolution<>(false, null);
        }

        /**
         * Returns an outcome that rejects the handler without considering later resolvers.
         */
        public static <M> Resolution<M> rejected() {
            return new Resolution<>(true, null);
        }

        /**
         * Returns an outcome backed by a reusable current-message value resolver.
         */
        public static <M> Resolution<M> resolved(Function<? super M, Object> resolver) {
            return new Resolution<>(true, java.util.Objects.requireNonNull(resolver));
        }
    }
}
