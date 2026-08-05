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

import io.fluxzero.sdk.web.ApiDoc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a model property contains the ID of a parent model.
 * <p>
 * A parent reference is stored independently from both model values so changing this property can attach, detach, or
 * move the child without loading or rewriting either parent. A {@code null} value means that the property currently has no
 * parent. Non-null values use their {@link Object#toString()} representation as the referenced model identity.
 * <p>
 * The parent model type is inferred when the property is an {@link Id}{@code <T>}. For a {@link String} or another
 * untyped ID, {@link #value()} may name the parent model explicitly. An explicit type is required only for features
 * that need parent model metadata, including automatic graph-document composition.
 * <p>
 * {@link #path()} is an optional path relative to the parent document. Supplying it opts this edge into automatic
 * virtual-document stitching and CQRS graph placement. Omitting it leaves relationship navigation and graph bundles
 * available without silently deriving a durable document path from a Java class name. The path names a list-valued
 * collection: the runtime appends deterministic numeric child positions, so numeric path segments are not allowed.
 * Graph placement is independent from {@link Model#searchable()}: a non-searchable child with an explicit path is
 * retained in a private current-document collection for composition, but is not exposed through its own collection.
 * {@link #apiDoc()} optionally describes the list-valued property created at that path when the graph is used as a
 * documented web response. It has no effect unless {@link #path()} is set.
 * <p>
 * Declaring metadata does not cause a parent to be loaded when the child is loaded or updated.
 *
 * @see Model
 * @see EntityId
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ParentId {

    /**
     * Explicit parent model type for an untyped ID. Must match the inferred {@link Id} target when both are present.
     */
    Class<?> value() default void.class;

    /**
     * Optional slash-separated, non-reserved collection path relative to the parent document.
     */
    String path() default "";

    /**
     * Optional API documentation for the list-valued graph property at {@link #path()}.
     * <p>
     * The child item schema is inferred from the model that declares this parent reference. Nested path segments are
     * represented as objects, while the final path segment is represented as an array of child models. Structural
     * {@link ApiDoc} hints such as {@link ApiDoc#type()}, {@link ApiDoc#format()}, and {@link ApiDoc#implementation()}
     * cannot override that inferred array and child type.
     */
    ApiDoc apiDoc() default @ApiDoc;
}
