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
 * Configures the direct current-state document maintained by a {@link Model} whose
 * {@link Model#persistence() persistence} stores a document.
 */
@Documented
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface DocumentProjection {

    /**
     * Whether this current document is exposed through the Model's public search collection.
     * <p>
     * When {@code false}, Fluxzero stores it in type-isolated private Model storage. It remains available to direct
     * Model loads, aliases, parent relationships and Graph composition, but is not returned by ordinary typed Model
     * searches.
     */
    boolean searchable() default true;

    /** Collection receiving current Model documents. Blank defaults to the Model's simple class name. */
    String collection() default "";

    /** Optional property path used as the document's start timestamp. */
    String timestampPath() default "";

    /** Optional property path used as the document's end timestamp. */
    String endPath() default "";
}
