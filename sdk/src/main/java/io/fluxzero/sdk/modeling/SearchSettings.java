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

package io.fluxzero.sdk.modeling;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-node search configuration, independent of activation through {@link Model#searchable()}.
 * A node included by a searchable ancestor uses its own collection and timestamp settings.
 */
@Documented
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface SearchSettings {
    /**
     * Whether a searchable scope started at this Model includes composed descendants.
     * <p>
     * Defaults to {@code true}. Setting this to {@code false} limits this Model's own root scope to itself; it does
     * not veto inclusion of this Model in a broader searchable ancestor scope. Descendant traversal still follows only
     * composed parent relationships, and {@link Parent#propagateSearch()} may block propagation across individual edges.
     */
    boolean includeDescendants() default true;

    /** Canonical node collection. Blank retains the stable internal collection derived from the logical Model name. */
    String collection() default "";

    /** Optional property path used as the document's start timestamp. */
    String timestampPath() default "";

    /** Optional property path used as the document's end timestamp. */
    String endPath() default "";
}
