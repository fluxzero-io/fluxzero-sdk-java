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
 * Adds optional human-readable API documentation metadata to generated API docs.
 * <p>
 * Fluxzero can infer most route, parameter, request, and response information from the existing web handler
 * annotations. Use {@code @ApiDoc} only for metadata that cannot be inferred reliably, such as descriptions,
 * tags, or a stable operation id.
 * </p>
 *
 * @see ApiDocExtractor
 * @see ApiDocResponse
 * @see ApiDocExclude
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE, ElementType.PACKAGE})
@Documented
public @interface ApiDoc {
    /**
     * Short endpoint summary.
     */
    String summary() default "";

    /**
     * Longer description for the domain or endpoint.
     */
    String description() default "";

    /**
     * Stable operation identifier for generated specs.
     */
    String operationId() default "";

    /**
     * Tags to associate with generated operations.
     */
    String[] tags() default {};

    /**
     * Marks generated operations as deprecated.
     */
    boolean deprecated() default false;
}
