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
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds an explicit response entry to generated API documentation.
 * <p>
 * Handler return types are inferred automatically. Use this annotation for additional status codes, error responses,
 * or response descriptions that cannot be derived from the method signature.
 * </p>
 *
 * @see ApiDoc
 * @see ApiDocResponses
 * @see OpenApiProcessor
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE, ElementType.PACKAGE})
@Repeatable(ApiDocResponses.class)
@Documented
public @interface ApiDocResponse {
    /**
     * HTTP status code.
     */
    int status();

    /**
     * Response description.
     */
    String description() default "";

    /**
     * Optional response body type. {@code Void.class} means no explicit type was provided.
     */
    Class<?> type() default Void.class;

    /**
     * Optional response content type.
     */
    String contentType() default "";
}
