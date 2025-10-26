/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.common.search;

import io.fluxzero.common.api.search.constraints.BetweenConstraint;
import io.fluxzero.common.api.search.constraints.ContainsConstraint;
import io.fluxzero.common.api.search.constraints.ExistsConstraint;
import io.fluxzero.common.api.search.constraints.FacetConstraint;
import io.fluxzero.common.api.search.constraints.LookAheadConstraint;
import io.fluxzero.common.api.search.constraints.MatchConstraint;
import io.fluxzero.common.api.search.constraints.QueryConstraint;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation that indicates that a property is to be ignored when indexing a document for text search.
 * <p>
 * When a property is ignored, the document can't be matched using this property. More specifically, the property is
 * ignored by {@link MatchConstraint}, {@link LookAheadConstraint}, {@link ContainsConstraint}, and
 * {@link QueryConstraint}. I.e. if the property is also marked with {@link Facet @Facet}, it can still be matched using
 * a {@link FacetConstraint}. Matching using {@link BetweenConstraint} or {@link ExistsConstraint} is also not affected.
 * <p>
 * When this annotation is present on a type, all properties of the class will be ignored when indexing, unless they are
 * individually annotated with {@link SearchExclude SearchExclude(false)} or {@link SearchInclude}.
 * <p>
 * Note that the property is not lost when the document is serialized or deserialized. If that is the intention, make
 * the property transient instead, e.g. using an annotation like {@link java.beans.Transient} or
 * {@link com.fasterxml.jackson.annotation.JsonIgnore}.
 * <p>
 * Subclasses can re-enable indexing by specifying a {@link #value()} of {@code false} on the overridden property or
 * class.
 */
@Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface SearchExclude {
    /**
     * Optional argument that defines whether this annotation is active ({@code true}) or not ({@code false}).
     */
    boolean value() default true;
}
