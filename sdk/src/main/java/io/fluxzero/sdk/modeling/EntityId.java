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

import io.fluxzero.sdk.tracking.handling.Association;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a property (field or getter) as the unique identifier of an entity or independently stored model.
 * <p>
 * The presence of this annotation enables automatic routing of updates to the correct entity instance inside an
 * aggregate, based on identifier matching.
 * <p>
 * This is particularly important in aggregates that consist of nested entities, e.g.: a {@code Project}
 * containing a list of {@code Task} entities. The framework uses the {@code @EntityId}-annotated property to match
 * update messages with their corresponding entity.
 * <p>
 * You can annotate either a field or its getter method. Optional affixes only affect the persisted identifier and
 * repository lookups; the property itself retains its functional value. They wrap any repository prefix already
 * supplied by an {@link Id}. For example, an ID whose repository value is {@code connection-123} combined with
 * {@code @EntityId(prefix = "move-")} is stored as {@code move-connection-123}.
 * <p>
 * A one-to-one companion model may use the same property as both its {@code @EntityId} and a {@link Parent}. The
 * functional property value then identifies the parent relationship, while these affixes still give the companion
 * its own globally unique repository identity.
 * <p>
 * A model whose functional identifier is unique only below its parent can set {@link #parentScoped()} to
 * {@code true}. Its persisted identity then combines the one non-null declared {@link Parent} with the functional
 * identifier. The model property keeps the functional value and graph-local {@link Graph#find(Object, Class)} lookup
 * therefore remains natural. Parent-scoped identity is intended for parent-owned values: moving such a model to a
 * different parent changes its persisted identity.
 *
 * @see Aggregate for how to define aggregates and their structure.
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Association
public @interface EntityId {
    /** Prefix added outside the identifier's own repository representation. */
    String prefix() default "";

    /** Postfix added outside the identifier's own repository representation. */
    String postfix() default "";

    /** Whether the persisted model identity is scoped by its one non-null declared parent. */
    boolean parentScoped() default false;
}
