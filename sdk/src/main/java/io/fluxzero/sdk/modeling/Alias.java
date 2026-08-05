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

import io.fluxzero.sdk.Fluxzero;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a property whose current value is an alternative identifier for an entity or independently stored model.
 * <p>
 * Aliases are used when looking for an embedded entity via {@link Entity#getEntity}, when resolving a legacy aggregate
 * through {@link Fluxzero#loadAggregateFor}, and when loading an independent model through
 * {@link Fluxzero#loadModel(Object)}.
 * <p>
 * You can annotate fields and property methods. If a property value is a collection the members of the collection are
 * all added as aliases of the entity. If the property value is {@code null} or an empty collection the alias is
 * ignored.
 * <p>
 * On an independently stored {@link Model}, the complete current alias set is replaced atomically with each model
 * transition. A changed or deleted alias therefore stops resolving after that commit. Such aliases identify a model
 * globally and must be unique across independently stored models. A primary model ID takes precedence over an equal
 * alias. Aliases which are intentionally local to one aggregate tree should not be exposed as independent-model
 * aliases.
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Alias {
    /**
     * Adds given string as a prefix to the alias (if the property value is non-null). Useful to prevent clashes with
     * other entity ids.
     */
    String prefix() default "";

    /**
     * Adds given string as a postfix to the alias (if the property value is non-null). Useful to prevent clashes with
     * other entity ids.
     */
    String postfix() default "";
}
