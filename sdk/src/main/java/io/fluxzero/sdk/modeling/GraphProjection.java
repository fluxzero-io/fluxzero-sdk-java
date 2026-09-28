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

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional precomputation of a searchable Model's complete Graph. Searchability and descendant scope are owned by
 * {@link Model#searchable()} and {@link Model#searchSettings()}; these settings alone do not activate search.
 */
@Documented
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface GraphProjection {

    /**
     * Whether to store the composed Graph and whether commits wait for it.
     */
    GraphProjectionMode mode() default GraphProjectionMode.NONE;

    /**
     * Distinct collection receiving materialized Graph documents. Blank derives from the explicitly configured canonical
     * node collection, or from the resolved logical root-model name when no node collection is configured.
     */
    String collection() default "";

    /**
     * Optional projection-local replacements for canonical relationship paths.
     */
    GraphPathOverride[] pathOverrides() default {};
}
