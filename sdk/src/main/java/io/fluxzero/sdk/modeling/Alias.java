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
 * globally and must be unique across independently stored models. Generic loads give a primary model ID precedence
 * over an equal alias. Aliases which are intentionally local to one aggregate tree should not be exposed as independent-model
 * aliases.
 * <p>
 * With {@code fluxzero.model.aliasTargets=true}, a mutation property with the same name as an alias property selects
 * through that alias when no explicit Graph, association, primary-ID property or typed ID selects the Model.
 * Alias prefix/postfix settings apply to the selector. Several alias properties (or collection values) must select
 * one owner; the selected value must match each found selector on its declared property. Equal values on different
 * alias properties are not interchangeable. A present but null primary-ID property does not enable alias fallback.
 * <p>
 * All missing selectors permit a new Apply result with a valid primary ID and matching selectors. Mixed found/missing
 * selectors update the found owner and require the final result to match every selector. Fully matched selections
 * may change aliases or delete the owner. Generic loads and embedded/legacy entity lookup keep their existing contract.
 * Enable this only with a Runtime advertising identity-selection version 1; unsupported peers reject the new requests
 * before effects. The setting defaults to false before defaults version {@code 2026.10.18}.
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
