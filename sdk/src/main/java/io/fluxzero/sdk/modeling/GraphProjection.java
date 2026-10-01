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
 * Optional materialization of a searchable Model's complete composed Graph.
 * <p>
 * Search activation and Graph materialization are independent choices. {@link Model#searchable()} and
 * {@link Model#searchSettings()} determine which canonical nodes participate in search. This annotation only controls
 * whether Fluxzero additionally stores the composed Graph.
 * <p>
 * The default mode is {@link GraphProjectionMode#NONE NONE}: no complete Graph document is stored. Searchable node
 * documents are still maintained, and Graph queries compose them live when read. {@link GraphProjectionMode#ASYNC}
 * and {@link GraphProjectionMode#AWAIT} additionally maintain a stored complete Graph.
 */
@Documented
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface GraphProjection {

    /**
     * Whether to materialize the composed Graph and whether commits wait for that materialization.
     * {@link GraphProjectionMode#NONE NONE} is the default and means live Graph composition from indexed nodes, not
     * absence of Graph search.
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
