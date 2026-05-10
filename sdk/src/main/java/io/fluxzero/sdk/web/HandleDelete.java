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

package io.fluxzero.sdk.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Handles incoming HTTP {@code DELETE} requests for the specified path(s).
 * <p>
 * This is a specialization of {@link HandleWeb} for {@code DELETE} method requests.
 * </p>
 *
 * @see HandleWeb
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@HandleWeb(value = "", method = HttpRequestMethod.DELETE)
public @interface HandleDelete {
    /**
     * One or more path patterns this handler applies to (e.g. {@code /users}, {@code /accounts/{id}},
     * {@code /accounts/*&#47;users}). If empty, the path is based on the {@link Path} annotation.
     * <p>
     * Patterns support literal path parts, {@code {name}} parameters, {@code {name:regex}} constrained parameters,
     * and {@code *} wildcards. A non-final {@code *} matches within a single path segment, while a final {@code *}
     * matches the rest of the path. Optional path fragments can be declared with square brackets, for example
     * {@code /users[/{id}]}. Trailing slashes on non-root paths are ignored.
     */
    String[] value() default {};

    /**
     * If {@code true}, disables this handler during discovery.
     */
    boolean disabled() default false;

    /**
     * If {@code true}, the handler will not publish a response to the {@code WebResponse} log.
     */
    boolean passive() default false;

    /**
     * If {@code true}, this route may contribute to automatically generated {@code OPTIONS} responses.
     */
    boolean autoOptions() default true;
}
