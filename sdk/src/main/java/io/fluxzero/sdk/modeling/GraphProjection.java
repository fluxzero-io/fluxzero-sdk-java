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
 * Configures an asynchronous materialized search document containing a complete model graph.
 * <p>
 * Configure this through {@link Model#graphProjection()} and enable it with {@link Model#materializeGraph()}. Without
 * an explicit {@link #collection()}, Fluxzero appends {@code -graphs} to the resolved direct-model collection when the
 * root has a direct document, or to the simple Model name otherwise. An explicit collection remains available when
 * that durable public search contract needs a custom name.
 */
@Documented
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface GraphProjection {

    /**
     * Default result-completion behavior for commits affecting this root projection.
     */
    GraphProjectionCompletion completion() default GraphProjectionCompletion.DEFAULT;

    /**
     * Distinct collection receiving materialized graph documents. Blank derives from the public direct-model collection
     * when enabled, or from the simple root-model name otherwise.
     */
    String collection() default "";

    /**
     * Optional projection-local replacements for canonical relationship paths.
     */
    GraphPathOverride[] pathOverrides() default {};
}
