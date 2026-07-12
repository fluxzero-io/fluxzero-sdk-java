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
 * Describes whether a handler method applies to a group of equivalent inputs and, when it does, how to invoke it.
 *
 * <p>Fluxzero uses this result to cache both matches and non-matches without changing handler selection. Equal
 * {@code cacheKey} values must therefore mean that the same {@link HandlerMethodPreparation} is valid for both
 * inputs.</p>
 *
 * @param cacheKey a stable key containing every input property that affects the result, or {@code null} when safe
 *                 caching is unsupported
 * @param payloadClassKey whether the payload's runtime class alone is a complete key for this result
 * @param preparation whether the method matches and, if so, the prepared invocation plan
 * @param <M> the message type used by the handling pipeline
 */
public record HandlerMethodApplicability<M>(Object cacheKey, boolean payloadClassKey,
                                            HandlerMethodPreparation<M> preparation) {
    private static final HandlerMethodApplicability<?> unsupported = new HandlerMethodApplicability<>(
            null, false, HandlerMethodPreparation.unsupported());

    public HandlerMethodApplicability {
        Objects.requireNonNull(preparation, "preparation");
        if ((cacheKey == null) != preparation.isUnsupported()) {
            throw new IllegalArgumentException("Only unsupported applicability may omit its cache key");
        }
        if (payloadClassKey && preparation.isUnsupported()) {
            throw new IllegalArgumentException("Unsupported applicability cannot use a payload-class key");
        }
    }

    /**
     * Creates a result that may be reused for inputs with the same cache key.
     *
     * @param cacheKey a stable key containing every input property that affects the result
     * @param payloadClassKey whether the payload's runtime class alone is sufficient as the key
     * @param preparation a prepared match or definitive non-match
     * @return the cacheable applicability result
     */
    public static <M> HandlerMethodApplicability<M> cacheable(
            Object cacheKey, boolean payloadClassKey, HandlerMethodPreparation<M> preparation) {
        if (preparation.isUnsupported()) {
            throw new IllegalArgumentException("Unsupported preparation cannot be cached");
        }
        return new HandlerMethodApplicability<>(Objects.requireNonNull(cacheKey), payloadClassKey, preparation);
    }

    /**
     * Returns a result indicating that applicability cannot be prepared safely for this input.
     *
     * <p>The caller should use normal per-message handler selection instead.</p>
     *
     * @return an unsupported result
     */
    @SuppressWarnings("unchecked")
    public static <M> HandlerMethodApplicability<M> unsupported() {
        return (HandlerMethodApplicability<M>) unsupported;
    }

    /**
     * Returns whether this result may be reused for another input with the same key.
     *
     * @return {@code true} if this result has a complete cache key
     */
    public boolean isCacheable() {
        return cacheKey != null;
    }

    /**
     * Copies this result with a different preparation while retaining its cache guarantees.
     *
     * <p>This is intended for handler decorators that do not change when the underlying handler applies, but that do
     * wrap, reject, or otherwise transform its invocation plan. If the new preparation is unsupported, the returned
     * applicability is unsupported as well.</p>
     *
     * @param newPreparation the replacement preparation
     * @return a result with the same key guarantees and the replacement preparation
     */
    public HandlerMethodApplicability<M> withPreparation(HandlerMethodPreparation<M> newPreparation) {
        Objects.requireNonNull(newPreparation, "newPreparation");
        return !isCacheable() || newPreparation.isUnsupported()
                ? unsupported() : cacheable(cacheKey, payloadClassKey, newPreparation);
    }
}
