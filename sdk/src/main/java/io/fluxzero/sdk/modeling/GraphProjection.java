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
 * Configures an opt-in asynchronous materialized search document containing a complete bounded model graph.
 * <p>
 * A blank {@link #collection()} disables materialization. The collection must be explicit because it is a durable
 * public search contract and must differ from the model's synchronous direct-document collection.
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
     * Distinct collection receiving materialized graph documents. Blank disables the projection.
     */
    String collection() default "";

    /**
     * Maximum relationship depth below a root.
     */
    int maxDepth() default 16;

    /**
     * Maximum distinct models in one root graph.
     */
    int maxModels() default 10_000;

    /**
     * Maximum placements, including repeated shared-DAG descendants.
     */
    int maxPlacements() default 25_000;

    /**
     * Maximum number of direct-model collections read while composing one root.
     */
    int maxCollections() default 128;

    /**
     * Maximum serialized output bytes for one materialized root.
     */
    long maxBytes() default 64L * 1024L * 1024L;

    /**
     * Optional projection-local replacements for canonical relationship paths.
     */
    GraphPathOverride[] pathOverrides() default {};
}
