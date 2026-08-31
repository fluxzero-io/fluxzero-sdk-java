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
 * Both sides remain independent model and persistence boundaries. Use a parent relationship when the child has its own
 * creation, update history, retention, or deletion lifecycle but belongs in the parent's domain graph. Being rendered
 * below the parent, or being deleted with it by default, does not turn the child into an embedded {@link Member}.
 * A child identifier that is meaningful only below this parent can use {@link EntityId#parentScoped()}.
 * <p>
 * A parent reference is stored independently from both model values so changing this property can attach, detach, or
 * move the child without loading or rewriting either parent. A {@code null} value means that the property currently has no
 * parent. Non-null values use their {@link Object#toString()} representation as the referenced model identity.
 * <p>
 * The parent model type is inferred when the property is an {@link Id}{@code <T>}. For a {@link String} or another
 * untyped ID, {@link #value()} may name the parent model explicitly. An explicit type is required only for features
 * that need parent model metadata, including automatic graph-document composition.
 * A typed parent may target the declaring model type itself, for example to represent folders within folders.
 * Concrete relationship cycles are rejected atomically when the relationship change is committed.
 * <p>
 * {@link #pathInParent()} is an optional path relative to the parent document. Supplying it opts this edge into
 * automatic virtual-document stitching and CQRS graph placement. Omitting it leaves relationship navigation and graph
 * bundles available without silently deriving a durable document path from a Java class name. The path names a
 * list-valued collection: the runtime appends deterministic numeric child positions, so numeric path segments are not
 * allowed.
 * Graph placement is independent from {@link Model#persistence()}: a child without a direct document but with an
 * explicit path is retained in a type-isolated private current-document collection for composition and indexed
 * relationship selection, but is not exposed through its own collection. Parent and Graph searches can therefore
 * select matching children first and traverse their current relationship edges without composing unrelated roots.
 * {@link #apiDoc()} optionally describes the list-valued property created at that path when the graph is used as a
 * documented web response. It has no effect unless {@link #pathInParent()} is set.
 * <p>
 * By default the relationship also owns the child's lifecycle: deleting the referenced parent deletes this model and
 * its likewise owned descendants in the same atomic model commit. Set {@link #deleteOnParentDeletion()} to
 * {@code false} for a shared or independently retained child. This lifecycle rule does not require a graph path and
 * moving a child by changing its parent ID does not count as parent deletion.
 * <p>
 * Declaring metadata does not cause a parent to be loaded when the child is loaded or updated.
 * A one-to-one companion model may annotate the same property with {@link EntityId}; entity-ID affixes affect only
 * the companion's repository identity and do not alter the parent reference value.
 *
 * @see Model
 * @see Member
 * @see EntityId
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Parent {

    /**
     * Explicit parent model type for an untyped ID. Must match the inferred {@link Id} target when both are present.
     */
    Class<?> value() default void.class;

    /**
     * Explicit possible parent model types for a polymorphic {@link Id} property.
     * <p>
     * At runtime the concrete {@link Id} subtype selects exactly one of these model types through its declared type.
     * This keeps graph validation, API documentation, cascade deletion and cycle detection statically knowable while
     * allowing one domain property such as {@code Id<?> nominee} to refer to different model types. This attribute and
     * {@link #value()} are mutually exclusive.
     */
    Class<?>[] types() default {};

    /**
     * Optional slash-separated, non-reserved collection path relative to the parent document.
     */
    String pathInParent() default "";

    /**
     * Optional API documentation for the list-valued graph property at {@link #pathInParent()}.
     * <p>
     * The child item schema is inferred from the model that declares this parent reference. Nested path segments are
     * represented as objects, while the final path segment is represented as an array of child models. Structural
     * {@link ApiDoc} hints such as {@link ApiDoc#type()}, {@link ApiDoc#format()}, and {@link ApiDoc#implementation()}
     * cannot override that inferred array and child type. Use {@link ApiDoc#exclude()} to keep this relationship out
     * of every documented graph while retaining it in the runtime model graph.
     */
    ApiDoc apiDoc() default @ApiDoc;

    /**
     * Whether this model is deleted when the referenced parent is logically deleted.
     * <p>
     * The default expresses ordinary parent-owned child lifecycle. Opt out for shared graph edges or models which must
     * remain independently addressable after their parent disappears.
     */
    boolean deleteOnParentDeletion() default true;
}
