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
 * available without silently deriving a durable document path from a Java class name.
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
     * Optional slash-separated placement path relative to the parent document.
     */
    String path() default "";
}
